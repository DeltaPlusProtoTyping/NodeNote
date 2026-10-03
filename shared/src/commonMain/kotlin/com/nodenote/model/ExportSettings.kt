package com.nodenote.model

import kotlinx.serialization.Serializable

/** Which part of the board the export covers. */
@Serializable
enum class ExportRegion(val label: String) {
    CurrentView("Current view"),
    AllContent("Whole board"),
    SelectedNodes("Selection"),
}

/** Rendering theme for the export (independent of the editor's dark UI). */
@Serializable
enum class ExportTheme(val label: String) {
    Dark("Dark"),
    Light("Light"),
}

/**
 * File type. PDF and SVG are vectors: sharp at any zoom, any size, and they open
 * everywhere (PDF in any PDF viewer, phones included; SVG in any browser). PNG is
 * for pasting into a chat or a document.
 */
@Serializable
enum class ExportFormat(val label: String, val extension: String) {
    Pdf("PDF", "pdf"),
    Svg("SVG", "svg"),
    Png("PNG", "png"),
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
    val region: ExportRegion = ExportRegion.AllContent,
    /** World-unit padding around the content for AllContent/SelectedNodes regions. */
    val padding: Float = 48f,
    /** Resolution multiplier for PNG files (preview always renders to fit). */
    val scale: Float = 2f,
    val theme: ExportTheme = ExportTheme.Light,
    val transparentBackground: Boolean = false,
    val showGrid: Boolean = false,
    val showLegend: Boolean = true,
    val legendX: Float = 1f,
    val legendY: Float = 0f,
    val legendScale: Float = 1f,
    val legendTitle: String = "Legend",
    val showEdgeLabels: Boolean = true,
    val showDescriptions: Boolean = true,
    /** Board-name plate; draggable like the legend. */
    val showTitleBlock: Boolean = true,
    val titleBlockX: Float = 0f,
    val titleBlockY: Float = 1f,
    val format: ExportFormat = ExportFormat.Pdf,
    /** Private nodes are left out of exports unless this is on. */
    val includePrivate: Boolean = false,
)

/** A named, saved copy of export settings. */
@Serializable
data class ExportPreset(
    val id: String,
    val name: String,
    val settings: ExportSettings,
)
