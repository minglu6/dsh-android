package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.WorkspaceChangedFile
import dev.dsh.pocket.protocol.WorkspaceChangesSummary
import dev.dsh.pocket.protocol.WorkspaceFileDiff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

private fun changesText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

@Composable
internal fun ChangesCard(
    sessionId: String, item: Item.Changes,
    loadSummary: suspend (String, Long) -> WorkspaceChangesSummary? = { session, seq -> Pocket.fetchChangesSummary(session, seq) },
) {
    val ops by Pocket.ops.collectAsState()
    val host by Pocket.active.collectAsState()
    var summary by remember(host, sessionId, item.seq) { mutableStateOf<WorkspaceChangesSummary?>(null) }
    var selected by rememberSaveable(host, sessionId, item.seq) { mutableStateOf<Int?>(null) }
    LaunchedEffect(host, sessionId, item.seq, ops) {
        summary = null
        val seq = item.seq ?: return@LaunchedEffect
        if ("workspaceChanges.summary" !in ops) return@LaunchedEffect
        try {
            summary = loadSummary(sessionId, seq)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Like desktop, unavailable summaries do not produce a fabricated empty card.
        }
    }
    val value = summary ?: return
    if (value.files.isEmpty()) return
    SelectableOutput {
        ChangesSummaryCard(value, "$host/$sessionId/${item.seq}",
            onOpen = if ("workspaceChanges.diff" in ops) ({ selected = it }) else null)
    }
    selected?.let { index ->
        ChangesReviewSheet(sessionId, item.seq ?: return@let, value, index, onDismiss = { selected = null })
    }
}

@Composable
internal fun ChangesSummaryCard(summary: WorkspaceChangesSummary, identity: String, onOpen: ((Int) -> Unit)?) {
    if (summary.files.isEmpty()) return
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    val single = summary.files.singleOrNull().takeIf { summary.total == 1 }
    Column(Modifier.fillMaxWidth().clip(T.R16).border(0.5.dp, T.Border, T.R16).testTag("changesSummary")) {
        Row(Modifier.fillMaxWidth().background(T.Fill2)
            .clickable(enabled = onOpen != null, onClickLabel = changesText("查看本轮改动", "Review turn changes")) { onOpen?.invoke(0) }
            .heightIn(min = 60.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(T.R12).background(T.Surface).border(0.5.dp, T.Border, T.R12), contentAlignment = Alignment.Center) {
                if (single != null) FileTypeIcon(single.path, modifier = Modifier.size(20.dp))
                else CodeFileIcon(Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (single == null) L.editedCount(summary.total) else L.editedName(single.display.substringAfterLast('/').substringAfterLast('\\')),
                    fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = T.Ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { if (single != null) contentDescription = single.display })
                Spacer(Modifier.height(3.dp))
                ChangeCounts(summary.added, summary.deleted, single, compact = true)
            }
        }
        if (single == null) {
            HorizontalDivider(color = T.Border)
            val files = if (expanded) summary.files else summary.files.take(4)
            files.forEachIndexed { index, file ->
                val openFile = onOpen?.let { open -> { open(index) } }
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("changedFile:$index")
                    .clickable(enabled = openFile != null, onClickLabel = changesText("查看 ${file.display} 的改动", "Review ${file.display}"),
                        onClick = openFile ?: {})
                    .padding(start = 14.dp, end = 18.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    PreviewPathLabel(file.display, Modifier.weight(1f), color = T.Text3, onClick = openFile)
                    Spacer(Modifier.width(10.dp))
                    ChangeCounts(file.added, file.deleted, file)
                }
            }
            if (summary.files.size > 4) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("changesExpand")
                    .clickable { expanded = !expanded }.padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (expanded) changesText("收起", "Collapse") else changesText("全部 ${summary.files.size} 个文件", "All ${summary.files.size} files"),
                        fontSize = 12.sp, color = T.Text3)
                    Spacer(Modifier.width(6.dp))
                    Icon(D.chevronDown, null, tint = T.Text3, modifier = Modifier.size(14.dp).rotate(if (expanded) 180f else 0f))
                }
            }
        }
    }
}

@Composable
private fun ChangeCounts(added: Int, deleted: Int, file: WorkspaceChangedFile? = null, compact: Boolean = false) {
    val size = if (compact) 10.sp else 12.sp
    when {
        file?.binary == true -> Text(changesText("二进制", "Binary"), color = T.Text3, fontSize = size)
        file?.oversized == true -> Text(changesText("过大", "Too large"), color = T.Text3, fontSize = size)
        else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("+$added", style = T.Mono, fontSize = size, color = T.Ok)
            Text("−$deleted", style = T.Mono, fontSize = size, color = T.Err)
        }
    }
}

