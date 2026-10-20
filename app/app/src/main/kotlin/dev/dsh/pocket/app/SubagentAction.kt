package dev.dsh.pocket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.SessionAddress

@Composable
internal fun SubagentAction(sessionId: String) {
    val children by Pocket.subagents.collectAsState()
    val chat by Pocket.chat.collectAsState()
    val active by Pocket.active.collectAsState()
    val status by Pocket.status.collectAsState()
    val online = status[active] == Dot.Online
    val entries = children ?: return
    if (entries.isEmpty()) return
    var open by remember(active, sessionId) { mutableStateOf(false) }
    val label = teamText("${entries.size} 个子智能体", "${entries.size} subagents")
    Box {
        Row(Modifier.height(48.dp).clip(T.R8).clickable { open = true }.padding(horizontal = 6.dp)
            .semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
            Text("${entries.size}", color = T.Text3, fontSize = 12.sp)
            Icon(D.chevronDown, null, tint = T.Text3, modifier = Modifier.size(12.dp))
        }
        if (open) HeaderPopup(336, 560, { open = false }) {
            SubagentPanel(entries, online, { address -> Pocket.openSession(address); open = false })
        }
    }
}

@Composable
internal fun SubagentPanel(entries: List<SubagentEntry>, online: Boolean, onOpen: (SessionAddress) -> Unit) {
    Column(Modifier.testTag("subagentPanel").verticalScroll(rememberScrollState()).padding(4.dp)) {
        entries.forEach { entry ->
            Column(Modifier.fillMaxWidth().clip(T.R8).clickable(enabled = online && entry.canOpen) {
                entry.address?.let(onOpen)
            }.padding(horizontal = 12.dp, vertical = 8.dp).testTag("subagent:${entry.id}")) {
                Text(entry.label, color = T.Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val mode = when (entry.mode) {
                    "continuable" -> teamText("可继续", "continuable")
                    "one-shot" -> teamText("一次性", "one-shot")
                    "external" -> teamText("外部任务", "External task")
                    else -> teamText("模式未知", "unknown mode")
                }
                val activity = when (entry.status) {
                    "running" -> teamText("正在运行", "running")
                    "completed" -> teamText("已完成", "completed")
                    else -> teamText("当前未运行", "not running")
                }
                Text(listOfNotNull(entry.title, mode, activity).joinToString(" · "), color = T.Text3, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
