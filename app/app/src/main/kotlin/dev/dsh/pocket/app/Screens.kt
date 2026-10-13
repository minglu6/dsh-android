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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.dsh.pocket.protocol.classifyWsRoute
import dev.dsh.pocket.protocol.Route
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
val SidebarPaneWidth = 280.dp

/** Breakpoint matching Material compact/medium (≥600dp → desktop two-pane). */
const val WideLayoutMinDp = 600

fun useTwoPaneLayout(widthDp: Int, mode: LayoutMode = runCatching { Pocket.prefs.layoutMode }.getOrDefault(LayoutMode.Auto)): Boolean =
    when (mode) {
        LayoutMode.Desktop -> true
        LayoutMode.Phone -> false
        LayoutMode.Auto -> widthDp >= WideLayoutMinDp
    }

@Composable
fun SessionDrawer(
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onConnections: () -> Unit,
    onPlugins: () -> Unit = {},
    onSchedule: () -> Unit = {},
    permanent: Boolean = false,
    onMenuOpenChange: (Boolean) -> Unit = {},
) {
    val drawerWidth = if (permanent) SidebarPaneWidth else drawerWidthFor(LocalConfiguration.current.screenWidthDp.dp)
    val body: @Composable () -> Unit = {
        SessionDrawerBody(
            onPick = onPick,
            onNew = onNew,
            onSettings = onSettings,
            onConnections = onConnections,
            onPlugins = onPlugins,
            onSchedule = onSchedule,
            onMenuOpenChange = onMenuOpenChange,
        )
    }
    if (permanent) {
        Column(
            Modifier
                .width(drawerWidth)
                .fillMaxHeight()
                .background(T.Drawer)
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("sessionDrawer"),
        ) { body() }
    } else {
        ModalDrawerSheet(
            drawerContainerColor = T.Drawer,
            drawerShape = RoundedCornerShapeEnd,
            modifier = Modifier.width(drawerWidth).testTag("sessionDrawer"),
        ) {
            Column(Modifier.fillMaxHeight().systemBarsPadding()) { body() }
        }
    }
}

