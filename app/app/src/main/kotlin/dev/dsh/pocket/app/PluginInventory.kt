package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.PocketClient
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private fun inventoryText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

internal fun inventoryLocalized(value: JsonElement?, english: Boolean): String? =
    value.str() ?: value[if (english) "en" else "zh"].str() ?: value["en"].str()

internal fun inventoryTitle(row: JsonElement, english: Boolean): String {
    val title = row["meta"]["title"]
    if (title is JsonObject) return inventoryLocalized(title, english).orEmpty()
    val name = title.str() ?: row["moduleName"].str().orEmpty()
    return (if (name.startsWith("@")) name.substringAfter('/') else name)
        .removePrefix("cordis:").removePrefix("cordis-plugin-")
        .replace(Regex("^dsh-(?:host-|client-)?"), "")
}

internal fun inventoryMatches(row: JsonElement, query: String, english: Boolean): Boolean =
    listOf(row["moduleName"].str(), row["entryId"].str(), inventoryTitle(row, english),
        inventoryLocalized(row["meta"]["description"], english))
        .any { it?.contains(query.trim(), ignoreCase = true) == true }

internal fun inventorySelectedPreset(presets: List<JsonElement>, selectedId: String?): JsonElement? =
    presets.firstOrNull { it["id"].str() == selectedId }
        ?: presets.firstOrNull { it["isDefault"].bool() == true } ?: presets.firstOrNull()

/** Preset declarations must never be presented as a global runtime or as a mutable profile row. */
internal fun inventoryProviders(entry: JsonElement, presets: List<JsonElement>): List<JsonElement> =
    if (entry["enabled"].bool() == true || entry["fiberPhase"].str() == "failed") emptyList()
    else presets.filter { preset -> preset["rows"].arr().orEmpty().any {
        it["moduleName"].str() == entry["moduleName"].str() && it["enabled"].bool() == true
    } }

internal fun inventoryEnablement(row: JsonElement, viaPresets: Boolean = false): String = when {
    row["fiberPhase"].str() == "failed" -> "failed"
    viaPresets -> "preset"
    row["enabled"].str() == "conditional" -> "conditional"
    row["enabled"].bool() == true -> "enabled"
    else -> "disabled"
}

private fun inventoryState(state: String): String = when (state) {
    "failed" -> inventoryText("启动失败", "Failed to start")
    "preset" -> inventoryText("预设中启用", "Via presets")
    "conditional" -> inventoryText("条件启用", "Conditional")
    "enabled" -> inventoryText("已启用", "Enabled")
    else -> inventoryText("已停用", "Disabled")
}

private fun inventoryPhase(row: JsonElement): String = when (row["fiberPhase"].str()) {
    "active" -> inventoryText("运行中", "Running")
    "pending" -> inventoryText("等待依赖", "Waiting for dependencies")
    "loading" -> inventoryText("加载中", "Loading")
    "unloading" -> inventoryText("卸载中", "Unloading")
    "failed" -> inventoryText("启动失败", "Failed to start")
    else -> inventoryText("未运行", "Not running")
}

internal class PluginInventoryLoadState {
    var snapshot by mutableStateOf<JsonElement?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<Exception?>(null)
}

/** One effect owns automatic and manual loads; cancellation must not suppress its replacement. */
@Composable
internal fun rememberPluginInventory(
    profile: String?,
    online: Boolean,
    supported: Boolean,
    connection: PocketClient?,
    revision: Long,
    refresh: Int,
    fetch: suspend (PocketClient) -> JsonElement = { it.pluginsInventory() },
): PluginInventoryLoadState {
    val state = remember(profile) { PluginInventoryLoadState() }
    LaunchedEffect(state, online, supported, connection, revision, refresh) {
        state.loading = false
        state.error = null
        if (!online || !supported) return@LaunchedEffect
        state.loading = true
        try {
            val result = fetch(checkNotNull(connection) { "Host connection unavailable" })
            currentCoroutineContext().ensureActive()
            state.snapshot = result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            state.error = e
        } finally {
            // A cancelled request may finish after its replacement has already started.
            if (currentCoroutineContext().isActive) state.loading = false
        }
    }
    return state
}

