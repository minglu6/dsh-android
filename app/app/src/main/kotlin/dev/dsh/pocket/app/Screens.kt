package dev.dsh.pocket.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.PairingLink
import kotlinx.coroutines.launch

@Composable
private fun Header(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(D.chevronLeft, "返回", tint = T.Ink) } else Spacer(Modifier.width(16.dp))
        Text(title, fontSize = 20.sp, color = T.Ink)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().clip(T.R16).border(1.dp, T.Border, T.R16), content = content)

@Composable
private fun Row2(title: String, sub: String? = null, icon: ImageVector? = null, value: String? = null, enabled: Boolean = true, divider: Boolean = true,
                 trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier).alpha(if (enabled) 1f else 0.5f)
        .padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = T.Ink, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = T.Ink)
            sub?.let { Text(it, fontSize = 12.sp, color = T.Text3, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        value?.let { Text(it, fontSize = 15.sp, color = T.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp)) }
        trailing?.invoke() ?: if (onClick != null) Icon(D.chevronRight, null, tint = T.Text3, modifier = Modifier.size(20.dp)) else Unit
    }
    if (divider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Fill)
}

// ---------- Drawer ----------

@Composable
fun SessionDrawer(onPick: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onConnections: () -> Unit) {
    val sessions by Pocket.sessions.collectAsState()
    val chat by Pocket.chat.collectAsState()
    val active by Pocket.active.collectAsState()
    val status by Pocket.status.collectAsState()
    val routes by Pocket.routes.collectAsState()
    val profile = Pocket.profiles.collectAsState().value.firstOrNull { it.id == active }
    ModalDrawerSheet(drawerContainerColor = T.Drawer, drawerShape = RoundedCornerShapeEnd, modifier = Modifier.width(320.dp)) {
        Column(Modifier.fillMaxHeight().systemBarsPadding()) {
            Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(D.wordmark, "deepseek HARNESS", modifier = Modifier.height(22.dp).width(167.dp))
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(T.R8).clickable(onClick = onConnections).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(status[active], relay = routes[active]?.isRelay == true); Spacer(Modifier.width(6.dp)); Text(profile?.name ?: "未连接", fontSize = 13.sp, color = T.Text2)
                }
            }
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(T.R12).background(Color.White).border(1.dp, T.Border, T.R12).clickable(onClick = onNew).padding(12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(D.newChat, null, Modifier.size(16.dp), tint = T.Ink); Spacer(Modifier.width(6.dp)); Text("新会话", fontSize = 15.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("工作区", fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 6.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val top = sessions.filter { it.parentSessionId == null && !it.blank }
                if (top.isEmpty()) Text(if (status[active] == Dot.Online) "暂无会话" else "连接后显示会话", fontSize = 14.sp, color = T.Caption, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                top.groupBy { it.cwd?.trimEnd('/')?.substringAfterLast('/') ?: "默认" }.forEach { (ws, list) ->
                    Row(Modifier.padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(D.folderOpen, null, Modifier.size(17.dp), tint = T.Accent); Spacer(Modifier.width(10.dp)); Text(ws, fontSize = 15.sp, color = T.Ink)
                    }
                    list.forEach { s ->
                        val sel = s.sessionId == chat?.sessionId
                        Row(Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(T.R8).background(if (sel) T.Fill else Color.Transparent).clickable { onPick(s.sessionId) }
                            .padding(start = 38.dp, end = 14.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.title ?: "新会话", fontSize = 15.sp, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            val pending = Pocket.approvals.collectAsState().value.any { it.sessionId == s.sessionId }
                            if (pending) Box(Modifier.size(7.dp).clip(CircleShape).background(T.WarnBorder)) else if (s.running) Box(Modifier.size(7.dp).clip(CircleShape).background(T.Accent))
                        }
                    }
                }
            }
            DrawerItem(D.settings, "设置", onClick = onSettings)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private val RoundedCornerShapeEnd = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)

@Composable
private fun DrawerItem(icon: ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit = {}) {
    Row(Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(T.R8).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.45f).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = T.Ink); Spacer(Modifier.width(12.dp)); Text(text, fontSize = 15.sp, color = T.Ink)
    }
}

// ---------- Connections ----------

@Composable
fun ConnectionsScreen(pairLink: PairingLink?, onPairLinkConsumed: () -> Unit, canBack: Boolean, onBack: () -> Unit, onConnected: () -> Unit) {
    val profiles by Pocket.profiles.collectAsState()
    val status by Pocket.status.collectAsState()
    val active by Pocket.active.collectAsState()
    var pairing by remember { mutableStateOf<PairingLink?>(null) }
    var showPair by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<Profile?>(null) }
    val routes by Pocket.routes.collectAsState()
    LaunchedEffect(Unit) { while (true) { Pocket.probe(); kotlinx.coroutines.delay(10_000) } }
    LaunchedEffect(pairLink) { if (pairLink != null) { pairing = pairLink; showPair = true; onPairLinkConsumed() } }

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
        Header("连接", if (canBack) onBack else null)
        if (profiles.isEmpty()) {
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(T.Fill2), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Devices, null, tint = T.Text3, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("连接到桌面端 dsh", fontSize = 16.sp, color = T.Ink)
                        Text("扫码或粘贴电脑「手机连接」里的配对串", fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        } else {
            Card {
                profiles.forEachIndexed { i, p ->
                    val d = status[p.id]
                    val route = routes[p.id]
                    Row(verticalAlignment = Alignment.CenterVertically) { Spacer(Modifier.width(16.dp)); StatusDot(d, relay = route?.isRelay == true); Box(Modifier.weight(1f)) {
                    Row2(p.name + if (p.id == active) "  · 当前" else "", "${p.subtitle} · ${dotLabel(d)}", icon = null, divider = i < profiles.lastIndex, onClick = { manage = p }, trailing = {
                        Text(route?.label ?: p.kind, fontSize = 12.sp, color = if (p.needsRepair) T.WarnBorder else T.Text2, modifier = Modifier.border(1.dp, T.Border, T.R8).padding(horizontal = 8.dp, vertical = 4.dp))
                        Icon(D.chevronRight, null, tint = T.Text3, modifier = Modifier.padding(start = 8.dp).size(20.dp))
                    }) } }
                }
            }
        }
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ pairing = null; showPair = true }, Modifier.weight(1f).height(52.dp), shape = T.R12, border = BorderStroke(1.dp, T.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) { Text("粘贴配对串", fontSize = 15.sp) }
            OutlinedButton({ showScan = true }, Modifier.weight(1f).height(52.dp), shape = T.R12, border = BorderStroke(1.dp, T.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) { Text("扫码配对", fontSize = 15.sp) }
        }
        Spacer(Modifier.weight(1f))
    }
    if (showPair) PairSheet(pairing, onDismiss = { showPair = false }) { p -> showPair = false; Pocket.connect(p.id); onConnected() }
    if (showScan) ScanPairFlow(
        onResult = { link -> pairing = link; showScan = false; showPair = true },
        onDismiss = { showScan = false },
    )
    manage?.let { p ->
        PocketSheet({ manage = null }) {
            Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status[p.id]); Spacer(Modifier.width(10.dp)); SheetTitle(p.name)
                if (p.id == active) Text("当前", fontSize = 12.sp, color = T.Accent, modifier = Modifier.background(T.Tint, T.R4).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Text("${p.subtitle} · ${dotLabel(status[p.id])}", style = T.Mono.copy(color = T.Text3), modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            if (p.needsRepair) {
                Text("此连接来自旧版方案，请删除后重新扫码配对。", fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp))
            } else if (p.id != active || status[p.id] != Dot.Online) {
                OptionRow("连接", icon = D.link) { Pocket.connect(p.id); manage = null; onConnected() }
            }
            OptionRow("删除", icon = D.trash) { Pocket.removeProfile(p.id); manage = null }
        }
    }
}

