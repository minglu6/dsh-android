package dev.dsh.pocket.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

private fun terminalText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

@Composable
fun TerminalSheet(sessionId: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null) {
    val revision by Pocket.terminalRevision.collectAsState()
    val meta by Pocket.terminalMeta.collectAsState()
    val active by Pocket.active.collectAsState()
    val connections by Pocket.status.collectAsState()
    val ops by Pocket.ops.collectAsState()
    var opening by remember(sessionId) { mutableStateOf(true) }
    var attempt by remember(sessionId) { mutableIntStateOf(0) }
    var forceNew by remember(sessionId) { mutableStateOf(false) }
    var everAttached by remember(sessionId) { mutableStateOf(false) }
    var confirmTerminate by remember(sessionId) { mutableStateOf(false) }
    var terminating by remember(sessionId) { mutableStateOf(false) }
    var operationError by remember(sessionId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val online = connections[active] == Dot.Online
    val current = meta?.takeIf { it.sessionId == sessionId && !opening }
    val running = online && !opening && !terminating && current?.state == "running"

    LaunchedEffect(sessionId, online, attempt) {
        if (!online) { opening = false; return@LaunchedEffect }
        opening = true
        operationError = null
        val attachment = Pocket.openTerminal(sessionId, forceNew = forceNew)
        forceNew = false
        try {
            attachment.join()
            opening = false
            if (Pocket.terminalMeta.value?.sessionId == sessionId) everAttached = true
            awaitCancellation()
        } finally {
            attachment.cancel()
            // Back and collapse only detach. Killing the shell is an explicit, confirmed action.
            Pocket.closeTerminalFollow()
        }
    }

    if (confirmTerminate) AlertDialog(
        onDismissRequest = { confirmTerminate = false },
        title = { Text(terminalText("终止终端？", "Terminate terminal?")) },
        text = { Text(terminalText("这将结束 Shell 和其中正在运行的命令。仅关闭面板不会终止它们。", "This ends the shell and its running commands. Closing the panel only detaches.")) },
        confirmButton = {
            TextButton(onClick = {
                confirmTerminate = false
                terminating = true
                scope.launch {
                    try { Pocket.terminateTerminal(sessionId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { operationError = failure.message }
                    finally { terminating = false }
                }
            }) { Text(terminalText("终止", "Terminate"), color = T.Err) }
        },
        dismissButton = { TextButton(onClick = { confirmTerminate = false }) { Text(terminalText("取消", "Cancel")) } },
    )

    RightPanelFrame(
        title = current?.title?.takeIf { it.isNotBlank() } ?: terminalText("终端", "Terminal"),
        onDismiss = onDismiss,
        onBack = onBack,
    ) {
        val status = when {
            !online -> terminalText("连接已断开；连接恢复后会重新附加。", "Disconnected; reattaching when the connection returns.")
            opening -> terminalText("正在连接…", "Connecting…")
            terminating -> terminalText("正在终止…", "Terminating…")
            current == null -> terminalText("终端不可用", "Terminal unavailable")
            current.state == "closed" || current.state == "exited" -> terminalText("终端已结束。", "Terminal ended.")
            current.state != "running" -> terminalText("终端不可用", "Terminal unavailable")
            else -> null
        }
        if (status != null || operationError != null) {
            Text(operationError ?: status.orEmpty(), color = if (operationError != null) T.Err else T.Text3, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (!running) {
                TextButton(onClick = { attempt++ }, enabled = online && !opening && !terminating) {
                    Icon(D.refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(terminalText("重新连接", "Reconnect"))
                }
                TextButton(onClick = { forceNew = true; attempt++ }, enabled = online && !opening && !terminating && "terminal.create" in ops) {
                    Text(terminalText("新建终端", "New terminal"))
                }
            }
            if (current != null && "terminal.close" in ops) TextButton(onClick = { confirmTerminate = true }, enabled = running) {
                Text(terminalText("终止终端", "Terminate"), color = if (running) T.Err else T.Text3)
            }
        }
        if (current != null || everAttached) {
            TerminalView(
                terminal = Pocket.terminal,
                revision = revision,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp),
                enabled = running,
                onResize = { cols, rows -> if (online) Pocket.resizeTerminal(cols, rows) },
                onInput = { if (running) Pocket.writeTerminal(it) },
            )
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (opening && online) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = T.Accent, strokeWidth = 2.dp)
            }
        }
    }
}
