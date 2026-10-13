package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Termux sidecar connect + dead-port guidance.
 * Themed PocketSheet only (no AlertDialog). Command blocks use Tokyo Night + Meslo.
 */
@Composable
fun TermuxConnectSheet(
    initialPaste: String = "",
    onDismiss: () -> Unit,
    onConnected: (Profile) -> Unit,
) {
    var portText by remember { mutableStateOf(TermuxSidecar.DEFAULT_PORT.toString()) }
    var paste by remember(initialPaste) { mutableStateOf(initialPaste) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(L.termuxDefaultName()) }
    /** UI reminder only — never causes the app to run su/tsu. */
    var privilegeReminder by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val gate = remember { PairingAttemptGate() }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { gate.invalidate() } }

    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    val credential = remember(paste, portText, code) {
        TermuxSidecar.parseCredential(
            paste = paste,
            portFallback = port ?: TermuxSidecar.DEFAULT_PORT,
            codeFallback = code,
        )
    }

    // One sheet at a time — no nested ModalBottomSheet / AlertDialog.
    if (showHelp) {
        TermuxHelpSheet(onDismiss = { showHelp = false })
        return
    }

    PocketSheet(onDismiss) {
        SheetTitle(L.termuxMode())
        Text(
            L.termuxModeHint(),
            fontSize = 13.sp,
            color = T.Text3,
            lineHeight = 19.sp,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp),
        )
        TermuxField(L.termuxPort(), portText, "7690") {
            portText = it.filter(Char::isDigit).take(5)
            err = null
        }
        TermuxField(L.termuxName(), name, L.termuxDefaultName()) { name = it.take(64); err = null }
        TermuxField(
            L.termuxPaste(),
            paste,
            "dshpocket://pair?h=127.0.0.1:7690&c=…… 或 token",
            singleLine = false,
        ) {
            paste = it
            err = null
            TermuxSidecar.parseCredential(it, port ?: TermuxSidecar.DEFAULT_PORT)?.let { cred ->
                when (cred) {
                    is TermuxSidecar.Credential.PairCode -> {
                        portText = cred.port.toString()
                        code = cred.code
                    }
                    is TermuxSidecar.Credential.Token -> portText = cred.port.toString()
                }
            }
        }
        if (credential !is TermuxSidecar.Credential.Token) {
            TermuxField(L.termuxPairCode(), code, "6 位配对码") {
                code = it.filter(Char::isDigit).take(6)
                err = null
            }
        }
        Row(
            Modifier
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .fillMaxWidth()
                .clip(T.R12)
                .clickable { privilegeReminder = !privilegeReminder }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                privilegeReminder,
                { privilegeReminder = it },
                colors = CheckboxDefaults.colors(checkedColor = T.Ink, checkmarkColor = Color.White),
            )
            Column(Modifier.padding(start = 4.dp)) {
                Text(L.termuxPrivilegeLabel(), fontSize = 15.sp, color = T.Ink)
                Text(L.termuxPrivilegeHint(), fontSize = 12.sp, color = T.Text3, lineHeight = 17.sp)
            }
        }
        if (privilegeReminder) {
            TokyoCommandBlock(TermuxSidecar.PRIVILEGE_REMINDER_COMMANDS, testTag = "termuxPrivilegeCommands")
        }
        err?.let {
            Text(it, color = T.Err, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        }
        TextButton(
            { showHelp = true },
            Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        ) {
            Text(L.termuxShowHelp(), fontSize = 13.sp, color = T.Accent)
        }
        val ready = credential != null && port != null && !busy
        Button(
            {
                val cred = credential ?: return@Button
                val generation = gate.begin() ?: return@Button
                busy = true
                err = null
                scope.launch {
                    val alive = withContext(Dispatchers.IO) {
                        TermuxSidecar.probeLoopback(cred.hostOrDefault(), cred.portOrDefault())
                    }
                    if (!alive) {
                        if (gate.complete(generation)) {
                            busy = false
                            showHelp = true
                            err = L.termuxPortDead()
                        }
                        return@launch
                    }
                    val result = when (cred) {
                        is TermuxSidecar.Credential.PairCode ->
                            Pocket.pairHost(name.trim().ifBlank { L.termuxDefaultName() }, cred.host, cred.port, cred.code)
                        is TermuxSidecar.Credential.Token ->
                            Pocket.connectWithToken(name.trim().ifBlank { L.termuxDefaultName() }, cred.host, cred.port, cred.token)
                    }
                    if (gate.complete(generation)) {
                        result
                            .onSuccess(onConnected)
                            .onFailure { e ->
                                err = e.message
                                val stillDead = withContext(Dispatchers.IO) {
                                    !TermuxSidecar.probeLoopback(cred.hostOrDefault(), cred.portOrDefault())
                                }
                                if (stillDead) showHelp = true
                            }
                        busy = false
                    }
                }
            },
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp).fillMaxWidth().height(52.dp).testTag("termuxConnect"),
            enabled = ready,
            shape = T.R12,
            colors = PrimaryColors(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(L.termuxConnect(), fontSize = 16.sp, color = Color.White)
        }
    }
}