private fun dotLabel(d: Dot?) = when (d) { Dot.Online -> "在线"; Dot.Connecting -> "连接中"; Dot.Unauthorized -> "需重新配对"; Dot.Offline -> "无法连接"; null -> "未知" }

@Composable
fun PairSheet(link: PairingLink?, onDismiss: () -> Unit, onPaired: (Profile) -> Unit) {
    var ticket by remember { mutableStateOf(link?.let { "${it.nodeId}#${it.code}" } ?: "") }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(link?.code ?: "") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val parsed = remember(ticket, code) {
        PairingLink.parse(ticket.trim())
            ?: ticket.trim().takeIf { it.length >= 16 }?.let { n ->
                code.takeIf { it.length == 6 }?.let { PairingLink(n, it) }
            }
    }
    PocketSheet(onDismiss) {
        SheetTitle("配对")
        Field("配对串", ticket, "粘贴电脑上显示的整行") {
            ticket = it
            err = null
            PairingLink.parse(it.trim())?.let { link -> code = link.code }
        }
        Field("名称", name, "例如 Mac mini") { name = it }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BasicTextField(code, { v -> code = v.filter(Char::isDigit).take(6); err = null }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.focusRequester(focus), cursorBrush = SolidColor(Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
                decorationBox = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(6) { i ->
                            val cur = i == code.length
                            Box(Modifier.size(46.dp, 56.dp).clip(T.R12).border(if (cur) 1.5.dp else 1.dp, if (cur) T.Ink else T.Border, T.R12), contentAlignment = Alignment.Center) {
                                Text(code.getOrNull(i)?.toString() ?: "", fontSize = 24.sp, color = T.Ink, textAlign = TextAlign.Center)
                            }
                        }
                    }
                })
        }
        err?.let { Text(it, color = T.Err, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) }
        val ready = parsed != null && !busy
        Button({
            busy = true
            scope.launch {
                val p = parsed!!
                Pocket.pair(name.trim(), p.nodeId, p.code).onSuccess(onPaired).onFailure { err = it.message }
                busy = false
            }
        }, Modifier.padding(horizontal = 18.dp, vertical = 16.dp).fillMaxWidth().height(52.dp), enabled = ready, shape = T.R12,
            colors = PrimaryColors()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Text("配对", fontSize = 16.sp, color = Color.White)
        }
    }
    LaunchedEffect(Unit) { if (link != null) runCatching { focus.requestFocus() } }
}

