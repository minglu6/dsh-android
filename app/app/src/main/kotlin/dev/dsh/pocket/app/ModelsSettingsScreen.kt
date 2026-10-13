package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.jsonOf
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun ModelsSettingsScreen(onBack: () -> Unit) {
    val ops by Pocket.ops.collectAsState()
    val host by Pocket.active.collectAsState()
    val scope = rememberCoroutineScope()
    var rows by remember(host) { mutableStateOf<List<JsonElement>>(emptyList()) }
    var namespaces by remember(host) { mutableStateOf<List<JsonElement>>(emptyList()) }
    var protocols by remember(host) { mutableStateOf<List<String>>(emptyList()) }
    var writable by remember(host) { mutableStateOf(false) }
    var loading by remember(host) { mutableStateOf(true) }
    var error by remember(host) { mutableStateOf<String?>(null) }
    var message by remember(host) { mutableStateOf<String?>(null) }
    var editor by remember(host) { mutableStateOf<JsonElement?>(null) }
    var adding by remember(host) { mutableStateOf(false) }
    var busy by remember(host) { mutableStateOf(false) }
    var setupConsidered by remember(host) { mutableStateOf(false) }
    suspend fun load() {
        val result = checkNotNull(Pocket.clientOrNull()) { L.unavailable() }.modelsProviders()
        rows = result["rows"].arr().orEmpty()
        namespaces = result["namespaces"].arr().orEmpty()
        protocols = result["protocols"].arr()?.mapNotNull { it.str() }
            ?: providerProtocolChoices(namespaces.find { it["ns"].str() == "llm-pi-ai" }?.get("schema"))
        writable = result["writable"].bool() == true
    }
    fun reload() {
        loading = true
        scope.launch {
            try { load(); error = null } catch (t: Throwable) { error = settingsFailure(t) }
            finally { loading = false }
        }
    }
    LaunchedEffect(host, ops) {
        loading = true
        try {
            check("models.providers" in ops) { L.unavailable() }
            load()
            if (!setupConsidered) {
                setupConsidered = true
                val anyUsable = rows.any {
                    it["active"].bool() == true &&
                        (it["provider"].str() == "deepseek-account" || it["apiKeyEnv"].str() == null || it["credential"]["configured"].bool() == true)
                }
                val setup = rows.firstOrNull {
                    it["provider"].str() != "deepseek-account" && it["settingsPath"].arr()?.isEmpty() == true &&
                        it["credential"]["configured"].bool() != true
                }
                if (!anyUsable && setup != null) {
                    editor = checkNotNull(Pocket.clientOrNull()).modelsProviderGet(setup["provider"].str().orEmpty())
                }
            }
        }
        catch (t: Throwable) { error = settingsFailure(t) }
        finally { loading = false }
    }
    fun open(row: JsonElement) {
        if (busy || loading) return
        loading = true
        scope.launch {
            try {
                load()
                editor = checkNotNull(Pocket.clientOrNull()) { L.unavailable() }.modelsProviderGet(row["provider"].str().orEmpty())
                adding = false; message = null; error = null
            } catch (t: Throwable) { error = settingsFailure(t) }
            finally { loading = false }
        }
    }
    SettingsScaffold(L.modelsTitle(), { if (!busy) onBack() }) {
        SettingsMessage(L.modelsIntro())
        if (!writable && !loading) SettingsMessage(st("主机配置为只读", "Host settings are read-only"))
        SettingsMessage(error, true)
        SettingsMessage(message)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) TextButton(::reload, enabled = !busy && !loading) { Text(L.retry()) }
        rows.filter { it["configured"].bool() == true }.forEach { row ->
            val route = row["provider"].str().orEmpty()
            SettingsCard {
                val expanded = editor["provider"]["provider"].str() == route
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row["displayName"].str() ?: route, color = T.Ink, fontSize = 16.sp)
                        row["error"].str()?.let { Text(it, color = T.Err, fontSize = 12.sp) }
                    }
                    if (row["declared"].bool() == true) {
                        Text(st("自定义", "Custom"), color = T.Text3, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    if (route != "deepseek-account") {
                        val credential = if (row["apiKeyEnv"].str() != null) row["credential"] else row["derivedCredential"]
                        val configured = credential["configured"].bool()
                        if (configured == true || (row["apiKeyEnv"].str() != null && configured == false)) {
                            val status = if (configured == true) L.credentialConfigured() else L.credentialMissing()
                            Box(Modifier.size(7.dp).background(if (configured == true) T.Ok else T.Err, CircleShape).semantics { contentDescription = status })
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    TextButton({
                        if (expanded) editor = null else open(row)
                    }, enabled = !busy && !loading, colors = ButtonDefaults.textButtonColors(contentColor = T.Ink)) { Text(st("编辑", "Edit")) }
                }
                if (editor["provider"]["provider"].str() == route) key(route) {
                    ProviderSettingsEditor(editor, null, protocols, emptyList(), writable, onBusy = { busy = it }, onClose = { changed ->
                        editor = null
                        if (changed) { message = st("已保存；以提供商状态确认是否生效", "Saved; check provider status for activation"); reload() }
                    })
                }
            }
        }
        val customNamespace = namespaces.find { it["ns"].str() == "llm-pi-ai" }
        val dormant = rows.filter { it["configured"].bool() == false && it["settingsNs"].str() == "llm-pi-ai" }
        if (customNamespace != null) {
            if (!adding) TextButton({ adding = true; editor = null; message = null }, enabled = !busy && !loading && (dormant.isNotEmpty() || protocols.isNotEmpty()), modifier = Modifier.padding(horizontal = 18.dp)) {
                Text(st("添加模型提供商", "Add model provider"))
            } else SettingsCard {
                AddModelProvider(dormant, customNamespace, protocols, rows.mapNotNull { it["provider"].str() }, writable,
                    onBusy = { busy = it }, onClose = { changed -> adding = false; if (changed) { message = st("已保存；以提供商状态确认是否生效", "Saved; check provider status for activation"); reload() } })
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
internal fun AddModelProvider(rows: List<JsonElement>, namespace: JsonElement, protocols: List<String>, taken: List<String>, writable: Boolean, onBusy: (Boolean) -> Unit, onClose: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var custom by remember { mutableStateOf(rows.isEmpty()) }
    var target by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun setBusy(value: Boolean) { busy = value; onBusy(value) }
    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!custom, { custom = false }, label = { Text(st("第三方模型提供商", "Third-party provider")) }, enabled = !busy && rows.isNotEmpty())
        FilterChip(custom, { custom = true }, label = { Text(st("自定义模型 API", "Custom model API")) }, enabled = !busy && protocols.isNotEmpty())
    }
    SettingsMessage(if (custom) st("连接中转站、自部署服务或兼容 OpenAI / Anthropic 的 API。", "Connect a gateway, self-hosted service, or OpenAI / Anthropic-compatible API.") else st("从已安装的提供商目录中选择。", "Choose from the installed provider catalog."))
    SettingsMessage(error, true)
    if (!custom) LabeledField(st("模型提供商", "Model provider")) {
        ModelSettingsSelect(target["provider"]["provider"].str().orEmpty(), rows.mapNotNull { it["provider"].str() }, !busy) { route ->
            setBusy(true)
            scope.launch {
                try { target = checkNotNull(Pocket.clientOrNull()) { L.unavailable() }.modelsProviderGet(route); error = null }
                catch (t: Throwable) { error = settingsFailure(t) }
                finally { setBusy(false) }
            }
        }
    }
    // Both panels stay composed so switching modes retains their write-only drafts.
    ProviderSettingsEditor(null, namespace, protocols, taken, writable, visible = custom, onBusy = ::setBusy, onClose = onClose)
    target?.let { snapshot -> key(snapshot["provider"]["provider"].str()) {
        ProviderSettingsEditor(snapshot, null, protocols, taken, writable, visible = !custom, onBusy = ::setBusy, onClose = onClose)
    } }
    if (!custom && target == null) TextButton({ onClose(false) }, enabled = !busy) { Text(st("取消", "Cancel")) }
}

@Composable
internal fun ProviderSettingsEditor(
    initial: JsonElement?, customNamespace: JsonElement?, protocols: List<String>, taken: List<String>, writable: Boolean,
    visible: Boolean = true, onBusy: (Boolean) -> Unit, onClose: (Boolean) -> Unit,
) {
    val ops by Pocket.ops.collectAsState()
    val scope = rememberCoroutineScope()
    var namespace by remember { mutableStateOf(initial?.get("namespace") ?: customNamespace) }
    val row = initial["provider"]
    var route by remember { mutableStateOf(row["provider"].str().orEmpty()) }
    var takenRoutes by remember { mutableStateOf(taken.toSet()) }
    val path = if (initial == null) listOf("providers", route) else row["settingsPath"].arr().orEmpty().mapNotNull { it.str() }
    val ns = namespace["ns"].str() ?: row["settingsNs"].str().orEmpty()
    val account = route == "deepseek-account"
    val deepseek = account || ns == "llm-deepseek"
    val custom = initial == null || row["declared"].bool() == true
    val original = settingsPathValue(namespace["user"], path)
    val effective = settingsPathValue(namespace["value"], path)
    var baseline by remember { mutableStateOf(original) }
    var revision by remember { mutableStateOf((namespace["revision"] as? JsonPrimitive)?.longOrNull) }
    var baseURL by remember { mutableStateOf(original["baseURL"].str().orEmpty()) }
    var displayName by remember { mutableStateOf(original["displayName"].str().orEmpty()) }
    var api by remember { mutableStateOf(original["api"].str() ?: effective["api"].str() ?: if (initial == null) protocols.firstOrNull().orEmpty() else "") }
    val inherited = initial["inheritedModels"].arr().orEmpty()
    var models by remember { mutableStateOf(original["models"].arr() ?: JsonArray(inherited)) }
    var overridden by remember { mutableStateOf(original["models"] != null) }
    var modelsTouched by remember { mutableStateOf(false) }
    // Secrets intentionally never enter saved instance state.
    var keyDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(initial == null || account) }
    var confirmation by remember { mutableStateOf(false) }
    var removalWritten by remember { mutableStateOf(false) }
    var managedKeyRemoved by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<JsonElement>?>(null) }
    var catalog by remember { mutableStateOf(initial["catalog"].arr().orEmpty()) }
    var credential by remember { mutableStateOf(row["credential"] ?: row["derivedCredential"]) }
    val ref = effective["apiKeyEnv"].str() ?: row["apiKeyEnv"].str() ?: providerKeyRef(route)
    val knownLayout = deepseek || ns == "llm-pi-ai"
    val editable = writable && knownLayout && namespace != null && "models.settingsMutate" in ops && !busy && !removalWritten
    val profileEditable = editable && !(initial == null && committed)
    val keyWritable = writable && credential["writable"].bool() != false && "credentials.set" in ops && !removalWritten
    val keyFailure = providerKeyFailure(keyDraft)
    val modelFailure = if (overridden || initial == null) validateProviderModels(models) else null
    fun mutate(action: suspend () -> Unit) {
        if (busy) return
        busy = true; onBusy(true); error = null
        scope.launch {
            try { action() } catch (t: Throwable) { error = settingsFailure(t, keyDraft.trim()) }
            finally { busy = false; onBusy(false) }
        }
    }
    fun save() = mutate {
        val client = checkNotNull(Pocket.clientOrNull()) { L.unavailable() }
        check(keyFailure == null) { st("请只粘贴有效的 API 密钥", "Paste only a valid API key") }
        modelFailure?.let { error(it) }
        val key = keyDraft.trim()
        val creating = initial == null && !committed
        if (creating) {
            check(Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$").matches(route) && route !in takenRoutes) { st("Provider ID 需唯一，以字母开头，仅含小写字母、数字与连字符", "Use a unique lowercase Provider ID beginning with a letter") }
            check(baseURL.isNotBlank() && api in protocols && models.isNotEmpty()) { st("请填写端点、协议和至少一个模型", "Provide an endpoint, protocol, and at least one model") }
        }
        if (baseURL.isNotBlank() && !account) {
            val uri = runCatching { java.net.URI(baseURL.trim()) }.getOrNull()
            check(uri?.scheme in listOf("http", "https") && !uri?.host.isNullOrBlank()) { st("请输入有效的 HTTP(S) 地址", "Enter a valid HTTP(S) endpoint") }
        }
        if (!(initial == null && committed)) {
            val fields = buildJsonObject {
                if (!account) put("baseURL", baseURL.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
                if (custom) {
                    put("displayName", displayName.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
                    put("api", api.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
                }
                if (modelsTouched || creating) put("models", if (overridden || creating) models else JsonNull)
                if (!account && ns == "llm-pi-ai" && key.isNotEmpty() && effective["apiKeyEnv"].str() == null) put("apiKeyEnv", ref)
            }
            val changes = when {
                creating -> JsonArray(listOf(buildJsonObject { put("op", "set"); put("path", JsonArray(path.map(::JsonPrimitive))); put("value", JsonObject(fields.filterValues { it != JsonNull })) }))
                baseline == null && effective == null && fields.values.all { it == JsonNull } -> JsonArray(listOf(buildJsonObject { put("op", "set"); put("path", JsonArray(path.map(::JsonPrimitive))); put("value", buildJsonObject {}) }))
                else -> providerFieldOps(path, baseline, fields)
            }
            if (changes.isNotEmpty()) {
                val result = client.modelsSettingsMutate(ns, changes, checkNotNull(revision) { st("缺少配置版本，请重新打开", "Configuration revision missing; reopen editor") })
                requireSettingsSuccess(result)
                // Adopt the returned revision before any credential/read-back phase.
                val written = if (result["ns"].str() == ns) result else result["view"] ?: result["namespace"]
                revision = (written["revision"] as? JsonPrimitive)?.longOrNull ?: error(st("主机未返回写入版本", "Host did not return the written revision"))
                baseline = settingsPathValue(written["user"], path)
                namespace = written
                committed = true
            }
        }
        if (!account && key.isNotEmpty()) {
            check(keyWritable) { st("密钥为只读", "API key is read-only") }
            requireSettingsSuccess(client.credentialsSet(ref, key))
        }
        val confirmed = client.modelsProviderGet(route)
        if (!account && key.isNotEmpty()) {
            val keyState = confirmed["provider"]["credential"] ?: confirmed["provider"]["derivedCredential"]
            check(keyState["configured"].bool() == true) { st("写入已返回，但尚未回读确认密钥；草稿已保留", "Write returned, but key is not confirmed; draft retained") }
        }
        keyDraft = ""
        onClose(true)
    }
    // Catalog queries name only the installed route; they do not contact its endpoint.
    LaunchedEffect(initial) {
        if (initial != null && ns == "llm-pi-ai" && !custom && "models.discover" in ops) {
            try {
                val result = checkNotNull(Pocket.clientOrNull()).modelsDiscover(ns, jsonOf("provider" to route))
                requireSettingsSuccess(result)
                catalog = result["models"].arr().orEmpty()
            } catch (t: Throwable) { error = settingsFailure(t) }
        }
    }
    if (!visible) return
    SettingsMessage(row["error"].str(), true)
    SettingsMessage(error, true)
    if (error != null && !removalWritten) TextButton({
        mutate {
            val client = checkNotNull(Pocket.clientOrNull()) { L.unavailable() }
            if (initial == null && !committed) {
                val refreshed = providerCreationBaseline(client.modelsProviders(), ns)
                namespace = refreshed.namespace
                revision = refreshed.revision
                takenRoutes = refreshed.taken
                baseline = settingsPathValue(refreshed.namespace["user"], path)
                check(route !in takenRoutes) {
                    st("该 Provider ID 已被占用；草稿已保留，请更换 ID 后保存。", "This Provider ID is now occupied; the draft is retained. Choose another ID before saving.")
                }
            } else {
                val refreshed = client.modelsProviderGet(route)
                val view = refreshed["namespace"] ?: error(L.unavailable())
                namespace = view
                revision = (view["revision"] as? JsonPrimitive)?.longOrNull
                baseline = settingsPathValue(view["user"], path)
            }
        }
    }, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp)) { Text(st("回读最新配置并保留草稿", "Refresh configuration and keep draft")) }
    if (!knownLayout) SettingsMessage(st("此提供商由主机配置管理。", "This provider is managed in Host configuration."))
    if (initial == null) {
        LabeledField("Provider ID") { SettingsTextField(route, { route = it }, "acme-gateway", enabled = profileEditable) }
        LabeledField(st("显示名称", "Display name")) { SettingsTextField(displayName, { displayName = it }, route, enabled = profileEditable) }
    }
    if (!account && knownLayout) LabeledField(st("API 密钥", "API key")) {
        SettingsTextField(keyDraft, { keyDraft = it }, if (credential["configured"].bool() == true) st("已配置；留空保留现有密钥", "Configured; leave blank to keep") else st("输入 API 密钥（可选）", "Enter API key (optional)"), secret = true, enabled = keyWritable && !busy)
        if (!keyWritable) Text(st("密钥由只读来源管理", "API key is managed by a read-only source"), color = T.Text3, fontSize = 12.sp)
        if (keyFailure != null) Text(st("请输入密钥本身，不要包含引号、环境变量赋值或非 ASCII 字符。", "Enter the key alone, without quotes, environment assignments, or non-ASCII characters."), color = T.Err, fontSize = 12.sp)
    }
    if (initial != null && !account && knownLayout) TextButton({ expanded = !expanded }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(st("自定义设置", "Custom settings")); Icon(if (expanded) D.chevronDown else D.chevronRight, null)
    }
    if (expanded && knownLayout) {
        if (custom && initial != null) LabeledField(st("显示名称", "Display name")) { SettingsTextField(displayName, { displayName = it }, route, enabled = profileEditable) }
        if (!account) LabeledField(st("API 地址", "Base URL")) {
            SettingsTextField(baseURL, { baseURL = it }, if (deepseek) "https://api.deepseek.com/anthropic" else effective["baseURL"].str() ?: if (api == "anthropic-messages") "https://gateway.example" else "https://gateway.example/v1", enabled = profileEditable)
        }
        if (custom) LabeledField(st("API 协议", "API protocol")) { ModelSettingsSelect(api, protocols, profileEditable, ::protocolLabel) { api = it } }
        NativeModelList(models, deepseek, overridden, profileEditable, catalog, effective,
            onChange = { models = it; overridden = true; modelsTouched = true },
            onReset = if (initial == null) null else ({ models = JsonArray(inherited); overridden = false; modelsTouched = true }))
        if (!deepseek) TextButton({ mutate {
            check(keyFailure == null) { st("请先修正 API 密钥", "Correct the API key first") }
            val result = checkNotNull(Pocket.clientOrNull()).modelsDiscover(ns, buildJsonObject {
                if (initial != null) put("provider", route)
                baseURL.ifBlank { effective["baseURL"].str().orEmpty() }.takeIf { it.isNotBlank() }?.let { put("baseURL", it) }
                api.takeIf { it.isNotBlank() }?.let { put("api", it) }
                keyDraft.trim().takeIf { it.isNotEmpty() }?.let { put("apiKey", it) }
            })
            requireSettingsSuccess(result)
            val found = result["models"].arr().orEmpty()
            check(found.isNotEmpty()) { st("未发现可用模型，可手动添加。", "No models found; add them manually.") }
            candidates = found
        } }, enabled = profileEditable && keyFailure == null && "models.discover" in ops, modifier = Modifier.padding(horizontal = 8.dp)) { Text(st("获取可用模型", "Get available models")) }
    }
    SettingsMessage(modelFailure, true)
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
        TextButton({ onClose(committed) }, enabled = !busy) { Text(st("取消", "Cancel")) }
        Spacer(Modifier.width(8.dp))
        Button(::save, enabled = editable && modelFailure == null && keyFailure == null, shape = T.R12, colors = ButtonDefaults.buttonColors(containerColor = T.PrimaryButton, contentColor = T.OnPrimary)) { Text(if (busy) st("应用中", "Applying") else st("应用", "Apply")) }
    }
    if (row["removable"].bool() == true) TextButton({ confirmation = true }, enabled = !busy && (editable || removalWritten), modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (removalWritten) st("重试删除收尾", "Retry removal cleanup") else st("删除提供商", "Remove provider"), color = T.Err) }
    if (confirmation) AlertDialog(onDismissRequest = { if (!busy) confirmation = false }, title = { Text(st("删除提供商？", "Remove provider?")) }, text = { Column {
        Text(st("移除 $route 的用户配置，仅清理此页面拥有的密钥引用。其他来源与自定义引用会保留。", "Remove $route and only its page-managed key. Other sources and custom references are retained."))
        SettingsMessage(error, true)
        if (removalWritten && !managedKeyRemoved) SettingsMessage(st("提供商配置已删除；正在确认删除状态并清理页面管理的密钥，失败可重试。", "Provider configuration has been removed; confirmation and managed-key cleanup can be retried."))
    } }, confirmButton = { TextButton({ mutate {
        val client = checkNotNull(Pocket.clientOrNull())
        val managed = providerKeyRef(route)
        removeProviderSafely(
            progress = ProviderRemovalProgress(removalWritten, managedKeyRemoved),
            onProgress = {
                removalWritten = it.configurationRemoved
                managedKeyRemoved = it.credentialRemoved
                committed = true
            },
            removeConfiguration = {
                requireSettingsSuccess(client.modelsSettingsMutate(ns, JsonArray(listOf(jsonOf("op" to "unset", "path" to path))), checkNotNull(revision) { L.unavailable() }))
            },
            confirmRemoval = {
                val listed = client.modelsProviders()
                check(listed["rows"].arr().orEmpty().none { it["provider"].str() == route && it["removable"].bool() == true }) {
                    st("删除尚未回读确认，请保留当前页面并重试", "Removal is not confirmed; keep this page and retry")
                }
            },
            clearManagedCredential = {
                if (row["apiKeyEnv"].str() == managed && credential["configured"].bool() == true && credential["writable"].bool() == true) {
                    requireSettingsSuccess(client.credentialsUnset(managed))
                }
            },
        )
        confirmation = false; onClose(true)
    } }, enabled = !busy) { Text(L.delete()) } }, dismissButton = { TextButton({ confirmation = false }, enabled = !busy) { Text(st("取消", "Cancel")) } })
    candidates?.let { found -> ModelCandidatePicker(found, models, { candidates = null }) { selected ->
        models = JsonArray(models + selected.map(::adoptedModel)); overridden = true; modelsTouched = true; candidates = null
    } }
}
