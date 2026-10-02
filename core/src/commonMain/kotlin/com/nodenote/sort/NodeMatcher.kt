package com.nodenote.sort

import com.nodenote.model.Message
import com.nodenote.model.NodeLinkRegex
import com.nodenote.model.Project
import kotlin.math.ln

/**
 * Suggests which node a free-form thought belongs under — plain keyword
 * relevance (BM25, what search engines used before AI), no model, instant.
 *
 * Each node is a "document" built from its title and keywords (weighted up),
 * summary, description and property values, plus the text of every message
 * already filed under it. That last part is how sorting teaches it: once a few
 * "server's slow again" thoughts are filed under *Server is slow*, the next one
 * lands there even if it shares no words with the node's title.
 *
 * Messages that `[[link]]` a node always suggest that node first.
 *
 * Build one per board state (cheap: a few ms for hundreds of nodes and
 * thousands of messages) and query it as often as needed.
 */
class NodeMatcher(private val project: Project, messages: List<Message>) {

    class Suggestion(val nodeId: String, val score: Double)

    private class Doc(val nodeId: String, val tf: Map<String, Double>, val length: Double)

    private val docs: List<Doc>
    private val df = HashMap<String, Int>()
    private val avgLength: Double

    init {
        val filed = HashMap<String, MutableList<String>>()
        for (m in messages) {
            if (m.deleted || m.nodeId.isEmpty()) continue
            filed.getOrPut(m.nodeId) { mutableListOf() }.add(m.text)
        }
        docs = project.nodes.map { node ->
            val tf = HashMap<String, Double>()
            fun add(text: String, weight: Double) {
                for (t in tokenize(text)) tf[t] = (tf[t] ?: 0.0) + weight
            }
            add(node.title, TITLE_WEIGHT)
            add(node.keywords, KEYWORD_WEIGHT)
            add(node.summary, 1.5)
            add(node.description, 1.0)
            node.properties.values.forEach { add(it, 1.0) }
            filed[node.id]?.forEach { add(it, 1.0) }
            Doc(node.id, tf, tf.values.sum())
        }
        for (doc in docs) for (term in doc.tf.keys) df[term] = (df[term] ?: 0) + 1
        avgLength = docs.map { it.length }.average().takeIf { it > 0 && !it.isNaN() } ?: 1.0
    }

