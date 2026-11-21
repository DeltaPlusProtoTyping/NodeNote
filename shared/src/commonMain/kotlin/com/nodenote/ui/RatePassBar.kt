package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary

/**
 * The rate pass, over the top of the map: which card of how many, the 1-12 picker for it, Skip
 * and Stop. Number keys rate too (1-9, 0 = 10), Space skips, Escape stops.
 */
@Composable
fun RatePassBar(state: AppState, modifier: Modifier = Modifier) {
    val id = state.ratePass.firstOrNull() ?: return
    val node = state.project.nodes.find { it.id == id } ?: return
    val done = state.ratePassTotal - state.ratePass.size + 1
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .pointerInput(Unit) { detectTapGestures { } }
            .shadow(6.dp, shape)
            .clip(shape)
            .background(PanelBackground.copy(alpha = 0.97f))
            .border(1.dp, Accent.copy(alpha = 0.7f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RATE  $done of ${state.ratePassTotal}", fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Accent)
            Spacer(Modifier.width(10.dp))
            Text(
                node.title.ifBlank { "(untitled)" },
                fontSize = 12.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ImportancePicker(node.importance, onChange = { v ->
                state.setNodeImportance(id, v)
                state.nextInRatePass()
            }, cell = 22.dp)
            Spacer(Modifier.width(10.dp))
            PassAction("Skip") { state.nextInRatePass() }
            PassAction("Stop") { state.endRatePass() }
        }
        Text("Keys: 1-9, 0 for 10 · Space skips · Esc stops. 1 minor, 10 critical, 11-12 urgent.", fontSize = 10.sp, color = TextFaint)
    }
}

@Composable
private fun PassAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = TextSecondary,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
