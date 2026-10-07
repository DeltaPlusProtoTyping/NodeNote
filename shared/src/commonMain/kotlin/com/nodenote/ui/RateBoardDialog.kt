package com.nodenote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.MAX_EFFORT
import com.nodenote.model.MAX_IMPORTANCE
import com.nodenote.model.Node
import com.nodenote.model.Pick
import com.nodenote.model.pick
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.color
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rating by placing: every open card as a chip on an importance × effort board (up =
 * matters more, right = a bigger job), with the PICK quadrants behind. Dragging a chip
 * sets both ratings at once; cards not rated yet wait in the strip along the bottom, and
 * dragging a chip back there clears its ratings. One undo step per drop. Importance runs
 * 1–12 up the side (the top two rows are urgent), effort 1–10 along the bottom.
 */
@Composable
fun RateBoardDialog(state: AppState) {
    if (!state.rateBoardOpen) return
    var picked by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val cards = rateableCards(state)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { state.rateBoardOpen = false } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PanelBackground,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .sizeIn(maxWidth = 1240.dp, maxHeight = 820.dp)
                .fillMaxSize(0.95f)
                .pointerInput(Unit) { detectTapGestures { picked = null } },
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rate by placing", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Drag each card: higher matters more, further right is a bigger job.",
                        fontSize = 11.sp,
                        color = TextFaint,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CloseButton(onClick = { state.rateBoardOpen = false })
                }
                Spacer(Modifier.height(10.dp))
                if (cards.isEmpty()) {
                    Text(
                        "No open work to rate yet. Issues, ideas and improvements show up here once they're on the map.",
                        fontSize = 12.sp,
                        color = TextFaint,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    RateBoard(
                        state, cards, picked,
                        onPick = { picked = it },
                        onPreview = { preview = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                RateFooter(state, picked?.let { id -> cards.find { it.id == id } }, preview)
            }
        }
    }
}

/** Under the board: the picked card in full, and where a drag would put it. */
@Composable
private fun RateFooter(state: AppState, node: Node?, preview: Pair<Int, Int>?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            preview != null -> {
                val (i, e) = preview
                val pick = Node(id = "", type = "", title = "", x = 0f, y = 0f, importance = i, effort = e).pick
                Text("Importance $i · Effort $e", fontSize = 12.sp, color = TextPrimary)
                if (pick != null) Text("  ${pick.label.removeSuffix("s")}: ${pick.hint}", fontSize = 12.sp, color = pickColor(pick))
            }
            node != null -> {
                TypeDot(state.nodeTypeOf(node.type).color, 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(node.title.ifBlank { "(untitled)" }, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val rating = if (node.importance > 0 && node.effort > 0) "  Importance ${node.importance} · Effort ${node.effort}" else "  not rated yet"
                Text(rating, fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.width(12.dp))
                ToolbarButton("Show on the map", onClick = {
                    state.rateBoardOpen = false
                    state.focusNode(node.id)
                }, color = Accent)
            }
            else -> Text("Click a card to read its whole title. Ctrl+Z undoes a drop.", fontSize = 11.sp, color = TextFaint)
        }
        Spacer(Modifier.weight(1f))
        ToolbarButton("Done", onClick = { state.rateBoardOpen = false }, color = Accent)
    }
}

