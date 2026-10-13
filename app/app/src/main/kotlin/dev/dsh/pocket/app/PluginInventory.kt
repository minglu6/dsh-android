package dev.dsh.pocket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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

@Composable
internal fun BuiltInPluginsScreen(onBack: () -> Unit) {
    val profile by Pocket.active.collectAsState()
    key(profile) {
        val status by Pocket.status.collectAsState()
        val ops by Pocket.ops.collectAsState()
        val revision by Pocket.pluginCatalogRevision.collectAsState()
        val online = profile != null && status[profile] == Dot.Online
        val scope = rememberCoroutineScope()
        var snapshot by remember { mutableStateOf<JsonElement?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        suspend fun refresh() {
            if (!online || loading || "plugins.inventory" !in ops) return
            loading = true
            error = null
            try {
                snapshot = checkNotNull(Pocket.clientOrNull()).pluginsInventory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: inventoryText("读取插件列表失败", "Could not load plugin inventory")
            } finally {
                loading = false
            }
        }
        LaunchedEffect(online, ops, revision) { refresh() }
        SettingsScaffold(inventoryText("内置插件", "Built-in plugins"), onBack, actions = {
            IconButton({ scope.launch { refresh() } }, enabled = online && !loading && "plugins.inventory" in ops) {
                Icon(D.refresh, inventoryText("刷新", "Refresh"))
            }
        }) {
            InventoryNote(inventoryText("查看内置部署的插件列表", "View plugins in the built-in deployment"))
            if (!online) InventoryNote(inventoryText("主机已断开连接", "Host disconnected"), true)
            error?.let { InventoryNote(it, true) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
            snapshot?.let { PluginInventoryContent(it) }
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
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var choosePreset by remember { mutableStateOf(false) }
    val presets = snapshot["agentPresets"].arr().orEmpty()
    val entries = snapshot["entries"].arr().orEmpty()
    val selected = inventorySelectedPreset(presets, selectedId)
    val searching = query.isNotBlank()
    val globalRows = entries.filter { inventoryMatches(it, query, english) }
        .sortedBy { it["fiberPhase"].str() != "failed" }
    val selectedRows = selected["rows"].arr().orEmpty()
    val otherMatches = if (searching) presets.filter { it != selected && it["rows"].arr().orEmpty().any { row -> inventoryMatches(row, query, english) } } else emptyList()

    OutlinedTextField(query, { query = it }, label = { Text(inventoryText("搜索插件", "Search plugins")) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("plugins.inventory.search"))
    if (entries.isEmpty() && presets.isEmpty()) InventoryNote(inventoryText("暂无插件。", "No plugins are available."))
    if (searching && globalRows.isEmpty() && selectedRows.none { inventoryMatches(it, query, english) } && otherMatches.isEmpty()) {
        InventoryNote(inventoryText("没有匹配的插件。", "No matching plugins."))
    }
    if (selected != null) {
        val rows = selectedRows.filter { inventoryMatches(it, query, english) }
        InventoryGroup(inventoryText("会话插件", "Session plugins"), inventoryText("由 Agent 预设按会话组成", "Composed per session by agent presets"), rows.size,
            presetOpen || searching, { presetOpen = !presetOpen })
        Box(Modifier.padding(horizontal = 18.dp)) {
            TextButton({ choosePreset = true }, Modifier.testTag("plugins.inventory.preset")) {
                val suffix = when {
                    selected["broken"].str() != null -> inventoryText("（加载失败）", " (failed to load)")
                    selected["isDefault"].bool() == true -> inventoryText("（默认）", " (default)")
                    else -> ""
                }
                Text(presetName(selected) + suffix)
                Icon(D.chevronDown, inventoryText("选择要查看的 Agent 预设", "Choose the agent preset to inspect"), Modifier.padding(start = 6.dp).size(16.dp))
            }
            DropdownMenu(choosePreset, { choosePreset = false }) {
                presets.forEach { preset -> DropdownMenuItem(text = { Text(presetName(preset)) }, onClick = {
                    selectedId = preset["id"].str(); choosePreset = false; expanded = null
                }) }
            }
        }
        if (presetOpen || searching) {
            selected["broken"].str()?.let { InventoryNote(it, true) }
            selectedRows.forEachIndexed { index, row ->
                if (inventoryMatches(row, query, english)) {
                    val key = "preset:${selected["id"].str()}:$index"
                    InventoryRow(row, english, expanded == key, { expanded = if (expanded == key) null else key }, preset = selected)
                }
            }
            if (otherMatches.isNotEmpty()) {
                InventoryNote(inventoryText("其他预设中也有匹配的插件：", "Matching plugins in other presets:"))
                otherMatches.forEach { preset -> TextButton({ selectedId = preset["id"].str(); expanded = null }, Modifier.padding(horizontal = 18.dp)) { Text(presetName(preset)) } }
            }
        }
    }
    if (entries.isNotEmpty()) {
        InventoryGroup(inventoryText("全局插件", "Global plugins"), inventoryText("系统与所有会话共用", "Shared by the system and every session"), globalRows.size,
            globalOpen || searching, { globalOpen = !globalOpen })
        if (globalOpen || searching) globalRows.forEach { row ->
            val key = "global:${row["entryId"].str()}"
            InventoryRow(row, english, expanded == key, { expanded = if (expanded == key) null else key },
                providers = inventoryProviders(row, presets), onPreset = { selectedId = it["id"].str(); presetOpen = true; expanded = null })
        }
    }
}

@Composable
private fun InventoryGroup(title: String, subtitle: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) {
        Row {
            Text("$title · $count", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Icon(if (open) D.chevronDown else D.chevronRight, if (open) inventoryText("收起", "Collapse") else inventoryText("展开", "Expand"), Modifier.size(18.dp))
        }
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InventoryNote(text: String, failed: Boolean = false) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 13.sp,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InventoryRow(row: JsonElement, english: Boolean, expanded: Boolean, onToggle: () -> Unit,
    preset: JsonElement? = null, providers: List<JsonElement> = emptyList(), onPreset: (JsonElement) -> Unit = {}) {
    val state = inventoryEnablement(row, providers.isNotEmpty())
    SettingsCard {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(inventoryTitle(row, english), fontWeight = FontWeight.Medium)
                Text(inventoryState(state) + if (row["enabled"].bool() == true) " · " + inventoryPhase(row) else "", fontSize = 12.sp,
                    color = if (state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                inventoryLocalized(row["meta"]["description"], english)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                row["entryId"].str()?.removePrefix("include:")?.takeIf { it != inventoryTitle(row, english) }?.let { Text(it, style = T.Mono, fontSize = 11.sp) }
                Text(if (expanded) inventoryText("收起详情", "Hide details") else inventoryText("查看详情", "View details"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            row["meta"]["error"].str()?.let { InventoryNote(inventoryText("包元信息错误：", "Package metadata error: ") + it, true) }
            if (expanded) {
                HorizontalDivider()
                SelectionContainer {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        row["entryId"].str()?.let { Text(it, style = T.Mono, fontSize = 12.sp) }
                        Text(inventoryText("完整名称：", "Module: ") + row["moduleName"].str().orEmpty(), fontSize = 13.sp)
                        Text(inventoryText("配置状态：", "Configuration: ") + if (providers.isEmpty()) inventoryState(state) else inventoryText("全局已停用，由 Agent 预设按会话提供", "Disabled globally; agent presets provide it per session"), fontSize = 13.sp)
                        if (preset != null) Text(inventoryText("来自：", "From: ") + presetName(preset), fontSize = 13.sp)
                        row["condition"].str()?.let { Text(inventoryText("禁用条件：", "Disabled when: ") + it, style = T.Mono, fontSize = 12.sp) }
                        val showRuntime = if (preset != null) row["fiberPhase"].str() != null else providers.isEmpty() && row["enabled"].bool() == true
                        if (showRuntime) Text(inventoryText("运行状态：", "Status: ") + inventoryPhase(row), fontSize = 13.sp)
                        if (providers.isNotEmpty()) Text(inventoryText("启用于：", "Enabled in: ") + providers.joinToString(" · ") { presetName(it) }, fontSize = 13.sp)
                    }
                }
                if (providers.isNotEmpty()) TextButton({ onPreset(providers.first()) }, Modifier.padding(horizontal = 8.dp)) { Text(inventoryText("去预设分组查看", "View in the preset group")) }
            }
        }
    }
}
