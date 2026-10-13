package dev.dsh.pocket.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.awaitCancellation

@Composable
fun TerminalSheet(sessionId: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null) {
    val revision by Pocket.terminalRevision.collectAsState()
    val meta by Pocket.terminalMeta.collectAsState()
    val active by Pocket.active.collectAsState()
    val connections by Pocket.status.collectAsState()
    var opening by remember(sessionId) { mutableStateOf(true) }
    val online = connections[active] == Dot.Online
    val current = meta?.takeIf { it.sessionId == sessionId && !opening }
    val en = Pocket.prefs.language == AppLanguage.En

    LaunchedEffect(sessionId) {
        val attachment = Pocket.openTerminal(sessionId)
        try {
            attachment.join()
            opening = false
            awaitCancellation()
        } finally {
            // Leave the host shell alive; only this panel's output subscription is released.
            attachment.cancel()
            Pocket.closeTerminalFollow()
        }
    }

    RightPanelFrame(
        title = current?.title?.takeIf { it.isNotBlank() } ?: if (en) "Terminal" else "终端",
        onDismiss = onDismiss,
        onBack = onBack,
    ) {
        val status = when {
            !online -> if (en) "Disconnected." else "连接已断开。"
            opening -> if (en) "Connecting…" else "正在连接…"
            current == null -> if (en) "Unavailable" else "不可用"
            current.state == "closed" || current.state == "exited" -> if (en) "Terminal closed." else "终端已关闭。"
            current.state != "running" -> if (en) "Unavailable" else "不可用"
            else -> null
        }
        if (current != null) {
            status?.let {
                Text(it, color = T.Text3, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            TerminalView(
                terminal = Pocket.terminal,
                revision = revision,
                modifier = Modifier.fillMaxSize().padding(12.dp),
                onResize = { cols, rows -> Pocket.resizeTerminal(cols, rows) },
                onInput = { if (online && current.state == "running") Pocket.writeTerminal(it) },
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (opening && online) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = T.Accent, strokeWidth = 2.dp)
                    }
                    Text(status.orEmpty(), color = T.Text3, fontSize = 13.sp)
                }
            }
        }
    }
}
