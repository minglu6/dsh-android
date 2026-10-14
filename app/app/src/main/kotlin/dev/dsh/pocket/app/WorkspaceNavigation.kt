package dev.dsh.pocket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun WorkspaceNavigationGroups(
    workspaces: List<WorkspaceView>, sessions: List<SessionSummary>, tree: Boolean,
    collapsed: Set<String>, onToggle: (String) -> Unit, onPick: (String) -> Unit,
    activities: Map<String, SidebarActivity>,
    renderSessions: @Composable (List<SessionSummary>) -> Unit,
) {
    val chat by Pocket.chat.collectAsState()
    val draftPath by Pocket.draftWorkspacePath.collectAsState()
    val currentId = chat?.sessionId
    val currentWorkspace = if (currentId != null) workspaces.firstOrNull { currentId in it.sessionIds }
        else if (draftPath != null) workspaces.firstOrNull { it.path == draftPath }
        else workspaces.firstOrNull()
    val currentPath = currentWorkspace?.let { workspacePathKey(it.path) }
    fun containsCurrent(branch: WorkspaceBranch): Boolean =
        branch.path == currentPath || branch.children.any(::containsCurrent)
    val assigned = workspaces.flatMap { it.sessionIds }.toSet()
    val orphanGroups = sessions.filter { it.sessionId !in assigned }.groupBy { "" }
    val paths = (workspaces.map { workspacePathKey(it.path) } + orphanGroups.keys).distinct()
    val workspaceActivities = remember(workspaces, activities) {
        workspaces.associate { workspace ->
            workspace.workspaceId to workspace.sessionIds.fold(SidebarActivity.Idle) { activity, id ->
                mergeSidebarActivity(activity, activities[id] ?: SidebarActivity.Idle)
            }
        }
    }
    fun branchActivity(branch: WorkspaceBranch): SidebarActivity {
        var activity = SidebarActivity.Idle
        workspaces.forEach { workspace ->
            if (workspacePathKey(workspace.path) == branch.path) {
                activity = mergeSidebarActivity(activity, workspaceActivities.getValue(workspace.workspaceId))
            }
        }
        branch.children.forEach { activity = mergeSidebarActivity(activity, branchActivity(it)) }
        return activity
    }
    @Composable fun group(path: String, children: List<WorkspaceBranch>, depth: Int) {
        val registered = workspaces.filter { workspacePathKey(it.path) == path }
        val fallback = orphanGroups[path].orEmpty()
        val childActivity = children.fold(SidebarActivity.Idle) { activity, child ->
            mergeSidebarActivity(activity, branchActivity(child))
        }
        val fallbackActivity = fallback.fold(SidebarActivity.Idle) { activity, session ->
            mergeSidebarActivity(activity, activities[session.sessionId] ?: SidebarActivity.Idle)
        }
        Column(Modifier.fillMaxWidth()) {
            if (registered.isEmpty()) {
                val key = "path:$path"
                WorkspaceGroupHeader(path.ifEmpty { L.ungrouped() }, path, key in collapsed, { onToggle(key) }, depth = depth,
                    active = fallback.any { it.sessionId == currentId } || children.any(::containsCurrent),
                    activity = mergeSidebarActivity(fallbackActivity, childActivity))
                if (key !in collapsed) {
                    renderSessions(fallback)
                    children.forEach { group(it.path, it.children, depth + 1) }
                }
            } else {
                registered.forEachIndexed { index, workspace ->
                    val key = "workspace:${workspace.workspaceId}"
                    WorkspaceGroupHeader(workspaceDisplayTitle(workspace.title, workspace.path), workspace.path,
                        key in collapsed, { onToggle(key) }, workspace, onPick, depth,
                        active = workspace.workspaceId == currentWorkspace?.workspaceId || (index == 0 && children.any(::containsCurrent)),
                        activity = mergeSidebarActivity(workspaceActivities.getValue(workspace.workspaceId),
                            if (index == 0) mergeSidebarActivity(fallbackActivity, childActivity) else SidebarActivity.Idle))
                    if (key !in collapsed) {
                        if (index == 0) children.forEach { group(it.path, it.children, depth + 1) }
                        renderSessions(sessions.filter { it.sessionId in workspace.sessionIds } + if (index == 0) fallback else emptyList())
                    }
                }
            }
        }
    }
    if (tree) {
        workspaceBranches(paths).forEach { group(it.path, it.children, 0) }
        if ("" in paths) group("", emptyList(), 0)
    } else paths.forEach { group(it, emptyList(), 0) }
}

