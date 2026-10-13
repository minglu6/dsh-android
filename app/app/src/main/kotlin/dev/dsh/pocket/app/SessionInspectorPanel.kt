package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*

private fun inspectorText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

/** Read-only local inspection. This panel never starts a follow or sends content to another service. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SessionInspectorSheet(sessionId: String, onDismiss: () -> Unit, onBack: (() -> Unit)? = null) {
    val current by Pocket.chat.collectAsState()
    val st = current?.takeIf { sessionId.isNotBlank() && it.sessionId == sessionId } ?: return
    key(sessionId) {
        RightPanelFrame(inspectorText("会话数据诊断", "Session inspector"), onDismiss, onBack) {
            Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("sessionInspectorPanel")) {
                SessionInspectorContent(st)
            }
        }
    }
}

@Composable
private fun ColumnScope.SessionInspectorContent(st: ChatState) {
    var grouped by remember { mutableStateOf(false) }
    var draft by remember(grouped) { mutableStateOf("") }
    var filter by remember(grouped) { mutableStateOf("") }
    var collapsed by remember(grouped) { mutableStateOf(emptySet<String>()) }
    var selectedKey by remember(grouped) { mutableStateOf<String?>(null) }
    var follow by remember(grouped) { mutableStateOf(true) }
    var scrollRequest by remember(grouped) { mutableIntStateOf(0) }
    val policy = presentationPolicyFor(Pocket.prefs.transcriptView, Pocket.prefs.collapseTiming)
    val rows = remember(grouped, st.inspectorRecords, st.items, st.turns, st.running, policy) {
        if (grouped) inspectorGroupRows(st, policy) else inspectorRawRows(st.inspectorRecords)
    }
    val visible = remember(rows, filter, collapsed) { inspectorVisibleRows(rows, filter, collapsed) }
    val matchingCount = remember(rows, filter) {
        inspectorVisibleRows(rows, filter, emptySet()).count { it.matches }
    }
    val totalCount = remember(rows) { inspectorVisibleRows(rows, "", emptySet()).size }
    val selected = visible.firstOrNull { it.row.key == selectedKey }?.row
    val list = key(grouped) { rememberLazyListState() }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(visible.lastOrNull()?.row, visible.size, follow, scrollRequest) {
        if (follow && visible.isNotEmpty()) list.scrollToItem(visible.lastIndex)
    }
    LaunchedEffect(filter) { if (!follow && visible.isNotEmpty()) list.scrollToItem(0) }

    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val colors = FilterChipDefaults.filterChipColors(
            containerColor = T.Surface, labelColor = T.Text2,
            selectedContainerColor = T.Tint, selectedLabelColor = T.Accent,
        )
        FilterChip(selected = !grouped, onClick = { grouped = false }, label = { Text(inspectorText("原始数据", "Raw data")) },
            colors = colors, modifier = Modifier.testTag("inspectorRaw"))
        FilterChip(selected = grouped, onClick = { grouped = true }, label = { Text(inspectorText("对话分组", "Chat groups")) },
            colors = colors, modifier = Modifier.testTag("inspectorGroups"))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, singleLine = true,
            label = { Text(inspectorText("类型关键词", "Type keywords"), fontSize = 12.sp) },
            modifier = Modifier.weight(1f).testTag("inspectorFilter"),
        )
        TextButton(onClick = {
            filter = draft.trim(); selectedKey = null; collapsed = emptySet(); follow = false
        }, modifier = Modifier.testTag("inspectorApplyFilter")) { Text(inspectorText("确认筛选", "Apply filter")) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(inspectorText("$matchingCount / $totalCount 行", "$matchingCount / $totalCount rows"),
            color = T.Text3, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("inspectorRowCount"))
        TextButton(onClick = {
            follow = false
            if (Pocket.chat.value?.sessionId == st.sessionId) Pocket.loadOlderHistory()
        }, enabled = st.loaded && st.hasMore && !st.loadingOlder, modifier = Modifier.testTag("inspectorLoadEarlier")) {
            Text(if (st.loadingOlder) inspectorText("加载中…", "Loading…") else inspectorText("加载更早", "Load earlier"), fontSize = 11.sp)
        }
        TextButton(onClick = { follow = true; scrollRequest++ }, enabled = !follow,
            modifier = Modifier.testTag("inspectorFollowLatest")) {
            Text(inspectorText("跟踪最新", "Follow latest"), fontSize = 11.sp)
        }
    }
    HorizontalDivider(color = T.Border)
    if (!st.loaded) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = T.Accent, modifier = Modifier.size(24.dp))
        }
    } else if (visible.isEmpty()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(inspectorText("暂无记录", "No records"), color = T.Text3, fontSize = 13.sp)
        }
    } else {
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("inspectorRows")) {
            items(visible, key = { it.row.key }) { entry ->
                val row = entry.row
                Column(Modifier.fillMaxWidth()
                    .background(if (selectedKey == row.key) T.Accent.copy(alpha = 0.08f) else T.Surface)
                    .clickable { selectedKey = row.key; follow = false }
                    .padding(start = (12 + entry.depth * 12).dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.children.isNotEmpty()) {
                            TextButton(onClick = {
                                collapsed = if (row.key in collapsed) collapsed - row.key else collapsed + row.key
                                follow = false
                            }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(32.dp)) {
                                Text(if (row.key in collapsed) "+" else "−", fontSize = 16.sp)
                            }
                        }
                        Text(row.type, color = if (entry.matches) T.Ink else T.Text3, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                    }
                    if (!grouped) Text("#${row.seq ?: "—"} · ${inspectorTime(row.time)}", color = T.Text3, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                    else Text(row.key, color = T.Text3, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = T.Border, thickness = 0.5.dp)
            }
        }
    }
    selected?.let { row ->
        HorizontalDivider(color = T.Border)
        key(row.key) { InspectorDetail(row) { selectedKey = null } }
    }
}

@Composable
private fun InspectorDetail(row: InspectorRow, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(row.value) { mutableStateOf(false) }
    var tree by remember { mutableStateOf(true) }
    val pretty = remember(row.value) { prettyJson(row.value.toString()) }
    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("inspectorDetail")) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(inspectorText("详情", "Details"), color = T.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { tree = !tree }) { Text(if (tree) "JSON" else inspectorText("展开树", "Tree"), fontSize = 11.sp) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(pretty)); copied = true }, modifier = Modifier.testTag("inspectorCopy")) {
                Text(if (copied) inspectorText("已复制", "Copied") else inspectorText("复制", "Copy"), fontSize = 11.sp)
            }
            TextButton(onClick = onClose) { Text(inspectorText("关闭", "Close"), fontSize = 11.sp) }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (tree) InspectorJsonNode("$", row.value, root = true)
            else SelectableOutput { Text(pretty, color = T.Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

/** Expand only requested branches. Large JSON arrays/objects reveal 50 children at a time. */
@Composable
private fun InspectorJsonNode(label: String, value: JsonElement, root: Boolean = false) {
    var open by remember { mutableStateOf(root) }
    var limit by remember { mutableIntStateOf(50) }
    val count = when (value) { is JsonObject -> value.size; is JsonArray -> value.size; else -> 0 }
    if (value !is JsonObject && value !is JsonArray) {
        SelectableOutput { Text("$label: $value", color = T.Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        return
    }
    Text("${if (open) "−" else "+"} $label ${if (value is JsonObject) "{$count}" else "[$count]"}",
        color = T.Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp))
    if (open) Column(Modifier.padding(start = 12.dp)) {
        when (value) {
            is JsonObject -> value.entries.take(limit).forEach { (name, child) ->
                key(name) { InspectorJsonNode(name, child) }
            }
            is JsonArray -> value.take(limit).forEachIndexed { index, child ->
                key(index) { InspectorJsonNode("[$index]", child) }
            }
            else -> Unit
        }
        if (count > limit) TextButton(onClick = { limit += 50 }) {
            Text(inspectorText("再展开 ${minOf(50, count - limit)} 项", "Show ${minOf(50, count - limit)} more"), fontSize = 11.sp)
        }
    }
}
