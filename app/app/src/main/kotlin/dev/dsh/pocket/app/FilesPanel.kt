package dev.dsh.pocket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.PocketError
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

private fun ft(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

private data class WorkspaceFileEntry(val name: String, val type: String)

private fun filesFailure(error: Throwable): String = when ((error as? PocketError)?.code) {
    "workspace-file/not-found" -> ft("这个目录不在了。可能已被移动或删除。", "That directory is gone. It may have been moved or deleted.")
    "workspace-file/outside-workspace" -> ft("这个目录在工作区之外，侧栏不会读取它。", "That directory is outside the workspace, so the sidebar will not read it.")
    "workspace-file/not-directory" -> ft("这不是一个目录。", "That is not a directory.")
    else -> ft("读取失败：", "Read failed: ") + (error.message ?: error.javaClass.simpleName)
}

/** Host listings use slash-separated workspace-relative paths; the root is empty. */
internal fun workspaceFilesParentPath(path: String): String? =
    if (path.isEmpty() || path == ".") null else path.substringBeforeLast('/', "")

@Composable
fun FilesSheet(sessionId: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null) {
    var requestedPath by remember(sessionId) { mutableStateOf("") }
    var path by remember(sessionId) { mutableStateOf("") }
    var entries by remember(sessionId) { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember(sessionId) { mutableStateOf(true) }
    var failure by remember(sessionId) { mutableStateOf<String?>(null) }
    var truncated by remember(sessionId) { mutableStateOf(false) }
    var generation by remember(sessionId) { mutableIntStateOf(0) }
    var previewPath by remember(sessionId) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val sessions by Pocket.sessions.collectAsState()
    val cwd = sessions.firstOrNull { it.sessionId == sessionId }?.cwd
    val reloadLabel = ft("重新读取", "Reload")

    fun load(target: String) {
        requestedPath = target
        path = target
        loading = true
        failure = null
        generation++
    }

    LaunchedEffect(sessionId, generation, cwd) {
        val requestGeneration = generation
        val target = requestedPath
        loading = true
        failure = null
        if (cwd.isNullOrBlank()) {
            entries = emptyList()
            loading = false
            return@LaunchedEffect
        }
        try {
            val result = Pocket.listFiles(sessionId, target.ifEmpty { "." })
            ensureActive()
            if (generation != requestGeneration) return@LaunchedEffect
            val listedPath = result["path"].str() ?: target
            // Never derive an up-navigation from an absolute or escaping path.
            val relativePath = if (listedPath == ".") "" else listedPath
            if (relativePath.startsWith('/') || relativePath.contains('\\') ||
                relativePath.split('/').any { it == ".." } ||
                (relativePath.length >= 2 && relativePath[1] == ':')
            ) {
                throw PocketError("workspace-file/outside-workspace", listedPath)
            }
            val listedEntries = result["entries"].arr().orEmpty().mapNotNull { entry ->
                val name = entry["name"].str() ?: return@mapNotNull null
                // A list entry is a single child, never another path.
                val validName = name.isNotEmpty() && name != "." && name != ".." &&
                    '/' !in name && '\\' !in name
                WorkspaceFileEntry(name, if (validName) entry["type"].str() ?: "other" else "other")
            }.sortedWith(compareBy<WorkspaceFileEntry> { it.type != "directory" }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            path = relativePath
            entries = listedEntries
            truncated = result["truncated"].bool() == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ensureActive()
            if (generation != requestGeneration) return@LaunchedEffect
            entries = emptyList()
            truncated = false
            failure = filesFailure(error)
        }
        if (generation == requestGeneration) loading = false
    }
    LaunchedEffect(sessionId, requestedPath) { listState.scrollToItem(0) }

    val preview = previewPath
    if (preview != null) {
        key(sessionId, preview) {
            FilePreviewSheet(sessionId, preview, onDismiss = onDismiss, onBack = { previewPath = null })
        }
        return
    }

    val parentPath = workspaceFilesParentPath(path)
    RightPanelFrame(
        title = ft("工作区文件", "Workspace files"),
        onDismiss = onDismiss,
        onBack = if (parentPath != null) ({ load(parentPath) }) else onBack,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val parent = workspaceFilesParentPath(path)
            IconButton(onClick = { parent?.let(::load) }, enabled = parent != null) {
                Icon(
                    D.chevronLeft,
                    contentDescription = ft("上级目录", "Parent directory"),
                    tint = if (parent != null) T.Text2 else T.Text3.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp),
                )
            }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { load("") }, enabled = !loading && !cwd.isNullOrBlank()) {
                    Text(cwd?.trimEnd('/', '\\')?.substringAfterLast('/')?.ifEmpty { "/" } ?: ft("无工作区", "No workspace"),
                        color = T.Text2, fontSize = 12.sp)
                }
                path.split('/').filter { it.isNotEmpty() }.forEachIndexed { index, part ->
                    Icon(D.chevronRight, null, tint = T.Text3, modifier = Modifier.size(12.dp))
                    TextButton(onClick = { load(path.split('/').take(index + 1).joinToString("/")) }, enabled = !loading) {
                        Text(part, color = T.Text2, fontSize = 12.sp)
                    }
                }
            }
            IconButton(onClick = { load(path) }, enabled = !loading && !cwd.isNullOrBlank()) {
                Icon(D.refresh, reloadLabel, tint = if (loading) T.Text3 else T.Text2, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = T.Border, thickness = 0.5.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            when {
                cwd.isNullOrBlank() -> item {
                    Text(ft("此会话没有工作区。请先在会话设置中选择工作区。", "This session has no workspace. Choose one in session settings first."),
                        color = T.Text3, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
                }
                loading -> item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = T.Accent, strokeWidth = 2.dp)
                        Text(ft("正在读取…", "Reading…"), color = T.Text3, fontSize = 13.sp)
                    }
                }
                failure != null -> item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        SelectableOutput {
                            Text(failure.orEmpty(), color = T.Err, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                        TextButton(onClick = { load(path) }) { Text(reloadLabel, color = T.Accent) }
                    }
                }
                else -> {
                    if (entries.isEmpty()) item {
                        Text(
                            ft("空目录", "Empty directory"), color = T.Text3, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                    items(entries, key = { it.name }) { entry ->
                        val directory = entry.type == "directory"
                        val openable = directory || entry.type == "file"
                        val otherLabel = ft("这不是文件或目录，没法打开。", "Not a file or a directory, so it cannot be opened.")
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = openable, role = Role.Button) {
                                    val child = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                                    if (directory) load(child) else previewPath = child
                                }
                                .then(if (openable) Modifier else Modifier.semantics { contentDescription = "${entry.name}。$otherLabel" })
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (openable) FileTypeIcon(entry.name, directory, Modifier.size(20.dp))
                            else Icon(D.warning, null, tint = T.Text3, modifier = Modifier.size(20.dp))
                            Text(
                                entry.name, color = if (openable) T.Ink else T.Text3,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (directory) Icon(D.chevronRight, contentDescription = null, tint = T.Text3, modifier = Modifier.size(14.dp))
                        }
                    }
                    if (truncated) item {
                        Text(
                            ft("条目太多，只显示了一部分。", "Too many entries, showing only some of them."),
                            color = T.Text3, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
