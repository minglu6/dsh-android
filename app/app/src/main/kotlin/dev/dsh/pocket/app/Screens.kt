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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.dsh.pocket.protocol.PairingLink
import dev.dsh.pocket.protocol.parseHostPort
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** Official DeepSeek drawer proportion: ~80% of screen width, capped at [max] on large screens. */
fun drawerWidthFor(screenWidth: Dp, fraction: Float = 0.8f, max: Dp = DrawerMaxWidth): Dp =
    minOf(screenWidth * fraction, max)

val DrawerMaxWidth = 320.dp

@Composable
fun SessionDrawer(onPick: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onConnections: () -> Unit) {
    val sessions by Pocket.sessions.collectAsState()
    val chat by Pocket.chat.collectAsState()
    val active by Pocket.active.collectAsState()
    val status by Pocket.status.collectAsState()
    val archived by Pocket.archivedIds.collectAsState()
    val pinned by Pocket.pinnedIds.collectAsState()
    val ops by Pocket.ops.collectAsState()
    val profile = Pocket.profiles.collectAsState().value.firstOrNull { it.id == active }
    val drawerWidth = drawerWidthFor(LocalConfiguration.current.screenWidthDp.dp)
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchHits by remember { mutableStateOf<List<dev.dsh.pocket.protocol.SessionSummary>?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    var menuSession by remember { mutableStateOf<dev.dsh.pocket.protocol.SessionSummary?>(null) }
    LaunchedEffect(search) {
        if (search.isBlank() || "sessions.search" !in ops) {
            searchHits = null; searching = false; return@LaunchedEffect
        }
        searching = true
        kotlinx.coroutines.delay(250)
        Pocket.searchSessions(search) { searchHits = it; searching = false }
    }
    ModalDrawerSheet(
        drawerContainerColor = T.Drawer,
        drawerShape = RoundedCornerShapeEnd,
        modifier = Modifier.width(drawerWidth).testTag("sessionDrawer"),
    ) {
        Column(Modifier.fillMaxHeight().systemBarsPadding()) {
            Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(D.wordmark, "deepseek HARNESS", modifier = Modifier.height(22.dp).width(167.dp))
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.weight(1f).clip(T.R8).clickable(onClick = onConnections).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    StatusDot(status[active]); Spacer(Modifier.width(6.dp))
                    Text(profile?.name ?: "未连接", fontSize = 13.sp, color = T.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(T.R12).background(Color.White).border(1.dp, T.Border, T.R12).clickable(onClick = onNew).padding(12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(D.newChat, null, Modifier.size(16.dp), tint = T.Ink); Spacer(Modifier.width(6.dp)); Text("新会话", fontSize = 15.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(T.R12).background(Color.White).border(1.dp, T.Border, T.R12)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(D.search, L.searchSessions(), Modifier.size(16.dp), tint = T.Text3)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    search,
                    { search = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = T.Ink),
                    cursorBrush = SolidColor(T.Accent),
                    modifier = Modifier.weight(1f).testTag("sessionSearch"),
                    decorationBox = { inner ->
                        if (search.isEmpty()) Text(L.searchSessions(), fontSize = 14.sp, color = T.Caption)
                        inner()
                    },
                )
                if (search.isNotEmpty()) {
                    Text(L.clearSearch(), fontSize = 12.sp, color = T.Text3, modifier = Modifier.clickable {
                        search = ""; searchHits = null
                    })
                }
            }
            if ("workspace.state" in ops) {
                Text(
                    if (showArchived) "全部对话（显示已归档）" else "隐藏已归档",
                    fontSize = 12.sp,
                    color = T.Accent,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp).clickable { showArchived = !showArchived },
                )
            }
            Spacer(Modifier.height(2.dp))
            Text("工作区", fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 6.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when {
                    searching -> Text(L.searchingSessions(), fontSize = 14.sp, color = T.Caption, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                    searchHits != null -> {
                        val hits = searchHits!!
                        if (hits.isEmpty()) Text(L.noMatchingSessions(), fontSize = 14.sp, color = T.Caption, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                        else hits.forEach { s ->
                            SessionRow(
                                s, selected = s.sessionId == chat?.sessionId,
                                archived = s.sessionId in archived, pinned = s.sessionId in pinned,
                                onPick = { onPick(s.sessionId) },
                                onMenu = { menuSession = s },
                            )
                        }
                    }
                    else -> {
                        val pinnedList = sessions.filter { it.sessionId in pinned && it.parentSessionId == null && !it.blank }
                        val top = sessions.filter {
                            it.parentSessionId == null && !it.blank &&
                                it.sessionId !in pinned &&
                                (showArchived || it.sessionId !in archived)
                        }
                        if (pinnedList.isEmpty() && top.isEmpty()) {
                            Text(if (status[active] == Dot.Online) "暂无会话" else "连接后显示会话", fontSize = 14.sp, color = T.Caption, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                        }
                        if (pinnedList.isNotEmpty()) {
                            Text(L.pinned(), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
                            pinnedList.forEach { s ->
                                SessionRow(
                                    s, selected = s.sessionId == chat?.sessionId,
                                    archived = s.sessionId in archived, pinned = true,
                                    onPick = { onPick(s.sessionId) },
                                    onMenu = { menuSession = s },
                                )
                            }
                        }
                        top.groupBy { it.cwd?.trimEnd('/')?.substringAfterLast('/') ?: "默认" }.forEach { (ws, list) ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(D.folderOpen, null, Modifier.size(17.dp), tint = T.Accent); Spacer(Modifier.width(10.dp))
                                Text(ws, fontSize = 15.sp, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            list.forEach { s ->
                                SessionRow(
                                    s, selected = s.sessionId == chat?.sessionId,
                                    archived = s.sessionId in archived, pinned = s.sessionId in pinned,
                                    onPick = { onPick(s.sessionId) },
                                    onMenu = { menuSession = s },
                                )
                            }
                        }
                    }
                }
            }
            DrawerItem(D.settings, "设置", onClick = onSettings)
            Spacer(Modifier.height(8.dp))
        }
    }
    menuSession?.let { s ->
        val isArchived = s.sessionId in archived
        val isPinned = s.sessionId in pinned
        PocketSheet({ menuSession = null }) {
            SheetTitle(s.title ?: "新会话")
            if ("workspace.pinSession" in ops) {
                OptionRow(if (isPinned) L.unpinSession() else L.pinSession(), icon = D.check) {
                    if (isPinned) Pocket.unpinSession(s.sessionId) else Pocket.pinSession(s.sessionId)
                    menuSession = null
                }
            }
            if ("workspace.archiveSession" in ops) {
                OptionRow(if (isArchived) L.unarchiveSession() else L.archiveSession(), icon = D.folderOpen) {
                    if (isArchived) Pocket.unarchiveSession(s.sessionId) else Pocket.archiveSession(s.sessionId)
                    menuSession = null
                }
            }
            if ("sessions.fork" in ops) {
                OptionRow("在新对话中分支", icon = D.newChat) {
                    Pocket.openSession(s.sessionId)
                    Pocket.forkSession(null)
                    menuSession = null
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    s: dev.dsh.pocket.protocol.SessionSummary,
    selected: Boolean,
    archived: Boolean,
    pinned: Boolean,
    onPick: () -> Unit,
    onMenu: () -> Unit,
) {
    val pending = Pocket.approvals.collectAsState().value.any { it.sessionId == s.sessionId }
    Row(
        Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(T.R8).background(if (selected) T.Fill else Color.Transparent)
            .combinedClickable(onClick = onPick, onLongClick = onMenu)
            .padding(start = 38.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.title ?: "新会话", fontSize = 15.sp, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (archived || pinned) {
                Text(
                    listOfNotNull(if (pinned) L.pinned() else null, if (archived) L.archived() else null).joinToString(" · "),
                    fontSize = 11.sp, color = T.Caption,
                )
            }
        }
        Icon(D.ellipsis, "更多", Modifier.size(18.dp).clickable(onClick = onMenu), tint = T.Caption)
        Spacer(Modifier.width(6.dp))
        if (pending) Box(Modifier.size(7.dp).clip(CircleShape).background(T.WarnBorder))
        else if (s.running) Box(Modifier.size(7.dp).clip(CircleShape).background(T.Accent))
    }
}

private val RoundedCornerShapeEnd = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)

@Composable
private fun DrawerItem(icon: ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit = {}) {
    Row(Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(T.R8).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.45f).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = T.Ink); Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.sp, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

// ---------- Connections ----------

@Composable
fun ConnectionsScreen(pairLink: PairingLink?, onPairLinkConsumed: () -> Unit, canBack: Boolean, onBack: () -> Unit, onConnected: () -> Unit) {
    val profiles by Pocket.profiles.collectAsState()
    val status by Pocket.status.collectAsState()
    val active by Pocket.active.collectAsState()
    val ctx = LocalContext.current
    var pairing by remember { mutableStateOf<PairingLink?>(null) }
    var showPair by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var manageId by remember { mutableStateOf<String?>(null) }
    var repairTarget by remember { mutableStateOf<Profile?>(null) }
    var nearby by remember { mutableStateOf<List<DiscoveredHost>>(emptyList()) }
    var showNearbyPerm by remember { mutableStateOf(false) }
    val manage = manageId?.let { id -> profiles.firstOrNull { it.id == id } }
    val routes by Pocket.routes.collectAsState()
    val sorted = remember(profiles) { Pocket.profilesSorted() }
    val scope = rememberCoroutineScope()
    val pollerExecutor = remember { Executors.newFixedThreadPool(3) }
    val poller = remember {
        DeviceStatusPoller(
            executor = pollerExecutor,
            dispatch = { action -> scope.launch { action() } },
            probe = { Pocket.probeOne(it) },
            onResult = { row, result -> Pocket.applyProbeResult(row, result) },
        )
    }
    DisposableEffect(Unit) {
        onDispose { poller.stop(); pollerExecutor.shutdownNow() }
    }
    LaunchedEffect(profiles, active) {
        while (true) {
            poller.refresh(profiles.filter { it.id != active })
            kotlinx.coroutines.delay(10_000)
        }
    }
    val nearbyPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Pocket.prefs.nearbyWifiDeclined = true
        showNearbyPerm = false
    }
    LaunchedEffect(Unit) {
        val needNearby = NearbyDiscoveryPermissionPolicy.shouldRequest(
            apiLevel = Build.VERSION.SDK_INT,
            granted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED,
            previouslyDeclined = Pocket.prefs.nearbyWifiDeclined,
        )
        if (needNearby) {
            showNearbyPerm = true
        }
        while (true) {
            val canceled = AtomicBoolean(false)
            val found = withContext(Dispatchers.IO) { LanDiscovery.scan(ctx, canceled = canceled) }
            nearby = found.filter { disc -> profiles.none { it.instanceId == disc.instanceId && it.instanceId.isNotBlank() } }
            Pocket.applyDiscoveredHosts(found)
            kotlinx.coroutines.delay(8_000)
        }
    }
    LaunchedEffect(pairLink) { if (pairLink != null) { pairing = pairLink; showPair = true; onPairLinkConsumed() } }

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().verticalScroll(rememberScrollState())) {
        Header("连接", if (canBack) onBack else null)
        if (sorted.isEmpty()) {
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(T.Fill2), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Devices, null, tint = T.Text3, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("连接到桌面端 dsh", fontSize = 16.sp, color = T.Ink)
                        Text("扫码或粘贴电脑「手机连接」里的配对信息", fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        } else {
            Card {
                sorted.forEachIndexed { i, p ->
                    val d = status[p.id]
                    val route = routes[p.id]
                    Row(verticalAlignment = Alignment.CenterVertically) { Spacer(Modifier.width(16.dp)); StatusDot(d); Box(Modifier.weight(1f)) {
                    Row2(p.name + if (p.id == active) "  · 当前" else "", "${p.subtitle} · ${dotLabel(d)}", icon = null, divider = i < sorted.lastIndex, onClick = { manageId = p.id }, trailing = {
                        Text(route?.label ?: p.kind, fontSize = 12.sp, color = if (p.needsRepair) T.WarnBorder else T.Text2, modifier = Modifier.border(1.dp, T.Border, T.R8).padding(horizontal = 8.dp, vertical = 4.dp))
                        Icon(D.chevronRight, null, tint = T.Text3, modifier = Modifier.padding(start = 8.dp).size(20.dp))
                    }) } }
                }
            }
        }
        if (nearby.isNotEmpty()) {
            Text(L.nearbyComputers(), fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 22.dp, top = 12.dp, bottom = 2.dp))
            Card {
                nearby.forEachIndexed { i, h ->
                    Row2(
                        h.deviceName,
                        "${h.host}:${h.port}",
                        icon = Icons.Outlined.Computer,
                        divider = i < nearby.lastIndex,
                        onClick = {
                            pairing = PairingLink(h.host, h.port, "")
                            showPair = true
                        },
                    )
                }
            }
        }
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ pairing = null; showPair = true }, Modifier.weight(1f).height(52.dp), shape = T.R12, border = BorderStroke(1.dp, T.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) { Text("手动配对", fontSize = 15.sp) }
            OutlinedButton({ showScan = true }, Modifier.weight(1f).height(52.dp), shape = T.R12, border = BorderStroke(1.dp, T.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) { Text("扫码配对", fontSize = 15.sp) }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showNearbyPerm && Build.VERSION.SDK_INT >= 33) {
        NearbyWifiPermissionSheet(
            onDismiss = {
                Pocket.prefs.nearbyWifiDeclined = true
                showNearbyPerm = false
            },
            onAllow = {
                nearbyPermLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            },
        )
    }
    if (showPair) PairSheet(
        link = pairing,
        prefName = repairTarget?.name.orEmpty(),
        onDismiss = { showPair = false; repairTarget = null },
    ) { p -> showPair = false; repairTarget = null; Pocket.connect(p.id); onConnected() }
    if (showScan) ScanPairFlow(
        onResult = { link -> pairing = link; showScan = false; showPair = true },
        onDismiss = { showScan = false },
    )
    manage?.let { p ->
        ProfileManageSheet(
            profile = p,
            status = status[p.id],
            isActive = p.id == active,
            onDismiss = { manageId = null },
            onConnect = { Pocket.connect(p.id); manageId = null; onConnected() },
            onRepair = {
                manageId = null
                repairTarget = p
                pairing = if (!p.needsRepair) PairingLink(p.host, p.port, "") else null
                showPair = true
            },
        )
    }
}

/** Device manage sheet: 重命名 / 重新配对 / 删除, matching desktop ManagePanel. */
@Composable
fun ProfileManageSheet(
    profile: Profile,
    status: Dot?,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onRepair: () -> Unit = {},
) {
    var renaming by remember(profile.id) { mutableStateOf(false) }
    PocketSheet(onDismiss) {
        if (renaming) {
            var editName by remember(profile.id, profile.name) { mutableStateOf(profile.name) }
            SheetTitle("重命名")
            OutlinedTextField(
                editName,
                { editName = it.take(64) },
                singleLine = true,
                shape = T.R12,
                textStyle = T.Mono.copy(fontSize = 15.sp),
                modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth().testTag("profileRenameInput"),
            )
            Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    { renaming = false },
                    Modifier.weight(1f).height(50.dp),
                    shape = T.R12,
                    border = BorderStroke(1.dp, T.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink),
                ) { Text("取消") }
                Button(
                    {
                        val trimmed = editName.trim()
                        if (trimmed.isNotEmpty() && trimmed != profile.name) Pocket.renameProfile(profile.id, trimmed)
                        renaming = false
                    },
                    Modifier.weight(1f).height(50.dp),
                    enabled = editName.trim().isNotEmpty(),
                    shape = T.R12,
                    colors = PrimaryColors(),
                ) { Text("保存") }
            }
            return@PocketSheet
        }
        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status); Spacer(Modifier.width(10.dp)); SheetTitle(profile.name)
            if (isActive) Text("当前", fontSize = 12.sp, color = T.Accent, modifier = Modifier.background(T.Tint, T.R4).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Text("${profile.subtitle} · ${dotLabel(status)}", style = T.Mono.copy(color = T.Text3), modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
        Spacer(Modifier.height(8.dp))
        if (profile.needsRepair) {
            Text("连接信息不完整，请重新配对。", fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp))
        } else if (!isActive || status != Dot.Online) {
            OptionRow("连接", icon = D.link) { onConnect() }
        }
        OptionRow(L.repairPair(), icon = D.link) { onRepair() }
        OptionRow("重命名", icon = D.edit) { renaming = true }
        OptionRow("删除", icon = D.trash) { Pocket.removeProfile(profile.id); onDismiss() }
    }
}

private fun dotLabel(d: Dot?) = when (d) {
    Dot.Online -> L.online()
    Dot.Connecting -> "连接中"
    Dot.Unauthorized -> L.revoked()
    Dot.Offline -> L.offline()
    null -> "未知"
}

@Composable
fun NearbyWifiPermissionSheet(onDismiss: () -> Unit, onAllow: () -> Unit) {
    PocketSheet(onDismiss) {
        SheetTitle(L.nearbyComputers())
        Text(
            "用于发现同一局域网内的电脑（不会用于定位）。",
            fontSize = 14.sp, color = T.Text2, lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onDismiss, Modifier.weight(1f).height(50.dp), shape = T.R12,
                border = BorderStroke(1.dp, T.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink),
            ) { Text("取消") }
            Button(onAllow, Modifier.weight(1f).height(50.dp), shape = T.R12, colors = PrimaryColors()) {
                Text("允许", color = Color.White)
            }
        }
    }
}

@Composable
fun PairSheet(link: PairingLink?, prefName: String = "", onDismiss: () -> Unit, onPaired: (Profile) -> Unit) {
    var address by remember(link) {
        mutableStateOf(link?.takeIf { it.host.isNotBlank() }?.let { "${it.host}:${it.port}" } ?: "")
    }
    var name by remember(prefName) { mutableStateOf(prefName) }
    var code by remember(link) { mutableStateOf(link?.code?.takeIf { it.length == 6 } ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    val gate = remember { PairingAttemptGate() }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { gate.invalidate() } }
    val parsed = remember(address, code) {
        when (val p = PairingLink.parse(address.trim())) {
            null -> parseHostPort(address)?.let { (h, port) ->
                code.takeIf { it.length == 6 }?.let { PairingLink(h, port, it) }
            }
            else -> {
                val c = code.takeIf { it.length == 6 } ?: p.code.takeIf { it.length == 6 }
                c?.let { PairingLink(p.host, p.port, it) }
            }
        }
    }
    PocketSheet(onDismiss) {
        SheetTitle("配对")
        Field("地址", address, "100.x.y.z:7690 或局域网 host:port") {
            address = it
            err = null
            PairingLink.parse(it.trim())?.let { parsedLink ->
                address = "${parsedLink.host}:${parsedLink.port}"
                if (parsedLink.code.length == 6) code = parsedLink.code
            }
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
            val generation = gate.begin() ?: return@Button
            busy = true
            scope.launch {
                val result = Pocket.pair(name.trim(), parsed!!)
                if (gate.complete(generation)) {
                    result.onSuccess(onPaired).onFailure { err = it.message }
                    busy = false
                }
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
fun SettingsScreen(
    onBack: () -> Unit,
    onGeneral: () -> Unit,
    onConnections: () -> Unit,
    onModels: () -> Unit = {},
    onPlugins: () -> Unit = {},
    onSchedule: () -> Unit = {},
    onCredentials: () -> Unit = {},
) {
    val profile = Pocket.activeProfile
    val ops by Pocket.ops.collectAsState()
    var notify by remember { mutableStateOf(Pocket.prefs.approvalNotify) }
    var sheet by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().verticalScroll(rememberScrollState())) {
        Header(L.settings(), onBack)
        Card {
            Row2(L.generalNav(), icon = D.settings, onClick = onGeneral)
            if ("models.providers" in ops) {
                Row2(L.modelsNav(), icon = D.database, onClick = onModels)
            }
            if ("plugins.list" in ops) {
                Row2(L.pluginsNav(), icon = D.plugin, onClick = onPlugins)
            }
            if ("schedule.catalog" in ops) {
                Row2(L.scheduleNav(), icon = D.alarm, onClick = onSchedule)
            }
            if ("credentials.list" in ops) {
                Row2(L.credentialsNav(), icon = D.shield, onClick = onCredentials)
            }
            Row2("Agent 预设", icon = D.agentPreset, value = "标准模式", divider = false, onClick = { sheet = true })
        }
        Text("dsh-android", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, top = 12.dp, bottom = 2.dp))
        Card {
            Row2("连接", icon = D.rightUp, value = profile?.name ?: "未连接", onClick = onConnections)
            Row2(L.diagnostics(), icon = D.inspect, onClick = { diagnostics = true })
            Row2("审批通知", divider = false, trailing = {
                Switch(
                    notify,
                    { notify = it; Pocket.prefs.approvalNotify = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            })
        }
        Text("当前版本：${BuildConfig.VERSION_NAME}${profile?.let { " · ${it.name}" } ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
    if (sheet) PresetSheet("standard", editable = false, onDismiss = { sheet = false }) {}
    if (diagnostics) DiagnosticsSheet(onDismiss = { diagnostics = false })
}

@Composable
fun DiagnosticsSheet(onDismiss: () -> Unit) {
    var report by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        loading = true
        runCatching { Pocket.fetchDiagnostics() }
            .onSuccess { report = it; err = null }
            .onFailure { err = it.message ?: L.diagnosticsUnavailable(); report = null }
        loading = false
    }
    PocketSheet(onDismiss) {
        SheetTitle(L.diagnostics())
        when {
            loading -> Text(L.diagnosticsChecking(), fontSize = 14.sp, color = T.Text3, modifier = Modifier.padding(22.dp))
            err != null -> Text(err!!, fontSize = 14.sp, color = T.Err, modifier = Modifier.padding(22.dp))
            else -> Text(
                report.orEmpty(),
                style = T.Mono.copy(fontSize = 12.sp, color = T.Text2, lineHeight = 18.sp),
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp).testTag("diagnosticsReport"),
            )
        }
        Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                {
                    loading = true
                    scope.launch {
                        runCatching { Pocket.fetchDiagnostics() }
                            .onSuccess { report = it; err = null }
                            .onFailure { err = it.message ?: L.diagnosticsUnavailable(); report = null }
                        loading = false
                    }
                },
                Modifier.weight(1f).height(50.dp),
                shape = T.R12,
                border = BorderStroke(1.dp, T.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink),
                enabled = !loading,
            ) { Text("刷新") }
            Button(
                {
                    report?.let {
                        clip.setText(AnnotatedString(it))
                        Pocket.notices.tryEmit(L.diagnosticsCopied())
                    }
                },
                Modifier.weight(1f).height(50.dp),
                enabled = !report.isNullOrBlank(),
                shape = T.R12,
                colors = PrimaryColors(),
            ) { Text(L.diagnosticsCopy(), color = Color.White) }
        }
    }
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
            Row2(L.language(), value = lang.label, onClick = { sheet = "lang" }, trailing = { DownArrow() })
            Row2(
                L.appearance(),
                value = when (appearance) {
                    Appearance.Light -> L.light()
                    Appearance.Dark -> L.dark()
                    Appearance.System -> L.followSystem()
                },
                onClick = { sheet = "appearance" },
                trailing = { DownArrow() },
            )
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
            SheetTitle(L.language())
            AppLanguage.entries.forEach { o -> OptionRow(o.label, selected = lang == o) { lang = o; Pocket.prefs.language = o; sheet = null } }
        }
        "appearance" -> PocketSheet({ sheet = null }) {
            SheetTitle(L.appearance())
            OptionRow(L.light(), selected = appearance == Appearance.Light) { appearance = Appearance.Light; Pocket.prefs.appearance = appearance; sheet = null }
            OptionRow(L.dark(), selected = appearance == Appearance.Dark) { appearance = Appearance.Dark; Pocket.prefs.appearance = appearance; sheet = null }
            OptionRow(L.followSystem(), selected = appearance == Appearance.System) { appearance = Appearance.System; Pocket.prefs.appearance = appearance; sheet = null }
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
            OptionRow(L.queueSend(), "本轮结束后再发送", selected = busy == BusySend.Queue) { busy = BusySend.Queue; Pocket.prefs.busySend = busy; sheet = null }
            OptionRow(L.steerSend(), "插入当前轮次，引导智能体", selected = busy == BusySend.Steer) { busy = BusySend.Steer; Pocket.prefs.busySend = busy; sheet = null }
        }
        "perf" -> PocketSheet({ sheet = null }) {
            SheetTitle("性能与用量")
            PerformanceUsage.entries.forEach { o -> OptionRow(o.label, selected = perf == o) { perf = o; Pocket.prefs.performanceUsage = o; sheet = null } }
        }
    }
}

@Composable private fun DownArrow() = Icon(D.chevronDown, null, tint = T.Text2, modifier = Modifier.padding(start = 2.dp).size(18.dp))