    /**
     * Best nodes for [text], most relevant first. Weak matches are dropped (below
     * an absolute floor, or far below the best one), so an empty list honestly
     * means "no idea — sort it by hand".
     */
    fun suggest(text: String, limit: Int = 3, linkedNodeIds: List<String> = emptyList()): List<Suggestion> {
        val linked = linkedNodeIds.filter { id -> project.nodes.any { it.id == id } }
            .map { Suggestion(it, Double.MAX_VALUE) }
        val query = tokenize(text).toSet()
        if (query.isEmpty() || docs.isEmpty()) return linked.take(limit)
        val n = docs.size.toDouble()
        val scored = docs.mapNotNull { doc ->
            var score = 0.0
            for (term in query) {
                val tf = doc.tf[term] ?: continue
                val idf = ln(1.0 + (n - df.getValue(term) + 0.5) / (df.getValue(term) + 0.5))
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * doc.length / avgLength))
            }
            if (score > 0) Suggestion(doc.nodeId, score) else null
        }.sortedByDescending { it.score }
        val best = scored.firstOrNull()?.score ?: 0.0
        val good = scored.filter { it.score >= MIN_SCORE && it.score >= best * RELATIVE_FLOOR }
        return (linked + good.filter { s -> linked.none { it.nodeId == s.nodeId } }).take(limit)
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75
        private const val TITLE_WEIGHT = 3.0
        private const val KEYWORD_WEIGHT = 3.0
        private const val MIN_SCORE = 0.8
        private const val RELATIVE_FLOOR = 0.35

        /**
         * Lowercase words, minus stop words, lightly stemmed ("warming" ~ "warm").
         * Hyphen/slash compounds also count joined, so "VF-2" matches "vf2" and
         * "warm-up" matches "warmup".
         */
        fun tokenize(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            // Apostrophes vanish rather than split ("don't" → "dont", a stop word).
            val clean = text.replace(NodeLinkRegex) { it.groupValues[1] }.lowercase().replace("'", "").replace("’", "")
            val out = ArrayList<String>()
            for (chunk in clean.split(' ', '\n', '\t', '\r')) {
                // Split on anything that isn't a letter or digit. (No \p{L} regex: the
                // browser build's regex engine doesn't support Unicode classes.)
                val parts = splitWords(chunk)
                for (p in parts) keep(p)?.let(out::add)
                if (parts.size in 2..3) keep(parts.joinToString(""))?.let(out::add)
            }
            return out
        }

        private fun splitWords(chunk: String): List<String> {
            val parts = ArrayList<String>()
            val current = StringBuilder()
            for (c in chunk) {
                if (c.isLetterOrDigit()) {
                    current.append(c)
                } else if (current.isNotEmpty()) {
                    parts.add(current.toString())
                    current.clear()
                }
            }
            if (current.isNotEmpty()) parts.add(current.toString())
            return parts
        }

        private fun keep(word: String): String? {
            if (word.length < 2 || word in StopWords) return null
            return stem(word)
        }

        private fun stem(w: String): String = when {
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
            else -> w
        }

        private val StopWords = setOf(
            "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "to", "in", "on", "at", "by", "for",
            "with", "from", "up", "down", "out", "over", "under", "about", "into", "as", "is", "are", "was", "were",
            "be", "been", "being", "am", "do", "does", "did", "doing", "have", "has", "had", "it", "its", "it's",
            "this", "that", "these", "those", "there", "here", "what", "which", "who", "whom", "when", "where",
            "why", "how", "all", "any", "both", "each", "few", "more", "most", "other", "some", "such", "no", "nor",
            "not", "only", "own", "same", "than", "too", "very", "can", "will", "just", "should", "would", "could",
            "now", "i", "me", "my", "we", "our", "us", "you", "your", "he", "him", "his", "she", "her", "they",
            "them", "their", "im", "ive", "dont", "doesnt", "didnt", "cant", "wont", "isnt", "arent", "wasnt",
            "really", "also", "like", "get", "got", "gets", "getting", "thing", "things", "stuff", "need", "needs",
            "think", "lol", "yeah", "ok", "okay", "maybe", "still", "again", "one", "way", "lot", "lots", "even",
            "much", "many", "make", "makes", "made", "going", "go", "goes", "want", "wants", "know", "see", "re", "ll",
        )
    }
}

/**
 * The shape of a board as a tree grown out from its main goal: each node's
 * parent is its neighbor one step closer to the root (connections are treated
 * as undirected). The root is the most-connected Goal node, or simply the
 * most-connected node when there's no Goal. Separate islands get their own root.
 */
class BoardHierarchy(project: Project) {

    /** nodeId → parent nodeId (roots have none). */
    val parents: Map<String, String>

    init {
        val adjacency = HashMap<String, MutableList<String>>()
        for (e in project.edges) {
            adjacency.getOrPut(e.fromNodeId) { mutableListOf() }.add(e.toNodeId)
            adjacency.getOrPut(e.toNodeId) { mutableListOf() }.add(e.fromNodeId)
        }
        fun degree(id: String) = adjacency[id]?.size ?: 0
        val result = HashMap<String, String>()
        val seen = HashSet<String>()
        // Goals first (most connected first), then everything else by connectedness.
        val rootOrder = project.nodes.sortedWith(
            compareByDescending<com.nodenote.model.Node> { it.type == "Goal" }.thenByDescending { degree(it.id) },
        )
        for (root in rootOrder) {
            if (root.id in seen) continue
            seen.add(root.id)
            val queue = ArrayDeque(listOf(root.id))
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                for (next in adjacency[id].orEmpty()) {
                    if (seen.add(next)) {
                        result[next] = id
                        queue.addLast(next)
                    }
                }
            }
        }
        parents = result
    }
}