@Composable
internal fun BuiltInPluginsScreen(onBack: () -> Unit) {
    val profile by Pocket.active.collectAsState()
    key(profile) {
        val status by Pocket.status.collectAsState()
        val ops by Pocket.ops.collectAsState()
        val revision by Pocket.pluginCatalogRevision.collectAsState()
        val online = profile != null && status[profile] == Dot.Online
        val supported = "plugins.inventory" in ops
        val connection = Pocket.clientOrNull()
        var refresh by remember { mutableIntStateOf(0) }
        val state = rememberPluginInventory(profile, online, supported, connection, revision, refresh)
        SettingsScaffold(inventoryText("内置插件", "Built-in plugins"), onBack, actions = {
            IconButton({ refresh++ }, enabled = online && !state.loading && supported) {
                Icon(D.refresh, inventoryText("刷新", "Refresh"))
            }
        }) {
            InventoryNote(inventoryText("查看内置部署的插件列表", "View plugins in the built-in deployment"))
            if (online && !supported) InventoryNote(inventoryText("主机未提供内置插件目录，请检查主机插件是否已加载。", "The host does not provide a built-in plugin inventory. Check that its plugin is loaded."), true)
            state.error?.let { InventoryNote(it.message ?: inventoryText("读取插件列表失败", "Could not load plugin inventory"), true) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
            state.snapshot?.let { PluginInventoryContent(it) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Desktop settings inventory is read-only, separate from installable bundle management. */
@Composable
internal fun PluginInventoryContent(snapshot: JsonElement) {
    val english = Pocket.prefs.language == AppLanguage.En
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var presetOpen by rememberSaveable { mutableStateOf(true) }
    var globalOpen by rememberSaveable { mutableStateOf(false) }
    var choosePreset by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val presets = snapshot["agentPresets"].arr().orEmpty()
    val entries = snapshot["entries"].arr().orEmpty()
    val selected = inventorySelectedPreset(presets, selectedId)
    val searching = query.isNotBlank()
    val globalRows = entries.filter { inventoryMatches(it, query, english) }
        .sortedBy { it["fiberPhase"].str() != "failed" }
    val selectedRows = selected["rows"].arr().orEmpty()
    val otherMatches = if (searching) presets.filter { it != selected && it["rows"].arr().orEmpty().any { row -> inventoryMatches(row, query, english) } } else emptyList()

    val searchLabel = inventoryText("搜索插件", "Search plugins")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(36.dp)
            .background(T.Surface, T.R12).border(0.5.dp, ThemeState.colors.BorderL4, T.R12)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(D.search, null, Modifier.size(16.dp), tint = T.Text3)
        BasicTextField(
            query, { query = it }, singleLine = true,
            textStyle = TextStyle(color = T.Ink, fontFamily = LocalFonts.text, fontSize = 13.sp),
            modifier = Modifier.weight(1f).testTag("plugins.inventory.search").semantics { contentDescription = searchLabel },
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(searchLabel, color = T.Text3, fontSize = 13.sp)
                    inner()
                }
            },
        )
    }
    if (entries.isEmpty() && presets.isEmpty()) InventoryNote(inventoryText("暂无插件。", "No plugins are available."))
    if (searching && globalRows.isEmpty() && selectedRows.none { inventoryMatches(it, query, english) } && otherMatches.isEmpty()) {
        InventoryNote(inventoryText("没有匹配的插件。", "No matching plugins."))
    }
    if (selected != null) {
        val rows = selectedRows.filter { inventoryMatches(it, query, english) }
        InventoryGroup(
            inventoryText("会话插件", "Session plugins"),
            inventoryText("由 Agent 预设按会话组成", "Composed per session by agent presets"),
            rows.size, presetOpen || searching, { presetOpen = !presetOpen },
        ) {
            fun label(preset: JsonElement): String = presetName(preset) + when {
                preset["broken"].str() != null -> inventoryText("（加载失败）", " (failed to load)")
                preset["isDefault"].bool() == true -> inventoryText("（默认）", " (default)")
                else -> ""
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(
                    Modifier.widthIn(max = 240.dp).height(36.dp).clip(T.R12).background(T.Fill2)
                        .clickable { choosePreset = !choosePreset }
                        .testTag("plugins.inventory.preset")
                        .semantics { contentDescription = inventoryText("选择要查看的 Agent 预设", "Choose the agent preset to inspect") }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(label(selected), color = T.Ink, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(D.chevronDown, null, Modifier.size(14.dp), tint = T.Ink)
                }
                DropdownMenu(choosePreset, { choosePreset = false }, containerColor = T.Surface, shape = T.R12) {
                    presets.forEach { preset ->
                        val chosen = preset["id"].str() == selected["id"].str()
                        DropdownMenuItem(
                            text = { Text(label(preset), color = T.Ink, fontSize = 14.sp) },
                            trailingIcon = { if (chosen) Icon(D.check, null, Modifier.size(14.dp), tint = T.Ink) },
                            onClick = { selectedId = preset["id"].str(); choosePreset = false },
                        )
                    }
                }
            }
        }
        if (presetOpen || searching) {
            selected["broken"].str()?.let { InventoryNote(it, true) }
            selectedRows.forEachIndexed { index, row ->
                if (inventoryMatches(row, query, english)) {
                    val rowKey = "preset:${selected["id"].str()}:$index"
                    InventoryRow(row, english, expanded == rowKey,
                        { expanded = if (expanded == rowKey) null else rowKey }, preset = selected)
                }
            }
            if (otherMatches.isNotEmpty()) {
                InventoryNote(inventoryText("其他预设中也有匹配的插件：", "Matching plugins in other presets:"))
                otherMatches.forEach { preset -> TextButton({ selectedId = preset["id"].str() }, Modifier.padding(horizontal = 18.dp)) { Text(presetName(preset)) } }
            }
        }
    }
    if (entries.isNotEmpty()) {
        InventoryGroup(inventoryText("全局插件", "Global plugins"), inventoryText("系统与所有会话共用", "Shared by the system and every session"), globalRows.size,
            globalOpen || searching, { globalOpen = !globalOpen })
        if (globalOpen || searching) globalRows.forEach { row ->
            val rowKey = "global:${row["entryId"].str()}"
            InventoryRow(row, english, expanded == rowKey,
                { expanded = if (expanded == rowKey) null else rowKey },
                providers = inventoryProviders(row, presets), onPreset = { selectedId = it["id"].str(); presetOpen = true })
        }
    }
}

@Composable
private fun InventoryGroup(
    title: String, subtitle: String, count: Int, open: Boolean, onToggle: () -> Unit,
    action: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clickable(onClick = onToggle).heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(D.chevronDown, if (open) inventoryText("收起", "Collapse") else inventoryText("展开", "Expand"),
                    Modifier.size(12.dp).rotate(if (open) 0f else -90f), tint = T.Ink)
                Text(title, color = T.Ink, fontSize = 14.sp, fontWeight = FontWeight.Normal)
            }
            action()
        }
        Text("$subtitle · $count ${inventoryText("个", "plugins")}", Modifier.padding(start = 20.dp),
            fontSize = 12.sp, lineHeight = 18.sp, color = T.Text3)
    }
}

