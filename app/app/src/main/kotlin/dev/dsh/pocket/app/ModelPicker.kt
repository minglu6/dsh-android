package dev.dsh.pocket.app

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.dsh.pocket.protocol.ModelCatalog

private enum class ModelPane { Root, Models, Effort }

private fun mt(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

internal fun modelEffortLabel(catalog: ModelCatalog?, current: ModelChoice?): String? {
    current ?: return null
    val model = catalog?.find(current.provider, current.model) ?: return null
    if (!model.hasReasoning) return null
    val effort = current.effort ?: model.defaultEffort
    return if (effort == null) "Default"
    else model.efforts.firstOrNull { it.id == effort }?.name ?: effort
}

/** Anchored above the composer, without a modal scrim or the unrelated sheet chrome. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ModelPicker(
    catalog: ModelCatalog?,
    current: ModelChoice?,
    onDismiss: () -> Unit,
    onPick: (ModelChoice, (Boolean) -> Unit) -> Unit,
    loading: Boolean = false,
    error: String? = null,
    pending: ModelChoice? = null,
    enabled: Boolean = true,
    openModels: Boolean = false,
    onReload: () -> Unit = {},
) {
    var pane by remember { mutableStateOf(if (openModels) ModelPane.Models else ModelPane.Root) }
    var query by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf<ModelChoice?>(null) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val busy = submitting ?: pending
    val latestDismiss by rememberUpdatedState(onDismiss)
    var mounted by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { mounted = false } }
    LaunchedEffect(enabled) { if (!enabled) submitting = null }
    val selectedModel = current?.let { catalog?.find(it.provider, it.model) }
    val effectiveEffort = current?.effort ?: selectedModel?.defaultEffort
    val modelLabel = selectedModel?.name ?: mt("选择模型", "Select model")
    val effortLabel = modelEffortLabel(catalog, current)
    val count = remember(catalog) { catalog?.groups?.sumOf { it.models.size } ?: 0 }
    val showSearch = count > 4
    val groups = remember(catalog, query, showSearch) {
        modelPickerGroups(catalog?.groups.orEmpty(), if (showSearch) query else "")
    }
    val visibleModels = remember(groups) { groups.flatMap { it.models } }
    var highlighted by remember(groups) { mutableIntStateOf(visibleModels.indexOfFirst { it.provider == current?.provider && it.id == current.model }.coerceAtLeast(0)) }
    val highlightedModel = visibleModels.getOrNull(highlighted)
    val listState = rememberLazyListState()
    val menuFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(query) { listState.scrollToItem(0) }
    LaunchedEffect(pane, catalog, current) {
        if (pane == ModelPane.Models && query.isEmpty()) {
            var index = 0
            for (group in groups) {
                val found = group.models.indexOfFirst { it.provider == current?.provider && it.id == current.model }
                // Leave room for the pinned provider heading above the selected row.
                if (found >= 0) { listState.scrollToItem(index + found); break }
                index += group.models.size + 1
            }
        }
        if (pane == ModelPane.Effort && selectedModel?.hasReasoning != true) pane = ModelPane.Root
    }
    LaunchedEffect(highlighted, groups) {
        if (pane == ModelPane.Models && highlightedModel != null) {
            var index = 0
            for (group in groups) {
                val found = group.models.indexOf(highlightedModel)
                if (found >= 0) {
                    val target = index + found + 1
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) listState.scrollToItem(target - 1)
                    break
                }
                index += group.models.size + 1
            }
        }
    }
    fun back() { if (pane == ModelPane.Root || openModels) latestDismiss() else pane = ModelPane.Root }
    androidx.activity.compose.BackHandler { back() }
    fun pick(choice: ModelChoice, unchanged: Boolean) {
        if (busy != null || !enabled || loading) return
        if (unchanged) { latestDismiss(); return }
        selectionError = null
        submitting = choice
        onPick(choice) { success ->
            submitting = null
            if (mounted && success) latestDismiss()
            if (mounted && !success) selectionError = mt("切换失败，当前选择未改变，请重试", "Could not switch. Your current selection is unchanged; try again.")
        }
    }
    val config = LocalConfiguration.current
    val width = minOf(360.dp, (config.screenWidthDp - 32).coerceAtLeast(1).dp)
    val maxHeight = minOf(400.dp, (config.screenHeightDp - 96).coerceAtLeast(96).dp)
    val margin = with(LocalDensity.current) { 16.dp.roundToPx() }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val position = remember(margin, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
                val above = anchorBounds.top - gap - popupContentSize.height
                val y = if (above >= margin) above else anchorBounds.bottom + gap
                return IntOffset(
                    x.coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin)),
                    y.coerceIn(margin, maxOf(margin, windowSize.height - popupContentSize.height - margin)),
                )
            }
        }
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false),
    ) {
        // Popup has its own composition; request focus only after its nodes attach.
        LaunchedEffect(pane, showSearch) {
            if (pane == ModelPane.Models && showSearch) searchFocus.requestFocus()
            else menuFocus.requestFocus()
        }
        Surface(
            modifier = Modifier.width(width).heightIn(max = maxHeight).testTag("modelPicker")
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape || it.key == Key.Back) {
                        if (it.type == KeyEventType.KeyUp) back()
                        true
                    } else false
                }.focusRequester(menuFocus).focusable(),
            shape = T.R12, color = T.Surface, shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, T.Border),
        ) {
            Column(Modifier.padding(4.dp)) {
                selectionError?.let { Text(it, color = T.Err, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
                if (pane == ModelPane.Root) {
                    ModelMenuCell(
                        mt("模型", "Model"),
                        if (loading && catalog == null) mt("加载中…", "Loading…") else modelLabel,
                        badge = modelReleaseBadge(current?.provider, selectedModel?.id, selectedModel?.name),
                    ) { pane = ModelPane.Models }
                    if (selectedModel?.hasReasoning == true) {
                        ModelMenuCell(mt("推理等级", "Reasoning effort"), effortLabel.orEmpty()) { pane = ModelPane.Effort }
                    }
                }
                if (pane == ModelPane.Models) {
                    if (showSearch) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(
                                value = query, onValueChange = { query = it }, singleLine = true, readOnly = busy != null,
                                textStyle = TextStyle(color = T.Ink, fontSize = 14.sp), cursorBrush = SolidColor(T.Accent),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).focusRequester(searchFocus)
                                    .onPreviewKeyEvent { event ->
                                        when (event.key) {
                                            Key.DirectionDown, Key.DirectionUp -> {
                                                if (event.type == KeyEventType.KeyDown && visibleModels.isNotEmpty()) {
                                                    highlighted = Math.floorMod(highlighted + if (event.key == Key.DirectionDown) 1 else -1, visibleModels.size)
                                                }
                                                true
                                            }
                                            Key.Enter -> {
                                                if (event.type == KeyEventType.KeyUp) highlightedModel?.let { model ->
                                                    pick(ModelChoice(model.provider, model.id, model.name, null),
                                                        current?.provider == model.provider && current.model == model.id)
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .testTag("modelSearch").semantics { contentDescription = mt("搜索模型…", "Search models…") },
                                decorationBox = { field -> Box { if (query.isEmpty()) Text(mt("搜索模型…", "Search models…"), color = T.Caption, fontSize = 14.sp); field() } },
                            )
                            if (query.isNotEmpty()) IconButton(onClick = { query = ""; searchFocus.requestFocus() }, enabled = busy == null, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.Close, mt("清除搜索", "Clear search"), tint = T.Caption, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = T.Accent)
                    if (error != null) ModelLoadNotice(error, loading, onReload)
                    catalog?.failures?.forEach { failure -> ModelLoadNotice("${failure.name}: ${failure.message}", loading, onReload) }
                    if (groups.isEmpty() && !loading && error == null) {
                        Text(if (count == 0) mt("暂无可用模型", "No models available") else mt("没有匹配的模型", "No matching models"),
                            color = T.Text3, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                    }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
                        groups.forEach { group ->
                            stickyHeader(key = "provider:${group.id}") {
                                Text(if (group.id == "deepseek-account") mt("DeepSeek 账号", "DeepSeek Account") else group.name,
                                    color = T.Text3, fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth().background(T.Surface).padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                            items(group.models, key = { "model:${group.id}:${it.id}" }) { model ->
                                val selected = current?.provider == group.id && current.model == model.id
                                ModelMenuOption(model.name, selected, busy?.provider == group.id && busy.model == model.id,
                                    enabled && !loading && busy == null, highlighted = showSearch && model == highlightedModel,
                                    badge = modelReleaseBadge(group.id, model.id, model.name)) {
                                    // Switching models uses that provider's default, never carries an unrelated effort.
                                    pick(ModelChoice(group.id, model.id, model.name, null), selected)
                                }
                            }
                        }
                    }
                }
                if (pane == ModelPane.Effort && current != null && selectedModel != null) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        if (selectedModel.defaultEffort == null) {
                            ModelMenuOption(mt("默认", "Default"), effectiveEffort == null,
                                busy != null && busy.effort == null, enabled && !loading && busy == null) {
                                pick(current.copy(effort = null), effectiveEffort == null)
                            }
                        }
                        selectedModel.efforts.forEach { effort ->
                            ModelMenuOption(effort.name, effectiveEffort == effort.id, busy?.effort == effort.id,
                                enabled && !loading && busy == null) {
                                pick(current.copy(effort = effort.id), effectiveEffort == effort.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelMenuCell(label: String, value: String, badge: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(T.R8).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = T.Ink)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            ModelLabel(value, badge, color = T.Text3)
        }
        Icon(D.chevronRight, null, tint = T.Caption, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

@Composable
private fun ModelMenuOption(label: String, selected: Boolean, pending: Boolean, enabled: Boolean, highlighted: Boolean = false, badge: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(T.R8).background(if (selected || highlighted) T.Fill2 else Color.Transparent)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        ModelLabel(label, badge, modifier = Modifier.weight(1f), color = if (enabled || selected) T.Ink else T.Text3)
        Box(Modifier.padding(start = 8.dp).size(18.dp), contentAlignment = Alignment.Center) {
            if (pending) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = T.Text3)
            else if (selected) Icon(D.check, null, tint = T.Ink, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ModelLoadNotice(message: String, loading: Boolean, onReload: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp).clip(T.R8).background(T.Fill2).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = T.Err, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onReload, enabled = !loading) { Text(mt("重试", "Retry"), fontSize = 12.sp) }
    }
}