@Composable
private fun SessionDrawerBody(
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onConnections: () -> Unit,
    onPlugins: () -> Unit,
    onSchedule: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
) {
    val sessions by Pocket.sessions.collectAsState()
    val chat by Pocket.chat.collectAsState()
    val active by Pocket.active.collectAsState()
    val status by Pocket.status.collectAsState()
    val archived by Pocket.archivedIds.collectAsState()
    val pinned by Pocket.pinnedIds.collectAsState()
    val ops by Pocket.ops.collectAsState()
    val profile = Pocket.profiles.collectAsState().value.firstOrNull { it.id == active }
    var search by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchHits by remember { mutableStateOf<List<dev.dsh.pocket.protocol.SessionSummary>?>(null) }
    var groupBy by remember { mutableStateOf(Pocket.prefs.sessionGroupBy) }
    var orderBy by remember { mutableStateOf(Pocket.prefs.sessionOrderBy) }
    var archivedFilter by remember { mutableStateOf(Pocket.prefs.archivedFilter) }
    var viewOptionsOpen by remember { mutableStateOf(false) }
    var addWorkspaceOpen by remember { mutableStateOf(false) }
    var menuSessionId by remember { mutableStateOf<String?>(null) }
    var collapsedWorkspaces by remember { mutableStateOf(setOf<String>()) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(menuSessionId) { onMenuOpenChange(menuSessionId != null) }
    LaunchedEffect(search) {
        if (search.isBlank() || "sessions.search" !in ops) {
            searchHits = null; searching = false; return@LaunchedEffect
        }
        searching = true
        kotlinx.coroutines.delay(250)
        Pocket.searchSessions(search) { searchHits = it; searching = false }
    }
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocus.requestFocus()
    }
    Column(Modifier.fillMaxHeight()) {
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
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(38.dp).clip(T.R12).background(T.Surface).border(1.dp, T.Border, T.R12)
                .clickable(onClick = onNew).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(D.newChat, null, Modifier.size(16.dp), tint = T.Ink); Spacer(Modifier.width(6.dp)); Text("新会话", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if ("plugins.list" in ops) {
                DrawerPanelRow(D.pluginPinwheel, L.pluginsNav(), onClick = onPlugins)
            }
            if ("schedule.catalog" in ops) {
                DrawerPanelRow(D.clock, L.scheduleTitle(), onClick = onSchedule)
            }
        }
        if ("plugins.list" in ops || "schedule.catalog" in ops) {
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searchExpanded) {
                Icon(D.search, L.searchSessionsAria(), Modifier.size(14.dp), tint = T.Text3)
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    search,
                    { search = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = T.Ink),
                    cursorBrush = SolidColor(T.Accent),
                    modifier = Modifier.weight(1f).focusRequester(searchFocus).testTag("sessionSearch"),
                    decorationBox = { inner ->
                        if (search.isEmpty()) Text(L.searchSessions(), fontSize = 14.sp, color = T.Caption)
                        inner()
                    },
                )
                if (search.isNotEmpty()) {
                    Text(L.clearSearch(), fontSize = 12.sp, color = T.Text3, modifier = Modifier.clickable {
                        search = ""; searchHits = null
                    })
                } else {
                    Icon(
                        D.chevronLeft, L.clearSearch(),
                        Modifier.size(18.dp).clickable {
                            searchExpanded = false; search = ""; searchHits = null
                        },
                        tint = T.Text3,
                    )
                }
            } else {
                Text(
                    if (groupBy == SessionGroupBy.Flat) L.sectionSessions() else L.sectionWorkspaces(),
                    fontSize = 13.sp,
                    color = T.Text3,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                DrawerHeaderIcon(D.search, L.search()) { searchExpanded = true }
                DrawerHeaderIcon(D.sliders, L.viewOptions()) { viewOptionsOpen = true }
                DrawerHeaderIcon(D.projectAdd, L.addWorkspace()) { addWorkspaceOpen = true }
            }
        }
        Spacer(Modifier.height(4.dp))
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
                            menuOpen = menuSessionId == s.sessionId,
                            onPick = { onPick(s.sessionId) },
                            onMenuOpenChange = { open -> menuSessionId = if (open) s.sessionId else null },
                        )
                    }
                }
                else -> {
                    val visible = sessions.filter { s ->
                        if (s.parentSessionId != null || s.blank) return@filter false
                        when (archivedFilter) {
                            ArchivedFilter.Default -> s.sessionId !in archived
                            ArchivedFilter.Show -> true
                            ArchivedFilter.Only -> s.sessionId in archived
                        }
                    }
                    val ordered = when (orderBy) {
                        SessionOrderBy.Updated -> visible.sortedByDescending { it.updatedAt }
                        SessionOrderBy.Manual -> visible
                    }
                    val pinnedList = ordered.filter { it.sessionId in pinned }
                    val rest = ordered.filter { it.sessionId !in pinned }
                    if (pinnedList.isEmpty() && rest.isEmpty()) {
                        val empty = when {
                            status[active] != Dot.Online -> "连接后显示会话"
                            archivedFilter == ArchivedFilter.Only -> L.emptyArchivedSessions()
                            else -> L.emptySessions()
                        }
                        Text(empty, fontSize = 14.sp, color = T.Caption, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                    }
                    if (pinnedList.isNotEmpty()) {
                        Text(L.pinned(), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
                        pinnedList.forEach { s ->
                            SessionRow(
                                s, selected = s.sessionId == chat?.sessionId,
                                archived = s.sessionId in archived, pinned = true,
                                menuOpen = menuSessionId == s.sessionId,
                                onPick = { onPick(s.sessionId) },
                                onMenuOpenChange = { open -> menuSessionId = if (open) s.sessionId else null },
                            )
                        }
                    }
                    when (groupBy) {
                        SessionGroupBy.Flat -> rest.forEach { s ->
                            SessionRow(
                                s, selected = s.sessionId == chat?.sessionId,
                                archived = s.sessionId in archived, pinned = s.sessionId in pinned,
                                menuOpen = menuSessionId == s.sessionId,
                                onPick = { onPick(s.sessionId) },
                                onMenuOpenChange = { open -> menuSessionId = if (open) s.sessionId else null },
                            )
                        }
                        SessionGroupBy.Workspace -> {
                            rest.groupBy { it.cwd?.trimEnd('/')?.substringAfterLast('/') ?: L.ungrouped() }.forEach { (ws, list) ->
                                WorkspaceCollapseGroup(
                                    ws = ws,
                                    list = list,
                                    collapsed = ws in collapsedWorkspaces,
                                    selectedId = chat?.sessionId,
                                    archived = archived,
                                    pinned = pinned,
                                    menuSessionId = menuSessionId,
                                    onToggle = {
                                        collapsedWorkspaces = if (ws in collapsedWorkspaces) collapsedWorkspaces - ws else collapsedWorkspaces + ws
                                    },
                                    onPick = onPick,
                                    onMenuOpenChange = { id, open -> menuSessionId = if (open) id else null },
                                )
                            }
                        }
                        SessionGroupBy.WorkspaceTree -> {
                            rest.groupBy { cwd ->
                                val path = cwd.cwd?.trimEnd('/')
                                when {
                                    path.isNullOrBlank() -> L.ungrouped()
                                    path.contains('/') -> path.substringBeforeLast('/')
                                    else -> path
                                }
                            }.forEach { (ws, list) ->
                                val label = ws.substringAfterLast('/').ifBlank { ws }
                                WorkspaceCollapseGroup(
                                    ws = label,
                                    list = list,
                                    collapsed = label in collapsedWorkspaces,
                                    selectedId = chat?.sessionId,
                                    archived = archived,
                                    pinned = pinned,
                                    menuSessionId = menuSessionId,
                                    onToggle = {
                                        collapsedWorkspaces = if (label in collapsedWorkspaces) collapsedWorkspaces - label else collapsedWorkspaces + label
                                    },
                                    onPick = onPick,
                                    onMenuOpenChange = { id, open -> menuSessionId = if (open) id else null },
                                )
                            }
                        }
                    }
                }
            }
        }
        DrawerItem(D.settings, "设置", onClick = onSettings)
        Spacer(Modifier.height(8.dp))
    }
    if (viewOptionsOpen) {
        ViewOptionsSheet(
            groupBy = groupBy,
            orderBy = orderBy,
            archivedFilter = archivedFilter,
            onDismiss = { viewOptionsOpen = false },
            onGroup = { groupBy = it; Pocket.prefs.sessionGroupBy = it },
            onOrder = { orderBy = it; Pocket.prefs.sessionOrderBy = it },
            onArchived = { archivedFilter = it; Pocket.prefs.archivedFilter = it },
        )
    }
    if (addWorkspaceOpen) {
        AddWorkspaceSheet(
            known = sessions.mapNotNull { it.cwd }.distinct(),
            onDismiss = { addWorkspaceOpen = false },
            onPick = { path ->
                addWorkspaceOpen = false
                Pocket.startInWorkspace(path)
            },
        )
    }
}


