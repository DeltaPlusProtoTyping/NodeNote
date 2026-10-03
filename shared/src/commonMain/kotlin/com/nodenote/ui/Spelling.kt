package com.nodenote.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.spell.Misspelling
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextSecondary

/** Squiggle color for misspelled words. */
val TypoRed = Color(0xFFE2606B)

/**
 * Red wavy underlines under [typos] in text laid out as [layout] — drawn in the
 * text's own coordinates, so apply it inside any padding of the text field.
 */
fun DrawScope.drawTypoSquiggles(layout: TextLayoutResult?, typos: List<Misspelling>, textLength: Int) {
    if (layout == null || typos.isEmpty()) return
    val amp = 1.3f * density
    val step = 2.6f * density
    for (typo in typos) {
        if (typo.end > textLength || typo.start >= typo.end) continue
        // One stroke per line the word spans (a long word can wrap).
        var i = typo.start
        while (i < typo.end) {
            val line = layout.getLineForOffset(i)
            val lineEnd = minOf(typo.end, layout.getLineEnd(line, visibleEnd = true).coerceAtLeast(i + 1))
            val left = layout.getBoundingBox(i).left
            val right = layout.getBoundingBox(lineEnd - 1).right
            val y = layout.getLineBottom(line) - amp
            val path = Path().apply {
                moveTo(left, y)
                var x = left
                var up = true
                while (x < right) {
                    x = minOf(x + step, right)
                    lineTo(x, if (up) y - amp else y + amp * 0.2f)
                    up = !up
                }
            }
            drawPath(path, TypoRed, style = Stroke(width = 1.1f * density))
            i = lineEnd
        }
    }
}

/**
 * "inneficiencies → Inefficiencies · Add" for the first misspelled word in [text]:
 * a tap on a suggestion calls [onReplace], "Add" teaches the dictionary the word.
 */
@Composable
fun SpellingSuggestions(state: AppState, typos: List<Misspelling>, onReplace: (Misspelling, String) -> Unit, modifier: Modifier = Modifier) {
    val spell = state.spell ?: return
    val typo = typos.firstOrNull() ?: return
    val suggestions = remember(typo.word, spell) { spell.suggest(typo.word, 3) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TypoRed, textDecoration = TextDecoration.LineThrough)) { append(typo.word) }
                if (typos.size > 1) withStyle(SpanStyle(color = TextFaint)) { append("  +${typos.size - 1}") }
            },
            fontSize = 11.sp,
        )
        if (suggestions.isEmpty()) Text("no suggestions", fontSize = 10.sp, color = TextFaint)
        suggestions.forEachIndexed { i, s ->
            FilterChip(s, selected = i == 0, color = Accent) { onReplace(typo, s) }
        }
        FilterChip("Add to dictionary", selected = false, color = TextSecondary) { state.addToDictionary(typo.word) }
    }
}
