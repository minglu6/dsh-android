package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
    Column(Modifier.fillMaxWidth().clip(T.R16).border(1.dp, T.Border, T.R16).testTag("changesSummary")) {
        Row(Modifier.fillMaxWidth().background(T.Fill2)
            .clickable(enabled = onOpen != null, onClickLabel = changesText("查看本轮改动", "Review turn changes")) { onOpen?.invoke(0) }
            .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(T.R12).background(T.Surface).border(1.dp, T.Border, T.R12), contentAlignment = Alignment.Center) {
                if (single != null) FileTypeIcon(single.path, modifier = Modifier.size(26.dp))
                else CodeFileIcon(Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (single == null) L.editedCount(summary.total) else L.editedName(single.display.substringAfterLast('/')),
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = T.Ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { if (single != null) contentDescription = single.display })
                Spacer(Modifier.height(3.dp))
                ChangeCounts(summary.added, summary.deleted, single)
            }
        }
        if (single == null) {
            HorizontalDivider(color = T.Border)
            val files = if (expanded) summary.files else summary.files.take(4)
            files.forEachIndexed { index, file ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("changedFile:$index")
                    .clickable(enabled = onOpen != null, onClickLabel = changesText("查看 ${file.display} 的改动", "Review ${file.display}")) { onOpen?.invoke(index) }
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(file.display, color = T.Text3, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { contentDescription = file.display })
                    Spacer(Modifier.width(12.dp))
                    ChangeCounts(file.added, file.deleted, file)
                }
            }
            if (summary.files.size > 4) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("changesExpand")
                    .clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (expanded) changesText("收起", "Collapse") else changesText("全部 ${summary.files.size} 个文件", "All ${summary.files.size} files"),
                        fontSize = 14.sp, color = T.Text3)
                    Spacer(Modifier.width(6.dp))
                    Icon(D.chevronDown, null, tint = T.Text3, modifier = Modifier.size(14.dp).rotate(if (expanded) 180f else 0f))
                }
            }
        }
    }
}

@Composable
private fun ChangeCounts(added: Int, deleted: Int, file: WorkspaceChangedFile? = null) {
    when {
        file?.binary == true -> Text(changesText("二进制", "Binary"), color = T.Text3, fontSize = 12.sp)
        file?.oversized == true -> Text(changesText("过大", "Too large"), color = T.Text3, fontSize = 12.sp)
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("+$added", style = T.Mono, fontSize = 12.sp, color = T.Ok)
            Text("−$deleted", style = T.Mono, fontSize = 12.sp, color = T.Err)
        }
    }
}

@Composable
internal fun ChangesReviewSheet(
    sessionId: String, seq: Long, summary: WorkspaceChangesSummary, initialIndex: Int, onDismiss: () -> Unit,
    loadDiff: suspend (String, Long, Int) -> WorkspaceFileDiff? = { session, event, index -> Pocket.fetchChangesDiff(session, event, index) },
) {
    var index by rememberSaveable(sessionId, seq) { mutableIntStateOf(initialIndex) }
    var menu by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember(sessionId, seq, index) { mutableStateOf(true) }
    var failure by remember(sessionId, seq, index) { mutableStateOf<String?>(null) }
    var diff by remember(sessionId, seq, index) { mutableStateOf<WorkspaceFileDiff?>(null) }
    val cache = remember(sessionId, seq) { mutableMapOf<Int, WorkspaceFileDiff?>() }
    val file = summary.files.getOrNull(index) ?: return
    LaunchedEffect(sessionId, seq, index, retry) {
        loading = true
        failure = null
        try {
            diff = if (cache.containsKey(index)) cache[index] else loadDiff(sessionId, seq, index).also { cache[index] = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: changesText("读取改动失败", "Failed to read changes")
        } finally {
            loading = false
        }
    }
    RightPanelFrame(title = changesText("本轮改动 · ${summary.turn}", "Turn ${summary.turn} changes"), onDismiss = onDismiss) {
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.testTag("changesFilePicker")) {
                Text(file.display, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(D.chevronDown, null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                summary.files.forEachIndexed { at, entry ->
                    DropdownMenuItem(text = { Text(entry.display) }, onClick = { index = at; menu = false })
                }
            }
        }
        ChangeCounts(file.added, file.deleted, file)
        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = T.Accent)
            }
            failure != null -> Column(Modifier.padding(16.dp)) {
                Text(failure!!, color = T.Err)
                TextButton(onClick = { retry++ }) { Text(L.docRetry()) }
            }
            else -> ChangesDiffBody(diff, Modifier.weight(1f))
        }
    }
}

internal data class ChangeDiffLine(val old: Int?, val new: Int?, val text: String)

internal fun changeDiffLines(diff: WorkspaceFileDiff): List<ChangeDiffLine> = buildList {
    for (hunk in diff.hunks) {
        var old = hunk.oldStart
        var new = hunk.newStart
        for (line in hunk.lines) {
            if (size >= 5000) return@buildList
            add(when (line.firstOrNull()) {
                '+' -> ChangeDiffLine(null, new++, line)
                '-' -> ChangeDiffLine(old++, null, line)
                else -> ChangeDiffLine(old++, new++, line)
            })
        }
    }
}

@Composable
private fun ChangesDiffBody(diff: WorkspaceFileDiff?, modifier: Modifier) {
    val notice = when {
        diff == null -> changesText("主机已不再提供本轮改动", "The host no longer has this comparison")
        diff.kind == "binary" -> changesText("二进制文件，无法显示文本差异", "Binary file; no text comparison")
        diff.kind == "oversized" -> changesText("文件过大，未捕获文本差异", "File too large; comparison was not captured")
        !diff.before -> changesText("本轮新增文件", "File added in this turn")
        !diff.after -> changesText("本轮删除文件", "File deleted in this turn")
        diff.hunks.isEmpty() -> changesText("文件内容相同", "File contents are identical")
        diff.coarse -> changesText("逐行对比超时，按整文件替换展示", "Comparison timed out; showing whole-file replacement")
        else -> null
    }
    val lines = remember(diff) { diff?.let(::changeDiffLines).orEmpty() }
    Column(modifier.fillMaxWidth()) {
        notice?.let { Text(it, color = T.Text3, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("changesDiff")) {
            itemsIndexed(lines) { _, line ->
                val color = when (line.text.firstOrNull()) { '+' -> T.Ok; '-' -> T.Err; else -> T.Text3 }
                Row(Modifier.fillMaxWidth().background(color.copy(alpha = if (line.text.startsWith(' ')) 0f else 0.09f)).padding(vertical = 2.dp)) {
                    Text(line.old?.toString().orEmpty(), color = T.Text3, style = T.Mono, fontSize = 11.sp, modifier = Modifier.width(38.dp))
                    Text(line.new?.toString().orEmpty(), color = T.Text3, style = T.Mono, fontSize = 11.sp, modifier = Modifier.width(38.dp))
                    Text(line.text, color = T.Ink, style = T.Mono, fontSize = Pocket.prefs.codeFontSize.sp,
                        softWrap = true, modifier = Modifier.weight(1f))
                }
            }
            if (diff != null && diff.hunks.sumOf { it.lines.size } > 5000) item {
                Text(changesText("仅显示前 5000 行", "Showing the first 5000 lines"), color = T.Text3, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