@Composable
private fun WorkspaceCollapseGroup(
    ws: String,
    list: List<dev.dsh.pocket.protocol.SessionSummary>,
    collapsed: Boolean,
    selectedId: String?,
    archived: Set<String>,
    pinned: Set<String>,
    menuSessionId: String?,
    onToggle: () -> Unit,
    onPick: (String) -> Unit,
    onMenuOpenChange: (String, Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(T.R8)
            .clickable(onClick = onToggle)
            .padding(horizontal = 22.dp, vertical = 8.dp)
            .testTag("workspaceCollapse:$ws"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(D.folderOpen, null, Modifier.size(17.dp), tint = T.Accent)
        Spacer(Modifier.width(10.dp))
        Text(ws, fontSize = 15.sp, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(if (collapsed) L.expand() else L.collapse(), fontSize = 12.sp, color = T.Caption)
    }
    if (!collapsed) {
        list.forEach { s ->
            SessionRow(
                s, selected = s.sessionId == selectedId,
                archived = s.sessionId in archived, pinned = s.sessionId in pinned,
                menuOpen = menuSessionId == s.sessionId,
                onPick = { onPick(s.sessionId) },
                onMenuOpenChange = { open -> onMenuOpenChange(s.sessionId, open) },
            )
        }
    }
}

/** Desktop `.panelRow`: 16px icon, 8px gap, 14px label, min-height 36. */
@Composable
private fun DrawerPanelRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(T.R12).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp).testTag("drawerPanel:$text"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = T.Ink)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, color = T.Ink, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Desktop WorkspaceBrowser `.iconButton` 28×28. */
@Composable
private fun DrawerHeaderIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(T.R8).clickable(onClick = onClick).testTag("drawerHeader:$label"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(16.dp), tint = T.Text2)
    }
}

