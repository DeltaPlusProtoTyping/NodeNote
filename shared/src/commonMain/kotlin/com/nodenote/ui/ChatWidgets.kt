package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.MessageKind
import com.nodenote.model.NodeLinkRegex
import com.nodenote.model.NodeStatus
import com.nodenote.model.Project
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/*
 * Small pieces shared by the chat, key-details and node views: importance
 * colors/badges/picker, message-kind and status pills, author colors, time
 * formatting, and message text with clickable [[node]] links.
 */

/**
 * Text style for short labels inside small boxes (badges, pills): line height =
 * font size with the font's extra top/bottom padding trimmed, so digits sit dead
 * center instead of riding low.
 */
fun tightText(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = TextPrimary) = TextStyle(
    fontSize = size,
    fontWeight = weight,
    color = color,
    lineHeight = size,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** 1–2 slate, 3–4 blue, 5–6 amber, 7–8 orange, 9–10 red. 0 (unrated) is faint. */
fun importanceColor(importance: Int): Color = when (importance) {
    0 -> TextFaint
    1, 2 -> Color(0xFF8FA0B3)
    3, 4 -> Color(0xFF5B9CFF)
    5, 6 -> Color(0xFFE5B25B)
    7, 8 -> Color(0xFFE58E5B)
    else -> Color(0xFFE2606B)
}

/** Filled square with the importance number; nothing is drawn for 0 unless [showUnrated]. */
@Composable
fun ImportanceBadge(importance: Int, modifier: Modifier = Modifier, size: Dp = 18.dp, showUnrated: Boolean = false) {
    if (importance <= 0 && !showUnrated) return
    val color = importanceColor(importance)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(if (importance > 0) color.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = if (importance > 0) 0.9f else 0.5f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (importance > 0) importance.toString() else "–",
            style = tightText((size.value * 0.58f).sp, FontWeight.Bold, if (importance > 0) color else TextFaint),
        )
    }
}

/** A row of cells "–, 1 … 10"; clicking the selected value again clears it. */
@Composable
fun ImportancePicker(value: Int, onChange: (Int) -> Unit, cell: Dp = 20.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (n in 0..10) {
            val selected = n == value
            val color = importanceColor(n)
            Box(
                Modifier
                    .size(cell)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selected) color.copy(alpha = 0.35f) else Color.Transparent)
                    .border(1.dp, if (selected) color else PanelBorder, RoundedCornerShape(4.dp))
                    .clickable { onChange(if (selected) 0 else n) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (n == 0) "–" else n.toString(),
                    style = tightText(
                        (cell.value * 0.52f).sp,
                        if (selected) FontWeight.Bold else FontWeight.Normal,
                        if (n == 0) TextFaint else color,
                    ),
                )
            }
        }
    }
}

/** Colored pill used for message kinds and node statuses. */
@Composable
fun Pill(label: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = if (filled) 0.30f else 0.14f))
            .border(1.dp, color.copy(alpha = if (filled) 0.9f else 0.35f), RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(4.dp))
        Text(label, style = tightText(10.sp), maxLines = 1, modifier = Modifier.padding(vertical = 2.dp))
    }
}

val MessageKind.color: Color get() = Color(colorArgb)
val NodeStatus.color: Color get() = Color(colorArgb)

@Composable
fun KindPill(kind: MessageKind, modifier: Modifier = Modifier, filled: Boolean = false, onClick: (() -> Unit)? = null) =
    Pill(kind.label, kind.color, modifier, filled, onClick)

@Composable
fun StatusPill(status: NodeStatus, modifier: Modifier = Modifier, filled: Boolean = false, onClick: (() -> Unit)? = null) =
    Pill(status.label, status.color, modifier, filled, onClick)

private val authorPalette = listOf(
    Color(0xFF5B9CFF), Color(0xFF53C27C), Color(0xFFE58E5B), Color(0xFFD97BC8),
    Color(0xFF45C4D6), Color(0xFFF0C04A), Color(0xFF9B7BFF), Color(0xFFE2606B),
)

/** Stable per-name color so Ben and Nick are recognizable at a glance. */
fun authorColor(name: String): Color {
    var h = 7
    for (c in name.lowercase()) h = h * 31 + c.code
    return authorPalette[(h and 0x7fffffff) % authorPalette.size]
}

// ---- Time ----

private fun local(ms: Long): LocalDateTime =
    Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())

private fun two(n: Int) = n.toString().padStart(2, '0')

fun formatClock(ms: Long): String = local(ms).let { "${two(it.hour)}:${two(it.minute)}" }

private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Today", "Yesterday", or "Mon Sep 28" (with the year when it isn't this year). */
fun formatDay(ms: Long): String {
    val d = local(ms).date
    val today = local(Clock.System.now().toEpochMilliseconds()).date
    return when {
        d == today -> "Today"
        d.toEpochDays() == today.toEpochDays() - 1 -> "Yesterday"
        else -> buildString {
            append(d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() })
            append(' ').append(monthNames[d.monthNumber - 1]).append(' ').append(d.dayOfMonth)
            if (d.year != today.year) append(", ").append(d.year)
        }
    }
}

fun dayKey(ms: Long): Int = local(ms).date.toEpochDays()

/** "Sep 28 14:03" — compact stamp for lists that mix days. */
fun formatStamp(ms: Long): String = local(ms).let { "${monthNames[it.monthNumber - 1]} ${it.dayOfMonth} ${two(it.hour)}:${two(it.minute)}" }

// ---- Message text ----

private val UrlRegex = Regex("""https?://[^\s<>()\[\]]+""")

/**
 * Message text with `[[Node title]]` links turned into clickable node names
 * (unknown titles stay as typed) and bare URLs made clickable.
 */
fun linkifyMessage(text: String, project: Project, linkColor: Color, onNode: (String) -> Unit): AnnotatedString {
    val byTitle = project.nodes.associateBy { it.title.trim().lowercase() }
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium))
    return buildAnnotatedString {
        var i = 0
        val tokens = (NodeLinkRegex.findAll(text).map { it to true } + UrlRegex.findAll(text).map { it to false })
            .sortedBy { it.first.range.first }
        for ((match, isNode) in tokens) {
            if (match.range.first < i) continue // overlapping (a URL inside a [[link]])
            append(text.substring(i, match.range.first))
            if (isNode) {
                val title = match.groupValues[1].trim()
                val node = byTitle[title.lowercase()]
                val shown = match.value.substringAfter('|', "").removeSuffix("]]").ifBlank { title }
                if (node != null) {
                    withLink(LinkAnnotation.Clickable("node:${node.id}", linkStyle) { onNode(node.id) }) { append(shown) }
                } else {
                    withStyle(SpanStyle(color = TextFaint, textDecoration = TextDecoration.LineThrough)) { append(shown) }
                }
            } else {
                withLink(LinkAnnotation.Url(match.value, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                    append(match.value)
                }
            }
            i = match.range.last + 1
        }
        append(text.substring(i))
    }
}

/** First line of a message with [[links]] flattened — for one-line previews. */
fun previewLine(text: String, max: Int = 140): String {
    val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        .replace(NodeLinkRegex) { it.groupValues[1] }
        .trim()
    return if (line.length > max) line.take(max - 1) + "…" else line
}

/** Small clickable node reference chip, e.g. in the board feed. */
@Composable
fun NodeChip(title: String, dot: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .border(1.dp, PanelBorder, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp)
            .height(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).background(dot, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 10.sp, color = Accent, maxLines = 1)
    }
}
