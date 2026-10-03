package com.nodenote.server

import com.nodenote.api.BoardSummary
import com.nodenote.model.BuiltinTypes
import com.nodenote.model.Message
import com.nodenote.model.MessageKind
import com.nodenote.model.Node
import com.nodenote.model.NodeLinkRegex
import com.nodenote.model.NodeStatus
import com.nodenote.model.Pick
import com.nodenote.model.Project
import com.nodenote.model.isOpenWork
import com.nodenote.model.pick
import com.nodenote.model.edgeType
import com.nodenote.model.nodeType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Everything the renderer needs about one board. [messages] may include tombstones; they are skipped. */
data class VaultBoard(val summary: BoardSummary, val project: Project, val messages: List<Message>)

/** A board as the vault index sees it, plus the folder it was last written to (null = never). */
data class VaultEntry(val summary: BoardSummary, val vaultFolder: String?)

/**
 * File and folder naming for the vault.
 *
 * Names have to survive Windows (no `\ / : * ? " < > |`, no trailing dots, no
 * CON/NUL…) *and* Obsidian links (no `# ^ [ ] |`). Collisions are resolved
 * case-insensitively — Windows and macOS file systems are — in a stable order
 * (creation time, then id), so a file keeps its name until its own title changes.
 */
object VaultNames {
    const val INBOX = "Inbox"
    const val BOARDS_INDEX = "Boards"
    const val MAX_LENGTH = 80
    private const val FALLBACK = "Untitled"

    private val Forbidden = Regex("""[\\/:*?"<>|#^\[\]\p{Cntrl}]""")
    private val Whitespace = Regex("""\s+""")
    private val WindowsReserved = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

    /** A file/folder name for [raw]: forbidden characters become spaces, whitespace collapses, max [MAX_LENGTH]. */
    fun sanitize(raw: String): String {
        var name = raw.replace(Forbidden, " ").replace(Whitespace, " ").trim().trimStart('.', ' ')
        if (name.length > MAX_LENGTH) {
            name = name.take(MAX_LENGTH)
            if (name.last().isHighSurrogate()) name = name.dropLast(1) // don't split an emoji
        }
        name = name.trimEnd('.', ' ')
        if (name.isEmpty()) return FALLBACK
        if (name.substringBefore('.').lowercase() in WindowsReserved) name += "_"
        return name
    }

    /**
     * Board id → folder name. A board keeps the folder it already has ([current]), even after a
     * rename, so links into it from elsewhere in the vault keep working. New boards get their
     * sanitized name; the older of two same-named boards keeps the plain name.
     */
    fun boardFolders(boards: List<BoardSummary>, current: Map<String, String?> = emptyMap()): Map<String, String> {
        val used = HashSet<String>()
        val ordered = boards.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val result = HashMap<String, String>()
        for (b in ordered) {
            val kept = current[b.id]?.takeIf { it.isNotBlank() && sanitize(it) == it }
            if (kept != null && used.add(kept.lowercase())) result[b.id] = kept
        }
        for (b in ordered) if (b.id !in result) result[b.id] = unique(sanitize(b.name), used)
        return result
    }

    /**
     * Node id → note file name (without `.md`) inside [boardFolder]. The board index
     * (`<boardFolder>.md`) and `Inbox.md` are taken, so a node titled like either gets a suffix.
     */
    fun nodeFiles(project: Project, boardFolder: String): Map<String, String> {
        val used = hashSetOf(boardFolder.lowercase(), INBOX.lowercase())
        return project.nodes.sortedWith(compareBy({ it.createdAt }, { it.id }))
            .associate { it.id to unique(sanitize(it.title), used) }
    }

    private fun unique(base: String, used: MutableSet<String>): String {
        var name = base
        var n = 2
        while (!used.add(name.lowercase())) name = "$base (${n++})"
        return name
    }
}

