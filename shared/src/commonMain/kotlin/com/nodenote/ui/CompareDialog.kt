package com.nodenote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.ProjectDiff
import com.nodenote.state.AppState
import com.nodenote.theme.DangerRed
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.SuccessGreen
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.WarnAmber

/**
 * Modal that shows how the open project differs from a chosen file. Read-only:
 * the open project is never modified. Additions are framed from the open
 * project's point of view ("in this project, not in the file").
 */
@Composable
fun CompareDialog(state: AppState) {
    val diff = state.compareDiff ?: return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { state.closeCompareDiff() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PanelBackground,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .sizeIn(maxWidth = 620.dp, maxHeight = 620.dp)
                .fillMaxWidth(0.7f)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Compare", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(
                            "this project  vs  ${diff.otherName}",
                            fontSize = 11.sp,
                            color = TextFaint,
                        )
                    }
                    Text(
                        "✕",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { state.closeCompareDiff() }
                            .padding(6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (diff.isEmpty) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("No differences — the projects match.", fontSize = 13.sp, color = TextSecondary)
                    }
                } else {
                    SummaryRow(diff)
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DiffSection("Nodes added", diff.addedNodes, SuccessGreen, "+")
                        DiffSection("Nodes removed", diff.removedNodes, DangerRed, "−")
                        DiffSection("Nodes changed", diff.changedNodes, WarnAmber, "~")
                        DiffSection("Connections added", diff.addedEdges, SuccessGreen, "+")
                        DiffSection("Connections removed", diff.removedEdges, DangerRed, "−")
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(diff: ProjectDiff) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("+${diff.addedNodes.size + diff.addedEdges.size}", SuccessGreen)
        Pill("−${diff.removedNodes.size + diff.removedEdges.size}", DangerRed)
        Pill("~${diff.changedNodes.size}", WarnAmber)
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DiffSection(title: String, items: List<String>, color: Color, marker: String) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        PanelCaption("$title (${items.size})")
        items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    Text(marker, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(6.dp))
                Text(item, fontSize = 12.sp, color = TextPrimary)
            }
        }
    }
}
