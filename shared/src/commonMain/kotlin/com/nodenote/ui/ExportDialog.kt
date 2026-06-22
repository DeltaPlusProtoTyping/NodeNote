package com.nodenote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.ExportRegion
import com.nodenote.model.ExportSettings
import com.nodenote.model.ExportTheme
import com.nodenote.state.AppState
import com.nodenote.storage.ProjectStorage
import com.nodenote.storage.copyImageToClipboard
import com.nodenote.storage.encodeToPng
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Modal export dialog: live preview on the left (checkerboard shows
 * transparency, legend/title block drag directly on it), options on the
 * right. Implemented as an in-app overlay so it stays 100% common code.
 */
@Composable
fun ExportDialog(state: AppState) {
    if (!state.exportDialogOpen) return
    val textMeasurer = rememberTextMeasurer()
    val settings = state.exportSettings

    // Preview always renders at 1x; the scale setting only affects the file.
    val preview = remember(
        settings, state.project, state.canvasSize, state.pan, state.zoom,
        state.selectedNodeIds, state.viewDensity,
    ) {
        PngExporter.render(state, settings.copy(scale = 1f), textMeasurer)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { state.closeExportDialog() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PanelBackground,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .sizeIn(maxWidth = 1080.dp, maxHeight = 700.dp)
                .fillMaxSize(0.92f)
                .pointerInput(Unit) { detectTapGestures { } }, // swallow clicks so the scrim doesn't close
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Export PNG", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "drag the legend / title block on the preview to place them",
                        fontSize = 10.sp,
                        color = TextFaint,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "✕",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { state.closeExportDialog() }
                            .padding(6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                Row(Modifier.weight(1f)) {
                    PreviewPane(state, preview, Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(14.dp))
                    OptionsPane(state, Modifier.width(280.dp).fillMaxHeight())
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sizeText = if (preview == null) {
                        "No content for this region"
                    } else {
                        "Output: ${(preview.bitmap.width * settings.scale).roundToInt()} × " +
                            "${(preview.bitmap.height * settings.scale).roundToInt()} px"
                    }
                    Text(sizeText, fontSize = 11.sp, color = TextFaint)
                    Spacer(Modifier.weight(1f))
                    ToolbarButton("Cancel", onClick = { state.closeExportDialog() }, color = TextSecondary)
                    Spacer(Modifier.width(4.dp))
                    ToolbarButton(
                        "Copy to clipboard",
                        onClick = { copyToClipboard(state, textMeasurer) },
                        enabled = preview != null,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { performExport(state, textMeasurer) },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("Export PNG", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun performExport(state: AppState, textMeasurer: androidx.compose.ui.text.TextMeasurer) {
    val render = PngExporter.render(state, state.exportSettings, textMeasurer)
    if (render == null) {
        state.status = "Nothing to export for this region"
        return
    }
    val bytes = encodeToPng(render.bitmap)
    if (bytes == null) {
        state.status = "PNG encoding failed"
        return
    }
    val fileName = state.project.name
        .replace(Regex("[^A-Za-z0-9 _-]"), "")
        .trim()
        .ifBlank { "export" } + ".png"
    val path = ProjectStorage.saveAttachmentAs(fileName, bytes)
    if (path != null) {
        state.status = "Exported $path"
        state.closeExportDialog()
    } else {
        state.status = "Export cancelled"
    }
}

/** Renders the image at the chosen settings and puts it on the OS clipboard for pasting into chat/email/docs. */
private fun copyToClipboard(state: AppState, textMeasurer: androidx.compose.ui.text.TextMeasurer) {
    val render = PngExporter.render(state, state.exportSettings, textMeasurer)
    if (render == null) {
        state.status = "Nothing to copy for this region"
        return
    }
    val ok = copyImageToClipboard(render.bitmap)
    if (ok) {
        state.status = "Image copied to clipboard"
        state.closeExportDialog()
    } else {
        state.status = "Could not copy image to clipboard"
    }
}

private enum class DragTarget { Legend, TitleBlock }

/**
 * Checkerboard-backed preview. The legend and title block can be dragged
 * directly: presses are hit-tested against the overlay rects reported by the
 * renderer (converted through the ContentScale.Fit mapping), and drags update
 * the overlay position fractions in the settings.
 */
@Composable
private fun PreviewPane(state: AppState, preview: ExportRender?, modifier: Modifier = Modifier) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val padPx = with(LocalDensity.current) { 10.dp.toPx() }
    // The preview re-renders on every settings change (including during a drag),
    // so the gesture must read the latest render without restarting.
    val currentPreview = rememberUpdatedState(preview)

    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PanelBackgroundRaised)
            .onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = 8f * density
            val light = Color(0xFF26292E)
            var x = 0f
            while (x < size.width) {
                var y = 0f
                while (y < size.height) {
                    if (((x / cell).toInt() + (y / cell).toInt()) % 2 == 0) {
                        drawRect(light, Offset(x, y), Size(cell, cell))
                    }
                    y += cell
                }
                x += cell
            }
        }
        if (preview != null) {
            Image(
                bitmap = preview.bitmap,
                contentDescription = "Export preview",
                modifier = Modifier.fillMaxSize().padding(10.dp),
                contentScale = ContentScale.Fit,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(boxSize) {
                        var target: DragTarget? = null
                        detectDragGestures(
                            onDragStart = { pos ->
                                val render = currentPreview.value ?: return@detectDragGestures
                                val fit = fitMapping(render, boxSize, padPx) ?: return@detectDragGestures
                                val imagePos = (pos - fit.offset) / fit.scale
                                target = when {
                                    render.legendRect?.contains(imagePos) == true -> DragTarget.Legend
                                    render.titleBlockRect?.contains(imagePos) == true -> DragTarget.TitleBlock
                                    else -> null
                                }
                            },
                            onDrag = { change, dragAmount ->
                                val t = target ?: return@detectDragGestures
                                change.consume()
                                val render = currentPreview.value ?: return@detectDragGestures
                                val fit = fitMapping(render, boxSize, padPx) ?: return@detectDragGestures
                                val dImage = dragAmount / fit.scale
                                val imgW = render.bitmap.width.toFloat()
                                val imgH = render.bitmap.height.toFloat()
                                val rect = if (t == DragTarget.Legend) render.legendRect else render.titleBlockRect
                                if (rect == null) return@detectDragGestures
                                val freeW = imgW - rect.width - render.overlayMargin * 2f
                                val freeH = imgH - rect.height - render.overlayMargin * 2f
                                val s = state.exportSettings
                                val dx = if (freeW > 1f) dImage.x / freeW else 0f
                                val dy = if (freeH > 1f) dImage.y / freeH else 0f
                                state.exportSettings = when (t) {
                                    DragTarget.Legend -> s.copy(
                                        legendX = (s.legendX + dx).coerceIn(0f, 1f),
                                        legendY = (s.legendY + dy).coerceIn(0f, 1f),
                                    )
                                    DragTarget.TitleBlock -> s.copy(
                                        titleBlockX = (s.titleBlockX + dx).coerceIn(0f, 1f),
                                        titleBlockY = (s.titleBlockY + dy).coerceIn(0f, 1f),
                                    )
                                }
                            },
                            onDragEnd = { target = null },
                            onDragCancel = { target = null },
                        )
                    },
            )
        } else {
            Text("Nothing to export for this region", fontSize = 12.sp, color = TextFaint)
        }
    }
}

private class FitMapping(val offset: Offset, val scale: Float)

/** Mapping from preview-pane local coordinates to export-image pixels (ContentScale.Fit with 10dp padding). */
private fun fitMapping(render: ExportRender, boxSize: IntSize, padPx: Float): FitMapping? {
    val availW = boxSize.width - padPx * 2f
    val availH = boxSize.height - padPx * 2f
    if (availW <= 0f || availH <= 0f) return null
    val imgW = render.bitmap.width.toFloat()
    val imgH = render.bitmap.height.toFloat()
    val scale = min(availW / imgW, availH / imgH)
    val offset = Offset(padPx + (availW - imgW * scale) / 2f, padPx + (availH - imgH * scale) / 2f)
    return FitMapping(offset, scale)
}

// ---- Options column ----

@Composable
private fun OptionsPane(state: AppState, modifier: Modifier = Modifier) {
    val s = state.exportSettings
    fun update(transform: (ExportSettings) -> ExportSettings) {
        state.exportSettings = transform(state.exportSettings)
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresetSection(state)
        HorizontalDivider(color = PanelBorder)

        PanelCaption("Region")
        OptionChips(
            options = ExportRegion.entries.map { it.label },
            selected = s.region.ordinal,
            enabled = { i -> ExportRegion.entries[i] != ExportRegion.SelectedNodes || state.selectedNodeIds.isNotEmpty() },
            onSelect = { update { st -> st.copy(region = ExportRegion.entries[it]) } },
        )
        if (s.region != ExportRegion.CurrentView) {
            LabeledSlider("Padding", s.padding, 0f..160f, { update { st -> st.copy(padding = it) } }) {
                "${s.padding.roundToInt()}"
            }
        }

        PanelCaption("Resolution")
        OptionChips(
            options = listOf("1×", "2×", "3×"),
            selected = (s.scale.roundToInt() - 1).coerceIn(0, 2),
            onSelect = { update { st -> st.copy(scale = (it + 1).toFloat()) } },
        )

        PanelCaption("Appearance")
        OptionChips(
            options = ExportTheme.entries.map { it.label },
            selected = s.theme.ordinal,
            onSelect = { update { st -> st.copy(theme = ExportTheme.entries[it]) } },
        )
        OptionChips(
            options = listOf("Transparent", "Solid"),
            selected = if (s.transparentBackground) 0 else 1,
            onSelect = { update { st -> st.copy(transparentBackground = it == 0) } },
        )
        ToggleRow("Dot grid (solid background only)", s.showGrid, enabled = !s.transparentBackground) {
            update { st -> st.copy(showGrid = it) }
        }

        PanelCaption("Legend")
        ToggleRow("Show legend (drag it on the preview)", s.showLegend) { update { st -> st.copy(showLegend = it) } }
        if (s.showLegend) {
            LabeledSlider("Size", s.legendScale, 0.6f..1.8f, { update { st -> st.copy(legendScale = it) } }) {
                "${(s.legendScale * 100).roundToInt()}%"
            }
            FieldLabel("Legend title")
            CompactTextField(
                value = s.legendTitle,
                onValueChange = { v -> update { st -> st.copy(legendTitle = v) } },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Connections",
            )
        }

        PanelCaption("Content")
        FieldLabel("Project name (shown in the title block)")
        CompactTextField(
            value = state.project.name,
            onValueChange = state::renameProject,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Project name",
        )
        ToggleRow("Project title block (drag it on the preview)", s.showTitleBlock) {
            update { st -> st.copy(showTitleBlock = it) }
        }
        ToggleRow("Connection labels", s.showEdgeLabels) { update { st -> st.copy(showEdgeLabels = it) } }
        ToggleRow("Node descriptions", s.showDescriptions) { update { st -> st.copy(showDescriptions = it) } }
    }
}

@Composable
private fun PresetSection(state: AppState) {
    val active = state.exportPresets.find { it.id == state.activeExportPresetId }
    var presetName by remember(state.activeExportPresetId) { mutableStateOf(active?.name ?: "") }
    var menuOpen by remember { mutableStateOf(false) }

    PanelCaption("Preset")
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(PanelBackgroundRaised)
                .clickable { menuOpen = true }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                active?.name ?: "Custom",
                fontSize = 12.sp,
                color = if (active != null) TextPrimary else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("▾", fontSize = 10.sp, color = TextFaint)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (state.exportPresets.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No presets saved yet", fontSize = 12.sp, color = TextFaint) },
                    onClick = { menuOpen = false },
                )
            }
            state.exportPresets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.name, fontSize = 12.sp) },
                    onClick = {
                        state.applyExportPreset(preset)
                        menuOpen = false
                    },
                )
            }
        }
    }
    CompactTextField(
        value = presetName,
        onValueChange = { presetName = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = "Preset name",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CompactOutlinedButton(
            "Save new",
            onClick = { if (presetName.isNotBlank()) state.saveExportPresetAsNew(presetName) },
            modifier = Modifier.weight(1f),
        )
        if (active != null) {
            CompactOutlinedButton("Update", onClick = { state.updateActiveExportPreset(presetName) }, modifier = Modifier.weight(1f))
            CompactOutlinedButton("Delete", onClick = { state.deleteActiveExportPreset() }, modifier = Modifier.weight(1f))
        }
    }
}

// ---- Small dialog-local controls ----

/** Compact segmented chip row. */
@Composable
private fun OptionChips(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    enabled: (Int) -> Boolean = { true },
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            val isSelected = i == selected
            val isEnabled = enabled(i)
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) Accent.copy(alpha = 0.18f) else PanelBackgroundRaised)
                    .clickable(enabled = isEnabled) { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    color = when {
                        !isEnabled -> TextFaint.copy(alpha = 0.5f)
                        isSelected -> Accent
                        else -> TextSecondary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Compact checkbox row. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) Accent else PanelBackgroundRaised),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", fontSize = 9.sp, color = Color(0xFF0B1018))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = if (enabled) TextPrimary else TextFaint.copy(alpha = 0.6f),
        )
    }
}

/** Slider with a caption and live value readout. */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    valueText: () -> String,
) {
    Column {
        Row {
            FieldLabel(label)
            Spacer(Modifier.weight(1f))
            Text(valueText(), fontSize = 10.sp, color = TextFaint)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
    }
}
