package dev.dsh.pocket.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.dsh.pocket.protocol.classifyWsRoute
import dev.dsh.pocket.protocol.Route
import android.Manifest
import android.animation.ValueAnimator
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

/** Desktop settings card: `--dsw-alias-settings-card-fill` + stroke + `--dsw-radius-lg`. */
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) =
    Column(
        Modifier
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(T.R16)
            .background(T.SettingsCard)
            .border(1.dp, T.SettingsCardStroke, T.R16),
        content = content,
    )

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

/** Automatically reserve a usable 480dp conversation beside the sidebar. */
const val WideLayoutMinDp = 760

fun useTwoPaneLayout(widthDp: Int): Boolean = widthDp >= WideLayoutMinDp

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
    onCollapse: (() -> Unit)? = null,
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
            onCollapse = onCollapse,
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
    onCollapse: (() -> Unit)?,
) {
    val sessions by Pocket.sessions.collectAsState()
    val chat by Pocket.chat.collectAsState()
    val active by Pocket.active.collectAsState()
    val status by Pocket.status.collectAsState()
    val archived by Pocket.archivedIds.collectAsState()
    val pinned by Pocket.pinnedIds.collectAsState()
    val ops by Pocket.ops.collectAsState()
    val activities = rememberSidebarActivities()
    val profile = Pocket.profiles.collectAsState().value.firstOrNull { it.id == active }
    val workspaces by Pocket.workspaces.collectAsState()
    val host = active.orEmpty()
    var search by remember(host) { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchHits by remember(host) { mutableStateOf<List<dev.dsh.pocket.protocol.SessionSummary>>(emptyList()) }
    var groupBy by remember { mutableStateOf(Pocket.prefs.sessionGroupBy) }
    var orderBy by remember { mutableStateOf(Pocket.prefs.sessionOrderBy) }
    var archivedFilter by remember { mutableStateOf(Pocket.prefs.archivedFilter) }
    var viewOptionsOpen by remember { mutableStateOf(false) }
    var addWorkspaceOpen by remember { mutableStateOf(false) }
    var menuSessionId by remember { mutableStateOf<String?>(null) }
    var collapsedWorkspaces by remember(host) { mutableStateOf(Pocket.prefs.collapsedWorkspaces(host)) }
    var savedOrder by remember(host) { mutableStateOf(Pocket.prefs.manualSessionOrder(host)) }
    val currentBlank = sessions.firstOrNull { it.sessionId == chat?.sessionId && it.blank }?.sessionId
    val completeOrder = reconcileSessionOrder(sessions, savedOrder).let { ids ->
        if (currentBlank == null) ids else listOf(currentBlank) + ids.filterNot { it == currentBlank }
    }
    val ordered = if (orderBy == SessionOrderBy.Updated) sessions.sortedByDescending { it.updatedAt }
        else completeOrder.mapNotNull { id -> sessions.firstOrNull { it.sessionId == id } }
    val moveOrder = if (orderBy == SessionOrderBy.Updated) ordered.map { it.sessionId } else completeOrder
    val visible = ordered.filter { visibleDrawerSession(it, chat?.sessionId, archived, archivedFilter) }
        .sortedByDescending { it.blank && it.sessionId == chat?.sessionId }
    val searchFocus = remember { FocusRequester() }
    val currentSearch by rememberUpdatedState(search)
    val currentHost by rememberUpdatedState(host)
    fun saveOrder(ids: List<String>) {
        savedOrder = ids
        Pocket.prefs.setManualSessionOrder(host, ids)
        orderBy = SessionOrderBy.Manual
        Pocket.prefs.sessionOrderBy = orderBy
    }
    fun toggleGroup(key: String) {
        collapsedWorkspaces = if (key in collapsedWorkspaces) collapsedWorkspaces - key else collapsedWorkspaces + key
        Pocket.prefs.setCollapsedWorkspaces(host, collapsedWorkspaces)
    }
    @Composable fun sessionRows(rows: List<dev.dsh.pocket.protocol.SessionSummary>) {
        val peers = rows.filter { !it.blank && it.sessionId !in archived && it.archived != true }.map { it.sessionId }
        rows.forEach { s ->
            key(s.sessionId) {
                SessionRow(s, s.sessionId == chat?.sessionId, s.sessionId in archived || s.archived == true,
                    s.sessionId in pinned && s.sessionId !in archived, menuSessionId == s.sessionId,
                    activity = activities[s.sessionId] ?: if (s.running) SidebarActivity.Running else SidebarActivity.Idle,
                    onPick = {
                        search = ""; searchExpanded = false
                        val workspace = workspaces.firstOrNull { s.sessionId in it.sessionIds }
                        if (workspace != null) {
                            val ancestors = workspaces.filter { workspace.path == it.path || workspace.path.startsWith(it.path.trimEnd('/') + "/") }
                                .map { "workspace:${it.workspaceId}" }.toSet()
                            collapsedWorkspaces = collapsedWorkspaces - ancestors
                            Pocket.prefs.setCollapsedWorkspaces(host, collapsedWorkspaces)
                        }
                        onPick(s.sessionId)
                    },
                    onMenuOpenChange = { menuSessionId = if (it) s.sessionId else null },
                    onMoveUp = peers.indexOf(s.sessionId).takeIf { search.isBlank() && it > 0 }?.let {
                        { saveOrder(moveSession(moveOrder, peers, s.sessionId, -1)) }
                    },
                    onMoveDown = peers.indexOf(s.sessionId).takeIf { search.isBlank() && it >= 0 && it < peers.lastIndex }?.let {
                        { saveOrder(moveSession(moveOrder, peers, s.sessionId, 1)) }
                    },
                )
            }
        }
    }
    LaunchedEffect(menuSessionId) { onMenuOpenChange(menuSessionId != null) }
    DisposableEffect(Unit) { onDispose { onMenuOpenChange(false) } }
    LaunchedEffect(completeOrder, orderBy, host) {
        if (orderBy == SessionOrderBy.Manual && sessions.isNotEmpty() && savedOrder != completeOrder) {
            savedOrder = completeOrder
            Pocket.prefs.setManualSessionOrder(host, completeOrder)
        }
    }
    LaunchedEffect(search, host, ops) {
        searchHits = emptyList(); searchError = null
        if (search.isBlank() || "sessions.search" !in ops) { searching = false; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(250)
        val query = search
        val requestHost = host
        Pocket.searchSessions(query) { result ->
            if (currentSearch == query && currentHost == requestHost) {
                searchHits = result.getOrDefault(emptyList())
                searchError = result.exceptionOrNull()?.message
                searching = false
            }
        }
    }
    LaunchedEffect(searchExpanded) { if (searchExpanded) searchFocus.requestFocus() }
    BackHandler(searchExpanded && menuSessionId == null) { searchExpanded = false; search = "" }
    Column(Modifier.fillMaxHeight()) {
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(D.wordmark, "deepseek HARNESS", modifier = Modifier.height(22.dp).width(167.dp))
            Spacer(Modifier.weight(1f))
            onCollapse?.let { IconButton(it) { Icon(D.chevronLeft, navText("收起侧栏", "Collapse sidebar"), Modifier.size(18.dp)) } }
        }
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(48.dp).clip(T.R12).background(T.Surface).border(1.dp, T.Border, T.R12)
                .clickable(onClick = onNew).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(D.newChat, null, Modifier.size(16.dp), tint = T.Ink); Spacer(Modifier.width(6.dp)); Text(navText("新会话", "New session"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
            Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 8.dp),
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
                    TextButton({ search = ""; searchHits = emptyList() }) { Text(L.clearSearch(), fontSize = 12.sp) }
                } else {
                    IconButton({ searchExpanded = false; search = ""; searchHits = emptyList() }) {
                        Icon(D.chevronLeft, navText("关闭搜索", "Close search"), Modifier.size(18.dp), tint = T.Text3)
                    }
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
                if ("workspace.create" in ops) DrawerHeaderIcon(D.projectAdd, L.addWorkspace()) { addWorkspaceOpen = true }
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (search.isNotBlank()) {
                val query = search.trim()
                val local = sessions.filter { s ->
                    s.title.orEmpty().contains(query, true) || s.cwd.orEmpty().contains(query, true) ||
                        workspaces.any { s.sessionId in it.sessionIds && (it.title.orEmpty().contains(query, true) || it.path.contains(query, true)) }
                }
                val hits = (searchHits + local).distinctBy { it.sessionId }.filter {
                    visibleDrawerSession(it, chat?.sessionId, archived, archivedFilter)
                }
                if (searching) Text(L.searchingSessions(), color = T.Caption, modifier = Modifier.padding(16.dp))
                searchError?.let { Text(it, color = T.Err, modifier = Modifier.padding(16.dp)) }
                if (hits.isEmpty() && !searching && searchError == null) Text(L.noMatchingSessions(), color = T.Caption, modifier = Modifier.padding(16.dp))
                sessionRows(hits)
            } else {
                val pinnedRows = visible.filter { it.sessionId in pinned && it.sessionId !in archived }
                val rest = visible.filter { it.sessionId !in pinned || it.sessionId in archived }
                if (pinnedRows.isNotEmpty()) {
                    Text(L.pinned(), fontSize = 12.sp, color = T.Text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
                    sessionRows(pinnedRows)
                }
                if (visible.isEmpty() && workspaces.isEmpty()) {
                    Text(if (status[active] != Dot.Online) navText("连接后显示会话", "Connect to view sessions")
                        else if (archivedFilter == ArchivedFilter.Only) L.emptyArchivedSessions() else L.emptySessions(),
                        color = T.Caption, modifier = Modifier.padding(16.dp))
                }
                if (groupBy == SessionGroupBy.Flat) sessionRows(rest)
                else {
                    WorkspaceNavigationGroups(
                        workspaces, rest, groupBy == SessionGroupBy.WorkspaceTree,
                        collapsedWorkspaces, ::toggleGroup, onPick,
                        renderSessions = { sessionRows(it) },
                    )
                }
            }
        }
        DrawerConnectionFooter(profile, status[active], onSettings, onConnections)
        Spacer(Modifier.height(8.dp))
    }
    if (viewOptionsOpen) {
        ViewOptionsSheet(
            groupBy = groupBy,
            orderBy = orderBy,
            archivedFilter = archivedFilter,
            onDismiss = { viewOptionsOpen = false },
            onGroup = { groupBy = it; Pocket.prefs.sessionGroupBy = it },
            onOrder = {
                orderBy = it; Pocket.prefs.sessionOrderBy = it
                savedOrder = if (it == SessionOrderBy.Manual) sessions.sortedByDescending { s -> s.updatedAt }.map { s -> s.sessionId } else emptyList()
                Pocket.prefs.setManualSessionOrder(host, savedOrder)
            },
            onArchived = { archivedFilter = it; Pocket.prefs.archivedFilter = it },
        )
    }
    if (addWorkspaceOpen) {
        WorkspaceDirectorySheet(onDismiss = { addWorkspaceOpen = false })
    }
}



/** Desktop `.panelRow`: 16px icon, 8px gap, 14px label, min-height 36. */
@Composable
private fun DrawerPanelRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(T.R12).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp).testTag("drawerPanel:$text"),
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
        Modifier.size(48.dp).clip(T.R8).clickable(onClick = onClick).testTag("drawerHeader:$label"),
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


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    s: dev.dsh.pocket.protocol.SessionSummary,
    selected: Boolean,
    archived: Boolean,
    pinned: Boolean,
    menuOpen: Boolean,
    activity: SidebarActivity,
    onPick: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val ops by Pocket.ops.collectAsState()
    val archivedIds by Pocket.archivedIds.collectAsState()
    val pinnedIds by Pocket.pinnedIds.collectAsState()
    val isArchived = archived || s.sessionId in archivedIds
    val isPinned = s.sessionId in pinnedIds
    var renameOpen by remember(s.sessionId) { mutableStateOf(false) }
    var renameTitle by remember(s.sessionId, s.title) { mutableStateOf(s.title.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var archiveConfirm by remember { mutableStateOf(false) }
    var archiveActivity by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
    var restoreConfirm by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(T.R8).background(if (selected) T.Fill else Color.Transparent)
                .combinedClickable(onClick = { if (isArchived) restoreConfirm = true else onPick() }, onLongClick = { onMenuOpenChange(true) })
                .heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(16.dp).height(14.dp), contentAlignment = Alignment.Center) {
                        SidebarActivityIndicator(activity, Modifier.size(14.dp).testTag("sessionActivity:${s.sessionId}"))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(s.title ?: navText("新会话", "New session"), fontSize = 15.sp, color = if (isArchived) T.Caption else T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                s.searchSnippet?.let { Text(it, color = T.Text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 22.dp)) }
                s.cwd?.let { Text(it, color = T.Caption, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 22.dp)) }
                if (archived || pinned) {
                    Text(
                        listOfNotNull(if (pinned) L.pinned() else null, if (archived) L.archived() else null).joinToString(" · "),
                        fontSize = 11.sp, color = T.Caption, modifier = Modifier.padding(start = 22.dp),
                    )
                }
            }
            IconButton({ onMenuOpenChange(true) }, Modifier.size(48.dp).testTag("sessionMenu:${s.sessionId}")) {
                Icon(D.ellipsis, L.moreActions(), Modifier.size(18.dp), tint = T.Caption)
            }
        }
        // DropdownMenu (not a bottom sheet): first tap reaches items; drawer gestures stay uninvolved.
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { onMenuOpenChange(false) },
            containerColor = T.Surface,
        ) {
            if ("sessions.rename" in ops && !isArchived) DropdownMenuItem(
                text = { Text(L.rename()) },
                onClick = { onMenuOpenChange(false); renameOpen = true },
            )
            if ("workspace.pinSession" in ops && !isArchived) {
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
                        if (isArchived) Pocket.unarchiveSession(s.sessionId)
                        else Pocket.archiveSession(s.sessionId, stopActivity = false, onActivity = { details ->
                            archiveActivity = details; archiveConfirm = true
                        })
                        onMenuOpenChange(false)
                    },
                )
            }
            if ("sessions.fork" in ops && !isArchived) {
                DropdownMenuItem(
                    text = { Text(navText("在新对话中分支", "Fork in new conversation")) },
                    onClick = {
                        Pocket.forkSessionFrom(s.sessionId)
                        onMenuOpenChange(false)
                    },
                )
            }
            if (!isArchived && !s.blank) {
                onMoveUp?.let { move -> DropdownMenuItem(text = { Text(navText("上移", "Move up")) }, onClick = { move(); onMenuOpenChange(false) }) }
                onMoveDown?.let { move -> DropdownMenuItem(text = { Text(navText("下移", "Move down")) }, onClick = { move(); onMenuOpenChange(false) }) }
            }
        }
    }
    if (renameOpen) AlertDialog(
        onDismissRequest = { if (!busy) renameOpen = false },
        title = { Text(L.rename()) },
        text = { OutlinedTextField(renameTitle, { renameTitle = it }, singleLine = true, enabled = !busy) },
        confirmButton = { TextButton({
            busy = true
            Pocket.rename(s.sessionId, renameTitle.trim()) { ok -> busy = false; if (ok) renameOpen = false }
        }, enabled = renameTitle.isNotBlank() && !busy) { Text(if (busy) navText("保存中…", "Saving…") else L.save()) } },
        dismissButton = { TextButton({ renameOpen = false }, enabled = !busy) { Text(L.cancel()) } },
    )
    if (archiveConfirm) AlertDialog(
        onDismissRequest = { if (!busy) archiveConfirm = false },
        title = { Text(navText("停止工作并归档？", "Stop work and archive?")) },
        text = { Column {
            Text(navText("归档将停止以下工作；恢复会话不会自动重启工作。", "Archiving stops the following work. Restoring the session will not restart it."))
            Text(archiveActivityDescription(archiveActivity), modifier = Modifier.padding(top = 12.dp))
        } },
        confirmButton = { TextButton({
            busy = true
            Pocket.archiveSession(s.sessionId, stopActivity = true, onResult = { ok -> busy = false; if (ok) archiveConfirm = false })
        }, enabled = !busy) { Text(L.archiveSession()) } },
        dismissButton = { TextButton({ archiveConfirm = false }, enabled = !busy) { Text(L.cancel()) } },
    )
    if (restoreConfirm) AlertDialog(
        onDismissRequest = { if (!busy) restoreConfirm = false },
        title = { Text(L.archived()) },
        text = { Text(navText("已归档会话需先恢复才能打开。", "Restore this archived session before opening it.")) },
        confirmButton = { TextButton({
            busy = true
            Pocket.unarchiveSession(s.sessionId) { ok -> busy = false; if (ok) { restoreConfirm = false; onPick() } }
        }, enabled = !busy) { Text(L.unarchiveSession()) } },
        dismissButton = { TextButton({ restoreConfirm = false }, enabled = !busy) { Text(L.cancel()) } },
    )
}