@Composable
private fun InventoryNote(text: String, failed: Boolean = false) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 13.sp,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InventoryRow(
    row: JsonElement, english: Boolean, expanded: Boolean, onToggle: () -> Unit,
    preset: JsonElement? = null, providers: List<JsonElement> = emptyList(), onPreset: (JsonElement) -> Unit = {},
) {
    val state = inventoryEnablement(row, providers.isNotEmpty())
    val title = inventoryTitle(row, english)
    SettingsCard {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, Modifier.weight(1f), color = T.Ink, fontWeight = FontWeight.Medium,
                        fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row["enabled"].bool() == true) when (row["fiberPhase"].str()) {
                        "pending" -> Box(Modifier.size(5.dp).background(T.Text3, androidx.compose.foundation.shape.CircleShape))
                        "loading", "unloading" -> CircularProgressIndicator(Modifier.size(8.dp), color = T.Text3, strokeWidth = 1.dp)
                    }
                    if (state != "enabled") {
                        val color = when (state) {
                            "failed" -> T.Err
                            "conditional" -> T.WarnText
                            "preset" -> T.Accent
                            else -> T.Text2
                        }
                        Text(inventoryState(state), color = color, fontSize = 11.sp,
                            modifier = Modifier.background(if (state == "disabled") T.Fill2 else color.copy(alpha = 0.08f), T.R4)
                                .padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Icon(D.chevronDown, null, Modifier.size(12.dp).rotate(if (expanded) 180f else 0f), tint = T.Text3)
                }
                inventoryLocalized(row["meta"]["description"], english)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = T.Text3,
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                }
                row["entryId"].str()?.removePrefix("include:")?.takeIf { it != title }?.let {
                    Box(Modifier.padding(top = 6.dp)) {
                        Text(it, style = T.Mono.copy(fontSize = 12.sp, lineHeight = 18.sp, color = T.Text2),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.background(T.Fill2, T.R4).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
            }
            row["meta"]["error"].str()?.let { InventoryNote(inventoryText("包元信息错误：", "Package metadata error: ") + it, true) }
            if (expanded) {
                HorizontalDivider(color = T.Border, thickness = 0.5.dp)
                SelectionContainer {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        row["entryId"].str()?.let { Text(it, style = T.Mono.copy(fontSize = 12.sp, lineHeight = 18.sp)) }
                        InventoryFact(inventoryText("完整名称", "Module"), row["moduleName"].str().orEmpty())
                        if (preset != null) InventoryFact(inventoryText("来自", "From"), presetName(preset))
                        InventoryFact(inventoryText("配置状态", "Configuration"), if (providers.isEmpty()) inventoryState(state)
                            else inventoryText("全局已停用，由 Agent 预设按会话提供", "Disabled globally; agent presets provide it per session"))
                        val showRuntime = if (preset != null) row["fiberPhase"].str() != null else providers.isEmpty() && row["enabled"].bool() == true
                        if (showRuntime) InventoryFact(inventoryText("运行状态", "Status"), inventoryPhase(row))
                        row["condition"].str()?.let { InventoryFact(inventoryText("禁用条件", "Disabled when"), it) }
                        if (providers.isNotEmpty()) {
                            InventoryFact(inventoryText("启用于", "Enabled in"), providers.joinToString(" · ") { presetName(it) })
                        }
                    }
                }
                if (providers.isNotEmpty()) TextButton({ onPreset(providers.first()) }, Modifier.padding(horizontal = 8.dp)) {
                    Text(inventoryText("去预设分组查看", "View in the preset group"))
                }
            }
        }
    }
}

@Composable
private fun InventoryFact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(76.dp), color = T.Text3, fontSize = 12.sp, lineHeight = 18.sp)
        Text(value, Modifier.weight(1f), color = T.Ink, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
