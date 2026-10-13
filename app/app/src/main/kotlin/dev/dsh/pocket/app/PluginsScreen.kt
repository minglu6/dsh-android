package dev.dsh.pocket.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.DialogProperties
import dev.dsh.pocket.protocol.PocketClient
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

private fun pt(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh
private val pluginJson = Json { prettyPrint = true }
private fun pretty(value: JsonElement?) = value?.let { pluginJson.encodeToString(JsonElement.serializer(), it) }.orEmpty()
private fun localized(value: JsonElement?): String? = value.str() ?: value[if (Pocket.prefs.language == AppLanguage.En) "en" else "zh"].str() ?: value["en"].str()
private fun pluginTitle(value: JsonElement) = localized(value["meta"]["title"]) ?: value["name"].str() ?: value["moduleName"].str().orEmpty()
private fun pluginDescription(value: JsonElement) = localized(value["meta"]["description"]) ?: value["description"].str().orEmpty()
private fun configurationTitle(ns: String, plugins: List<JsonElement>): String {
    val runtime = plugins.firstOrNull { it["patchId"].str() == ns }
    return when (ns) {
        "shell", "bash-sandbox", "pwsh-sandbox" -> pt("终端", "Terminal")
        "agent-loop" -> pt("Agent 循环", "Agent loop")
        "subagent" -> pt("子智能体", "Subagents")
        "web-search", "web-search-deepseek" -> pt("网页搜索", "Web search")
        else -> localized(runtime["meta"]["title"]) ?: ns
    }
}
private val officialConfigurations = listOf("subagent", "web-search-deepseek", "agent-loop", "bash-sandbox", "pwsh-sandbox")
private val primaryOptionalBundles = listOf(
    "@deepseek-ai/dsh-experimental-agent-team-profile",
    "@deepseek-ai/dsh-experimental-voice-input-bundle",
    "@deepseek-ai/dsh-experimental-auto-review",
    "@deepseek-ai/dsh-experimental-inspector-profile",
)
private fun configurationDescription(ns: String) = when (ns) {
    "subagent" -> pt("设置子智能体的递归层级、数量和模型。", "Set Subagent recursion depth, count, and models.")
    "web-search-deepseek" -> pt("设置 DeepSeek 的搜索提供方。", "Set up the DeepSeek search provider.")
    "agent-loop" -> pt("控制 Agent 派发工具调用的方式。", "Control how the Agent dispatches tool calls.")
    "bash-sandbox", "pwsh-sandbox" -> pt("限制每条命令最多能跑多久、最多输出多少内容。", "Limit how long each command may run and how much it may output.")
    else -> pt("插件高级配置", "Advanced plugin configuration")
}
private fun configurationArtwork(ns: String): String = when (ns) {
    "subagent" -> "subagent"
    "web-search-deepseek" -> "search"
    "agent-loop" -> "loop"
    "bash-sandbox", "pwsh-sandbox" -> "terminal"
    else -> "default"
}
private val hiddenProfileBundles = setOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "@deepseek-ai/dsh-headless", "@deepseek-ai/dsh-sdk-app", "@deepseek-ai/dsh-acp-app", "@deepseek-ai/dsh-sdk-minimal")
private fun readOnlyText(reason: String?) = when (reason) {
    "management-required" -> pt("管理界面依赖此组件，不能关闭或卸载。", "Required by plugin management; cannot disable or remove.")
    "unaddressable" -> pt("此组件不能通过当前 profile 修改。", "This component cannot be changed through this profile.")
    null -> ""
    else -> reason
}
private fun phaseText(row: JsonElement?) = if (row["enabled"].bool() != true) pt("已停用", "Disabled") else when (row["fiberPhase"].str()) {
    "active" -> pt("运行中", "Running")
    "pending" -> pt("等待依赖", "Waiting for dependencies")
    "loading" -> pt("加载中", "Loading")
    "unloading" -> pt("卸载中", "Unloading")
    "failed" -> pt("异常", "Failed")
    else -> pt("未运行", "Not running")
}
private fun changeText(result: JsonElement): String = when (result["application"].str()) {
    "applied" -> pt("更改已生效", "Change applied")
    "restart-required" -> pt("更改已保存，将在主机下次启动生效", "Saved; takes effect after the host restarts")
    "overridden" -> pt("更改已保存，但被更高优先级配置覆盖，当前未生效", "Saved, but overridden by higher-priority configuration")
    "cancelled" -> pt("已取消安装", "Installation cancelled")
    "failed" -> pt("操作失败", "Operation failed")
    else -> pt("未收到明确的应用结果，请刷新核对", "Application outcome unknown; refresh to check")
}

/** Bundle declarations are not runtime state: only the entryId join can authorize a row switch. */
internal fun pluginLiveRow(row: JsonElement, plugins: List<JsonElement>): JsonElement? {
    val id = row["entryId"].str() ?: return null
    return plugins.firstOrNull { it["entryId"].str() == id }
}

@Composable
fun PluginsSettingsScreen(onBack: () -> Unit) {
    val profile by Pocket.active.collectAsState()
    key(profile) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = T.PrimaryButton, onPrimary = T.OnPrimary)) {
            PluginsPage(profile, onBack)
        }
    }
}