private val RoundedCornerShapeEnd = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)

/** Keep connection identity next to Settings, leaving the session-list header uncluttered. */
@Composable
internal fun DrawerConnectionFooter(
    profile: Profile?,
    status: Dot?,
    onSettings: () -> Unit,
    onConnections: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).testTag("drawerFooter"), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(T.R8).clickable(role = Role.Button, onClick = onSettings)
                .heightIn(min = 48.dp).padding(horizontal = 10.dp).testTag("drawerSettings"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(D.settings, null, Modifier.size(18.dp), tint = T.Ink)
            Spacer(Modifier.width(8.dp))
            Text(L.settings(), fontSize = 14.sp, color = T.Ink)
        }
        Spacer(Modifier.width(4.dp))
        val connectionName = profile?.name ?: if (Pocket.prefs.language == AppLanguage.En) "Not connected" else "未连接"
        Row(
            Modifier.weight(1f).clip(T.R8).clickable(role = Role.Button, onClick = onConnections)
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 5.dp)
                .testTag("drawerConnection")
                .semantics { contentDescription = listOfNotNull(connectionName, dotLabel(status)).joinToString("，") },
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status, Modifier.testTag("drawerConnectionStatus"))
            Spacer(Modifier.width(6.dp))
            Text(connectionName, modifier = Modifier.weight(1f, fill = false),
                fontSize = 12.sp, color = T.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- Connections (desktop settings list rows · --dsw-alias-*) ----------

@Composable
fun ConnectionsScreen(
    pairLink: PairingLink?,
    onPairLinkConsumed: () -> Unit,
    canBack: Boolean,
    onBack: () -> Unit,
    onConnected: () -> Unit,
    /** Test/screenshot seed for mDNS `_dshpocket._tcp` rows (production leaves empty). */
    nearbySeed: List<DiscoveredHost> = emptyList(),
) {
    val profiles by Pocket.profiles.collectAsState()
    val status by Pocket.status.collectAsState()
    val active by Pocket.active.collectAsState()
    val ctx = LocalContext.current
    val en = Pocket.prefs.language == AppLanguage.En
    var pairing by remember { mutableStateOf<PairingLink?>(null) }
    var addressHintTab by remember { mutableStateOf(PeerTransport.Lan) }
    var pairPrefName by remember { mutableStateOf("") }
    var showPair by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var manageId by remember { mutableStateOf<String?>(null) }
    var repairTarget by remember { mutableStateOf<Profile?>(null) }
    var nearby by remember { mutableStateOf(nearbySeed) }
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
            // Compose waitForIdle advances LaunchedEffect delay on the test clock.
            // An infinite poll would never settle Espresso/Compose idle.
            if (!ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
            kotlinx.coroutines.delay(10_000)
        }
    }
    val nearbyPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Pocket.prefs.nearbyWifiDeclined = true
        showNearbyPerm = false
    }
    val nearbyGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
    val needNearbyPerm = NearbyDiscoveryPermissionPolicy.shouldRequest(
        apiLevel = Build.VERSION.SDK_INT,
        granted = nearbyGranted,
        previouslyDeclined = Pocket.prefs.nearbyWifiDeclined,
    )
    LaunchedEffect(Unit) {
        if (nearbySeed.isNotEmpty()) return@LaunchedEffect
        while (true) {
            val canceled = AtomicBoolean(false)
            val found = withContext(Dispatchers.IO) { LanDiscovery.scan(ctx, canceled = canceled) }
            nearby = LanDiscovery.unpairedNearby(found, Pocket.profiles.value)
            Pocket.applyDiscoveredHosts(found)
            if (!ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
            kotlinx.coroutines.delay(8_000)
        }
    }
    LaunchedEffect(profiles, nearbySeed) {
        if (nearbySeed.isNotEmpty()) {
            nearby = LanDiscovery.unpairedNearby(nearbySeed, profiles)
        }
    }
    LaunchedEffect(pairLink) {
        if (pairLink != null) {
            pairing = pairLink
            pairPrefName = ""
            addressHintTab = when (classifyWsRoute(pairLink.host)) {
                Route.WsTailscale -> PeerTransport.Tailscale
                else -> PeerTransport.Lan
            }
            showPair = true
            onPairLinkConsumed()
        }
    }

    fun openAddressSheet(
        seed: PairingLink? = null,
        tab: PeerTransport = PeerTransport.Lan,
        name: String = "",
    ) {
        addressHintTab = tab
        pairing = seed
        pairPrefName = name
        showPair = true
    }

    val compatibility by Pocket.compatibility.collectAsState()
    val lanLabel = PeerTransport.Lan.label(en)
    Column(Modifier.fillMaxSize().background(Conn.Bg).systemBarsPadding().verticalScroll(rememberScrollState()).testTag("connectionsScreen")) {
        ConnHeader(
            title = L.connections(),
            onBack = if (canBack) onBack else null,
            onOverflow = { showOverflow = true },
        )
        CompatibilityBanner(compatibility)

        if (sorted.isNotEmpty()) {
            ConnSectionTitle(L.pairedDevices())
            Card {
                sorted.forEachIndexed { i, p ->
                    val d = status[p.id]
                    val routeLabel = routes[p.id]?.label ?: p.kind
                    ConnDeviceRow(
                        name = p.name,
                        statusLine = buildConnStatusLine(d, routeLabel, isCurrent = p.id == active, warn = p.needsRepair),
                        detail = p.subtitle.takeIf { !p.needsRepair },
                        warn = p.needsRepair,
                        divider = i < sorted.lastIndex,
                        onClick = { manageId = p.id },
                        leading = { StatusDot(d) },
                    )
                }
            }
        }

        // 附近设备 — mDNS unpaired only; hide entirely when empty (no placeholder).
        if (nearby.isNotEmpty()) {
            ConnSectionTitle(L.nearbyDevices())
            Card {
                nearby.forEachIndexed { i, h ->
                    ConnDeviceRow(
                        name = h.deviceName,
                        statusLine = lanLabel,
                        detail = "${h.host}:${h.port}",
                        warn = false,
                        divider = i < nearby.lastIndex,
                        onClick = {
                            openAddressSheet(
                                PairingLink(h.host, h.port, ""),
                                PeerTransport.Lan,
                                name = h.deviceName,
                            )
                        },
                        leading = { Icon(Icons.Outlined.Computer, null, tint = Conn.Accent, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }

        Card {
            ConnActionRow(
                title = L.scanQr(),
                icon = Icons.Outlined.QrCodeScanner,
                divider = true,
                onClick = { showScan = true },
            )
            ConnActionRow(
                title = L.enterAddress(),
                icon = D.link,
                divider = false,
                onClick = { openAddressSheet() },
            )
        }
        Spacer(Modifier.height(24.dp))
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
            showNearbyAllow = needNearbyPerm,
            onNearbyAllow = { showOverflow = false; showNearbyPerm = true },
            onDiagnostics = { showOverflow = false; showDiagnostics = true },
        )
    }
    if (showDiagnostics) DiagnosticsSheet(onDismiss = { showDiagnostics = false })
    if (showPair) PairSheet(
        link = pairing,
        prefName = repairTarget?.name?.takeIf { it.isNotBlank() } ?: pairPrefName,
        addressHint = peerAddressHint(addressHintTab, en),
        onDismiss = { showPair = false; repairTarget = null; pairPrefName = "" },
    ) { p -> showPair = false; repairTarget = null; pairPrefName = ""; Pocket.connect(p.id); onConnected() }
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
                pairPrefName = ""
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
        if (onBack != null) IconButton(onBack) { Icon(D.chevronLeft, navText("返回", "Back"), tint = Conn.Fg) } else Spacer(Modifier.width(16.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Conn.Fg, modifier = Modifier.weight(1f))
        IconButton(onOverflow) { Icon(D.ellipsis, L.moreActions(), tint = Conn.FgDim, modifier = Modifier.size(18.dp)) }
    }
}

/** Desktop settingsRow / deviceRow section head (14 / 20). */
@Composable
private fun ConnSectionTitle(title: String) {
    Text(
        title,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = Conn.Fg,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ConnHairline() {
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = T.Border,
    )
}

/**
 * Desktop deviceRow twin: status dot + name + status line (online · route), no boxed chip.
 */
@Composable
private fun ConnDeviceRow(
    name: String,
    statusLine: String,
    detail: String?,
    warn: Boolean,
    divider: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).fillMaxWidth()) {
            Text(
                name,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                color = if (warn) Conn.Warn else Conn.Fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLine,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Conn.FgDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            detail?.let {
                Text(
                    it,
                    style = Conn.Mono.copy(fontSize = 12.sp, color = Conn.FgMute, lineHeight = 18.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(D.chevronRight, null, tint = Conn.FgMute, modifier = Modifier.size(20.dp))
    }
    if (divider) ConnHairline()
}

/** Desktop settingsRow action: icon + title + chevron. */
@Composable
private fun ConnActionRow(
    title: String,
    icon: ImageVector,
    divider: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Conn.Fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 16.sp, lineHeight = 22.sp, color = Conn.Fg, modifier = Modifier.weight(1f))
        Icon(D.chevronRight, null, tint = Conn.FgMute, modifier = Modifier.size(20.dp))
    }
    if (divider) ConnHairline()
}

/** Clear status line: `在线 · Tailscale · 当前` (desktop deviceMeta pattern). */
private fun buildConnStatusLine(dot: Dot?, routeLabel: String, isCurrent: Boolean, warn: Boolean): String {
    val status = when {
        warn -> L.repairNeeded()
        else -> dotLabel(dot)
    }
    return buildString {
        append(status)
        if (routeLabel.isNotBlank()) {
            append(" · ")
            append(routeLabel)
        }
        if (isCurrent) {
            append(" · ")
            append(L.currentDevice())
        }
    }
}

@Composable
fun ConnOverflowSheet(
    onDismiss: () -> Unit,
    onDiagnostics: () -> Unit,
    showNearbyAllow: Boolean = false,
    onNearbyAllow: () -> Unit = {},
) {
    ConnSheet(onDismiss) {
        ConnSheetTitle(L.moreActions())
        if (showNearbyAllow) ConnOptionRow(L.allowNearby(), icon = D.link, onClick = onNearbyAllow)
        ConnOptionRow(L.diagnostics(), icon = D.inspect, onClick = onDiagnostics)
    }
}

/** Desktop primary CTA — `--dsw-alias-button-primary-fill` / label-primary-foreground. */
@Composable
fun ConnPrimaryColors() = PrimaryColors()

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
            StatusDot(status)
            Spacer(Modifier.width(10.dp))
            ConnSheetTitle(profile.name)
        }
        Text(
            buildConnStatusLine(status, profile.kind, isCurrent = isActive, warn = profile.needsRepair),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Conn.FgDim,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        if (!profile.needsRepair) {
            Text(
                profile.subtitle,
                style = Conn.Mono.copy(color = Conn.FgMute),
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 4.dp),
            )
        }
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
        ConnSheetTitle(L.nearbyDevices())
        Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onDismiss, Modifier.weight(1f).height(48.dp), shape = Conn.R12,
                border = BorderStroke(1.dp, Conn.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Conn.Fg),
            ) { Text(L.cancel()) }
            Button(onAllow, Modifier.weight(1f).height(48.dp), shape = Conn.R12, colors = ConnPrimaryColors()) {
                Text(L.allowNearby(), color = T.OnPrimary)
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
                modifier = Modifier.focusRequester(focus).semantics { contentDescription = navText("六位配对码", "Six-digit pairing code") },
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
        err?.let {
            Text(
                it,
                color = Conn.Err,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .padding(horizontal = 22.dp, vertical = 8.dp)
                    .testTag("pairError"),
            )
        }
        val canSubmit = parsed != null && !busy
        Button(
            {
                val generation = gate.begin() ?: return@Button
                busy = true
                err = null
                scope.launch {
                    val result = Pocket.pair(name.trim(), parsed!!)
                    if (gate.complete(generation)) {
                        result.onSuccess(onPaired).onFailure { err = it.message ?: L.pairFailed() }
                        busy = false
                    }
                }
            },
            Modifier.padding(horizontal = 22.dp, vertical = 14.dp).fillMaxWidth().height(48.dp),
            enabled = canSubmit,
            shape = RoundedCornerShape(8.dp),
            colors = ConnPrimaryColors(),
        ) {
            // Always keep the desktop primary label visible — never a bare spinner.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp).testTag("pairBusy"),
                        color = T.OnPrimary,
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    if (busy) L.pairingBusy() else L.pairAction(),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = T.OnPrimary,
                )
            }
        }
    }
    LaunchedEffect(Unit) { if (link != null) runCatching { focus.requestFocus() } }
}

