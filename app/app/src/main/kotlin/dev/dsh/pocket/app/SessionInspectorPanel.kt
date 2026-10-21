package dev.dsh.pocket.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import android.animation.ValueAnimator
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.distinctUntilChanged

private fun inspectorText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

@Stable
private class InspectorViewState {
    var grouped by mutableStateOf(false)
    var filter by mutableStateOf("")
    var collapsed by mutableStateOf(emptySet<String>())
    var expanded by mutableStateOf(emptySet<String>())
    var selectedKey by mutableStateOf<String?>(null)
    var follow by mutableStateOf(true)
    var scrollRequest by mutableIntStateOf(0)
    var detailFraction by mutableFloatStateOf(.4f)
    fun mode(group: Boolean) {
        grouped = group
        filter = ""; collapsed = emptySet(); expanded = emptySet(); selectedKey = null; follow = true
    }
    fun applyFilter(value: String) {
        filter = value.trim().trim('*'); selectedKey = null; collapsed = emptySet(); expanded = emptySet(); follow = false
    }
}

/** The same table controls and copy as session-inspector; inspection never sends a prompt. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SessionInspectorSheet(
    sessionId: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    onLoadOlder: () -> Unit = { Pocket.loadOlderHistory() },
) {
    val current by Pocket.chat.collectAsState()
    val st = current?.takeIf { sessionId.isNotBlank() && it.sessionId == sessionId } ?: return
    key(sessionId) {
        val state = remember { InspectorViewState() }
        DisposableEffect(sessionId) { onDispose { InspectorNavigation.reset() } }
        if (!InspectorNavigation.picking || InspectorNavigation.sessionId != sessionId) {
            RightPanelFrame(inspectorText("会话数据诊断", "Session Log"), onDismiss, onBack) {
                BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("sessionInspectorPanel")) {
                    val availableHeight = maxHeight
                    key(state.grouped) {
                        Column(Modifier.fillMaxSize()) { SessionInspectorContent(st, state, availableHeight, onLoadOlder) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.SessionInspectorContent(st: ChatState, state: InspectorViewState, availableHeight: Dp, onLoadOlder: () -> Unit) {
    val grouped = state.grouped
    val policy = presentationPolicyFor(Pocket.prefs.transcriptView, Pocket.prefs.collapseTiming)
    val rows = remember(grouped, st.inspectorRecords, st.items, st.turns, st.running, policy) {
        if (grouped) inspectorGroupRows(st, policy) else inspectorRawRows(st.inspectorRecords)
    }
    val all = remember(rows) { buildList { fun visit(row: InspectorRow) { add(row); row.children.forEach(::visit) }; rows.forEach(::visit) } }
    val visible = remember(rows, state.filter, state.collapsed, state.expanded) {
        inspectorVisibleRows(rows, state.filter, state.collapsed, state.expanded)
    }
    val matchingCount = remember(all, state.filter) { all.count { inspectorTypeMatches(it.type, state.filter) } }
    val selected = all.firstOrNull { it.key == state.selectedKey }
    val list = key(grouped) { rememberLazyListState() }
    val horizontal = key(grouped) { rememberScrollState() }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val skipMotion = LocalInspectionMode.current || !ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(dragged) { if (dragged) state.follow = false }
    var requestedBefore by remember(st.sessionId, grouped, state.filter) { mutableStateOf<Long?>(null) }
    var attemptedInDrag by remember(st.sessionId, grouped, state.filter) { mutableStateOf(false) }
    LaunchedEffect(dragged) { if (dragged) attemptedInDrag = false }
    fun loadEarlier(pull: Boolean) {
        if (!st.loaded || !dev.dsh.pocket.protocol.HistoryPagingPolicy.canLoadOlder(st.hasMore, st.loadingOlder, st.firstSeq)) return
        if (pull && attemptedInDrag || !pull && requestedBefore == st.firstSeq) return
        requestedBefore = st.firstSeq
        attemptedInDrag = true
        state.follow = false
        onLoadOlder()
    }
    val loadAtBoundary by rememberUpdatedState({ loadEarlier(false) })
    LaunchedEffect(list, state.filter) {
        var previous = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        var movingEarlier = false
        snapshotFlow {
            Triple(list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset, list.isScrollInProgress, dragged)
        }.distinctUntilChanged().collect { (position, scrolling, dragging) ->
            val (index, offset) = position
            if (position != previous) {
                if (dragging || movingEarlier) {
                    movingEarlier = index < previous.first || index == previous.first && offset < previous.second
                }
                previous = position
            }
            if (!scrolling && movingEarlier && !state.follow && index == 0 && offset < 48) {
                movingEarlier = false
                loadAtBoundary()
            }
        }
    }
    LaunchedEffect(visible.lastOrNull()?.row, visible.size, state.follow, state.scrollRequest, skipMotion) {
        if (state.follow && visible.isNotEmpty()) {
            if (skipMotion) list.requestScrollToItem(visible.lastIndex) else list.scrollToItem(visible.lastIndex)
        }
    }
    LaunchedEffect(state.filter, skipMotion) {
        if (!state.follow && visible.isNotEmpty()) {
            if (skipMotion) list.requestScrollToItem(0) else list.scrollToItem(0)
        }
    }
    val picked = InspectorNavigation.pickedItemKey.takeIf { InspectorNavigation.sessionId == st.sessionId }
    LaunchedEffect(picked, rows) {
        val item = st.items.firstOrNull { it.key == picked } ?: return@LaunchedEffect
        val row = inspectorRowForItem(rows, item) ?: return@LaunchedEffect
        state.filter = ""; state.collapsed = emptySet(); state.expanded = all.mapTo(mutableSetOf()) { it.key }
        state.selectedKey = row.key; state.follow = false
        InspectorNavigation.pickedItemKey = null
    }
    LaunchedEffect(state.selectedKey, skipMotion) {
        val index = visible.indexOfFirst { it.row.key == state.selectedKey }
        if (index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            if (skipMotion) list.requestScrollToItem(index) else list.scrollToItem(index)
        }
    }
    var modesOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Row(Modifier.testTag("inspectorPresentation").semantics { contentDescription = inspectorText("展示形式", "Presentation") }
                    .clickable { modesOpen = true }.background(T.Fill2).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    InspectorText(if (grouped) inspectorText("对话分组", "Chat Group") else inspectorText("原始数据", "Raw Log"))
                    Icon(D.chevronDown, null, tint = T.Ink, modifier = Modifier.padding(start = 4.dp).size(12.dp))
                }
                if (modesOpen) Popup(alignment = Alignment.TopStart, onDismissRequest = { modesOpen = false },
                    properties = PopupProperties(focusable = true)) {
                    Surface(color = T.Surface, shape = RoundedCornerShape(4.dp), shadowElevation = 4.dp,
                        border = androidx.compose.foundation.BorderStroke(.5.dp, T.Border)) {
                        Column(Modifier.width(112.dp).padding(vertical = 3.dp)) {
                            listOf(false, true).forEach { group ->
                                InspectorText(if (group) inspectorText("对话分组", "Chat Group") else inspectorText("原始数据", "Raw Log"),
                                    Modifier.fillMaxWidth().testTag(if (group) "inspectorGroups" else "inspectorRaw")
                                        .background(if (group == grouped) T.HoverBg else T.Surface)
                                        .clickable { modesOpen = false; state.mode(group) }.padding(horizontal = 8.dp, vertical = 5.dp))
                            }
                        }
                    }
                }
            }
            Box(Modifier.size(22.dp).testTag("inspectorPick").semantics { contentDescription = inspectorText("在对话面板中选取元素", "Pick an element in Chat View") }
                .clickable { InspectorNavigation.begin(st.sessionId) }.padding(3.dp)) { InspectorPickIcon() }
            InspectorText("· " + if (state.filter.isNotEmpty()) "$matchingCount / ${all.size}" else "${all.size}",
                Modifier.testTag("inspectorRowCount"))
        }
        InspectorAction(inspectorText("跟踪最新", "Follow Latest"), Modifier.testTag("inspectorFollowLatest")) {
            state.follow = true; state.scrollRequest++
        }
    }
    HorizontalDivider(color = T.Border, thickness = .5.dp)
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val tableWidth = maxOf(maxWidth, 660.dp)
        Column(Modifier.fillMaxSize().horizontalScroll(horizontal).width(tableWidth)) {
            Row(Modifier.fillMaxWidth().height(29.5.dp).background(T.Fill2), verticalAlignment = Alignment.CenterVertically) {
                InspectorCell(inspectorText("序号", "Index"), 64.dp, header = true)
                Row(Modifier.width(190.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InspectorText(inspectorText("类型", "Type"), mono = true, bold = true)
                    InspectorTypeFilter(state.filter, rows, state::applyFilter)
                }
                InspectorCell("T/S", 65.dp, header = true)
                if (!grouped) InspectorCell(inspectorText("时间 (UTC)", "Time (UTC)"), 100.dp, header = true)
                InspectorCell(inspectorText("数据", "Data"), tableWidth - if (grouped) 319.dp else 419.dp, header = true)
            }
            HorizontalDivider(color = T.Border, thickness = .5.dp)
            PullToRefreshBox(
                isRefreshing = st.loadingOlder,
                onRefresh = { loadEarlier(true) },
                modifier = Modifier.fillMaxSize().testTag("inspectorHistoryPull"),
            ) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("inspectorRows")) {
                    if (visible.isEmpty()) {
                        item(key = "inspector-empty") {
                            InspectorText(inspectorText("暂无记录", "No records"), Modifier.padding(12.dp), muted = true)
                        }
                    }
                    items(visible, key = { it.row.key }) { entry ->
                        val row = entry.row
                        val collapsed = row.key in state.collapsed || (state.filter.isEmpty() && row.defaultCollapsed && row.key !in state.expanded)
                        val select: () -> Unit = {
                            state.selectedKey = row.key
                            inspectorItemForRow(st, row)?.let { InspectorNavigation.reveal(st.sessionId, it) }
                        }
                        Row(Modifier.fillMaxWidth().height(29.5.dp).background(if (state.selectedKey == row.key) T.HoverBg else T.Surface),
                            verticalAlignment = Alignment.CenterVertically) {
                            InspectorCell(row.identity, 64.dp, muted = true)
                            Row(Modifier.width(190.dp).padding(start = (8 + entry.depth * 20).dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (row.children.isNotEmpty()) InspectorText(if (collapsed) "▸" else "▾",
                                    Modifier.width(16.dp).semantics { contentDescription = if (collapsed) inspectorText("展开子行", "Expand children") else inspectorText("收起子行", "Collapse children") }
                                        .clickable {
                                            if (collapsed) { state.collapsed -= row.key; state.expanded += row.key }
                                            else { state.collapsed += row.key; state.expanded -= row.key }
                                            state.follow = false
                                        }, mono = true)
                                InspectorText(row.type, Modifier.weight(1f).clickable(onClick = select), muted = !entry.matches, mono = true)
                            }
                            InspectorCell(row.location, 65.dp)
                            if (!grouped) InspectorCell(inspectorTime(row.time), 100.dp)
                            InspectorText(row.summary?.takeIf { row.type != "block-start" || collapsed } ?: inspectorPreview(row.value),
                                Modifier.weight(1f).padding(horizontal = 8.dp).clickable(onClick = select), mono = true)
                        }
                        HorizontalDivider(color = ThemeState.colors.BorderL1, thickness = .5.dp)
                    }
                }
            }
        }
    }
    if (state.selectedKey != null) {
        val density = LocalDensity.current
        val availablePx = with(density) { availableHeight.toPx() }
        Column(Modifier.fillMaxWidth().height(availableHeight * state.detailFraction).testTag("inspectorDetail")) {
            Box(Modifier.fillMaxWidth().height(5.dp).background(T.Border).testTag("inspectorResize")
                .semantics { contentDescription = inspectorText("调整原始数据面板高度", "Resize raw data panel") }
                .draggable(rememberDraggableState { delta ->
                    if (availablePx > 0) state.detailFraction = (state.detailFraction - delta / availablePx).coerceIn(.15f, .8f)
                }, Orientation.Vertical))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                InspectorText(inspectorText("原始数据", "Raw data"), Modifier.weight(1f))
                InspectorAction(inspectorText("关闭", "Close"), Modifier.testTag("inspectorCloseDetail")) { state.selectedKey = null }
            }
            HorizontalDivider(color = T.Border, thickness = .5.dp)
            if (selected == null) InspectorText(inspectorText("该记录已移除或结算，请选择当前记录。", "This record was removed or settled. Select a current record."), Modifier.padding(8.dp))
            else key(selected.key) {
                if (grouped) {
                    InspectorText("${selected.type} · ${selected.identity}", Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp))
                    HorizontalDivider(color = T.Border, thickness = .5.dp)
                }
                InspectorValueTree(selected.value, grouped)
            }
        }
    }
}

@Composable
private fun InspectorText(text: String, modifier: Modifier = Modifier, muted: Boolean = false, mono: Boolean = false, bold: Boolean = false) {
    Text(text, modifier, color = if (muted) T.Text3 else T.Ink, fontSize = 11.sp, lineHeight = 15.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun InspectorAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    InspectorText(text, modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 2.dp), muted = !enabled)
}

@Composable
private fun InspectorCell(text: String, width: Dp, header: Boolean = false, muted: Boolean = false) {
    InspectorText(text, Modifier.width(width).padding(horizontal = 8.dp), muted = muted, mono = true, bold = header)
}

@Composable
private fun InspectorPickIcon() {
    val color = T.Ink
    Canvas(Modifier.fillMaxSize()) {
        val scale = size.width / 16
        drawCircle(color, radius = 4.5f * scale, style = Stroke(1.3f * scale))
        listOf(Offset(8f, 0f) to Offset(8f, 5f), Offset(8f, 11f) to Offset(8f, 16f),
            Offset(0f, 8f) to Offset(5f, 8f), Offset(11f, 8f) to Offset(16f, 8f)).forEach { (a, b) ->
            drawLine(color, a * scale, b * scale, strokeWidth = 1.3f * scale)
        }
    }
}

@Composable
private fun InspectorTypeFilter(value: String, rows: List<InspectorRow>, apply: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value) }
    val focus = remember { FocusRequester() }
    val popupOffset = with(LocalDensity.current) { IntOffset(0, 21.dp.roundToPx()) }
    val suggestions = remember(rows, draft) { inspectorTypeSuggestions(rows, draft) }
    val commit = { query: String -> apply(query); open = false }
    Box {
        val color = if (value.isNotEmpty()) T.Accent else T.Ink
        Canvas(Modifier.size(17.dp).testTag("inspectorFilterButton").semantics { contentDescription = inspectorText("筛选类型", "Filter types") }
            .clickable { draft = value; open = !open }.padding(2.dp)) {
            val s = size.width / 16
            val path = Path().apply { moveTo(2*s, 3*s); lineTo(14*s, 3*s); lineTo(9.5f*s, 8*s); lineTo(9.5f*s, 13*s); lineTo(6.5f*s, 11.5f*s); lineTo(6.5f*s, 8*s); close() }
            drawPath(path, color, style = Stroke(1.3f*s))
        }
        if (open) Popup(alignment = Alignment.TopStart, offset = popupOffset, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            LaunchedEffect(Unit) { focus.requestFocus() }
            Surface(Modifier.width(290.dp).padding(0.dp).testTag("inspectorFilterPopup"), shape = RoundedCornerShape(8.dp), color = T.Surface,
                shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(.5.dp, T.Border)) {
                Column(Modifier.padding(10.dp).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { open = false; true } else false
                }) {
                    BasicTextField(draft, { draft = it }, singleLine = true,
                        textStyle = TextStyle(color = T.Ink, fontSize = 11.sp), cursorBrush = SolidColor(T.Ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit(draft) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).border(.5.dp, T.Border, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 5.dp)
                            .testTag("inspectorFilter").semantics { contentDescription = inspectorText("类型关键词", "Type keyword") }
                            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { commit(draft); true } else false },
                        decorationBox = { field -> Box { if (draft.isEmpty()) InspectorText(inspectorText("输入类型片段，如 delta", "Type a fragment, e.g. delta"), muted = true); field() } })
                    Text(inspectorText("默认包含匹配（*关键词*）；Enter 确认，Esc 取消。", "Contains matching (*keyword*). Enter confirms; Esc cancels."),
                        color = T.Text2, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).semantics { contentDescription = inspectorText("类型候选", "Type suggestions") }) {
                        suggestions.forEach { type -> InspectorText(type, Modifier.fillMaxWidth().clickable { commit(type) }.padding(horizontal = 6.dp, vertical = 4.dp)) }
                    }
                    if (suggestions.isEmpty()) Text(inspectorText("没有匹配候选，仍可确认当前关键词。", "No suggestions. You can still apply this keyword."),
                        color = T.Ink, fontSize = 11.sp, lineHeight = 15.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        listOf(inspectorText("清空", "Clear") to { draft = "" }, inspectorText("取消", "Cancel") to { open = false },
                            inspectorText("确认筛选", "Apply filter") to { commit(draft) }).forEachIndexed { i, (label, action) ->
                            InspectorText(label, Modifier.border(.5.dp, T.Border, RoundedCornerShape(4.dp))
                                .testTag(listOf("inspectorClearFilter", "inspectorCancelFilter", "inspectorApplyFilter")[i])
                                .clickable(onClick = action).padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Raw JSON starts expanded, group objects are lazy; both use the official Object / Array labels. */
