package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*


private val builtInPresetIds = setOf("standard", "ptc", "minimal", "cordis")
private val codingPresetIds = setOf("ptc", "minimal")

/** The desktop registry's display fold, not a guess based on the id alone. */
internal fun isBuiltInPreset(row: JsonElement): Boolean =
    row["name"].str() == null && row["id"].str() in builtInPresetIds

internal fun presetRequiresCodingTools(row: JsonElement): Boolean =
    isBuiltInPreset(row) && row["id"].str() in codingPresetIds

private data class PresetConfiguration(val row: JsonElement, val content: String)

private class PresetSettingsState {
    var data by mutableStateOf<JsonElement?>(null)
    var loading by mutableStateOf(true)
    var revisionFresh by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var view by mutableStateOf<PresetConfiguration?>(null)
    var viewRequest = 0
    var guide by mutableStateOf<Pair<String, PresetGuidePage>?>(null)
    var attached = true
}

@Composable
internal fun AgentPresetSettingsScreen(onBack: () -> Unit, onCreator: (() -> Unit)? = null) {
    val host by Pocket.active.collectAsState()
    val ops by Pocket.ops.collectAsState()
    val status by Pocket.status.collectAsState()
    val online = host != null && status[host] == Dot.Online
    val canLoad = online && ("presets.settings" in ops || "presets.list" in ops)
    val settingsRevision by Pocket.modelSettingsRevision.collectAsState()
    val client = Pocket.clientOrNull()
    val state = remember(host) { PresetSettingsState() }
    val scope = rememberCoroutineScope()
    DisposableEffect(state) { onDispose { state.attached = false } }
    fun current() = state.attached && Pocket.active.value == host && Pocket.clientOrNull() === client
    fun connected() = current() && host != null && Pocket.status.value[host] == Dot.Online
    suspend fun readRoster(): JsonElement {
        check(connected()) { L.unavailable() }
        val connection = checkNotNull(client) { L.unavailable() }
        return if ("presets.settings" in ops) connection.call("presets.settings") else {
            check("presets.list" in ops) { L.unavailable() }
            val roster = connection.call("presets.list")
            buildJsonObject { put("presets", roster["presets"] ?: JsonArray(emptyList())); put("writable", false) }
        }
    }
    fun adopt(value: JsonElement) {
        if (!connected()) return
        state.data = value
        state.revisionFresh = true
        Pocket.presetRoster.value = value["presets"].arr().orEmpty().filterIsInstance<JsonObject>()
    }
    suspend fun load(clearError: Boolean = true) {
        if (!canLoad || !connected()) return
        state.loading = true
        state.revisionFresh = false
        if (clearError) state.error = null
        try { adopt(readRoster()) }
        catch (t: Throwable) { if (current()) state.error = settingsFailure(t) }
        finally { if (current()) state.loading = false }
    }
    fun viewConfiguration(row: JsonElement) {
        val connection = client ?: return
        if (!connected() || "presets.read" !in ops) return
        val request = ++state.viewRequest
        state.error = null
        state.view = null
        scope.launch {
            try {
                val content = connection.call("presets.read", jsonOf("id" to row["id"].str()))["content"].str()
                    ?: error(st("主机未返回预设组成", "Host did not return the preset composition"))
                if (connected() && request == state.viewRequest) state.view = PresetConfiguration(row, content)
            } catch (t: Throwable) {
                if (current() && request == state.viewRequest) state.error = settingsFailure(t)
            }
        }
    }
    LaunchedEffect(state, client, ops, settingsRevision, online) {
        state.revisionFresh = false
        if (!canLoad) {
            state.loading = false
            return@LaunchedEffect
        }
        // Coalesce notifications during a save; its read-back and error must settle first.
        snapshotFlow { state.busy }.first { !it }
        load(clearError = false)
    }
    val rows = state.data["presets"].arr().orEmpty()
    val revision = (state.data["namespace"]["revision"] as? JsonPrimitive)?.longOrNull
    val hostWritable = state.data["writable"].bool() == true && revision != null && "models.settingsMutate" in ops
    val writable = online && state.revisionFresh && hostWritable
    fun makeDefault(row: JsonElement) {
        val id = row["id"].str() ?: return
        if (!connected() || !writable || state.busy || state.loading || row["broken"].str() != null || row["isDefault"].bool() == true) return
        val expected = revision ?: return
        val connection = client ?: return
        state.busy = true
        state.error = null
        scope.launch {
            try {
                var writeFailure: Throwable? = null
                try {
                    requireSettingsSuccess(connection.modelsSettingsMutate("agent-preset-registry", JsonArray(listOf(
                        jsonOf("op" to "set", "path" to listOf("selectedDefault"), "value" to id),
                    )), expected))
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    writeFailure = t
                }
                if (!connected()) return@launch
                // A rejection can carry a newer revision; never retry a stale CAS or claim an override applied.
                var readFailure: Throwable? = null
                state.revisionFresh = false
                val confirmed = try { readRoster().also(::adopt) } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    readFailure = t
                    null
                }
                if (!connected()) return@launch
                if (writeFailure != null) {
                    state.error = settingsFailure(writeFailure) + (readFailure?.let { "\n" + settingsFailure(it) } ?: "")
                } else {
                    readFailure?.let { throw it }
                    check(confirmed["presets"].arr().orEmpty().any { it["id"].str() == id && it["isDefault"].bool() == true }) {
                        st("已保存，但主机当前默认值未改变，可能存在更高优先级覆盖", "Saved, but the Host default is unchanged; a higher-priority override may be active")
                    }
                }
            } catch (t: Throwable) { if (current()) state.error = settingsFailure(t) }
            finally { if (state.attached) state.busy = false }
        }
    }
    SettingsScaffold(st("Agent 预设", "Agent presets"), onBack = { if (!online || !state.busy) onBack() }, actions = {
        if (state.error != null) IconButton({ scope.launch { load() } }, enabled = canLoad && !state.busy && !state.loading) { Icon(D.refresh, L.retry(), tint = T.Ink) }
    }) {
        SettingsMessage(st("选择 Agent 的工具和工作方式。日常任务用「标准模式」，扩展 DSH 的能力用「创造模式」。", "Choose the agent’s tools and how it works. Use Standard mode for everyday tasks, or Creator mode to add capabilities to DSH."))
        SettingsMessage(state.error, true)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
        when {
            state.loading -> ErrorOrEmpty(L.loading())
            rows.isEmpty() -> ErrorOrEmpty(L.unavailable(), onRetry = if (canLoad) ({ scope.launch { load() }; Unit }) else null)
            else -> PresetCardGroups(
                rows = rows, codingTools = Pocket.prefs.showCodeWorkView,
                writable = writable, busy = state.busy, canRead = online && "presets.read" in ops,
                onSelect = ::makeDefault,
                onGuide = { id, page -> state.guide = id to page },
                onView = ::viewConfiguration,
                onCreator = onCreator?.takeIf { rows.any { row -> row["id"].str() == "cordis" && row["broken"].str() == null } },
                creatorEnabled = online && "sessions.create" in ops,
            )
        }
        if (online && state.revisionFresh && !hostWritable) SettingsMessage(st("当前主机仅支持查看预设，默认设置为只读", "This Host allows viewing presets only; the default is read-only"))
        Spacer(Modifier.height(20.dp))
    }
    state.guide?.let { (id, page) -> PresetGuideDialog(id, page) { state.guide = null } }
    state.view?.let { view ->
        key(state, view.row["id"].str()) {
            PresetConfigurationDialog(view.row, view.content, onDismiss = { state.viewRequest++; state.view = null })
        }
    }
}

