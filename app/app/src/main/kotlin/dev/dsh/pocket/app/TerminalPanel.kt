package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun terminalText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

@Composable
fun TerminalSheet(sessionId: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null) {
    val revision by Pocket.terminalRevision.collectAsState()
    val meta by Pocket.terminalMeta.collectAsState()
    val active by Pocket.active.collectAsState()
    val connections by Pocket.status.collectAsState()
    val ops by Pocket.ops.collectAsState()
    val detectedPlatform by Pocket.terminalPlatform.collectAsState()
    var ownerSessionId by remember(active, sessionId) {
        mutableStateOf(active?.let { Pocket.prefs.terminalPanelOwner(it, sessionId) } ?: sessionId)
    }
    var opening by remember(active, sessionId) { mutableStateOf(true) }
    var attempt by remember(active, sessionId) { mutableIntStateOf(0) }
    var forceNew by remember(active, sessionId) { mutableStateOf(false) }
    var requestedTerminalId by remember(active, sessionId) { mutableStateOf<String?>(null) }
    var everAttached by remember(active, ownerSessionId) { mutableStateOf(false) }
    var exitTarget by remember(active, sessionId) { mutableStateOf<TerminalMeta?>(null) }
    var terminating by remember(active, sessionId) { mutableStateOf(false) }
    var exited by remember(active, sessionId) { mutableStateOf(false) }
    var operationError by remember(active, sessionId) { mutableStateOf<String?>(null) }
    var menuOpen by remember(active, sessionId) { mutableStateOf(false) }
    var entries by remember(active, sessionId) { mutableStateOf<List<TerminalSessionEntry>>(emptyList()) }
    var listing by remember(active, sessionId) { mutableStateOf(false) }
    var listError by remember(active, sessionId) { mutableStateOf<String?>(null) }
    var listAttempt by remember(active, sessionId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val online = connections[active] == Dot.Online
    val current = meta?.takeIf { it.sessionId == ownerSessionId && !opening }
    val running = online && !opening && !terminating && current?.state == "running"
    val canOpen = online && !opening && !terminating

    // Dispose the previous attachment synchronously before opening another one.
    DisposableEffect(active, ownerSessionId, online, attempt) {
        opening = online && !exited
        operationError = null
        val attachment = if (online && !exited) Pocket.openTerminal(ownerSessionId, forceNew, requestedTerminalId) else null
        forceNew = false
        val completion = attachment?.invokeOnCompletion {
            // The listener is disposed before our own cancellation; remote failures and
            // snapshot timeouts must also leave the connecting state.
            opening = false
            if (Pocket.terminalMeta.value?.sessionId == ownerSessionId) {
                everAttached = true
                active?.let { Pocket.prefs.setTerminalPanelOwner(it, sessionId, ownerSessionId) }
            }
        }
        onDispose {
            completion?.dispose()
            attachment?.cancel()
            // Closing the panel or switching entries never terminates the shell.
            Pocket.closeTerminalFollow()
        }
    }

    LaunchedEffect(active, sessionId, menuOpen, online, listAttempt) {
        if (!menuOpen || !online || "terminal.list" !in ops) return@LaunchedEffect
        listing = true
        listError = null
        try {
            entries = Pocket.terminalSessions(sessionId).entries
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            listError = failure.message ?: terminalText("无法读取终端会话", "Unable to load terminal sessions")
        } finally {
            listing = false
        }
    }

    fun openEntry(entry: TerminalSessionEntry) {
        menuOpen = false
        exited = false
        everAttached = false
        requestedTerminalId = entry.id
        ownerSessionId = entry.sessionId
        attempt++
    }
    fun createTerminal() {
        menuOpen = false
        everAttached = false
        exited = false
        requestedTerminalId = null
        forceNew = true
        attempt++
    }

    exitTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { exitTarget = null },
            title = { Text(terminalText("退出终端？", "Exit terminal?")) },
            text = { Text(terminalText("这将结束当前 Shell 和其中正在运行的命令。仅收起面板不会结束会话。", "This ends the current shell and its running commands. Collapsing the panel keeps the session.")) },
            confirmButton = {
                TextButton(onClick = {
                    exitTarget = null
                    terminating = true
                    scope.launch {
                        try {
                            Pocket.terminateTerminal(target.sessionId, target.id)
                            exited = true
                            requestedTerminalId = null
                            menuOpen = true
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { operationError = failure.message }
                        finally { terminating = false }
                    }
                }) { Text(terminalText("退出", "Exit"), color = T.Ink) }
            },
            dismissButton = { TextButton(onClick = { exitTarget = null }) { Text(terminalText("取消", "Cancel"), color = T.Ink) } },
        )
    }

    RightPanelFrame(
        title = current?.title?.takeIf { it.isNotBlank() } ?: terminalText("终端", "Terminal"),
        onDismiss = onDismiss,
        onBack = onBack,
        keyboardFirstBack = true,
        actions = {
            val label = when {
                !online -> terminalText("离线", "Offline")
                opening -> terminalText("连接中", "Connecting")
                terminating -> terminalText("退出中", "Exiting")
                running -> terminalText("运行中", "Running")
                else -> terminalText("已停止", "Stopped")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) {}) {
                Box(Modifier.size(6.dp).background(if (running) T.Ok else T.Text3, CircleShape))
                Text(label, color = T.Text3, fontSize = 11.sp)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(D.ellipsis, terminalText("终端会话与操作", "Terminal sessions and actions"), Modifier.size(18.dp), tint = T.Text2)
                }
                DropdownMenu(menuOpen, { menuOpen = false }, modifier = Modifier.widthIn(max = 288.dp),
                    containerColor = T.Surface, shape = T.R12) {
                    Text(terminalText("终端会话", "Terminal sessions"), color = T.Text3, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    if (!online) Text(terminalText("连接后可切换会话", "Connect to switch sessions"), color = T.Text3, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    else if (listing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = T.Accent)
                    else if (listError != null) {
                        Text(listError.orEmpty(), color = T.Err, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        DropdownMenuItem(text = { Text(terminalText("重试", "Retry"), fontSize = 13.sp) }, onClick = { listAttempt++ })
                    } else if (entries.isEmpty()) Text(terminalText("暂无保留的会话", "No retained sessions"), color = T.Text3, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    if (online && !listing && listError == null) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        entries.forEach { entry ->
                            val isCurrent = current?.let { it.sessionId == entry.sessionId && it.id == entry.id } == true
                            DropdownMenuItem(
                                modifier = Modifier.semantics { selected = isCurrent },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("${entry.title?.takeIf { it.isNotBlank() } ?: "Terminal"} · ${entry.id.take(8)}",
                                            color = T.Ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(entry.cwd?.takeIf { it.isNotBlank() } ?: entry.state.orEmpty(),
                                            color = T.Text3, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                enabled = canOpen && "terminal.follow" in ops,
                                trailingIcon = { if (isCurrent) Icon(D.check, null, Modifier.size(16.dp), tint = T.Ink) },
                                onClick = { if (!isCurrent) openEntry(entry) else menuOpen = false },
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = T.Border, thickness = 0.5.dp)
                    if ("terminal.create" in ops) DropdownMenuItem(
                        text = { Text(terminalText("新建终端", "New terminal"), color = T.Ink, fontSize = 13.sp) },
                        leadingIcon = { Icon(D.plus, null, Modifier.size(16.dp), tint = T.Text2) },
                        enabled = canOpen,
                        onClick = { createTerminal() },
                    )
                    if (current != null && "terminal.close" in ops) DropdownMenuItem(
                        text = { Text(terminalText("退出", "Exit"), color = if (canOpen) T.Ink else T.Text3, fontSize = 13.sp) },
                        enabled = canOpen,
                        onClick = { menuOpen = false; exitTarget = current },
                    )
                }
            }
        },
    ) {
        val status = when {
            !online -> terminalText("连接已断开；连接恢复后会重新附加。", "Disconnected; reattaching when the connection returns.")
            opening -> terminalText("正在连接…", "Connecting…")
            terminating -> terminalText("正在退出…", "Exiting…")
            exited -> terminalText("终端已退出。可从菜单切换会话或新建终端。", "Terminal exited. Switch sessions or create a terminal from the menu.")
            current == null -> terminalText("终端不可用", "Terminal unavailable")
            current.state == "closed" || current.state == "exited" -> terminalText("终端已结束。", "Terminal ended.")
            current.state != "running" -> terminalText("终端不可用", "Terminal unavailable")
            else -> null
        }
        if (status != null || operationError != null) {
            Text(operationError ?: status.orEmpty(), color = if (operationError != null) T.Err else T.Text3, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (!running && canOpen && !exited) TextButton(onClick = { attempt++ }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(D.refresh, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(terminalText("重新连接", "Reconnect"))
        }
        if (current != null || everAttached) {
            TerminalView(
                terminal = Pocket.terminal,
                revision = revision,
                modifier = Modifier.fillMaxWidth().weight(1f),
                enabled = running,
                platform = detectedPlatform,
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