@Composable
private fun ColumnScope.InspectorValueTree(value: JsonElement, grouped: Boolean) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val limits = remember { mutableStateMapOf<String, Int>() }
    data class Entry(val path: String, val name: String, val value: JsonElement?, val depth: Int, val more: Boolean = false)
    val entries = buildList {
        fun visit(path: String, name: String, node: JsonElement, depth: Int) {
            add(Entry(path, name, node, depth))
            if (expanded[path] ?: (!grouped || path == "$")) {
                val children = when (node) { is JsonObject -> node.entries.map { it.key to it.value }; is JsonArray -> node.mapIndexed { i, child -> i.toString() to child }; else -> return }
                val limit = if (grouped) limits[path] ?: 50 else children.size
                if (children.isEmpty()) add(Entry("$path/empty", "", null, depth + 1))
                children.take(limit).forEach { (key, child) -> visit("$path/${key.length}:$key", key, child, depth + 1) }
                if (children.size > limit) add(Entry(path + "/more", path, null, depth + 1, more = true))
            }
        }
        visit("$", "$", value, 0)
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("inspectorTree"), contentPadding = PaddingValues(8.dp)) {
        items(entries, key = { it.path }) { entry ->
            val node = entry.value
            val container = node is JsonObject || node is JsonArray
            val open = expanded[entry.path] ?: (!grouped || entry.path == "$")
            val label = when (node) { is JsonObject -> "Object"; is JsonArray -> "Array(${node.size})"; else -> node.toString() }
            val text = when {
                entry.more -> inspectorText("显示更多", "Show more")
                node == null -> inspectorText("空", "Empty")
                else -> (if (container) if (open) "▾ " else "▸ " else "") + "${entry.name}: $label"
            }
            val modifier = Modifier.fillMaxWidth().padding(start = (16 + entry.depth * 16).dp, top = 2.dp, bottom = 2.dp)
            if (container || entry.more) Text(text, color = if (entry.more) T.Accent else T.Ink, fontSize = 11.sp, lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace, modifier = modifier.clickable {
                    if (entry.more) limits[entry.name] = (limits[entry.name] ?: 50) + 50 else expanded[entry.path] = !open
                })
            else SelectableOutput { Text(text, color = T.Text2, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, modifier = modifier) }
        }
    }
}
