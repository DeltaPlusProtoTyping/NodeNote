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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.state.ConnectionState
import com.nodenote.state.Workspace
import com.nodenote.storage.isWebPlatform
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import kotlinx.coroutines.launch

/** Scrim + centered card used by the workspace dialogs. Clicking the scrim dismisses (unless [dismissible] is false). */
@Composable
fun ModalCard(title: String, onDismiss: (() -> Unit)?, width: Dp = 440.dp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss?.invoke() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PanelBackground,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.width(width).pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.weight(1f))
                    if (onDismiss != null) {
                        CloseButton(onClick = { onDismiss() })
                    }
                }
                content()
            }
        }
    }
}

/**
 * Account + connection: change username or password (both re-confirm the
 * current password), sign out, and — desktop only — the server address.
 */
@Composable
fun SettingsDialog(workspace: Workspace) {
    if (!workspace.settingsOpen) return
    val session = workspace.session
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    var namePassword by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(session.serverUrl) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> String?, success: String, reset: () -> Unit) {
        busy = true
        scope.launch {
            val error = block()
            busy = false
            message = (error ?: success) to (error != null)
            if (error == null) reset()
        }
    }

    ModalCard(title = "Settings", onDismiss = { workspace.settingsOpen = false }, width = 460.dp) {
        if (session.onThisDevice) {
            DeviceSettings(workspace)
            return@ModalCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Signed in as ", fontSize = 13.sp, color = TextSecondary)
            Text(session.userName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = authorColor(session.userName))
            Spacer(Modifier.weight(1f))
            CompactOutlinedButton("Sign out", onClick = {
                workspace.settingsOpen = false
                session.signOut()
            })
        }

        PanelCaption("Change username")
        LoginField(newName, { newName = it }, placeholder = "New username")
        LoginField(namePassword, { namePassword = it }, secret = true, placeholder = "Current password")
        Row {
            Text(
                "Your past messages and nodes switch to the new name.",
                fontSize = 11.sp,
                color = TextFaint,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            CompactOutlinedButton("Change username", onClick = {
                if (!busy && newName.isNotBlank() && namePassword.isNotEmpty()) {
                    run({ session.changeUsername(namePassword, newName) }, "Username changed") {
                        newName = ""
                        namePassword = ""
                    }
                }
            })
        }

        PanelCaption("Change password")
        LoginField(currentPassword, { currentPassword = it }, secret = true, placeholder = "Current password")
        LoginField(newPassword, { newPassword = it }, secret = true, placeholder = "New password (8+ characters)")
        LoginField(confirmPassword, { confirmPassword = it }, secret = true, placeholder = "Repeat new password")
        Row {
            Spacer(Modifier.weight(1f))
            CompactOutlinedButton("Change password", onClick = {
                when {
                    busy || currentPassword.isEmpty() || newPassword.isEmpty() -> Unit
                    newPassword != confirmPassword -> message = "The new passwords don't match" to true
                    newPassword.length < 8 -> message = "Passwords need at least 8 characters" to true
                    else -> run({ session.changePassword(currentPassword, newPassword) }, "Password changed — other devices were signed out") {
                        currentPassword = ""
                        newPassword = ""
                        confirmPassword = ""
                    }
                }
            })
        }

        message?.let { (text, isError) ->
            Text(text, fontSize = 12.sp, color = if (isError) com.nodenote.theme.DangerRed else com.nodenote.theme.SuccessGreen)
        }

        if (!isWebPlatform) {
            PanelCaption("Server")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { LoginField(server, { server = it }) }
                Spacer(Modifier.width(6.dp))
                CompactOutlinedButton("Connect", onClick = {
                    if (server.trim().trimEnd('/') != session.serverUrl) {
                        workspace.settingsOpen = false
                        session.updateServerUrl(server) // signs out: sign in on the new server
                    }
                })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (label, color) = when (session.connection) {
                ConnectionState.Online -> "Connected to ${session.serverUrl}" to com.nodenote.theme.SuccessGreen
                ConnectionState.Connecting -> "Connecting to ${session.serverUrl}…" to com.nodenote.theme.WarnAmber
                ConnectionState.Offline -> "Can't reach ${session.serverUrl}${session.lastError?.let { " ($it)" } ?: ""}" to com.nodenote.theme.DangerRed
            }
            Text(label, fontSize = 11.sp, color = color)
        }
        session.serverInfo?.let { info ->
            Text("Obsidian vault on the server: ${info.vaultPath}", fontSize = 11.sp, color = TextFaint)
        }

        PanelCaption("Keep boards on this device instead")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "No server or account, just you. The server's boards stay on the server.",
                fontSize = 11.sp,
                color = TextFaint,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            CompactOutlinedButton("Use this device", onClick = {
                workspace.settingsOpen = false
                session.useThisDevice()
            })
        }
    }
}

/** Settings while the boards are kept on this device: the name on your messages, and the way to a server. */
@Composable
private fun DeviceSettings(workspace: Workspace) {
    val session = workspace.session
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(session.userName) }
    var server by remember { mutableStateOf(session.serverUrl) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    Text(
        "Your boards are kept on this device: no server, no account. Export a board to keep a copy elsewhere.",
        fontSize = 12.sp,
        color = TextSecondary,
    )
    PanelCaption("Your name (on your messages and cards)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { LoginField(name, { name = it }) }
        Spacer(Modifier.width(6.dp))
        CompactOutlinedButton("Rename", onClick = {
            scope.launch {
                val error = session.renameOnThisDevice(name)
                message = (error ?: "Renamed") to (error != null)
            }
        })
    }
    message?.let { (text, isError) ->
        Text(text, fontSize = 12.sp, color = if (isError) com.nodenote.theme.DangerRed else com.nodenote.theme.SuccessGreen)
    }

    PanelCaption("Use a server instead")
    Text(
        "To share boards with others and mirror them into Obsidian. The boards on this device stay here.",
        fontSize = 11.sp,
        color = TextFaint,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!isWebPlatform) {
            Box(Modifier.weight(1f)) { LoginField(server, { server = it }) }
            Spacer(Modifier.width(6.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        CompactOutlinedButton(if (isWebPlatform) "Sign in to this site's server" else "Connect", onClick = {
            workspace.settingsOpen = false
            session.useServer(if (isWebPlatform) session.serverUrl else server)
        })
    }
}

/** Name a new, empty board. */
@Composable
fun NewBoardDialog(workspace: Workspace) {
    if (!workspace.newBoardOpen) return
    var name by remember { mutableStateOf("") }

    fun create() {
        if (name.isBlank()) return
        workspace.createBoard(name)
        workspace.newBoardOpen = false
    }

    ModalCard(title = "New board", onDismiss = { workspace.newBoardOpen = false }) {
        Text(
            "A board is one map of related topics — e.g. \"Shop floor\", \"Estimating\", \"Q4 improvements\". " +
                "Each node on it gets its own chat.",
            fontSize = 12.sp,
            color = TextSecondary,
            lineHeight = 17.sp,
        )
        CompactTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), placeholder = "Board name")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { create() }, enabled = name.isNotBlank(), shape = RoundedCornerShape(6.dp), modifier = Modifier.height(32.dp)) {
                Text("Create", fontSize = 12.sp)
            }
        }
    }
}
