package com.nodenote.model

import kotlinx.serialization.Serializable

/** Which part of the project the PNG covers. */
@Serializable
enum class ExportRegion(val label: String) {
    CurrentView("Current view"),
    AllContent("All content"),
    SelectedNodes("Selection"),
}

/** Rendering theme for the exported image (independent of the editor's dark UI). */
@Serializable
enum class ExportTheme(val label: String) {
    Dark("Dark"),
    Light("Light"),
}

/**
 * Everything the export dialog can customize. Serializable so presets are
 * just named copies of this saved to disk.
 *
 * Overlay positions (legend, title block) are stored as fractions of the free
 * area: (0,0) = top-left corner, (1,1) = bottom-right. The user sets them by
 * dragging the overlays on the dialog's preview.
 */
@Serializable
data class ExportSettings(
    val region: ExportRegion = ExportRegion.CurrentView,
    /** World-unit padding around the content for AllContent/SelectedNodes regions. */
    val padding: Float = 48f,
    /** Resolution multiplier for the output file (preview always renders at 1x). */
    val scale: Float = 1f,
    val theme: ExportTheme = ExportTheme.Dark,
    val transparentBackground: Boolean = true,
    val showGrid: Boolean = false,
    val showLegend: Boolean = true,
    val legendX: Float = 1f,
    val legendY: Float = 0f,
    val legendScale: Float = 1f,
    val legendTitle: String = "Connections",
    val showEdgeLabels: Boolean = true,
    val showDescriptions: Boolean = true,
    /** Project-name plate; draggable like the legend. */
    val showTitleBlock: Boolean = false,
    val titleBlockX: Float = 0f,
    val titleBlockY: Float = 1f,
)

/** A named, saved copy of export settings. */
@Serializable
data class ExportPreset(
    val id: String,
    val name: String,
    val settings: ExportSettings,
)