/** The importance × effort plane, the "not rated yet" strip, and the chips on them. */
@Composable
private fun RateBoard(
    state: AppState,
    cards: List<Node>,
    picked: String?,
    onPick: (String?) -> Unit,
    onPreview: (Pair<Int, Int>?) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current.density
        val totalW = constraints.maxWidth.toFloat()
        val totalH = constraints.maxHeight.toFloat()
        val gutter = 30f * density
        val axisH = 22f * density
        val gap = 4f * density
        val chipH = 22f * density
        val planeW = totalW - gutter
        val rowsI = MAX_IMPORTANCE.toFloat()
        val colsE = MAX_EFFORT.toFloat()
        val chipW = (planeW / colsE - gap).coerceIn(64f * density, 150f * density)
        val rated = cards.filter { it.importance > 0 && it.effort > 0 }
        val waiting = cards.filter { it.importance == 0 || it.effort == 0 }
        val perRow = max(1, floor((planeW + gap) / (chipW + gap)).toInt())
        val rows = (waiting.size + perRow - 1) / perRow
        val stripH = if (waiting.isEmpty()) 30f * density else 24f * density + rows * (chipH + gap) + gap
        val planeH = max(160f * density, totalH - axisH - stripH)
        val stripTop = planeH + axisH

        /** Ratings for a chip whose centre is at ([cx], [cy]); null when it isn't over the plane. */
        fun ratingAt(cx: Float, cy: Float): Pair<Int, Int>? {
            if (cx < gutter || cx > totalW || cy < 0f || cy > planeH) return null
            val effort = (floor((cx - gutter) / planeW * colsE).toInt() + 1).coerceIn(1, MAX_EFFORT)
            val importance = (MAX_IMPORTANCE - floor(cy / planeH * rowsI).toInt()).coerceIn(1, MAX_IMPORTANCE)
            return importance to effort
        }

        // Where each chip rests: its cell on the plane (cards sharing a cell fan out), or its slot in the strip.
        val home = HashMap<String, Offset>()
        rated.groupBy { it.importance to it.effort }.forEach { (cell, group) ->
            val (i, e) = cell
            val cx = gutter + (e - 0.5f) / colsE * planeW
            val cy = (MAX_IMPORTANCE - i + 0.5f) / rowsI * planeH
            group.sortedBy { it.title.lowercase() }.forEachIndexed { j, n ->
                val dy = (j - (group.size - 1) / 2f) * chipH * 0.6f
                home[n.id] = Offset(cx - chipW / 2f, (cy - chipH / 2f + dy).coerceIn(0f, planeH - chipH))
            }
        }
        waiting.sortedBy { it.title.lowercase() }.forEachIndexed { j, n ->
            home[n.id] = Offset(gutter + (j % perRow) * (chipW + gap), stripTop + 22f * density + (j / perRow) * (chipH + gap))
        }

        // Effort 4 | 5 and importance 5 | 6 split the PICK quadrants; above importance 10 is urgent.
        val split = Offset(gutter + Pick.SMALL_JOB / colsE * planeW, (MAX_IMPORTANCE - Pick.IMPORTANT + 1) / rowsI * planeH)
        val urgentBottom = (MAX_IMPORTANCE - 10) / rowsI * planeH

        // Quadrants, grid and axes.
        Canvas(Modifier.fillMaxSize()) {
            fun quad(pick: Pick, l: Float, t: Float, r: Float, b: Float) =
                drawRect(pickColor(pick).copy(alpha = 0.09f), Offset(l, t), Size(r - l, b - t))
            quad(Pick.QuickWin, gutter, 0f, split.x, split.y)
            quad(Pick.BigBet, split.x, 0f, totalW, split.y)
            quad(Pick.FillIn, gutter, split.y, split.x, planeH)
            quad(Pick.Rethink, split.x, split.y, totalW, planeH)
            drawRect(importanceColor(MAX_IMPORTANCE).copy(alpha = 0.08f), Offset(gutter, 0f), Size(planeW, urgentBottom))
            for (k in 1 until MAX_EFFORT) {
                drawLine(PanelBorder.copy(alpha = 0.35f), Offset(gutter + k / colsE * planeW, 0f), Offset(gutter + k / colsE * planeW, planeH))
            }
            for (k in 1 until MAX_IMPORTANCE) {
                drawLine(PanelBorder.copy(alpha = 0.35f), Offset(gutter, k / rowsI * planeH), Offset(totalW, k / rowsI * planeH))
            }
            drawLine(importanceColor(MAX_IMPORTANCE).copy(alpha = 0.6f), Offset(gutter, urgentBottom), Offset(totalW, urgentBottom), strokeWidth = 1f * density)
            drawLine(TextFaint.copy(alpha = 0.6f), Offset(split.x, 0f), Offset(split.x, planeH), strokeWidth = 1.5f * density)
            drawLine(TextFaint.copy(alpha = 0.6f), Offset(gutter, split.y), Offset(totalW, split.y), strokeWidth = 1.5f * density)
            drawRect(PanelBorder, Offset(gutter, 0f), Size(planeW, planeH), style = androidx.compose.ui.graphics.drawscope.Stroke(1f * density))
            drawRect(PanelBorder.copy(alpha = 0.25f), Offset(gutter, stripTop), Size(planeW, totalH - stripTop))
        }
        QuadrantLabel(Pick.QuickWin, Offset(gutter + 8f * density, urgentBottom + 6f * density))
        QuadrantLabel(Pick.BigBet, Offset(split.x + 8f * density, urgentBottom + 6f * density))
        QuadrantLabel(Pick.FillIn, Offset(gutter + 8f * density, split.y + 6f * density))
        QuadrantLabel(Pick.Rethink, Offset(split.x + 8f * density, split.y + 6f * density))
        for (i in 1..MAX_IMPORTANCE) {
            AxisText("$i", Offset(4f * density, (MAX_IMPORTANCE - i + 0.5f) / rowsI * planeH - 8f * density), color = if (i > 10) importanceColor(i) else null)
        }
        for (e in 1..MAX_EFFORT) {
            AxisText("$e", Offset(gutter + (e - 0.5f) / colsE * planeW - 4f * density, planeH + 3f * density))
        }
        AxisText("Urgent", Offset(gutter + planeW - 52f * density, 4f * density), color = importanceColor(MAX_IMPORTANCE))
        AxisText("Matters more", Offset(gutter + planeW - 92f * density, urgentBottom + 6f * density), faint = true)
        AxisText("Bigger job", Offset(gutter + planeW - 70f * density, planeH - 18f * density), faint = true)
        AxisText(
            if (waiting.isEmpty()) "Every open card is rated. Drag one back here to clear its rating."
            else "Not rated yet (${waiting.size}): drag each one up onto the board",
            Offset(gutter + 4f * density, stripTop + 4f * density),
            faint = true,
        )

        var dragging by remember { mutableStateOf<String?>(null) }
        val ordered = cards.sortedBy { if (it.id == dragging) 1 else 0 }
        for (node in ordered) {
            key(node.id) {
                val rest = home[node.id] ?: Offset.Zero
                RateChip(
                    state, node, rest, chipW, chipH,
                    selected = node.id == picked,
                    onTap = { onPick(node.id) },
                    onDragStarted = { dragging = node.id; onPick(node.id) },
                    onDragMoved = { topLeft -> onPreview(ratingAt(topLeft.x + chipW / 2f, topLeft.y + chipH / 2f)) },
                    onDropped = { topLeft ->
                        dragging = null
                        onPreview(null)
                        val cx = topLeft.x + chipW / 2f
                        val cy = topLeft.y + chipH / 2f
                        val rating = ratingAt(cx, cy)
                        when {
                            rating != null -> state.rateNode(node.id, rating.first, rating.second)
                            cy > stripTop && node.importance > 0 && node.effort > 0 -> state.rateNode(node.id, 0, 0)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun QuadrantLabel(pick: Pick, at: Offset) {
    Text(
        pick.label.uppercase(),
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.SemiBold,
        color = pickColor(pick),
        modifier = Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) },
    )
}

@Composable
private fun AxisText(text: String, at: Offset, faint: Boolean = false, color: Color? = null) {
    Text(
        text,
        fontSize = if (faint) 10.sp else 11.sp,
        color = color ?: if (faint) TextFaint else TextSecondary,
        modifier = Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) },
    )
}

/** One card on the rating board: type dot and title, outlined in its quadrant's color. */
@Composable
private fun RateChip(
    state: AppState,
    node: Node,
    rest: Offset,
    widthPx: Float,
    heightPx: Float,
    selected: Boolean,
    onTap: () -> Unit,
    onDragStarted: () -> Unit,
    onDragMoved: (Offset) -> Unit,
    onDropped: (Offset) -> Unit,
) {
    val density = LocalDensity.current.density
    var drag by remember { mutableStateOf<Offset?>(null) }
    val at = rest + (drag ?: Offset.Zero)
    val outline = node.pick?.let { pickColor(it) } ?: PanelBorder
    val typeColor = state.nodeTypeOf(node.type).color
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .size((widthPx / density).dp, (heightPx / density).dp)
            .shadow(if (drag != null) 8.dp else 1.dp, shape)
            .clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.18f).compositeOver(PanelBackgroundRaised) else PanelBackgroundRaised)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Accent else outline.copy(alpha = 0.8f), shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(node.id) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(node.id, rest) {
                detectDragGestures(
                    onDragStart = {
                        drag = Offset.Zero
                        onDragStarted()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val d = (drag ?: Offset.Zero) + amount
                        drag = d
                        onDragMoved(rest + d)
                    },
                    onDragEnd = {
                        val d = drag ?: Offset.Zero
                        drag = null
                        onDropped(rest + d)
                    },
                    onDragCancel = {
                        drag = null
                        onDropped(rest)
                    },
                )
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(typeColor, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(5.dp))
        Text(
            node.title.ifBlank { "(untitled)" },
            fontSize = 11.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