@Composable
internal fun ChangesReviewSheet(
    sessionId: String, seq: Long, summary: WorkspaceChangesSummary, initialIndex: Int, onDismiss: () -> Unit,
    loadDiff: suspend (String, Long, Int) -> WorkspaceFileDiff? = { session, event, index -> Pocket.fetchChangesDiff(session, event, index) },
) {
    val host by Pocket.active.collectAsState()
    var index by rememberSaveable(host, sessionId, seq) { mutableIntStateOf(initialIndex) }
    var menu by remember(host, sessionId, seq) { mutableStateOf(false) }
    var retry by remember(host, sessionId, seq) { mutableIntStateOf(0) }
    var loading by remember(host, sessionId, seq, index) { mutableStateOf(true) }
    var failure by remember(host, sessionId, seq, index) { mutableStateOf<String?>(null) }
    var diff by remember(host, sessionId, seq, index) { mutableStateOf<WorkspaceFileDiff?>(null) }
    var mode by remember(host, sessionId, seq, index) { mutableStateOf(TextPreviewMode.Code) }
    var wrap by remember(host, sessionId, seq, index) { mutableStateOf(Pocket.prefs.codeWordWrap) }
    val cache = remember(host, sessionId, seq) { mutableMapOf<Int, WorkspaceFileDiff?>() }
    val file = summary.files.getOrNull(index) ?: return
    val refresh = { cache.remove(index); retry++; Unit }
    LaunchedEffect(host, sessionId, seq, index, retry) {
        loading = true
        failure = null
        try {
            val next = if (cache.containsKey(index)) cache[index] else loadDiff(sessionId, seq, index)
            currentCoroutineContext().ensureActive()
            cache[index] = next
            diff = next
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (currentCoroutineContext().isActive) failure = error.message ?: changesText("读取改动失败", "Failed to read changes")
        } finally {
            if (currentCoroutineContext().isActive) loading = false
        }
    }
    val comparison = diff?.takeIf { it.kind == "text" }
    val textDiff = comparison?.takeIf { it.hunks.isNotEmpty() }
    FilePreviewChrome(
        path = file.path,
        sourceKey = "$host/$sessionId/$seq",
        onDismiss = onDismiss,
        mode = mode,
        modes = if (comparison != null) listOf(TextPreviewMode.Code, TextPreviewMode.Plain) else emptyList(),
        onModeChange = { mode = it },
        wrap = wrap,
        onWrapChange = { wrap = !wrap },
        loading = loading,
        onRefresh = refresh,
        language = previewCodeLanguage(file.path),
        copyText = comparison?.takeIf { !loading && failure == null }?.let { value -> { codeComparisonCopyText(value) } },
        onPathClick = if (summary.files.size > 1) ({ menu = true }) else null,
        pathActions = {
            if (summary.files.size > 1) Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp).testTag("changesFilePicker")) {
                    Icon(D.chevronDown, changesText("选择本轮改动文件", "Select changed file"),
                        modifier = Modifier.size(14.dp), tint = T.Text2)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = T.Surface) {
                    summary.files.forEachIndexed { at, entry ->
                        val selectFile = { index = at; menu = false }
                        DropdownMenuItem(
                            text = { PreviewPathLabel(entry.display, Modifier.fillMaxWidth(), onClick = selectFile) },
                            modifier = Modifier.testTag("changesFileOption:$at"),
                            trailingIcon = { if (at == index) Icon(D.check, null, Modifier.size(16.dp), tint = T.Text2) },
                            onClick = selectFile,
                        )
                    }
                }
            }
        },
        codeFacts = {
            ChangeCounts(file.added, file.deleted, file)
            Spacer(Modifier.width(8.dp))
        },
    ) {
        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f).testTag("changesLoading"), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = T.Accent)
            }
            failure != null -> Column(Modifier.padding(16.dp)) {
                SelectableOutput { Text(failure!!, color = T.Err) }
                TextButton(onClick = refresh) { Text(L.docRetry()) }
            }
            else -> {
                comparisonNotice(diff)?.let { Text(it, color = T.Text3, fontSize = 12.sp, modifier = Modifier.padding(16.dp).testTag("changesNotice")) }
                textDiff?.let {
                    CodeComparisonBody(it, wrap = wrap, plain = mode == TextPreviewMode.Plain,
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("changesDiff"))
                }
            }
        }
    }
}

private fun comparisonNotice(diff: WorkspaceFileDiff?): String? = when {
    diff == null -> changesText("主机已不再提供本轮改动", "The host no longer has this comparison")
    diff.kind == "binary" -> changesText("二进制文件，无法显示文本差异", "Binary file; no text comparison")
    diff.kind == "oversized" -> changesText("文件过大，未捕获文本差异", "File too large; comparison was not captured")
    diff.hunks.isNotEmpty() -> null
    !diff.before -> changesText("本轮新增文件", "File added in this turn")
    !diff.after -> changesText("本轮删除文件", "File deleted in this turn")
    else -> changesText("文件内容相同", "File contents are identical")
}