@Composable
internal fun PresetCardGroups(
    rows: List<JsonElement>, codingTools: Boolean, writable: Boolean, busy: Boolean, canRead: Boolean,
    onSelect: (JsonElement) -> Unit, onGuide: (String, PresetGuidePage) -> Unit,
    onView: (JsonElement) -> Unit, onCreator: (() -> Unit)?,
    creatorEnabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        listOf(true, false).forEach { builtIn ->
            val group = rows.filter { isBuiltInPreset(it) == builtIn && (codingTools || !presetRequiresCodingTools(it)) }
            val creator = onCreator.takeUnless { builtIn }
            if (group.isNotEmpty() || creator != null) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (builtIn) st("内置", "Built-in") else st("自定义", "Custom"), color = T.Text3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 548.dp) 2 else 1
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.chunked(columns).forEach { line ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                line.forEach { row ->
                                    PresetCard(row, builtIn, writable, busy, canRead, onSelect, onGuide, onView, Modifier.weight(1f).fillMaxHeight())
                                }
                                if (line.size < columns) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (creator != null) {
                    val borderColor = ThemeState.colors.BorderL3
                    Row(Modifier.fillMaxWidth().height(44.dp).testTag("preset-creator")
                        .drawBehind { drawRoundRect(borderColor, cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))) }
                        .clickable(enabled = creatorEnabled && !busy, role = Role.Button, onClick = creator).padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(D.plus, null, Modifier.size(14.dp), tint = if (!creatorEnabled || busy) T.Caption else T.Ink)
                        Spacer(Modifier.width(6.dp))
                        Text(st("让 Agent 帮我创建预设模式", "Let the agent help me create a preset"), fontSize = 14.sp, color = if (!creatorEnabled || busy) T.Caption else T.Ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    row: JsonElement, builtIn: Boolean, writable: Boolean, busy: Boolean, canRead: Boolean,
    onSelect: (JsonElement) -> Unit, onGuide: (String, PresetGuidePage) -> Unit,
    onView: (JsonElement) -> Unit, modifier: Modifier,
) {
    val id = row["id"].str().orEmpty()
    val name = presetName(row)
    val active = row["isDefault"].bool() == true
    val broken = row["broken"].str()
    Column(modifier.clip(T.R20).background(if (active) T.Fill2 else T.SettingsCard)
        .border(0.5.dp, if (broken != null) T.Err else if (active) Color(0xFFADB2B8) else T.SettingsCardStroke, T.R20)) {
        Column(Modifier.fillMaxWidth().weight(1f).testTag("preset-select-$id")
            .semantics { selected = active; contentDescription = (if (active) st("新任务默认：", "New task default: ") else st("设为新任务默认：", "Set as new task default: ")) + name }
            .clickable(enabled = writable && !busy && !active && broken == null, role = Role.Button) { onSelect(row) }
            .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, color = T.Ink, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (broken != null) Text(st("加载失败", "Failed to load"), fontSize = 11.sp, lineHeight = 17.sp, color = T.OnPrimary,
                        modifier = Modifier.clip(T.R12).background(T.Err).padding(horizontal = 8.dp, vertical = 1.dp))
                    Text(if (active) st("新任务默认", "New task default") else if (builtIn) st("内置", "Built-in") else st("自定义", "Custom"),
                        color = if (active) T.Surface3 else T.Text3, fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.clip(T.R12).background(if (active) T.Ink else Color.Transparent)
                            .then(if (active) Modifier else Modifier.border(0.5.dp, T.BorderStrong, T.R12)).padding(horizontal = 8.dp, vertical = 1.dp))
                }
                Text(id, style = T.Mono.copy(fontSize = 11.sp, lineHeight = 21.sp), color = T.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
            }
            Text(presetDescription(row) ?: st("暂无描述。", "No description."), color = T.Text2, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            broken?.let { Text(it, color = T.Err, fontSize = 12.sp) }
        }
        HorizontalDivider(color = T.BorderStrong, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (builtIn && presetGuideAvailable(id)) {
                TextButton({ onGuide(id, PresetGuidePage.Explanation) }, modifier = Modifier.testTag("preset-explanation-$id"), contentPadding = PaddingValues(horizontal = 8.dp), colors = ButtonDefaults.textButtonColors(contentColor = T.Text3)) { Text(st("模式说明", "Mode explanation"), fontSize = 12.sp) }
                TextButton({ onGuide(id, PresetGuidePage.Usage) }, modifier = Modifier.testTag("preset-usage-$id"), contentPadding = PaddingValues(horizontal = 8.dp), colors = ButtonDefaults.textButtonColors(contentColor = T.Text3)) { Text(st("如何使用", "How to use"), fontSize = 12.sp) }
            }
            Spacer(Modifier.weight(1f))
            IconButton({ onView(row) }, enabled = canRead, modifier = Modifier.testTag("preset-view-$id")) { Icon(D.browse, st("查看配置：", "View configuration: ") + name, Modifier.size(16.dp), tint = if (canRead) T.Text3 else T.Caption) }
        }
    }
}

@Composable
private fun PresetConfigurationDialog(row: JsonElement, content: String, onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(20.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.86f).testTag("preset-configuration-dialog")
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDismiss(); true } else false
            }, shape = T.R28, color = T.Surface2) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(st("查看配置 · ", "View configuration · ") + presetName(row), modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = T.Ink)
                    IconButton(onDismiss, Modifier.testTag("preset-configuration-dismiss")) { Icon(D.close, st("关闭", "Close"), tint = T.Text3, modifier = Modifier.size(14.dp)) }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    SelectionContainer {
                        Box(Modifier.fillMaxWidth().border(0.5.dp, T.BorderStrong, T.R16).clip(T.R16).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                            Text(content, style = T.Mono.copy(fontSize = 12.5.sp, lineHeight = 18.75.sp), color = T.Text2, softWrap = false)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onDismiss, modifier = Modifier.testTag("preset-configuration-close").focusRequester(closeFocus), shape = T.R12,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, ThemeState.colors.BorderL3),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink)) { Text(st("关闭", "Close"), fontSize = 14.sp) }
                }
            }
            LaunchedEffect(Unit) { closeFocus.requestFocus() }
        }
    }
}