@Composable
private fun ConnField(label: String, v: String, hint: String, mono: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = Conn.FgMute, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(Conn.R12)
                .background(Conn.Elevated)
                .border(0.5.dp, Conn.Border, Conn.R12)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (v.isEmpty()) {
                Text(hint, color = Conn.FgMute, style = if (mono) Conn.Mono.copy(fontSize = 14.sp, color = Conn.FgMute) else TextStyle.Default.copy(fontSize = 14.sp, color = Conn.FgMute))
            }
            BasicTextField(
                v,
                onChange,
                singleLine = true,
                textStyle = if (mono) Conn.Mono.copy(fontSize = 14.sp, color = Conn.Fg) else TextStyle.Default.copy(fontSize = 14.sp, color = Conn.Fg),
                cursorBrush = SolidColor(Conn.Accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
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
    onCreatePreset: (() -> Unit)? = null,
) {
    val profile = Pocket.activeProfile
    val ops by Pocket.ops.collectAsState()
    var notify by remember { mutableStateOf(Pocket.prefs.approvalNotify) }
    var presets by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    if (presets) {
        AgentPresetSettingsScreen(onBack = { presets = false }, onCreator = onCreatePreset?.takeIf { "sessions.create" in ops })
        return
    }
    SettingsScaffold(L.settings(), onBack) {
        SettingsCard {
            SettingsNavRow(L.generalNav(), D.settings, onClick = onGeneral)
            SettingsNavRow(L.modelsNav(), D.dataMedium, onClick = onModels)
            if ("plugins.inventory" in ops) SettingsNavRow(st("内置插件", "Built-in plugins"), D.personalization, onClick = onPlugins)
            if ("schedule.catalog" in ops) SettingsNavRow(L.scheduleNav(), D.clock, onClick = onSchedule)
            SettingsNavRow(st("Agent 预设", "Agent presets"), D.agentPreset, divider = false, onClick = { presets = true })
        }
        Text(st("移动端", "Mobile"), fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(start = 22.dp, top = 12.dp, bottom = 2.dp))
        Card {
            Row2(st("连接", "Connections"), icon = D.rightUp, value = profile?.name ?: st("未连接", "Disconnected"), onClick = onConnections)
            Row2(L.diagnostics(), icon = D.inspect, onClick = { diagnostics = true })
            Row2(st("审批通知", "Approval notifications"), divider = false, trailing = {
                Switch(notify, { notify = it; Pocket.prefs.approvalNotify = it }, colors = SwitchDefaults.colors(checkedTrackColor = T.PrimaryButton, checkedThumbColor = T.OnPrimary))
            })
        }
        Text(st("当前版本：", "Version: ") + BuildConfig.VERSION_NAME, fontSize = 12.sp, color = T.Caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
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
            else -> SelectionContainer {
                Text(
                    report.orEmpty(),
                    style = T.Mono.copy(fontSize = 12.sp, color = T.Text2, lineHeight = 18.sp),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp).testTag("diagnosticsReport"),
                )
            }
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
            ) { Text(L.diagnosticsCopy(), color = T.OnPrimary) }
        }
    }
}

