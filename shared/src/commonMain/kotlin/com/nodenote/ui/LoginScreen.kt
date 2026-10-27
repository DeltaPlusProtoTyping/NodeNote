package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.state.Session
import com.nodenote.storage.isWebPlatform
import com.nodenote.storage.readClipboardText
import com.nodenote.theme.Accent
import com.nodenote.theme.CanvasBackground
import com.nodenote.theme.DangerRed
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Full-window sign-in. Desktop can also change the server address here (the browser always
 * uses its own). A fresh server with no accounts yet (a new install) gets "create your
 * account" instead, which makes the first account and signs straight in. A server shared with
 * a group asks for a name and the group's password (accounts can still sign in below it).
 */
@Composable
fun LoginScreen(session: Session, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(session.userName) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(session.serverUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Fresh install? Asked again whenever the server address changes.
    LaunchedEffect(session.api) { session.checkServer() }
    val setup = session.serverInfo?.needsSetup == true
    var withAccount by remember { mutableStateOf(false) }
    val group = !setup && !withAccount && session.serverInfo?.groupSignIn == true

    fun submit() {
        if (busy || username.isBlank() || password.isEmpty()) return
        if (setup && password != repeat) {
            error = "The two passwords don't match"
            return
        }
        if (!isWebPlatform && server.trim().trimEnd('/') != session.serverUrl) session.updateServerUrl(server)
        busy = true
        error = null
        scope.launch {
            error = when {
                setup -> session.setUp(username, password)
                group -> session.join(username, password)
                else -> session.signIn(username, password)
            }
            busy = false
            if (error != null) {
                password = ""
                repeat = ""
            }
        }
    }

    Box(modifier.fillMaxSize().background(CanvasBackground), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .padding(16.dp)
                .background(PanelBackground, RoundedCornerShape(12.dp))
                .border(1.dp, PanelBorder, RoundedCornerShape(12.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(Accent, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(10.dp))
                Text("NodeNote", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            if (setup) {
                Text("Welcome! This NodeNote has no accounts yet: create yours.", fontSize = 13.sp, color = TextSecondary)
                Text(
                    "Your boards are kept on this server. You'll sign in with this name and password from other devices too.",
                    fontSize = 11.sp,
                    color = TextFaint,
                )
            } else if (group) {
                Text("Join the group's boards", fontSize = 13.sp, color = TextSecondary)
                Text("Your name goes on the cards and messages you add.", fontSize = 11.sp, color = TextFaint)
            } else {
                Text("Sign in to your boards", fontSize = 13.sp, color = TextSecondary)
            }
            session.signInNotice?.let { Text(it, fontSize = 12.sp, color = TextSecondary) }
            Spacer(Modifier.height(4.dp))

            FieldLabel(if (setup || group) "Your name" else "Username")
            LoginField(username, { username = it }, onEnter = ::submit)
            FieldLabel(
                when {
                    setup -> "Password (8+ characters)"
                    group -> "Group password"
                    else -> "Password"
                },
            )
            LoginField(password, { password = it }, onEnter = ::submit, secret = true)
            if (setup) {
                FieldLabel("Password again")
                LoginField(repeat, { repeat = it }, onEnter = ::submit, secret = true)
            }
            if (!isWebPlatform) {
                FieldLabel("Server")
                LoginField(server, { server = it }, onEnter = ::submit)
            }
            error?.let { Text(it, fontSize = 12.sp, color = DangerRed) }
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(
                onClick = ::submit,
                enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().height(36.dp),
            ) {
                Text(
                    when {
                        setup && busy -> "Creating…"
                        setup -> "Create account"
                        busy -> "Signing in…"
                        group -> "Join"
                        else -> "Sign in"
                    },
                    fontSize = 13.sp,
                )
            }
            if (session.serverInfo?.groupSignIn == true && !setup) {
                Text(
                    if (group) "Have an account? Sign in with it" else "Join with the group password instead",
                    fontSize = 12.sp,
                    color = Accent,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable {
                        withAccount = !withAccount
                        error = null
                    }.padding(vertical = 4.dp),
                )
            }
            if (!setup && !group) Text("Forgot your password? Whoever runs this server can reset it.", fontSize = 11.sp, color = TextFaint)
            Spacer(Modifier.height(6.dp))
            Text(
                "No server? Keep your boards on this device instead",
                fontSize = 12.sp,
                color = Accent,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { session.useThisDevice() }.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FieldAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = Accent,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Plain text field for the sign-in and account forms; Enter submits. Password fields get Paste/Show buttons. */
@Composable
fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    onEnter: () -> Unit = {},
    secret: Boolean = false,
    placeholder: String = "",
) {
    var focused by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
        cursorBrush = SolidColor(Accent),
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    onEnter()
                    true
                } else {
                    false
                }
            }
            .background(PanelBackgroundRaised, shape)
            .border(1.dp, if (focused) Accent else PanelBorder, shape)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = 14.sp, color = TextFaint)
                    inner()
                }
                if (secret) {
                    // Phones can't long-press-paste into these fields, and a password
                    // manager's copy is the usual way in — so paste and reveal are buttons.
                    FieldAction("Paste") { readClipboardText { text -> text?.trim()?.takeIf { it.isNotEmpty() }?.let(onValueChange) } }
                    FieldAction(if (reveal) "Hide" else "Show") { reveal = !reveal }
                }
            }
        },
    )
}
