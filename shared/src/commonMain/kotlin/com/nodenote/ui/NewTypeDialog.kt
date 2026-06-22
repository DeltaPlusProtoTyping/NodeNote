package com.nodenote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.BuiltinTypes
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary

/**
 * Small modal for creating a custom node or connection type: a name and a
 * color swatch. The created type is added to the project (so it travels with
 * the file) and applied wherever the user invoked it.
 */
@Composable
fun NewTypeDialog(state: AppState) {
    val kind = state.newTypeKind ?: return
    val noun = if (kind == AppState.NewTypeKind.Node) "node type" else "connection type"

    var name by remember(kind) { mutableStateOf("") }
    var colorArgb by remember(kind) { mutableStateOf(BuiltinTypes.SWATCHES.first()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { state.closeNewType() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PanelBackground,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .width(340.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("New $noun", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(Modifier.height(12.dp))

                FieldLabel("Name")
                CompactTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "e.g. Lambda, Topic, Gateway…",
                )
                Spacer(Modifier.height(12.dp))

                FieldLabel("Color")
                Spacer(Modifier.height(6.dp))
                // Swatch grid (two rows of six).
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BuiltinTypes.SWATCHES.chunked(6).forEach { rowColors ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowColors.forEach { argb ->
                                val selected = argb == colorArgb
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(argb))
                                        .border(
                                            width = if (selected) 2.5.dp else 1.dp,
                                            color = if (selected) TextPrimary else PanelBorder,
                                            shape = CircleShape,
                                        )
                                        .clickable { colorArgb = argb },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Live preview of the swatch + name.
                    TypeDot(Color(colorArgb), 8.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        name.trim().ifBlank { "Custom" },
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ToolbarButton("Cancel", onClick = { state.closeNewType() }, color = TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { state.confirmNewType(name, colorArgb) },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(32.dp).wrapContentWidth(),
                    ) {
                        Text("Create", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