@Composable
fun GeneralSettingsScreen(onBack: () -> Unit) {
    val shared = Pocket.prefs.hostSettings
    val perm = Pocket.prefs.defaultPermission
    val busy = Pocket.prefs.busySend
    val lang = Pocket.prefs.language
    val appearance = Pocket.prefs.appearance
    val fontSize = Pocket.prefs.fontSize
    val codeFontSize = Pocket.prefs.codeFontSize
    val terminalFontSize = Pocket.prefs.terminalFontSize
    val codeWork = Pocket.prefs.showCodeWorkView
    var codeWrap by remember { mutableStateOf(Pocket.prefs.codeWordWrap) }
    val transcript = Pocket.prefs.transcriptView
    val collapse = Pocket.prefs.collapseTiming
    val perf = Pocket.prefs.performanceUsage
    var sheet by remember { mutableStateOf<String?>(null) }
    var fontsExpanded by remember { mutableStateOf(false) }
    fun save(setting: SharedSetting, value: String) =
        shared.set(setting, kotlinx.serialization.json.JsonPrimitive(value)) { sheet = null }
    val ops by Pocket.ops.collectAsState()
    val permissionDirectory by Pocket.permissionCatalog.collectAsState()
    val permissionLoading by Pocket.permissionCatalogLoading.collectAsState()
    val permissionError by Pocket.permissionCatalogError.collectAsState()
    val activeHost by Pocket.active.collectAsState()
    val connections by Pocket.status.collectAsState()
    val permissionOnline = connections[activeHost] == Dot.Online
    LaunchedEffect(sheet) { if (sheet == "perm") Pocket.reloadPermissionCatalog() }
    LaunchedEffect(permissionDirectory, permissionOnline, activeHost) {
        if (sheet == "permRisk" && (!permissionOnline || permissionDirectory?.allowsDefault("danger-full-access") != true)) sheet = null
    }
    LaunchedEffect(activeHost) { sheet = null }
    SettingsScaffold(st("通用设置", "General"), onBack) {
        SettingsMessage(when {
            shared.saving -> st("保存中…", "Saving…")
            shared.loading -> L.loading()
            !shared.ready -> L.unavailable()
            else -> null
        })
        SettingsMessage(shared.error, error = true)
        if (shared.error != null && shared.online) {
            TextButton(
                onClick = { shared.dismissError(); shared.refresh() },
                enabled = !shared.loading && !shared.saving,
            ) { Text(L.retry()) }
        }
        if ("permissions.catalog" in ops) {
            GeneralPreferenceRow(st("权限", "Permission"), st("选择新会话的默认权限模式", "Choose the default permission mode for new sessions"), value = permissionLabel(perm, permissionDirectory?.find(perm)), onClick = { sheet = "perm" })
        }
        GeneralPreferenceRow(L.language(), value = lang.label, onClick = { sheet = "lang" })
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L.appearance(), fontSize = 14.sp, color = T.Ink)
            AppearanceCards(appearance) { Pocket.prefs.appearance = it }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Border)
        SettingsFontRow(
            st("字号大小", "Font size"), st("仅影响会话内容的字号", "Only affects conversation content"), fontSize, 10..22,
            expanded = fontsExpanded, onToggle = { fontsExpanded = !fontsExpanded },
        ) { Pocket.prefs.fontSize = it }
        if (fontsExpanded) {
            SettingsFontFamilyRow(
                st("正文字体", "Text font"),
                st("用于界面与会话正文。多个字体用逗号分隔，留空使用默认字体", "Interface and conversation text. Separate fonts with commas; leave empty for defaults."),
                Pocket.prefs.textFontFamily, "textFontFamily",
            ) { Pocket.prefs.textFontFamily = it }
            SettingsFontFamilyRow(
                st("代码字体", "Code font"),
                st("用于代码块、行内代码与工具输出。多个字体用逗号分隔，留空使用默认字体", "Code blocks, inline code, and tool output. Separate fonts with commas; leave empty for defaults."),
                Pocket.prefs.codeFontFamily, "codeFontFamily",
            ) { Pocket.prefs.codeFontFamily = it }
            SettingsFontRow(st("代码字号", "Code font size"), st("用于代码块、行内代码与工具输出", "Used for code blocks, inline code, and tool output"), codeFontSize, 10..16) { Pocket.prefs.codeFontSize = it }
            SettingsFontFamilyRow(
                st("终端字体", "Terminal font"),
                st("用于侧栏终端。多个字体用逗号分隔，留空使用默认字体", "Sidebar terminal. Separate fonts with commas; leave empty for defaults."),
                Pocket.prefs.terminalFontFamily, "terminalFontFamily",
            ) { Pocket.prefs.terminalFontFamily = it }
            SettingsFontRow(st("终端字号", "Terminal font size"), st("用于侧栏终端", "Used for the sidebar terminal"), terminalFontSize, 10..20) { Pocket.prefs.terminalFontSize = it }
        }
        GeneralPreferenceRow(st("工作步骤展示", "Work details"), st("选择希望看到多少工具调用细节", "Choose how much detail to show for tool calls"), value = transcriptText(transcript), onClick = { sheet = "transcript" })
        GeneralPreferenceRow(st("工作步骤收起时机", "When to Collapse Work Details"), st("选择何时自动收起工作步骤", "Choose when to automatically collapse work details"), value = collapseText(collapse), onClick = { sheet = "collapse" })
        GeneralPreferenceRow(st("显示代码工作视图", "Show coding view"), st("开启后，显示轨迹、本轮代码差异，可选择完整的 Agent 预设切换", "Shows trajectory, code diffs, and all Agent presets"), trailing = {
            Switch(codeWork, { Pocket.prefs.showCodeWorkView = it }, enabled = shared.writable(SharedSetting.CodeWork), colors = SwitchDefaults.colors(checkedTrackColor = T.PrimaryButton, checkedThumbColor = T.OnPrimary))
        })
        GeneralPreferenceRow(st("繁忙时的发送行为", "Send behavior while busy"), st("智能体运行时 Enter 键和发送按钮的行为；Cmd/Ctrl+Enter 使用另一行为", "What Enter and the Send button do while the agent is running; Cmd/Ctrl+Enter uses the other behavior"), value = if (busy == BusySend.Queue) L.queueSend() else L.steerSend(), onClick = { sheet = "busy" })
        GeneralPreferenceRow(st("性能与用量", "Performance & usage"), st("选择性能与用量信息展示的详细程度", "Choose how much performance and usage information to show"), value = if (perf == PerformanceUsage.Compact) st("简洁", "Compact") else st("详细", "Detailed"), onClick = { sheet = "perf" })
        SessionLogUploadPreference()
        GeneralPreferenceRow(st("代码自动换行", "Wrap code"), divider = false, trailing = {
            Switch(codeWrap, { codeWrap = it; Pocket.prefs.codeWordWrap = it }, colors = SwitchDefaults.colors(checkedTrackColor = T.PrimaryButton, checkedThumbColor = T.OnPrimary))
        })
        Text(st("当前版本：", "Current version: ") + BuildConfig.VERSION_NAME, fontSize = 12.sp, color = T.Caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
    when (sheet) {
        "perm" -> PermissionPickerSheet(
            current = perm, catalog = permissionDirectory, loading = permissionLoading, error = permissionError,
            onRefresh = { Pocket.reloadPermissionCatalog() }, onDismiss = { sheet = null },
            enabled = permissionOnline && shared.writable(SharedSetting.Permission), forDefaults = true,
            onPick = { value ->
                if (value == "danger-full-access") sheet = "permRisk"
                else if (Pocket.permissionCatalog.value?.allowsDefault(value) == true) {
                    save(SharedSetting.Permission, value)
                }
            },
        )
        "permRisk" -> PermissionRiskSheet(
            preset = "danger-full-access", onDismiss = { sheet = null }, forDefaults = true,
            enabled = permissionOnline && shared.writable(SharedSetting.Permission) && !permissionLoading && permissionDirectory?.allowsDefault("danger-full-access") == true,
            onConfirm = {
                if (Pocket.permissionCatalog.value?.allowsDefault("danger-full-access") == true) {
                    save(SharedSetting.Permission, "danger-full-access")
                }
            },
        )
        "lang" -> PocketSheet({ sheet = null }) {
            SheetTitle(L.language())
            AppLanguage.entries.forEach { o -> OptionRow(o.label, selected = lang == o) { save(SharedSetting.Language, o.name.lowercase()) } }
        }
        "transcript" -> PocketSheet({ sheet = null }) {
            SheetTitle(st("工作步骤展示", "Work details"))
            TranscriptView.entries.forEach { o -> OptionRow(transcriptText(o), selected = transcript == o) { save(SharedSetting.Transcript, o.name.lowercase()) } }
        }
        "collapse" -> PocketSheet({ sheet = null }) {
            SheetTitle(st("工作步骤收起时机", "When to Collapse Work Details"))
            CollapseTiming.entries.forEach { o -> OptionRow(collapseText(o), selected = collapse == o) { Pocket.prefs.collapseTiming = o; sheet = null } }
        }
        "busy" -> PocketSheet({ sheet = null }) {
            SheetTitle(st("繁忙时的发送行为", "Send behavior while busy"))
            OptionRow(L.queueSend(), selected = busy == BusySend.Queue) { save(SharedSetting.BusySend, "queue") }
            OptionRow(L.steerSend(), selected = busy == BusySend.Steer) { save(SharedSetting.BusySend, "steer") }
        }
        "perf" -> PocketSheet({ sheet = null }) {
            SheetTitle(st("性能与用量", "Performance & usage"))
            PerformanceUsage.entries.forEach { o -> OptionRow(if (o == PerformanceUsage.Compact) st("简洁", "Compact") else st("详细", "Detailed"), selected = perf == o) { save(SharedSetting.Performance, o.name.lowercase()) } }
        }
    }
    if (shared.error != null && sheet != null) AlertDialog(
        onDismissRequest = shared::dismissError,
        title = { Text(st("保存失败，请重试", "Could not save. Please try again.")) },
        text = { Text(shared.error.orEmpty()) },
        confirmButton = { TextButton(onClick = shared::dismissError) { Text(st("确定", "OK")) } },
    )
}
