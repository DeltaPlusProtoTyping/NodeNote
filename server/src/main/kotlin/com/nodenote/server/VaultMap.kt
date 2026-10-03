package com.nodenote.server

import com.nodenote.export.MapSceneBuilder
import com.nodenote.export.MapSceneOptions
import com.nodenote.export.MapStyle
import com.nodenote.export.SvgWriter
import com.nodenote.model.Project

/**
 * The picture of a board's map that sits next to its notes (`<Board> map.svg`) and is
 * embedded at the top of the board note, so Obsidian shows the map itself — the same
 * vector drawing as the app's SVG export, private nodes left out.
 */
object VaultMap {
    /** Marks the file as ours (an SVG has no frontmatter), so the writer may replace or delete it. */
    const val MARKER = "<!-- nodenote: generated -->"

    fun fileName(folder: String) = "$folder map.svg"

    /** The SVG for [project], or null when it has nothing to draw. */
    fun render(project: Project): String? {
        val map = MapSceneBuilder.build(project, MapSceneOptions(style = MapStyle.Light, showTitleBlock = false)) ?: return null
        val svg = SvgWriter.write(map.scene)
        // Right after the XML declaration, where the writer looks for it.
        val cut = if (svg.startsWith("<?xml")) svg.indexOf('\n') + 1 else 0
        return svg.substring(0, cut) + MARKER + "\n" + svg.substring(cut)
    }

    /** True when [project] has something to draw (what [MarkdownRenderer] checks before embedding the file). */
    fun hasContent(project: Project): Boolean = project.nodes.any { !it.isPrivate } || project.elements.isNotEmpty()
}