@Composable
internal fun ViewOptionsSheet(
    groupBy: SessionGroupBy,
    orderBy: SessionOrderBy,
    archivedFilter: ArchivedFilter,
    onDismiss: () -> Unit,
    onGroup: (SessionGroupBy) -> Unit,
    onOrder: (SessionOrderBy) -> Unit,
    onArchived: (ArchivedFilter) -> Unit,
) {
    PocketSheet(onDismiss) {
        SheetTitle(L.viewOptions())
        Text(L.groupByLabel(), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        OptionRow(L.groupByWorkspace(), icon = D.folderOpen, selected = groupBy == SessionGroupBy.Workspace) {
            onGroup(SessionGroupBy.Workspace); onDismiss()
        }
        OptionRow(L.groupByWorkspaceTree(), icon = D.folderOpen, selected = groupBy == SessionGroupBy.WorkspaceTree) {
            onGroup(SessionGroupBy.WorkspaceTree); onDismiss()
        }
        OptionRow(L.groupByFlat(), icon = D.chat, selected = groupBy == SessionGroupBy.Flat) {
            onGroup(SessionGroupBy.Flat); onDismiss()
        }
        Text(L.orderByLabel(), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        OptionRow(L.orderByManual(), icon = D.ellipsis, selected = orderBy == SessionOrderBy.Manual) {
            onOrder(SessionOrderBy.Manual); onDismiss()
        }
        OptionRow(L.orderByUpdated(), icon = D.clock, selected = orderBy == SessionOrderBy.Updated) {
            onOrder(SessionOrderBy.Updated); onDismiss()
        }
        Text(L.filterByLabel(), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        OptionRow(L.hideArchived(), selected = archivedFilter == ArchivedFilter.Default) {
            onArchived(ArchivedFilter.Default); onDismiss()
        }
        OptionRow(L.showArchived(), selected = archivedFilter == ArchivedFilter.Show) {
            onArchived(ArchivedFilter.Show); onDismiss()
        }
        OptionRow(L.onlyArchived(), selected = archivedFilter == ArchivedFilter.Only) {
            onArchived(ArchivedFilter.Only); onDismiss()
        }
    }
}

@Composable
private fun AddWorkspaceSheet(known: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    PocketSheet(onDismiss) {
        SheetTitle(L.addWorkspace())
        OutlinedTextField(
            path,
            { path = it },
            placeholder = { Text(L.workspacePathPlaceholder()) },
            shape = T.R12,
            singleLine = true,
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth().testTag("addWorkspacePath"),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            { onPick(path.trim()) },
            Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(50.dp),
            enabled = path.isNotBlank(),
            shape = T.R12,
            colors = PrimaryColors(),
        ) { Text(L.addWorkspace()) }
        if (known.isNotEmpty()) {
            Text(L.sectionWorkspaces(), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            known.forEach { w ->
                OptionRow(w.substringAfterLast('/'), w, D.folderOpen) { onPick(w) }
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
    menuOpen: Boolean,
    onPick: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
) {
    val pending = Pocket.approvals.collectAsState().value.any { it.sessionId == s.sessionId }
    val ops by Pocket.ops.collectAsState()
    val archivedIds by Pocket.archivedIds.collectAsState()
    val pinnedIds by Pocket.pinnedIds.collectAsState()
    val isArchived = s.sessionId in archivedIds
    val isPinned = s.sessionId in pinnedIds
    Box {
        Row(
            Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(T.R8).background(if (selected) T.Fill else Color.Transparent)
                .combinedClickable(onClick = onPick, onLongClick = { onMenuOpenChange(true) })
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
            Icon(
                D.ellipsis, "更多",
                Modifier.size(18.dp).clickable { onMenuOpenChange(true) }.testTag("sessionMenu:${s.sessionId}"),
                tint = T.Caption,
            )
            Spacer(Modifier.width(6.dp))
            if (pending) Box(Modifier.size(7.dp).clip(CircleShape).background(T.WarnBorder))
            else if (s.running) Box(Modifier.size(7.dp).clip(CircleShape).background(T.Accent))
        }
        // DropdownMenu (not a bottom sheet): first tap reaches items; drawer gestures stay uninvolved.
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { onMenuOpenChange(false) },
            containerColor = T.Surface,
        ) {
            if ("workspace.pinSession" in ops) {
                DropdownMenuItem(
                    text = { Text(if (isPinned) L.unpinSession() else L.pinSession()) },
                    onClick = {
                        if (isPinned) Pocket.unpinSession(s.sessionId) else Pocket.pinSession(s.sessionId)
                        onMenuOpenChange(false)
                    },
                )
            }
            if ("workspace.archiveSession" in ops) {
                DropdownMenuItem(
                    text = { Text(if (isArchived) L.unarchiveSession() else L.archiveSession()) },
                    onClick = {
                        if (isArchived) Pocket.unarchiveSession(s.sessionId) else Pocket.archiveSession(s.sessionId)
                        onMenuOpenChange(false)
                    },
                )
            }
            if ("sessions.fork" in ops) {
                DropdownMenuItem(
                    text = { Text("在新对话中分支") },
                    onClick = {
                        Pocket.openSession(s.sessionId)
                        Pocket.forkSession(null)
                        onMenuOpenChange(false)
                    },
                )
            }
        }
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

// ---------- Connections (Tokyo Night · compact LAN / Tailscale) ----------

@Composable
fun ConnectionsScreen(pairLink: PairingLink?, onPairLinkConsumed: () -> Unit, canBack: Boolean, onBack: () -> Unit, onConnected: () -> Unit) {
    val profiles by Pocket.profiles.collectAsState()
    val status by Pocket.status.collectAsState()
    val active by Pocket.active.collectAsState()
    val ctx = LocalContext.current
    val en = remember { runCatching { Pocket.prefs.language == AppLanguage.En }.getOrDefault(false) }
    var pairing by remember { mutableStateOf<PairingLink?>(null) }
    var addressHintTab by remember { mutableStateOf(PeerTransport.Lan) }
    var showPair by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var manageId by remember { mutableStateOf<String?>(null) }
    var repairTarget by remember { mutableStateOf<Profile?>(null) }
    var nearby by remember { mutableStateOf<List<DiscoveredHost>>(emptyList()) }
    var showNearbyPerm by remember { mutableStateOf(false) }
    val tabs = remember { peerTransportTabs() }
    var transport by remember { mutableStateOf(PeerTransport.Lan) }
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
    val nearbyGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(Unit) {
        while (true) {
            val canceled = AtomicBoolean(false)
            val found = withContext(Dispatchers.IO) { LanDiscovery.scan(ctx, canceled = canceled) }
            nearby = found.filter { disc -> profiles.none { it.instanceId == disc.instanceId && it.instanceId.isNotBlank() } }
            Pocket.applyDiscoveredHosts(found)
            kotlinx.coroutines.delay(8_000)
        }
    }
    LaunchedEffect(pairLink) {
        if (pairLink != null) {
            pairing = pairLink
            addressHintTab = when (classifyWsRoute(pairLink.host)) {
                Route.WsTailscale -> PeerTransport.Tailscale
                else -> PeerTransport.Lan
            }
            showPair = true
            onPairLinkConsumed()
        }
    }

    fun openAddressSheet(seed: PairingLink? = null, tab: PeerTransport = transport) {
        addressHintTab = tab
        pairing = seed
        showPair = true
    }

    Column(Modifier.fillMaxSize().background(Conn.Bg).systemBarsPadding().verticalScroll(rememberScrollState())) {
        ConnHeader(
            title = L.connections(),
            onBack = if (canBack) onBack else null,
            onOverflow = { showOverflow = true },
        )
        if (sorted.isNotEmpty()) {
            Text(L.pairedDevices(), fontSize = 12.sp, color = Conn.FgMute, modifier = Modifier.padding(start = 22.dp, top = 2.dp, bottom = 4.dp))
            ConnCard {
                sorted.forEachIndexed { i, p ->
                    val d = status[p.id]
                    val route = routes[p.id]
                    ConnProfileRow(
                        name = p.name + if (p.id == active) " · ${L.currentDevice()}" else "",
                        subtitle = "${p.subtitle} · ${dotLabel(d)}",
                        chip = route?.label ?: p.kind,
                        warn = p.needsRepair,
                        divider = i < sorted.lastIndex,
                        onClick = { manageId = p.id },
                        leading = { StatusDot(d) },
                    )
                }
            }
        }

        PeerTransportTabs(tabs = tabs, selected = transport, en = en, onSelect = { transport = it })

        when (transport) {
            PeerTransport.Lan -> {
                val needPerm = NearbyDiscoveryPermissionPolicy.shouldRequest(
                    apiLevel = Build.VERSION.SDK_INT,
                    granted = nearbyGranted,
                    previouslyDeclined = Pocket.prefs.nearbyWifiDeclined,
                )
                if (needPerm || nearby.isNotEmpty()) {
                    Text(L.nearbyComputers(), fontSize = 12.sp, color = Conn.FgMute, modifier = Modifier.padding(start = 22.dp, top = 2.dp, bottom = 4.dp))
                    ConnCard {
                        when {
                            needPerm -> ConnOptionRow(L.allowNearby(), icon = D.link) { showNearbyPerm = true }
                            else -> nearby.forEachIndexed { i, h ->
                                ConnProfileRow(
                                    name = h.deviceName,
                                    subtitle = "${h.host}:${h.port}",
                                    chip = "LAN",
                                    warn = false,
                                    divider = i < nearby.lastIndex,
                                    onClick = { openAddressSheet(PairingLink(h.host, h.port, ""), PeerTransport.Lan) },
                                    leading = { Icon(Icons.Outlined.Computer, null, tint = Conn.Accent, modifier = Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                }
            }
            PeerTransport.Tailscale -> Unit
        }

        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnActionButton(L.scanQr(), Modifier.weight(1f)) { showScan = true }
            ConnActionButton(L.enterAddress(), Modifier.weight(1f)) { openAddressSheet() }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showNearbyPerm && Build.VERSION.SDK_INT >= 33) {
        NearbyWifiPermissionSheet(
            onDismiss = {
                Pocket.prefs.nearbyWifiDeclined = true
                showNearbyPerm = false
            },
            onAllow = { nearbyPermLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES) },
        )
    }
    if (showOverflow) {
        ConnOverflowSheet(
            onDismiss = { showOverflow = false },
            onDiagnostics = { showOverflow = false; showDiagnostics = true },
        )
    }
    if (showDiagnostics) DiagnosticsSheet(onDismiss = { showDiagnostics = false })
    if (showPair) PairSheet(
        link = pairing,
        prefName = repairTarget?.name.orEmpty(),
        addressHint = peerAddressHint(addressHintTab, en),
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
                addressHintTab = if (PeerTransport.Tailscale.matchesProfile(p)) PeerTransport.Tailscale else PeerTransport.Lan
                showPair = true
            },
        )
    }
}

@Composable
private fun ConnHeader(title: String, onBack: (() -> Unit)?, onOverflow: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(D.chevronLeft, null, tint = Conn.Fg) } else Spacer(Modifier.width(16.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Conn.Fg, modifier = Modifier.weight(1f))
        IconButton(onOverflow) { Icon(D.ellipsis, L.moreActions(), tint = Conn.FgDim, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun ConnCard(content: @Composable ColumnScope.() -> Unit) =
    Column(
        Modifier.padding(horizontal = 18.dp, vertical = 4.dp).fillMaxWidth()
            .clip(Conn.R12).border(1.dp, Conn.Border, Conn.R12).background(Conn.Surface),
        content = content,
    )

@Composable
private fun ConnActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick,
        modifier.height(48.dp),
        shape = Conn.R12,
        border = BorderStroke(1.dp, Conn.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Conn.Fg, containerColor = Conn.Elevated),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) { Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun PeerTransportTabs(
    tabs: List<PeerTransport>,
    selected: PeerTransport,
    en: Boolean,
    onSelect: (PeerTransport) -> Unit,
) {
    Row(
        Modifier.padding(horizontal = 18.dp, vertical = 8.dp).fillMaxWidth()
            .clip(Conn.R12).background(Conn.Elevated).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        tabs.forEach { tab ->
            val on = tab == selected
            Box(
                Modifier.weight(1f).height(36.dp).clip(Conn.R12)
                    .background(if (on) Conn.Accent.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label(en),
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Conn.Accent else Conn.FgDim,
                )
            }
        }
    }
}

@Composable
private fun ConnProfileRow(
    name: String,
    subtitle: String,
    chip: String,
    warn: Boolean,
    divider: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading(); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, color = Conn.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = Conn.Mono.copy(fontSize = 11.sp, color = Conn.FgMute, lineHeight = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Text(
            chip,
            fontSize = 11.sp,
            color = if (warn) Conn.Warn else Conn.FgDim,
            modifier = Modifier.border(1.dp, Conn.Border, T.R8).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Icon(D.chevronRight, null, tint = Conn.FgMute, modifier = Modifier.padding(start = 6.dp).size(18.dp))
    }
    if (divider) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = Conn.Border.copy(alpha = 0.4f))
}

@Composable
fun ConnOverflowSheet(onDismiss: () -> Unit, onDiagnostics: () -> Unit) {
    ConnSheet(onDismiss) {
        ConnSheetTitle(L.moreActions())
        ConnOptionRow(L.diagnostics(), icon = D.inspect, onClick = onDiagnostics)
    }
}

@Composable
fun ConnPrimaryColors() = ButtonDefaults.buttonColors(
    containerColor = Conn.Accent,
    contentColor = Conn.Bg,
    disabledContainerColor = Conn.Border,
    disabledContentColor = Conn.FgMute,
)

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
    ConnSheet(onDismiss) {
        if (renaming) {
            var editName by remember(profile.id, profile.name) { mutableStateOf(profile.name) }
            ConnSheetTitle(L.rename())
            OutlinedTextField(
                editName,
                { editName = it.take(64) },
                singleLine = true,
                shape = Conn.R12,
                textStyle = Conn.Mono.copy(fontSize = 15.sp, color = Conn.Fg),
                colors = connFieldColors(),
                modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth().testTag("profileRenameInput"),
            )
            Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    { renaming = false },
                    Modifier.weight(1f).height(48.dp),
                    shape = Conn.R12,
                    border = BorderStroke(1.dp, Conn.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Conn.Fg),
                ) { Text(L.cancel()) }
                Button(
                    {
                        val trimmed = editName.trim()
                        if (trimmed.isNotEmpty() && trimmed != profile.name) Pocket.renameProfile(profile.id, trimmed)
                        renaming = false
                    },
                    Modifier.weight(1f).height(48.dp),
                    enabled = editName.trim().isNotEmpty(),
                    shape = Conn.R12,
                    colors = ConnPrimaryColors(),
                ) { Text(L.save()) }
            }
            return@ConnSheet
        }
        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status); Spacer(Modifier.width(10.dp)); ConnSheetTitle(profile.name)
            if (isActive) {
                Text(
                    L.currentDevice(),
                    fontSize = 12.sp,
                    color = Conn.Accent,
                    modifier = Modifier.background(Conn.Accent.copy(alpha = 0.15f), T.R4).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            "${profile.subtitle} · ${dotLabel(status)}",
            style = Conn.Mono.copy(color = Conn.FgMute),
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (profile.needsRepair) {
            Text(L.repairNeeded(), fontSize = 13.sp, color = Conn.FgMute, modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp))
        } else if (!isActive || status != Dot.Online) {
            ConnOptionRow(L.connectAction(), icon = D.link) { onConnect() }
        }
        ConnOptionRow(L.repairPair(), icon = D.link) { onRepair() }
        ConnOptionRow(L.rename(), icon = D.edit) { renaming = true }
        ConnOptionRow(L.delete(), icon = D.trash) { Pocket.removeProfile(profile.id); onDismiss() }
    }
}

private fun dotLabel(d: Dot?) = when (d) {
    Dot.Online -> L.online()
    Dot.Connecting -> L.connecting()
    Dot.Unauthorized -> L.revoked()
    Dot.Offline -> L.offline()
    null -> L.unknownStatus()
}

@Composable
fun NearbyWifiPermissionSheet(onDismiss: () -> Unit, onAllow: () -> Unit) {
    ConnSheet(onDismiss) {
        ConnSheetTitle(L.nearbyComputers())
        Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onDismiss, Modifier.weight(1f).height(48.dp), shape = Conn.R12,
                border = BorderStroke(1.dp, Conn.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Conn.Fg),
            ) { Text(L.cancel()) }
            Button(onAllow, Modifier.weight(1f).height(48.dp), shape = Conn.R12, colors = ConnPrimaryColors()) {
                Text(L.allowNearby(), color = Conn.Bg)
            }
        }
    }
}

@Composable
fun PairSheet(
    link: PairingLink?,
    prefName: String = "",
    addressHint: String = peerAddressHint(PeerTransport.Lan),
    onDismiss: () -> Unit,
    onPaired: (Profile) -> Unit,
) {
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
    ConnSheet(onDismiss) {
        ConnSheetTitle(L.pairSheetTitle())
        ConnField(L.addressField(), address, addressHint, mono = true) {
            address = it
            err = null
            PairingLink.parse(it.trim())?.let { parsedLink ->
                address = "${parsedLink.host}:${parsedLink.port}"
                if (parsedLink.code.length == 6) code = parsedLink.code
            }
        }
        ConnField(L.nameField(), name, L.nameHint()) { name = it }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BasicTextField(
                code,
                { v -> code = v.filter(Char::isDigit).take(6); err = null },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.focusRequester(focus),
                cursorBrush = SolidColor(Color.Transparent),
                textStyle = TextStyle(color = Color.Transparent),
                decorationBox = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(6) { i ->
                            val cur = i == code.length
                            Box(
                                Modifier.size(42.dp, 50.dp).clip(Conn.R12)
                                    .border(if (cur) 1.5.dp else 1.dp, if (cur) Conn.Accent else Conn.Border, Conn.R12)
                                    .background(Conn.Elevated),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    code.getOrNull(i)?.toString() ?: "",
                                    style = Conn.Mono.copy(fontSize = 22.sp, color = Conn.Fg),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                },
            )
        }
        err?.let { Text(it, color = Conn.Err, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) }
        val ready = parsed != null && !busy
        Button(
            {
                val generation = gate.begin() ?: return@Button
                busy = true
                scope.launch {
                    val result = Pocket.pair(name.trim(), parsed!!)
                    if (gate.complete(generation)) {
                        result.onSuccess(onPaired).onFailure { err = it.message }
                        busy = false
                    }
                }
            },
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp).fillMaxWidth().height(48.dp),
            enabled = ready,
            shape = Conn.R12,
            colors = ConnPrimaryColors(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Conn.Bg, strokeWidth = 2.dp)
            else Text(L.pairAction(), fontSize = 15.sp, color = Conn.Bg)
        }
    }
    LaunchedEffect(Unit) { if (link != null) runCatching { focus.requestFocus() } }
}

@Composable
private fun ConnField(label: String, v: String, hint: String, mono: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = Conn.FgMute, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Box(Modifier.fillMaxWidth().clip(Conn.R12).background(Conn.Elevated).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (v.isEmpty()) {
                Text(hint, color = Conn.FgMute, style = if (mono) Conn.Mono.copy(fontSize = 14.sp, color = Conn.FgMute) else TextStyle.Default.copy(fontSize = 14.sp, color = Conn.FgMute))
            }
            BasicTextField(
                v,
                onChange,
                singleLine = true,
                textStyle = if (mono) Conn.Mono.copy(fontSize = 14.sp, color = Conn.Fg) else TextStyle.Default.copy(fontSize = 14.sp, color = Conn.Fg),
                cursorBrush = SolidColor(Conn.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun connFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Conn.Fg,
    unfocusedTextColor = Conn.Fg,
    focusedBorderColor = Conn.Accent,
    unfocusedBorderColor = Conn.Border,
    cursorColor = Conn.Accent,
    focusedContainerColor = Conn.Elevated,
    unfocusedContainerColor = Conn.Elevated,
)

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
    Column(Modifier.fillMaxSize().background(T.Bg).systemBarsPadding().verticalScroll(rememberScrollState())) {
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
    var layout by remember { mutableStateOf(Pocket.prefs.layoutMode) }
    var sheet by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(T.Bg).systemBarsPadding().verticalScroll(rememberScrollState())) {
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
            Row2(
                L.layout(),
                "宽屏（≥${WideLayoutMinDp}dp）时侧栏常显",
                value = when (layout) {
                    LayoutMode.Auto -> L.layoutAuto()
                    LayoutMode.Phone -> L.layoutPhone()
                    LayoutMode.Desktop -> L.layoutDesktop()
                },
                onClick = { sheet = "layout" },
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
        "layout" -> PocketSheet({ sheet = null }) {
            SheetTitle(L.layout())
            OptionRow(L.layoutAuto(), "跟随屏幕宽度", selected = layout == LayoutMode.Auto) {
                layout = LayoutMode.Auto; Pocket.prefs.layoutMode = layout; sheet = null
            }
            OptionRow(L.layoutPhone(), "始终抽屉侧栏", selected = layout == LayoutMode.Phone) {
                layout = LayoutMode.Phone; Pocket.prefs.layoutMode = layout; sheet = null
            }
            OptionRow(L.layoutDesktop(), "始终双栏侧栏", selected = layout == LayoutMode.Desktop) {
                layout = LayoutMode.Desktop; Pocket.prefs.layoutMode = layout; sheet = null
            }
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