@Composable
fun TermuxHelpSheet(onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Terminal, null, tint = T.Ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            SheetTitle(L.termuxHelpTitle())
        }
        Text(
            L.termuxHelpBody(),
            fontSize = 14.sp,
            color = T.Text2,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
        )
        Text(
            L.termuxInstallers(),
            fontSize = 12.sp,
            color = T.Text3,
            lineHeight = 18.sp,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp),
        )
        SectionLabel(L.termuxBootstrap())
        TokyoCommandBlock(TermuxSidecar.BOOTSTRAP_COMMANDS, testTag = "termuxBootstrapCommands")
        SectionLabel(L.termuxPrivilegeLabel())
        TokyoCommandBlock(TermuxSidecar.PRIVILEGE_REMINDER_COMMANDS, testTag = "termuxHelpPrivilegeCommands")
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TermuxField(
    label: String,
    value: String,
    hint: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) {
        Text(label, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(T.R12)
                .background(T.Fill2)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            if (value.isEmpty()) Text(hint, color = T.Caption, fontSize = 15.sp)
            BasicTextField(
                value,
                onChange,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontFamily = MesloNerdFamily),
                cursorBrush = SolidColor(T.Accent),
                keyboardOptions = if (label == L.termuxPort() || label == L.termuxPairCode()) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().then(
                    if (singleLine) Modifier else Modifier.heightIn(min = 72.dp),
                ),
            )
        }
    }
}

/** Tokyo Night code surface with Meslo + one-tap copy (no AlertDialog). */
@Composable
fun TokyoCommandBlock(text: String, testTag: String = "tokyoCommandBlock") {
    val clip = LocalClipboardManager.current
    Column(
        Modifier
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TokyoNight.Bg)
            .testTag(testTag),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Termux",
                style = T.Mono.copy(fontSize = 11.sp, color = TokyoNight.Ansi[8], fontFamily = MesloNerdFamily),
                modifier = Modifier.weight(1f),
            )
            Text(
                L.copy(),
                fontSize = 12.sp,
                color = TokyoNight.Ansi[4],
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(T.R8)
                    .clickable {
                        clip.setText(AnnotatedString(text))
                        Pocket.notices.tryEmit(L.copied())
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("${testTag}Copy"),
            )
        }
        SelectableOutput {
            Text(
                text,
                style = T.Mono.copy(
                    fontSize = 12.sp,
                    color = TokyoNight.Fg,
                    fontFamily = MesloNerdFamily,
                    lineHeight = 18.sp,
                ),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth(),
            )
        }
    }
}

private fun TermuxSidecar.Credential.hostOrDefault(): String = when (this) {
    is TermuxSidecar.Credential.PairCode -> host
    is TermuxSidecar.Credential.Token -> host
}

private fun TermuxSidecar.Credential.portOrDefault(): Int = when (this) {
    is TermuxSidecar.Credential.PairCode -> port
    is TermuxSidecar.Credential.Token -> port
}