/**
 * Turns boards into Obsidian markdown. Pure: no I/O, and the output depends only on
 * the inputs (plus the time zone), so an unchanged board renders byte-identical
 * files and [VaultWriter] can skip rewriting them.
 *
 * Every file starts with frontmatter containing `nodenote: generated`; that marker
 * is what allows the writer to delete or replace a file, so hand-written notes that
 * happen to share a folder are never touched.
 *
 * Links use the path form `[[Board/Node|Title]]` because the mirror may live in a
 * subfolder of a bigger vault, where a bare `[[Node]]` could resolve elsewhere.
 */
class MarkdownRenderer(private val zone: ZoneId = ZoneId.systemDefault()) {

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val displayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** File name (with `.md`, relative to [folder]) → content, for one board. */
    fun renderBoard(board: VaultBoard, folder: String): Map<String, String> {
        val ctx = BoardContext(board, folder)
        val files = LinkedHashMap<String, String>()
        files["$folder.md"] = ctx.boardIndex()
        for (node in board.project.nodes) files["${ctx.fileOf(node)}.md"] = ctx.nodeNote(node)
        if (ctx.inbox.isNotEmpty()) files["${VaultNames.INBOX}.md"] = ctx.inboxNote()
        return files
    }

    /** `Boards.md` at the vault root: one row per board, given with its folder. */
    fun renderBoardsIndex(boards: List<Pair<BoardSummary, String>>): String {
        val sorted = boards.sortedByDescending { it.first.updatedAt }
        val (archived, active) = sorted.partition { it.first.archived }
        val fm = Frontmatter()
            .raw("boards", boards.size)
            .apply { sorted.maxOfOrNull { it.first.updatedAt }?.takeIf { it > 0 }?.let { raw("updated", dateTime(it)) } }
        fun table(rows: List<Pair<BoardSummary, String>>) = buildString {
            append("| Board | Open | Nodes | Messages | Updated |\n")
            append("|---|---:|---:|---:|---|\n")
            for ((summary, folder) in rows) {
                val link = wikiLink("$folder/$folder", summary.name, inTable = true)
                append("| $link | ${summary.openCount} | ${summary.nodeCount} | ${summary.messageCount} | ${display(summary.updatedAt)} |\n")
            }
        }.trimEnd()
        return document(
            fm,
            "# Boards",
            if (active.isEmpty()) "_No active boards._" else table(active),
            archived.takeIf { it.isNotEmpty() }?.let { "## Archived\n\n" + table(it) },
        )
    }