@Composable
private fun PluginsPage(profile: String?, onBack: () -> Unit) {
    val ops by Pocket.ops.collectAsState()
    val status by Pocket.status.collectAsState()
    val online = profile != null && status[profile] == Dot.Online
    val scope = rememberCoroutineScope()
    var bundles by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var plugins by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var namespaces by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var writable by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var advancedConfigurations by remember { mutableStateOf(false) }
    var moreOptional by remember { mutableStateOf(false) }
    var configError by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<JsonElement?>(null) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<String?>(null) }
    var config by remember { mutableStateOf<JsonElement?>(null) }
    var uninstall by remember { mutableStateOf<JsonElement?>(null) }
    var installOpen by remember { mutableStateOf(false) }
    var installSpec by rememberSaveable(profile) { mutableStateOf("") }
    var registry by rememberSaveable(profile) { mutableStateOf("") }
    var registries by remember { mutableStateOf<List<String>>(emptyList()) }
    var inspection by remember { mutableStateOf<JsonElement?>(null) }
    var installResult by remember { mutableStateOf<JsonElement?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var installRequest by rememberSaveable(profile) { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var catalogSelection by remember { mutableStateOf<Boolean?>(null) }
    var approval by remember { mutableStateOf(false) }
    val locked = busy || loading || installing || checking || installRequest != null
    val catalogUpdate = catalogSelection != null && inspection["problem"].str() == "already-installed" &&
        bundles.any { it["official"].bool() == true && it["installed"].bool() == true && it["availability"].str() != "installation" && it["installTarget"]["spec"].str() == installSpec.trim() }

    fun client(): PocketClient {
        check(Pocket.active.value == profile && profile != null && Pocket.status.value[profile] == Dot.Online) { pt("主机已断开连接", "Host disconnected") }
        return checkNotNull(Pocket.clientOrNull()) { pt("主机已断开连接", "Host disconnected") }
    }
    suspend fun refresh() {
        loading = true
        error = null
        try {
            check("plugins.list" in ops) { pt("本部署没有可管理的 profile", "This deployment has no manageable profile") }
            val c = client()
            val data = c.pluginsList()
            check(data["managementAvailable"].bool() != false) { pt("插件管理不可用", "Plugin management unavailable") }
            bundles = data["bundles"].arr().orEmpty().filter { it["name"].str() !in hiddenProfileBundles }
            plugins = data["plugins"].arr().orEmpty()
            if (detail != null && bundles.none { it["name"].str() == detail }) detail = null
            if ("plugins.settingsDescribe" in ops) {
                try {
                    val settings = c.pluginsSettingsDescribe()
                    namespaces = settings["namespaces"].arr().orEmpty()
                    writable = settings["writable"].bool() == true
                    configError = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { configError = e.message; writable = false }
            } else { namespaces = emptyList(); writable = false }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: pt("读取插件失败", "Could not load plugins") }
        finally { loading = false }
    }
    fun reload() {
        if (busy || loading || installing || checking || installRequest != null) return
        loading = true
        scope.launch { refresh() }
    }
    fun mutate(block: suspend (PocketClient) -> JsonElement) {
        if (busy || loading || installing || checking || installRequest != null || !online) return
        busy = true
        error = null
        scope.launch {
            try { outcome = block(client()); refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: pt("操作失败", "Operation failed") }
            finally { busy = false }
        }
    }
    fun openInstall(spec: String = "", selection: Boolean? = null) {
        if (installRequest == null && !installing && spec.isNotEmpty()) {
            installSpec = spec; inspection = null; installResult = null; installError = null; catalogSelection = selection
        }
        installOpen = true
        if ("plugins.registries" in ops && online) scope.launch {
            try {
                val r = client().pluginsRegistries()
                registries = (listOfNotNull(r["registry"].str()) + r["fallbackRegistries"].arr().orEmpty().mapNotNull { it.str() } + listOfNotNull(r["resolved"].str())).distinct()
                if (registry.isBlank()) registry = r["registry"].str().orEmpty()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { installError = e.message }
        }
    }
    fun runInstall(approved: List<String> = emptyList()) {
        if (busy || loading || installing || checking || installRequest != null || !online || (inspection?.get("status").str() != "accepted" && !catalogUpdate)) return
        val request = UUID.randomUUID().toString()
        installRequest = request; installing = true; installError = null; installResult = null
        val spec = installSpec.trim()
        val options = buildJsonObject {
            put("requestId", request)
            put("enabled", catalogSelection ?: false)
            if (catalogSelection != null && !spec.startsWith("link:")) put("saveExact", true)
            if (registry.isNotBlank()) put("registry", registry.trim())
            if (approved.isNotEmpty()) put("approvedBuilds", JsonArray(approved.map(::JsonPrimitive)))
        }
        scope.launch {
            try {
                installResult = client().pluginsInstall(spec, options)
                installRequest = null
                approval = false
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { installError = pt("未确认安装结果，请核对安装状态，勿重复提交。", "Installation outcome unconfirmed. Check its status before retrying.") + "\n" + e.message }
            finally { installing = false }
        }
    }
    LaunchedEffect(profile, online, ops) { if (online && !busy && !installing) refresh() }
    fun back() {
        when {
            config != null -> config = null
            detail != null -> detail = null
            installRequest != null || installing -> installOpen = true
            else -> onBack()
        }
    }
    BackHandler { back() }

    if (config != null) {
        val ns = config!!["ns"].str().orEmpty()
        key(ns) {
            PluginConfiguration(config!!, online && writable && "plugins.settingsMutate" in ops, { config = null },
                refresh = {
                    val r = client().pluginsSettingsDescribe()
                    writable = r["writable"].bool() == true
                    r["namespaces"].arr().orEmpty().firstOrNull { it["ns"].str() == ns }
                        ?: error(pt("该配置已不再可用，请返回插件列表", "This configuration is no longer available"))
                },
                save = { operations, revision -> client().pluginsSettingsMutate(ns, operations, revision) })
        }
    } else {
        val selected = bundles.firstOrNull { it["name"].str() == detail }
        SettingsScaffold(selected?.let(::pluginTitle) ?: pt("插件", "Plugins"), ::back, actions = {
            TextButton(::reload, enabled = online && !locked) { Text(pt("刷新", "Refresh"), fontSize = 13.sp) }
            if (selected == null || installRequest != null) {
                Button({ openInstall() }, enabled = online && (installRequest != null || !busy),
                    shape = T.R8, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(if (installRequest != null) pt("安装任务", "Installation") else pt("添加插件", "Add plugin"), fontSize = 13.sp)
                }
            }
        }) {
            if (!online) PluginMessage(pt("主机已断开连接；草稿已保留，连接恢复后可重试。", "Host disconnected. Drafts are retained; retry after reconnecting."), true)
            error?.let { PluginMessage(it, true) }
            outcome?.let { PluginOutcome(it) }
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
            if (selected == null) {
                PluginMessage(pt("安装、启用和配置插件", "Install, enable and configure plugins"))
                OutlinedTextField(query, { query = it }, label = { Text(pt("搜索插件或配置", "Search plugins or configuration")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                val matchingNamespaces = namespaces.filter { (it["ns"].str().orEmpty() + " " + configurationTitle(it["ns"].str().orEmpty(), plugins)).contains(query, true) }
                PluginSection(pt("内置插件配置", "Official configuration"))
                configError?.let { PluginMessage(it, true) }
                matchingNamespaces.filter { it["ns"].str() in officialConfigurations }
                    .sortedBy { officialConfigurations.indexOf(it["ns"].str()) }.forEach { ns ->
                        val nsId = ns["ns"].str().orEmpty()
                        PluginCard(configurationTitle(nsId, plugins), configurationDescription(nsId), "", { config = ns }, artwork = configurationArtwork(nsId))
                    }
                val extra = matchingNamespaces.filter { it["ns"].str() !in officialConfigurations }
                if (extra.isNotEmpty()) {
                    TextButton({ advancedConfigurations = !advancedConfigurations }, modifier = Modifier.padding(horizontal = 18.dp)) {
                        Text(if (advancedConfigurations) pt("收起其他配置", "Hide other configuration") else pt("其他配置 / 高级", "Other configuration / advanced"))
                    }
                    if (advancedConfigurations || query.isNotBlank()) extra.forEach { ns ->
                        val nsId = ns["ns"].str().orEmpty()
                        PluginCard(configurationTitle(nsId, plugins), pt("主机提供的配置参数", "Configuration exposed by the host"), "", { config = ns }, icon = D.settings)
                    }
                }
                val visible = bundles.filter {
                    (it["installed"].bool() == true || it["official"].bool() == true || it["error"] != null) &&
                        (pluginTitle(it) + " " + pluginDescription(it) + " " + it["name"].str()).contains(query, true)
                }
                listOf(true, false).forEach { official ->
                    Row(Modifier.fillMaxWidth().padding(end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { PluginSection(if (official) pt("实验性插件", "Experimental plugins") else pt("已安装插件", "Installed plugins")) }
                        if (official) TextButton({ moreOptional = !moreOptional }) { Text(if (moreOptional) pt("收起", "Less") else pt("更多", "More")) }
                    }
                    val group = visible.filter { (it["official"].bool() == true) == official }
                        .filter { !official || moreOptional || query.isNotBlank() || it["name"].str() in primaryOptionalBundles }
                        .sortedBy { primaryOptionalBundles.indexOf(it["name"].str()).let { rank -> if (rank < 0) Int.MAX_VALUE else rank } }
                    if (group.isEmpty()) PluginMessage(pt("没有匹配的插件", "No matching plugins"))
                    group.forEach { bundle ->
                        val name = bundle["name"].str().orEmpty()
                        val state = when {
                            bundle["error"] != null -> pt("异常", "Problem")
                            bundle["availability"].str() == "missing" -> if (bundle["installed"].bool() == true) pt("包不可用", "Package unavailable") else pt("未安装", "Not installed")
                            else -> ""
                        }
                        PluginCard(pluginTitle(bundle), pluginDescription(bundle), state, { detail = name }, bundle["version"].str(),
                            enabled = bundle["enabled"].bool() == true,
                            switchAvailable = online && !locked && bundle["readOnlyReason"].str() == null && "plugins.setEnabled" in ops &&
                                (bundle["availability"].str() != "missing" || bundle["installTarget"]["spec"].str() != null),
                            onToggle = { enabled ->
                                if (enabled && bundle["availability"].str() == "missing") {
                                    bundle["installTarget"]["spec"].str()?.let { openInstall(it, true) }
                                } else mutate { it.pluginsSetEnabled(enabled, bundle = name) }
                            },
                            metadataIcon = bundle["meta"]["icon"].str())
                    }
                }
            } else {
                val name = selected["name"].str().orEmpty()
                val reason = selected["readOnlyReason"].str()
                SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(pluginDescription(selected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer { Text(name, style = T.Mono, fontSize = 12.sp) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pt("启用插件", "Enable plugin"), Modifier.weight(1f))
                            Switch(selected["enabled"].bool() == true, { enabled -> mutate { it.pluginsSetEnabled(enabled, bundle = name) } }, enabled = online && !locked && reason == null && selected["availability"].str() != "missing" && "plugins.setEnabled" in ops)
                        }
                        if (reason != null) Text(readOnlyText(reason), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        selected["error"]?.let { SelectionContainer { Text(pretty(it), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) } }
                        Text(pt("来源：", "Source: ") + (selected["source"].str() ?: if (selected["availability"].str() == "installation") pt("内置", "Built-in") else name), fontSize = 12.sp)
                        selected["version"].str()?.let { Text(pt("当前版本：", "Version: ") + it, fontSize = 12.sp) }
                        val target = selected["installTarget"]
                        if (target["spec"].str() != null && (selected["availability"].str() == "missing" || target["version"].str() != selected["version"].str())) {
                            Button({ openInstall(target["spec"].str()!!, selected["enabled"].bool() == true || selected["availability"].str() == "missing") }, enabled = online && !locked) {
                                Text(pt("安装 / 更新至 ", "Install / update to ") + target["version"].str().orEmpty())
                            }
                        }
                        if (selected["removable"].bool() == true && selected["optional"].bool() != true && reason == null && selected["availability"].str() != "installation") {
                            TextButton({ uninstall = selected }, enabled = online && !locked && "plugins.uninstall" in ops) { Text(pt("卸载", "Uninstall"), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                PluginSection(pt("包含的组件", "Components"))
                val rows = selected["rows"].arr().orEmpty()
                if (rows.isEmpty()) PluginMessage(pt("这个插件包不包含任何组件。", "This bundle contains no components."))
                rows.forEach { row ->
                    val live = pluginLiveRow(row, plugins)
                    val ns = namespaces.firstOrNull { it["ns"].str() == row["rowId"].str() }
                    SettingsCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(pluginTitle(row), fontWeight = FontWeight.Medium)
                                    Text(phaseText(live), fontSize = 12.sp, color = if (live["fiberPhase"].str() == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(live["enabled"].bool() == true, { enabled -> mutate { it.pluginsSetEnabled(enabled, id = row["entryId"].str()) } }, enabled = online && !locked && live != null && live["readOnlyReason"].str() == null && "plugins.setEnabled" in ops)
                            }
                            if (pluginDescription(row).isNotBlank()) Text(pluginDescription(row), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            live["readOnlyReason"].str()?.let { Text(readOnlyText(it), fontSize = 12.sp) }
                            if (ns != null) TextButton({ config = ns }) { Text(pt("配置组件", "Configure component")) }
                        }
                    }
                }
                PluginMessage(pt("专属 React 插件界面无法在原生 Android 中执行；可用配置通过主机 schema 编辑。", "Plugin-specific React interfaces cannot run in native Android; exposed settings use the host schema editor."))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    uninstall?.let { bundle ->
        AlertDialog(onDismissRequest = { if (!busy) uninstall = null }, title = { Text(pt("卸载插件？", "Uninstall plugin?")) }, text = { Text(pluginTitle(bundle) + "\n" + pt("将从当前主机 profile 移除，插件功能将不可用。", "Removes this package from the host profile. Its functionality will no longer be available.")) },
            confirmButton = { TextButton({ uninstall = null; mutate { it.pluginsUninstall(bundle["name"].str().orEmpty()) } }, enabled = online && !locked) { Text(pt("确认卸载", "Uninstall")) } },
            dismissButton = { TextButton({ uninstall = null }) { Text(pt("取消", "Cancel")) } })
    }
    if (installOpen) PluginInstallDialog(
        spec = installSpec, onSpec = { installSpec = it; inspection = null; installResult = null; catalogSelection = null; approval = false },
        registry = registry, onRegistry = { registry = it; inspection = null; installResult = null; approval = false }, registries = registries,
        inspection = inspection, result = installResult, error = installError, catalogUpdate = catalogUpdate,
        busy = installing || checking, unresolved = installRequest != null, online = online && !busy && !loading,
        approval = approval, onApproval = { approval = it }, cancelling = cancelling,
        onClose = { installOpen = false },
        onInspect = {
            if (!busy && !loading && !installing && !checking && installRequest == null && online && installSpec.isNotBlank()) {
                checking = true; installError = null; inspection = null; installResult = null
                scope.launch {
                    try {
                        check(registry.isBlank() || Regex("https?://[^\\s]+").matches(registry.trim())) { pt("安装源必须是 http(s) 地址", "Registry must be an http(s) URL") }
                        inspection = client().pluginsInspect(installSpec.trim(), registry.trim().ifBlank { null })
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { installError = e.message }
                    finally { checking = false }
                }
            }
        },
        onInstall = { runInstall() },
        onApprove = { runInstall(installResult?.get("pendingBuilds").arr().orEmpty().mapNotNull { it.str() }) },
        onCancel = {
            val request = installRequest
            if (request != null && !cancelling && "plugins.cancelInstall" in ops) {
                cancelling = true
                scope.launch {
                    try {
                        val r = client().pluginsCancelInstall(request)
                        installError = when (r["status"].str()) {
                            "cancelled" -> pt("取消已确认，正在等待安装最终结果。", "Cancellation confirmed; awaiting the final installation result.")
                            "too-late" -> pt("安装已进入收尾阶段，无法取消，请等待结果。", "Installation is applying and cannot be cancelled. Awaiting its result.")
                            else -> pt("主机未找到运行中的任务，请核对安装状态。", "Host has no running task; reconcile installation status.")
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { installError = e.message }
                    finally { cancelling = false }
                }
            }
        },
        canCancel = "plugins.cancelInstall" in ops,
        onReconcile = {
            val request = installRequest
            if (request != null && !installing && !checking) {
                checking = true
                scope.launch {
                    try {
                        val r = client().pluginsWaitForInstall(request)
                        if (r is JsonNull) installError = pt("主机没有此任务。请查看刷新后的插件列表，再重新检查安装。", "Host has no such task. Inspect the refreshed plugin list before trying again.")
                        else { installResult = r; installError = null }
                        installRequest = null; refresh()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { installError = e.message }
                    finally { checking = false }
                }
            }
        },
        onEnable = {
            val bundle = installResult?.get("bundle").str()
            if (bundle != null) mutate { c ->
                c.pluginsSetEnabled(true, bundle = bundle).also { changed ->
                    installResult = JsonObject((installResult as? JsonObject).orEmpty() + (changed as? JsonObject).orEmpty())
                }
            }
        },
    )
}

@Composable
private fun PluginSection(title: String) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp)) }

@Composable
private fun PluginMessage(message: String, failure: Boolean = false) {
    SelectionContainer { Text(message, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 13.sp, color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun PluginCard(
    title: String, description: String, status: String, onClick: () -> Unit, version: String? = null,
    icon: ImageVector = D.pluginPinwheel, enabled: Boolean? = null, switchAvailable: Boolean = false,
    onToggle: (Boolean) -> Unit = {},
    artwork: String? = null,
    metadataIcon: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().clip(T.R12).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(T.R12).border(1.dp, cs.outlineVariant.copy(alpha = 0.65f), T.R12), contentAlignment = Alignment.Center) {
            when {
                artwork != null -> PluginArtwork(artwork, Modifier.size(36.dp))
                enabled != null -> PluginMetadataArtwork(metadataIcon, Modifier.size(36.dp))
                else -> Icon(icon, null, Modifier.size(23.dp), tint = cs.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (description.isNotBlank()) Text(description, fontSize = 12.sp, color = cs.onSurfaceVariant)
            if (status.isNotEmpty()) Text(status, fontSize = 11.sp, color = cs.onSurfaceVariant)
            if (version != null && status.isNotEmpty()) Text("v$version", fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
        if (enabled != null) Switch(enabled, onToggle, enabled = switchAvailable,
            colors = SwitchDefaults.colors(checkedTrackColor = T.PrimaryButton, checkedThumbColor = T.OnPrimary, uncheckedTrackColor = cs.surfaceVariant, uncheckedBorderColor = cs.surfaceVariant))
    }
}

@Composable
private fun PluginOutcome(result: JsonElement) {
    var expanded by remember(result) { mutableStateOf(false) }
    PluginMessage(changeText(result), result["application"].str() == "failed")
    result["error"]?.let { PluginMessage(pretty(it), true) }
    result["warnings"].arr()?.takeIf { it.isNotEmpty() }?.let { PluginMessage(it.joinToString("\n") { warning -> warning.str().orEmpty() }) }
    if (result["packageResult"] != null || result["pendingBuilds"] != null) {
        TextButton({ expanded = !expanded }, modifier = Modifier.padding(horizontal = 10.dp)) { Text(if (expanded) pt("收起安装详情", "Hide installation details") else pt("查看安装详情", "Show installation details")) }
        if (expanded) SelectionContainer { Text(pretty(result), modifier = Modifier.padding(18.dp), style = T.Mono, fontSize = 11.sp) }
    }
}

@Composable
private fun PluginInstallDialog(
    spec: String, onSpec: (String) -> Unit, registry: String, onRegistry: (String) -> Unit, registries: List<String>,
    inspection: JsonElement?, result: JsonElement?, error: String?, busy: Boolean, unresolved: Boolean, online: Boolean,
    approval: Boolean, onApproval: (Boolean) -> Unit, cancelling: Boolean, canCancel: Boolean,
    catalogUpdate: Boolean,
    onClose: () -> Unit, onInspect: () -> Unit, onInstall: () -> Unit, onApprove: () -> Unit,
    onCancel: () -> Unit, onReconcile: () -> Unit, onEnable: () -> Unit,
) {
    var registryOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 720.dp), shape = T.R16) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pt("添加插件", "Add plugin"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(pt("输入 npm 包名、Git 仓库、tarball 地址或主机目录路径。路径属于主机，不是手机。", "Enter an npm package, Git repository, tarball URL or host directory path. Paths refer to the host, not this phone."), fontSize = 13.sp)
                Text(pt("只安装可信插件：插件在主机以你的权限运行，可访问你的数据。", "Only install trusted plugins: they execute with your host permissions and can access your data."), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(spec, onSpec, enabled = !busy && !unresolved, label = { Text(pt("包名或地址", "Package or address")) }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3)
                TextButton({ registryOpen = !registryOpen }) { Text(pt("安装源", "Registry")) }
                if (registryOpen) {
                    (listOf("") + registries).distinct().forEach { url ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy && !unresolved) { onRegistry(url) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(registry == url, { onRegistry(url) }, enabled = !busy && !unresolved)
                            Text(url.ifBlank { pt("主机配置的默认安装源", "Host-configured default registry") }, fontSize = 12.sp)
                        }
                    }
                    OutlinedTextField(registry, onRegistry, enabled = !busy && !unresolved, label = { Text(pt("自定义 http(s) 地址", "Custom http(s) URL")) }, modifier = Modifier.fillMaxWidth())
                    Text(pt("私有源凭据请配置在主机 ~/.npmrc；Git/tarball 的源站不由 npm 镜像替代。", "Configure private registry credentials in host ~/.npmrc. npm mirrors do not replace Git/tarball hosts."), fontSize = 12.sp)
                }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(pt("正在处理，请等待主机结果…", "Working; waiting for host result…"), fontSize = 13.sp) }
                error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
                inspection?.let { r ->
                    if (r["status"].str() == "accepted") {
                        Text(listOfNotNull(r["name"].str(), r["version"].str(), r["kind"].str()).joinToString(" · "), fontWeight = FontWeight.Medium)
                        r["description"].str()?.let { Text(it, fontSize = 13.sp) }
                        if (r["bundle"] is JsonNull) Text(pt("需下载后才能确认包名和插件声明。", "Package identity and bundle declaration are checked after download."), fontSize = 12.sp)
                    } else if (catalogUpdate) Text(pt("该插件已安装。此操作将使用主机官方目录中的精确目标更新，并保持原启用状态。", "This plugin is installed. Update to the host's exact official catalog target while preserving its selection."), fontSize = 13.sp)
                    else SelectionContainer { Text(pretty(r), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
                result?.let { PluginOutcome(it) }
                val pending = result["pendingBuilds"].arr().orEmpty().mapNotNull { it.str() }
                if (pending.isNotEmpty()) {
                    Text(pt("下列依赖需要执行安装脚本：", "The following dependencies require installation scripts:") + "\n" + pending.joinToString("\n"), fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(approval, onApproval, enabled = !busy && !unresolved); Text(pt("允许这些包在主机执行脚本", "Allow these packages to run scripts on the host"), fontSize = 12.sp) }
                    Button(onApprove, enabled = approval && online && !busy && !unresolved) { Text(pt("允许并重试", "Approve and retry")) }
                }
                if (unresolved) {
                    if (canCancel) TextButton(onCancel, enabled = online && !cancelling) { Text(if (cancelling) pt("正在取消…", "Cancelling…") else pt("取消安装", "Cancel installation")) }
                    TextButton(onReconcile, enabled = online && !busy) { Text(pt("核对安装状态", "Reconcile installation")) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onInspect, enabled = online && !busy && spec.isNotBlank()) { Text(pt("检查", "Check")) }
                        Button(onInstall, enabled = online && !busy && (inspection?.get("status").str() == "accepted" || catalogUpdate) && result == null) { Text(if (catalogUpdate) pt("更新", "Update") else pt("安装", "Install")) }
                    }
                    if (result["bundle"].str() != null && result["application"].str() == "applied" && result["enabled"].bool() == false) Button(onEnable, enabled = online && !busy) { Text(pt("立即启用", "Enable now")) }
                }
                TextButton(onClose) { Text(if (unresolved) pt("收起（任务继续）", "Hide (task continues)") else pt("关闭", "Close")) }
            }
        }
    }
}

private fun settingsPath(text: String): List<String>? {
    return try {
        val value = Json.parseToJsonElement(text) as? JsonArray
        value?.map { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
    } catch (_: Exception) { null }
}

/** Prevent replacement of redacted parents, which would otherwise silently erase stored secrets. */
internal fun pluginSettingsReplacesSecret(path: List<String>, secrets: List<List<String>>): Boolean =
    secrets.any { secret -> path.size < secret.size && secret.take(path.size) == path }

internal data class PluginSchemaField(val path: List<String>, val type: String, val schema: JsonElement, val choices: List<JsonElement> = emptyList())

/** Schemastery serializes a reference graph, not an inline JSON-schema object. */
internal fun pluginSchemaFields(envelope: JsonElement?): List<PluginSchemaField> {
    val refs = envelope["refs"] as? JsonObject
    fun resolve(node: JsonElement?): JsonElement? {
        val id = (node as? JsonPrimitive)?.content ?: (node?.get("uid") as? JsonPrimitive)?.content
        return if (id != null && refs != null) refs[id] else node
    }
    val fields = mutableListOf<PluginSchemaField>()
    fun visit(raw: JsonElement?, path: List<String>, ancestors: Set<JsonElement>) {
        val node = resolve(raw) ?: return
        if (node in ancestors) return
        val type = node["type"].str().orEmpty()
        when (type) {
            "object" -> (node["dict"] as? JsonObject)?.forEach { (key, child) -> visit(child, path + key, ancestors + node) }
            "intersect" -> node["list"].arr().orEmpty().forEach { visit(it, path, ancestors + node) }
            "union" -> {
                val choices = node["list"].arr().orEmpty().mapNotNull { resolve(it) }
                    .filter { it["type"].str() == "const" }.mapNotNull { it["value"] }
                if (path.isNotEmpty() && choices.isNotEmpty()) fields += PluginSchemaField(path, "enum", node, choices)
            }
            "boolean", "number", "string" -> if (path.isNotEmpty()) fields += PluginSchemaField(path, type, node)
        }
    }
    visit(envelope, emptyList(), emptySet())
    return fields.distinctBy { it.path }
}

private fun configurationSavedText(snapshot: JsonElement) = if (snapshot["applies"].str() == "live")
    pt("配置已保存；主机声明此配置实时生效。", "Saved; the host declares this configuration live.")
else pt("配置已保存；生效时机由主机插件决定。", "Saved; application timing is controlled by the host plugin.")

private fun configurationFieldLabel(field: PluginSchemaField): String = when (field.path.last()) {
    "maxParallelToolCalls" -> pt("并行工具调用数", "Parallel tool calls")
    "maxDepth" -> pt("最大递归深度", "Maximum recursion depth")
    "maxActive", "maxActiveSubagents" -> pt("子智能体并行数量上限", "Subagent parallelism limit")
    "timeoutMs" -> pt("命令超时（毫秒）", "Command timeout (ms)")
    "maxOutputBytes" -> pt("单流输出上限（字节）", "Output cap per stream (bytes)")
    "baseUrl" -> pt("接口地址", "Endpoint")
    "maxUses" -> pt("单次请求最多搜索次数", "Max searches per request")
    "apiKeyEnv" -> pt("API Key 环境变量引用", "API key environment reference")
    else -> localized(field.schema["meta"]["description"]) ?: field.path.joinToString(" / ")
}

private fun settingsValue(root: JsonElement?, path: List<String>): JsonElement? = path.fold(root) { current, key -> current[key] }

@Composable
private fun PluginConfiguration(initial: JsonElement, writable: Boolean, onBack: () -> Unit, refresh: suspend () -> JsonElement, save: suspend (JsonArray, Int) -> JsonElement) {
    var snapshot by remember { mutableStateOf(initial) }
    var advanced by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf<Map<List<String>, String>>(emptyMap()) }
    var resets by remember { mutableStateOf<Set<List<String>>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fields = remember(snapshot["schema"]) { pluginSchemaFields(snapshot["schema"]) }
    val secretPaths = snapshot["secrets"].arr().orEmpty().map { it["path"].arr().orEmpty().mapNotNull { part -> part.str() } }
    val ns = snapshot["ns"].str().orEmpty()
    fun isSecret(field: PluginSchemaField) = field.path in secretPaths || field.schema["meta"]["role"].str() == "secret"
    fun draftValue(field: PluginSchemaField): String {
        if (field.path in resets) return ""
        drafts[field.path]?.let { return it }
        if (isSecret(field)) return ""
        val value = settingsValue(snapshot["value"], field.path) ?: field.schema["meta"]["default"]
        return if (field.type == "enum") value?.toString().orEmpty() else (value as? JsonPrimitive)?.content.orEmpty()
    }
    fun change(field: PluginSchemaField, value: String) {
        drafts = if (isSecret(field) && value.isEmpty()) drafts - listOf(field.path) else drafts + (field.path to value)
        resets = resets.filterNot { it == field.path }.toSet()
    }
    fun commit() {
        if (busy || !writable || (drafts.isEmpty() && resets.isEmpty())) return
        busy = true; message = null
        scope.launch {
            try {
                val revision = (snapshot["revision"] as? JsonPrimitive)?.intOrNull ?: error(pt("缺少修订号，请重新读取", "Missing revision; reload first"))
                val changes = fields.filter { it.path in drafts || it.path in resets }.map { field ->
                    val raw = drafts[field.path].orEmpty()
                    val unset = field.path in resets || ((field.type == "number" || field.path.last().equals("baseUrl", ignoreCase = true)) && raw.isBlank())
                    buildJsonObject {
                        put("op", if (unset) "unset" else "set")
                        put("path", JsonArray(field.path.map(::JsonPrimitive)))
                        if (!unset) {
                            val value = when (field.type) {
                                "boolean" -> JsonPrimitive(raw.toBooleanStrict())
                                "number" -> {
                                    val number = raw.toDoubleOrNull()
                                    require(number != null && number.isFinite()) { configurationFieldLabel(field) + pt("：请输入有效数字", ": enter a valid number") }
                                    val min = (field.schema["meta"]["min"] as? JsonPrimitive)?.doubleOrNull
                                    val max = (field.schema["meta"]["max"] as? JsonPrimitive)?.doubleOrNull
                                    require((min == null || number >= min) && (max == null || number <= max)) { configurationFieldLabel(field) + pt("：超出允许范围", ": outside the allowed range") }
                                    val step = (field.schema["meta"]["step"] as? JsonPrimitive)?.doubleOrNull
                                    require(step != 1.0 || number % 1.0 == 0.0) { configurationFieldLabel(field) + pt("：请输入整数", ": enter a whole number") }
                                    JsonPrimitive(number)
                                }
                                "enum" -> Json.parseToJsonElement(raw).also { require(it in field.choices) }
                                else -> JsonPrimitive(raw)
                            }
                            put("value", value)
                        }
                    }
                }
                snapshot = save(JsonArray(changes), revision)
                drafts = emptyMap(); resets = emptySet(); failed = false; message = configurationSavedText(snapshot)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failed = true
                message = if (fields.any { isSecret(it) && it.path in drafts }) pt("敏感配置未保存；草稿已保留，请查看主机诊断或重新读取后重试。", "Secret configuration was not saved. Draft retained; check host diagnostics or reload and retry.")
                else (e.message ?: pt("保存失败", "Save failed")) + "\n" + pt("草稿已保留。若修订冲突，请重新读取后确认再保存。", "Draft retained. For revision conflicts, reload, review and save again.")
            } finally { busy = false }
        }
    }
    if (advanced) {
        BackHandler { advanced = false }
        PluginAdvancedConfiguration(snapshot, writable, { advanced = false }, refresh = { refresh().also { snapshot = it } }, save = { operations, revision -> save(operations, revision).also { snapshot = it } })
        return
    }
    SettingsScaffold(configurationTitle(ns, emptyList()), onBack) {
        PluginMessage(configurationDescription(ns))
        if (!writable) PluginMessage(pt("配置当前只读或主机断线", "Settings are read-only or the host is disconnected"), true)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
        message?.let { PluginMessage(it, failed) }
        fields.forEach { field ->
            key(field.path) {
                val current = draftValue(field)
                val secret = isSecret(field)
                SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(configurationFieldLabel(field), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        when (field.type) {
                            "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (current == "true") pt("已开启", "Enabled") else pt("已关闭", "Disabled"), Modifier.weight(1f), fontSize = 13.sp)
                                Switch(current == "true", { change(field, it.toString()) }, enabled = writable && !busy,
                                    colors = SwitchDefaults.colors(checkedTrackColor = T.PrimaryButton, checkedThumbColor = T.OnPrimary))
                            }
                            "enum" -> {
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton({ expanded = true }, enabled = writable && !busy) { Text(field.choices.firstOrNull { it.toString() == current }?.let { (it as? JsonPrimitive)?.content ?: it.toString() } ?: pt("使用默认值", "Use default")) }
                                    DropdownMenu(expanded, { expanded = false }) {
                                        field.choices.forEach { choice -> DropdownMenuItem(text = { Text((choice as? JsonPrimitive)?.content ?: choice.toString()) }, onClick = { change(field, choice.toString()); expanded = false }) }
                                    }
                                }
                            }
                            else -> OutlinedTextField(current, { change(field, it) }, modifier = Modifier.fillMaxWidth(), enabled = writable && !busy, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "number") KeyboardType.Decimal else if (secret) KeyboardType.Password else KeyboardType.Text),
                                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                                placeholder = { Text(if (secret) pt("留空保持现有密钥", "Leave untouched to keep the current secret") else pt("使用默认值", "Use default")) })
                        }
                        val description = localized(field.schema["meta"]["description"])
                        if (!description.isNullOrBlank() && description != configurationFieldLabel(field)) Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (field.type == "number") {
                            val bounds = listOfNotNull(field.schema["meta"]["min"]?.let { pt("最小 ", "Min ") + it }, field.schema["meta"]["max"]?.let { pt("最大 ", "Max ") + it })
                            if (bounds.isNotEmpty()) Text(bounds.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (secret) Text(pt("现有敏感值不会传到手机。", "The stored secret is never sent to this phone."), fontSize = 12.sp)
                        TextButton({ resets = resets + listOf(field.path); drafts = drafts - listOf(field.path) }, enabled = writable && !busy) { Text(if (field.path in resets) pt("保存后恢复默认", "Default restored on save") else pt("恢复默认", "Reset to default")) }
                    }
                }
            }
        }
        if (fields.isEmpty()) PluginMessage(pt("该配置包含专用数据结构，请使用高级编辑；React 专属界面请在主机 Web 打开。", "This configuration uses specialized data structures. Use advanced editing, or host Web for custom React interfaces."))
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(::commit, enabled = writable && !busy && (drafts.isNotEmpty() || resets.isNotEmpty())) { Text(pt("保存", "Save")) }
            TextButton({
                if (!busy) { busy = true; scope.launch {
                    try { snapshot = refresh(); failed = false; message = pt("已读取最新修订号，草稿保持不变。请确认后保存。", "Latest revision loaded; draft retained. Review before saving.") }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed = true; message = e.message }
                    finally { busy = false }
                } }
            }, enabled = !busy) { Text(pt("重新读取", "Reload")) }
        }
        TextButton({ advanced = true }, enabled = !busy, modifier = Modifier.padding(horizontal = 18.dp)) { Text(pt("高级编辑（JSON / schema）", "Advanced editor (JSON / schema)")) }
        if (ns == "web-search-deepseek") PluginMessage(pt("API Key 由主机凭据服务管理；apiKeyEnv 是引用名，不是密钥值。", "API keys are managed by host credentials; apiKeyEnv names a reference, not the key value."))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PluginAdvancedConfiguration(initial: JsonElement, writable: Boolean, onBack: () -> Unit, refresh: suspend () -> JsonElement, save: suspend (JsonArray, Int) -> JsonElement) {
    var snapshot by remember { mutableStateOf(initial) }
    var pathDraft by remember { mutableStateOf("[]") }
    var valueDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var showSchema by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val path = settingsPath(pathDraft)
    val secrets = snapshot["secrets"].arr().orEmpty().map { it["path"].arr().orEmpty().mapNotNull { part -> part.str() } }
    val secret = path != null && secrets.any { path.take(it.size) == it }
    val parentOfSecret = path != null && pluginSettingsReplacesSecret(path, secrets)
    fun submit(unset: Boolean) {
        if (busy || !writable || path == null || parentOfSecret) return
        busy = true; message = null
        scope.launch {
            try {
                val revision = (snapshot["revision"] as? JsonPrimitive)?.intOrNull ?: error(pt("缺少配置修订号，请重新读取", "Missing configuration revision; reload first"))
                val op = buildJsonObject {
                    put("op", if (unset) "unset" else "set")
                    put("path", JsonArray(path.map(::JsonPrimitive)))
                    if (!unset) put("value", if (secret) JsonPrimitive(valueDraft) else Json.parseToJsonElement(valueDraft))
                }
                snapshot = save(JsonArray(listOf(op)), revision)
                valueDraft = ""; failed = false; message = configurationSavedText(snapshot)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failed = true
                message = if (secret) pt("敏感字段保存失败；草稿已保留。请重新读取修订号后重试，详细原因请在主机查看。", "Secret update failed; draft retained. Reload the revision and retry. Check host diagnostics for details.") else e.message ?: pt("保存失败，草稿已保留", "Save failed; draft retained")
            } finally { busy = false }
        }
    }
    SettingsScaffold(snapshot["ns"].str().orEmpty(), onBack) {
        PluginMessage(pt("基于主机 schema 的结构化编辑。每次只修改指定路径，并携带修订号；未知专属 React 界面请在主机 Web 打开。", "Structured editing from the host schema. Each write changes only the selected path with a revision fence. Use host Web for custom React interfaces."))
        if (!writable) PluginMessage(pt("配置当前只读或主机断线", "Settings are read-only or the host is disconnected"), true)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
        message?.let { PluginMessage(it, failed) }
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pt("当前值（敏感字段已隐藏）", "Current value (secrets redacted)"), fontWeight = FontWeight.Medium)
                SelectionContainer { Text(pretty(snapshot["value"]), style = T.Mono, fontSize = 12.sp) }
                Text(pt("修订号：", "Revision: ") + snapshot["revision"].toString(), fontSize = 12.sp)
                TextButton({ showSchema = !showSchema }) { Text(pt("查看 / 收起 schema", "Show / hide schema")) }
                if (showSchema) SelectionContainer { Text(pretty(snapshot["schema"]), style = T.Mono, fontSize = 11.sp) }
                TextButton({
                    if (!busy) { busy = true; scope.launch {
                        try { snapshot = refresh(); failed = false; message = pt("已读取最新修订号，草稿保持不变；请确认后再保存。", "Latest revision loaded; draft retained. Review before saving.") }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failed = true; message = e.message }
                        finally { busy = false }
                    } }
                }, enabled = !busy) { Text(pt("重新读取（保留草稿）", "Reload (keep draft)")) }
            }
        }
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val fields = ((snapshot["value"] as? JsonObject)?.keys.orEmpty() + pluginSchemaFields(snapshot["schema"]).mapNotNull { it.path.firstOrNull() }).distinct()
                if (fields.isNotEmpty()) {
                    Text(pt("选择顶层字段", "Choose a top-level field"), fontSize = 13.sp)
                    fields.forEach { field -> TextButton({
                        pathDraft = JsonArray(listOf(JsonPrimitive(field))).toString()
                        valueDraft = pretty(snapshot["value"][field])
                    }, enabled = !busy) { Text(field) } }
                }
                snapshot["secrets"].arr().orEmpty().forEach { slot ->
                    TextButton({ pathDraft = slot["path"].toString(); valueDraft = "" }, enabled = !busy) {
                        Text(slot["path"].toString() + if (slot["set"].bool() == true) pt(" · 已配置（隐藏）", " · configured (hidden)") else pt(" · 未配置", " · not configured"))
                    }
                }
                OutlinedTextField(pathDraft, { pathDraft = it; valueDraft = "" }, enabled = !busy, label = { Text(pt("路径（JSON 字符串数组）", "Path (JSON string array)")) }, supportingText = { Text("[\"options\", \"timeout\"]") }, modifier = Modifier.fillMaxWidth(), isError = path == null)
                if (parentOfSecret) Text(pt("此路径包含隐藏的敏感字段，不能整体替换或清除。请选择具体字段。", "This path contains redacted secrets. Select a specific field instead of replacing/removing its parent."), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                OutlinedTextField(valueDraft, { valueDraft = it }, enabled = !busy && !parentOfSecret, label = { Text(if (secret) pt("新的敏感值（纯文本）", "New secret (plain text)") else pt("新值（合法 JSON）", "New value (valid JSON)")) }, modifier = Modifier.fillMaxWidth(), minLines = if (secret) 1 else 3, visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ submit(false) }, enabled = writable && !busy && path != null && !parentOfSecret && valueDraft.isNotBlank()) { Text(pt("保存", "Save")) }
                    OutlinedButton({ submit(true) }, enabled = writable && !busy && path != null && !parentOfSecret) { Text(pt("清除覆盖值", "Unset override")) }
                }
                Text(pt("清除覆盖值后恢复主机继承值；未修改的敏感字段不会被提交。", "Unsetting restores the inherited host value. Untouched secrets are never submitted."), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