@Composable
private fun WorkspaceGroupHeader(
    title: String, path: String, collapsed: Boolean, onToggle: () -> Unit,
    workspace: WorkspaceView? = null, onPick: (String) -> Unit = {},
    depth: Int = 0,
    active: Boolean = false,
    activity: SidebarActivity,
) {
    val ops by Pocket.ops.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var draft by remember(workspace?.title, title) { mutableStateOf(workspace?.title?.takeIf { it.isNotBlank() } ?: title) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("workspaceCollapse:${workspace?.workspaceId ?: path}"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp)
            .clickable(onClickLabel = if (collapsed) L.expand() else L.collapse(), onClick = onToggle)
            .padding(start = (12 + minOf(depth, 4) * 12).dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                if (activity != SidebarActivity.Idle) {
                    SidebarActivityIndicator(activity, Modifier.size(14.dp).testTag("workspaceActivity:${workspace?.workspaceId ?: path}"), workspace = true)
                } else {
                    Icon(painterResource(if (collapsed) R.drawable.dsh_folder_close else R.drawable.dsh_folder_selected), null,
                        Modifier.size(16.dp).testTag("workspaceIcon:${workspace?.workspaceId ?: path}"),
                        tint = if (active) T.Accent else T.Text3)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = T.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (path.isNotEmpty()) Text(path, fontSize = 10.sp, color = T.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (workspace != null) Box {
            IconButton({ menu = true }, enabled = !busy) { Icon(D.ellipsis, L.moreActions(), Modifier.size(18.dp)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(navText("新会话", "New session")) }, onClick = {
                    menu = false
                    action { onPick(Pocket.startWorkspaceSession(workspace.workspaceId)) }
                })
                if ("workspace.rename" in ops) DropdownMenuItem(text = { Text(L.rename()) }, onClick = { menu = false; rename = true })
                if ("workspace.delete" in ops) DropdownMenuItem(text = { Text(navText("移除工作区", "Remove workspace")) }, onClick = { menu = false; remove = true })
            }
        }
    }
    error?.let { Text(it, color = T.Err, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
    if (rename && workspace != null) AlertDialog(
        onDismissRequest = { if (!busy) rename = false }, title = { Text(L.rename()) },
        text = { Column { OutlinedTextField(draft, { draft = it }, singleLine = true, enabled = !busy); error?.let { Text(it, color = T.Err) } } },
        confirmButton = { TextButton({ action { Pocket.renameWorkspace(workspace.workspaceId, draft.trim()); rename = false } }, enabled = !busy && draft.isNotBlank()) { Text(L.save()) } },
        dismissButton = { TextButton({ rename = false }, enabled = !busy) { Text(L.cancel()) } },
    )
    if (remove && workspace != null) AlertDialog(
        onDismissRequest = { if (!busy) remove = false }, title = { Text(navText("移除工作区？", "Remove workspace?")) },
        text = { Column { Text(navText("仅移除工作区登记，不会删除目录或会话记录。", "Only the workspace registration is removed. Directories and session history are kept.")); error?.let { Text(it, color = T.Err) } } },
        confirmButton = { TextButton({ action { Pocket.deleteWorkspace(workspace.workspaceId); remove = false } }, enabled = !busy) { Text(L.delete()) } },
        dismissButton = { TextButton({ remove = false }, enabled = !busy) { Text(L.cancel()) } },
    )
}