    /** Per-board state shared by all of that board's notes. */
    private inner class BoardContext(board: VaultBoard, private val folder: String) {
        private val summary = board.summary
        private val project = board.project
        private val files = VaultNames.nodeFiles(project, folder)
        private val nodesById = project.nodes.associateBy { it.id }
        /** Same matching rule as core's `resolveNodeLinks`: trimmed, case-insensitive title. */
        private val nodesByTitle = project.nodes.associateBy { it.title.trim().lowercase() }

        /** Live messages in chat order. */
        private val live: List<Message> = board.messages
            .filter { !it.deleted }
            .sortedWith(compareBy({ it.createdAt }, { it.seq }))

        /** The note a message's chat entry (and block id) lives in: its node, or "" for the inbox. */
        private fun homeOf(m: Message): String = if (m.nodeId in nodesById) m.nodeId else ""

        private val byHome: Map<String, List<Message>> = live.groupBy(::homeOf)

        /** Unfiled messages, plus any whose node has since been deleted — nothing drops out of the vault. */
        val inbox: List<Message> = byHome[""].orEmpty()

        fun fileOf(node: Node): String = files.getValue(node.id)

        // ---- notes ----

        fun nodeNote(node: Node): String {
            val own = byHome[node.id].orEmpty()
            val related = live.filter { it.nodeId == node.id || node.id in it.linkedNodeIds }
            val keyDetails = related.filter { it.importance >= KEY_IMPORTANCE }
                .sortedWith(compareByDescending<Message> { it.importance }.thenByDescending { it.createdAt })
            val mentions = live.filter { homeOf(it) != node.id && node.id in it.linkedNodeIds }.asReversed()
            val actions = own.filter { it.kind == MessageKind.Action }
            val updated = (own.flatMap { listOf(it.createdAt, it.editedAt) } + node.createdAt).max()

            val fm = Frontmatter()
                .str("board", project.name)
                .str("node_id", node.id)
                .str("type", project.nodeType(node.type).label)
                .apply { if (node.status != NodeStatus.None) raw("status", yamlScalar(node.status.label)) }
                .apply { if (node.importance > 0) raw("importance", node.importance) }
                .apply { if (node.effort > 0) raw("effort", node.effort) }
                .apply { if (node.solvedAt > 0) raw("solved", date(node.solvedAt)) }
                .apply { if (node.isPrivate) raw("private", true) }
                .apply { if (node.owner.isNotBlank()) str("owner", node.owner.trim()) }
                .apply { if (node.keywords.isNotBlank()) str("keywords", node.keywords.trim()) }
                .apply { if (node.createdAt > 0) raw("created", date(node.createdAt)) }
                .apply { if (updated > 0) raw("updated", dateTime(updated)) }
                .raw("messages", own.size)
                .raw("key_details", keyDetails.size)
                .apply { if (summary.archived) raw("archived", true) }
                .tags(listOfNotNull("nodenote", typeTag(node), statusTag(node.status), "private".takeIf { node.isPrivate }))

            val connections = project.edges.mapNotNull { edge ->
                val type = project.edgeType(edge.type).label
                val label = edge.label.takeIf { it.isNotBlank() }?.let { " · " + plain(it) }.orEmpty()
                when {
                    edge.fromNodeId == node.id -> nodesById[edge.toNodeId]?.let { "- → ${link(it)} · $type$label" }
                    edge.toNodeId == node.id -> nodesById[edge.fromNodeId]?.let { "- ← ${link(it)} · $type$label" }
                    else -> null
                }
            }

            return document(
                fm,
                "# ${plain(node.title)}",
                node.summary.takeIf { it.isNotBlank() }?.let { s ->
                    "> [!summary]\n" + rewriteLinks(normalize(s).trim()).lines()
                        .joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
                },
                node.description.takeIf { it.isNotBlank() }?.let { rewriteLinks(normalize(it).trim()) },
                node.properties.takeIf { it.isNotEmpty() }?.let { props ->
                    "**Properties**\n" + props.entries.joinToString("\n") { (k, v) -> "- ${plain(k)}: ${rewriteLinks(oneLine(v))}" }
                },
                section("Connections", connections),
                section("Key details", keyDetails.map { keyDetail(it, from = node.id, withNode = false) }),
                section("Action items", actions.map { m ->
                    val box = if (m.done) "[x]" else "[ ]"
                    "- $box ${firstLine(m.text)} (${plain(author(m))}) ${jump(m, from = node.id)}"
                }),
                own.takeIf { it.isNotEmpty() }?.let { "## Chat\n\n" + chat(it) },
                section("Mentioned in", mentions.map { m ->
                    "- **${plain(author(m))}** in ${noteLink(m)} · ${date(m.createdAt)} — ${firstLine(m.text)} ${jump(m, from = null)}"
                }),
            )
        }

        fun inboxNote(): String {
            val orphans = inbox.any { it.nodeId.isNotEmpty() }
            val fm = Frontmatter()
                .str("board", project.name)
                .str("board_id", summary.id)
                .raw("messages", inbox.size)
                .apply { if (summary.archived) raw("archived", true) }
                .tags(listOf("nodenote", "inbox"))
            return document(
                fm,
                "# ${VaultNames.INBOX} — ${plain(project.name)}",
                "Unfiled braindumps — file them under a node in the app." +
                    if (orphans) " Messages whose node was deleted land here too." else "",
                "## Chat\n\n" + chat(inbox),
            )
        }

        fun boardIndex(): String {
            val fm = Frontmatter()
                .str("board", project.name)
                .str("board_id", summary.id)
                .raw("rev", summary.rev)
                .raw("archived", summary.archived)
                .apply { if (summary.updatedAt > 0) raw("updated", dateTime(summary.updatedAt)) }
                .raw("nodes", project.nodes.size)
                .raw("messages", live.size)
                .raw("open", summary.openCount)
                .tags(listOf("nodenote", "nodenote-board"))

            val open = project.nodes.filter { it.status.isActive }
                .sortedWith(compareByDescending<Node> { it.importance }.thenBy { it.title.lowercase() })
            val openTable = open.takeIf { it.isNotEmpty() }?.let { nodes ->
                buildString {
                    append("## Open work\n\n")
                    append("| Node | Type | Status | Importance | Effort | Owner | Msgs |\n")
                    append("|---|---|---|---:|---:|---|---:|\n")
                    for (n in nodes) {
                        val importance = if (n.importance > 0) n.importance.toString() else ""
                        val effort = if (n.effort > 0) n.effort.toString() else ""
                        append("| ${link(n, inTable = true)} | ${plain(project.nodeType(n.type).label)} | ${n.status.label} ")
                        append("| $importance | $effort | ${plain(n.owner)} | ${byHome[n.id].orEmpty().size} |\n")
                    }
                }.trimEnd()
            }

            val allNodes = project.nodes.takeIf { it.isNotEmpty() }?.let { nodes ->
                val groups = nodes.groupBy { project.nodeType(it.type).label }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                "## All nodes\n\n" + groups.entries.joinToString("\n\n") { (label, group) ->
                    "### ${plain(label)}\n\n" + group.sortedBy { it.title.lowercase() }.joinToString("\n") { n ->
                        val extras = buildList {
                            if (n.status != NodeStatus.None) add(n.status.label)
                            if (n.importance > 0) add("★${n.importance}")
                            if (n.effort > 0) add("effort ${n.effort}")
                            if (n.isPrivate) add("private")
                            byHome[n.id]?.size?.let { add(if (it == 1) "1 message" else "$it messages") }
                        }
                        "- ${link(n)}" + extras.joinToString("") { " · $it" }
                    }
                }
            }

            val keyDetails = live.filter { it.importance >= KEY_IMPORTANCE }
                .sortedWith(compareByDescending<Message> { it.importance }.thenByDescending { it.createdAt })
                .take(BOARD_KEY_DETAILS)
            val recent = live.asReversed().take(RECENT_ACTIVITY)
            val quickWins = project.nodes.filter { it.isOpenWork && it.pick == Pick.QuickWin }
                .sortedWith(compareByDescending<Node> { it.importance }.thenBy { it.effort }.thenBy { it.title.lowercase() })
                .map { n -> "- ${link(n)} · importance ${n.importance}, effort ${n.effort}" + (if (n.isPrivate) " · private" else "") }
            val wins = project.nodes.filter { it.status == NodeStatus.Solved }
                .sortedWith(compareByDescending<Node> { it.solvedAt }.thenBy { it.title.lowercase() })
                .map { n ->
                    val day = if (n.solvedAt > 0) date(n.solvedAt) else "Solved"
                    "- $day · ${link(n)} · ${plain(project.nodeType(n.type).label)}" + (if (n.isPrivate) " · private" else "")
                }

            return document(
                fm,
                "# ${plain(project.name)}",
                if (summary.archived) "> [!note] Archived board" else null,
                // The map itself (written next to the notes by VaultWriter; private nodes left out).
                if (VaultMap.hasContent(project)) "![[$folder/${VaultMap.fileName(folder)}]]" else null,
                inbox.takeIf { it.isNotEmpty() }?.let {
                    "Unfiled: ${wikiLink("$folder/${VaultNames.INBOX}", VaultNames.INBOX)} (${it.size})"
                },
                section("Quick wins", quickWins),
                openTable,
                section("Wins", wins),
                allNodes,
                section("Key details", keyDetails.map { keyDetail(it, from = null, withNode = true) }),
                section("Recent activity", recent.map { m ->
                    "- ${display(m.createdAt)} · ${plain(author(m))} · ${noteLink(m)} — ${firstLine(m.text)}"
                }),
            )
        }

        // ---- pieces ----

        /** Day headings, then one paragraph per message whose last line carries its block id. */
        private fun chat(messages: List<Message>): String = buildString {
            var day: String? = null
            for (m in messages) {
                val d = if (m.createdAt > 0) date(m.createdAt) else "Undated"
                if (d != day) {
                    append("### ").append(d).append("\n\n")
                    day = d
                }
                val header = buildList {
                    add("**${plain(author(m))}**")
                    if (m.createdAt > 0) add(time(m.createdAt))
                    if (m.kind != MessageKind.Note) add(kindLabel(m))
                    if (m.importance > 0) add("★${m.importance}")
                    if (m.editedAt > 0) add("(edited)")
                }.joinToString(" · ")
                append(header).append('\n').append(body(m)).append("\n\n")
            }
        }.trimEnd()

        /**
         * Message text with links rewritten and ` ^m-<id>` on the last line, so other notes
         * can jump to it. Obsidian only recognises a block id after a code fence, table or
         * quote when it sits on its own line, so those get it one blank line below.
         */
        private fun body(m: Message): String {
            val lines = rewriteLinks(normalize(m.text).trimEnd().trimStart('\n')).lines()
            val last = lines.last().trimStart()
            val blockRef = "^" + blockId(m)
            val standalone = listOf("```", "~~~", "|", ">", "$$").any { last.startsWith(it) }
            return if (standalone) {
                lines.joinToString("\n") + "\n\n" + blockRef
            } else {
                (lines.dropLast(1) + "${lines.last()} $blockRef").joinToString("\n")
            }
        }

        private fun keyDetail(m: Message, from: String?, withNode: Boolean): String {
            val parts = buildList {
                add("**${m.importance}**")
                if (withNode) add(noteLink(m))
                if (m.kind != MessageKind.Note) add(kindLabel(m))
                add("${plain(author(m))}, ${date(m.createdAt)}")
            }
            return "- " + parts.joinToString(" · ") + " — ${firstLine(m.text)} ${jump(m, from)}"
        }

        /** `[[#^m-id|↩]]` within the same note, else a path to the note the message lives in. */
        private fun jump(m: Message, from: String?): String {
            val home = homeOf(m)
            val note = if (home == from) "" else notePath(home)
            return "[[$note#^${blockId(m)}|↩]]"
        }

        private fun notePath(home: String): String =
            if (home.isEmpty()) "$folder/${VaultNames.INBOX}" else "$folder/${files.getValue(home)}"

        private fun noteLink(m: Message): String =
            nodesById[homeOf(m)]?.let { link(it) } ?: wikiLink(notePath(""), VaultNames.INBOX)

        private fun link(node: Node, alias: String = node.title, inTable: Boolean = false): String =
            wikiLink("$folder/${fileOf(node)}", alias, inTable)

        /** `[[Title]]` / `[[Title|alias]]` naming a node on this board → path form; anything else is left alone. */
        fun rewriteLinks(text: String): String = NodeLinkRegex.replace(text) { match ->
            val title = match.groupValues[1].trim()
            val node = nodesByTitle[title.lowercase()] ?: return@replace match.value
            val alias = match.value.substringAfter('|', "").removeSuffix("]]").trim()
            link(node, alias.ifEmpty { title })
        }

        /** First non-blank line of [text], at most 160 chars, links rewritten — for one-line summaries. */
        private fun firstLine(text: String): String {
            val line = normalize(text).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val cut = if (line.length > FIRST_LINE_MAX) line.take(FIRST_LINE_MAX - 1).trimEnd() + "…" else line
            return rewriteLinks(cut)
        }

        private fun typeTag(node: Node): String? {
            // Built-in type ids read well ("ActionItem" → action-item); custom ones are random hex, so use the label.
            val source = if (node.type in BuiltinTypes.nodeById) node.type else project.nodeType(node.type).label
            return tagify(source)
        }
    }