@Composable
private fun Field(label: String, v: String, hint: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) {
        Text(label, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Box(Modifier.fillMaxWidth().clip(T.R12).background(T.Fill2).padding(horizontal = 14.dp, vertical = 13.dp)) {
            if (v.isEmpty()) Text(hint, color = T.Caption, fontSize = 15.sp)
            BasicTextField(v, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), cursorBrush = SolidColor(T.Accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen(onBack: () -> Unit, onGeneral: () -> Unit, onConnections: () -> Unit) {
    val profile = Pocket.activeProfile
    var notify by remember { mutableStateOf(Pocket.prefs.approvalNotify) }
    var sheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().verticalScroll(rememberScrollState())) {
        Header("设置", onBack)
        Card {
            Row2("通用设置", icon = D.settings, onClick = onGeneral)
            Row2("Agent 预设", icon = D.agentPreset, value = "标准模式", divider = false, onClick = { sheet = true })
        }
        Text("dsh-android", fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 22.dp, top = 12.dp, bottom = 2.dp))
        Card {
            Row2("连接", icon = D.rightUp, value = profile?.name ?: "未连接", onClick = onConnections)
            Row2("审批通知", divider = false, trailing = {
                Switch(notify, { notify = it; Pocket.prefs.approvalNotify = it }, colors = SwitchDefaults.colors(checkedTrackColor = T.Ink, checkedThumbColor = Color.White))
            })
        }
        Text("当前版本：${BuildConfig.VERSION_NAME}${profile?.let { " · ${it.name}" } ?: ""}", fontSize = 12.sp, color = T.Caption,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
    if (sheet) PresetSheet("standard", editable = false, onDismiss = { sheet = false }) {}
}

@Composable
fun GeneralSettingsScreen(onBack: () -> Unit) {
    var perm by remember { mutableStateOf(Pocket.prefs.defaultPermission) }
    var busy by remember { mutableStateOf(Pocket.prefs.busySend) }
    var lang by remember { mutableStateOf(Pocket.prefs.language) }
    var appearance by remember { mutableStateOf(Pocket.prefs.appearance) }
    var fontSize by remember { mutableIntStateOf(Pocket.prefs.fontSize) }
    var transcript by remember { mutableStateOf(Pocket.prefs.transcriptView) }
    var collapse by remember { mutableStateOf(Pocket.prefs.collapseTiming) }
    var perf by remember { mutableStateOf(Pocket.prefs.performanceUsage) }
    var sheet by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().verticalScroll(rememberScrollState())) {
        Header("通用设置", onBack)
        Card {
            Row2("权限", "选择新会话的默认权限模式", value = permissionLabel(perm), onClick = { sheet = "perm" }, trailing = { DownArrow() })
            Row2("语言", value = lang.label, onClick = { sheet = "lang" }, trailing = { DownArrow() })
            Row2("外观", value = appearance.label, onClick = { sheet = "appearance" }, trailing = { DownArrow() })
            Row2("字号大小", "仅影响会话内容的字号", value = "$fontSize px", onClick = { sheet = "font" }, divider = false, trailing = { DownArrow() })
        }
        Card {
            Row2("工作步骤展示", "选择希望看到多少工具调用细节", value = transcript.label, onClick = { sheet = "transcript" }, trailing = { DownArrow() })
            Row2("工作步骤收起时机", "选择何时自动收起工作步骤", value = collapse.label, onClick = { sheet = "collapse" }, divider = false, trailing = { DownArrow() })
        }
        Card {
            Row2("繁忙时的发送行为", "智能体运行时发送按钮的行为", value = busy.label, onClick = { sheet = "busy" }, trailing = { DownArrow() })
            Row2("性能与用量", "选择性能与用量信息展示的详细程度", value = perf.label, onClick = { sheet = "perf" }, divider = false, trailing = { DownArrow() })
        }
        Text("当前版本：${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = T.Caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
    when (sheet) {
        "perm" -> PocketSheet({ sheet = null }) {
            SheetTitle("默认权限")
            PERMISSION_LABEL.forEach { (id, l) -> OptionRow(l.first, l.second, selected = perm == id) { perm = id; Pocket.prefs.defaultPermission = id; sheet = null } }
        }
        "lang" -> PocketSheet({ sheet = null }) {
            SheetTitle("语言")
            AppLanguage.entries.forEach { o -> OptionRow(o.label, selected = lang == o) { lang = o; Pocket.prefs.language = o; sheet = null } }
        }
        "appearance" -> PocketSheet({ sheet = null }) {
            SheetTitle("外观")
            Appearance.entries.forEach { o -> OptionRow(o.label, selected = appearance == o) { appearance = o; Pocket.prefs.appearance = o; sheet = null } }
        }
        "font" -> PocketSheet({ sheet = null }) {
            SheetTitle("字号大小")
            listOf(12, 14, 16, 18, 20).forEach { n ->
                OptionRow("$n px", selected = fontSize == n) { fontSize = n; Pocket.prefs.fontSize = n; sheet = null }
            }
        }
        "transcript" -> PocketSheet({ sheet = null }) {
            SheetTitle("工作步骤展示")
            TranscriptView.entries.forEach { o -> OptionRow(o.label, selected = transcript == o) { transcript = o; Pocket.prefs.transcriptView = o; sheet = null } }
        }
        "collapse" -> PocketSheet({ sheet = null }) {
            SheetTitle("工作步骤收起时机")
            CollapseTiming.entries.forEach { o -> OptionRow(o.label, selected = collapse == o) { collapse = o; Pocket.prefs.collapseTiming = o; sheet = null } }
        }
        "busy" -> PocketSheet({ sheet = null }) {
            SheetTitle("繁忙时的发送行为")
            OptionRow(BusySend.Queue.label, "本轮结束后再发送", selected = busy == BusySend.Queue) { busy = BusySend.Queue; Pocket.prefs.busySend = busy; sheet = null }
            OptionRow(BusySend.Steer.label, "插入当前轮次，引导智能体", selected = busy == BusySend.Steer) { busy = BusySend.Steer; Pocket.prefs.busySend = busy; sheet = null }
        }
        "perf" -> PocketSheet({ sheet = null }) {
            SheetTitle("性能与用量")
            PerformanceUsage.entries.forEach { o -> OptionRow(o.label, selected = perf == o) { perf = o; Pocket.prefs.performanceUsage = o; sheet = null } }
        }
    }
}

@Composable private fun DownArrow() = Icon(D.chevronDown, null, tint = T.Text2, modifier = Modifier.padding(start = 2.dp).size(18.dp))