    // ---- formatting helpers ----

    private fun section(title: String, lines: List<String>): String? =
        lines.takeIf { it.isNotEmpty() }?.let { "## $title\n\n" + it.joinToString("\n") }

    /** Frontmatter, then the non-null blocks separated by blank lines, ending in one newline. */
    private fun document(frontmatter: Frontmatter, vararg blocks: String?): String =
        frontmatter.render() + "\n" + blocks.filterNotNull().joinToString("\n\n") + "\n"

    private fun wikiLink(target: String, alias: String, inTable: Boolean = false): String {
        val text = oneLine(alias).replace('|', '/').replace('[', '(').replace(']', ')').ifBlank { target.substringAfterLast('/') }
        return "[[" + target + (if (inTable) "\\|" else "|") + text + "]]"
    }

    private fun date(millis: Long): String = dateFormat.format(Instant.ofEpochMilli(millis).atZone(zone))
    private fun time(millis: Long): String = timeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))
    private fun dateTime(millis: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))
    private fun display(millis: Long): String = if (millis > 0) displayFormat.format(Instant.ofEpochMilli(millis).atZone(zone)) else ""

    private companion object {
        const val KEY_IMPORTANCE = 7
        const val BOARD_KEY_DETAILS = 50
        const val RECENT_ACTIVITY = 30
        const val FIRST_LINE_MAX = 160

        private val Whitespace = Regex("""\s+""")
        private val MarkdownSpecial = Regex("""([\\*_\[\]|#<`])""")
        private val SafeYamlPlain = Regex("""[A-Za-z][A-Za-z0-9 _./-]*""")
        private val YamlKeywords = setOf("true", "false", "yes", "no", "on", "off", "null", "y", "n")
        private val BlockIdUnsafe = Regex("""[^A-Za-z0-9-]""")
        private val CamelBoundary = Regex("""([a-z0-9])([A-Z])""")
        private val TagUnsafe = Regex("""[^a-z0-9_/-]+""")

        fun author(m: Message): String = m.author.trim().ifEmpty { "Unknown" }

        fun kindLabel(m: Message): String =
            if (m.kind == MessageKind.Action && m.done) "${m.kind.label} ✓" else m.kind.label

        fun blockId(m: Message): String = "m-" + m.id.replace(BlockIdUnsafe, "").ifEmpty { "seq${m.seq}" }

        fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

        fun oneLine(text: String): String = text.replace(Whitespace, " ").trim()

        /** Inline text that must not turn into markup (names, titles, table cells). */
        fun plain(text: String): String = oneLine(text).replace(MarkdownSpecial, """\\$1""")

        /** "InProgress" → "in-progress", "Issue / Problem" → "issue-problem"; null if nothing usable is left. */
        fun tagify(raw: String): String? {
            val tag = raw.replace(CamelBoundary, "$1-$2").lowercase()
                .replace(TagUnsafe, "-").replace(Regex("-{2,}"), "-").trim('-', '/')
            return when {
                tag.isEmpty() -> null
                tag.all { it.isDigit() } -> "type-$tag" // Obsidian ignores purely numeric tags
                else -> tag
            }
        }

        fun statusTag(status: NodeStatus): String? = if (status == NodeStatus.None) null else tagify(status.name)

        fun yamlScalar(value: String): String =
            if (SafeYamlPlain.matches(value) && value.lowercase() !in YamlKeywords && !value.endsWith(" ")) value
            else yamlQuote(value)

        fun yamlQuote(value: String): String = buildString {
            append('"')
            for (c in value) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }

    /** Ordered YAML frontmatter that always starts with the `nodenote: generated` marker. */
    private class Frontmatter {
        private val lines = mutableListOf("nodenote: generated")

        fun raw(key: String, value: Any) = apply { lines += "$key: $value" }
        fun str(key: String, value: String) = apply { lines += "$key: ${yamlQuote(value)}" }
        fun tags(tags: List<String>) = apply {
            lines += "tags: [" + tags.distinct().joinToString(", ") { if (it.first().isLetter()) it else yamlQuote(it) } + "]"
        }

        fun render(): String = "---\n" + lines.joinToString("\n") + "\n---\n"
    }
}
