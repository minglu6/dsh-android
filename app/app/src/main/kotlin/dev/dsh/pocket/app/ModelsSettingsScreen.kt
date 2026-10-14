package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.jsonOf
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun ModelsSettingsScreen(onBack: () -> Unit) {
    val ops by Pocket.ops.collectAsState()
    val host by Pocket.active.collectAsState()
    val status by Pocket.status.collectAsState()
    val online = host != null && status[host] == Dot.Online
    val settingsRevision by Pocket.modelSettingsRevision.collectAsState()
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
        if (!online || "models.providers" !in ops) return
        loading = true
        scope.launch {
            try { load(); error = null } catch (t: Throwable) { error = settingsFailure(t) }
            finally { loading = false }
        }
    }
    LaunchedEffect(host, ops, settingsRevision, online) {
        if (!online) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            check("models.providers" in ops) { L.unavailable() }
            load()
            error = null
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
                if (!anyUsable && setup != null && "models.providerGet" in ops) {
                    editor = checkNotNull(Pocket.clientOrNull()).modelsProviderGet(setup["provider"].str().orEmpty())
                }
            }
        }
        catch (t: Throwable) {
            if (t is CancellationException) throw t
            error = settingsFailure(t)
        }
        finally { loading = false }
    }
    fun open(row: JsonElement) {
        if (!online || "models.providers" !in ops || "models.providerGet" !in ops || busy || loading) return
        loading = true
        scope.launch {
            try {
                load()
                editor = checkNotNull(Pocket.clientOrNull()) { L.unavailable() }.modelsProviderGet(row["provider"].str().orEmpty())
                message = null; error = null
            } catch (t: Throwable) { error = settingsFailure(t) }
            finally { loading = false }
        }
    }
    val addOptions = providerAddOptions(rows, namespaces)
    val canAdd = online && !busy && !loading && writable && "models.settingsMutate" in ops && addOptions.enabled(protocols)
    SettingsScaffold(L.modelsTitle(), { if (!online || !busy) onBack() }) {
        SettingsMessage(L.modelsIntro())
        if (online && !writable && !loading && error == null) SettingsMessage(st("主机配置为只读", "Host settings are read-only"))
        SettingsMessage(error, true)
        SettingsMessage(message)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) TextButton(::reload, enabled = online && "models.providers" in ops && !busy && !loading) { Text(L.retry()) }
        rows.filter { it["configured"].bool() == true }.forEach { row ->
            val route = row["provider"].str().orEmpty()
            val expanded = editor["provider"]["provider"].str() == route
            ModelProviderCard(expanded) {
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        if (expanded) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(row["displayName"].str() ?: route, color = T.Ink, fontSize = 14.sp)
                                Text(route, color = T.Text3, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row["displayName"].str() ?: route, color = T.Ink, fontSize = 14.sp)
                                if (row["declared"].bool() == true) Text(st("自定义", "Custom"), color = T.Text2, fontSize = 11.sp,
                                    modifier = Modifier.border(0.5.dp, ThemeState.colors.BorderL3, T.R4).padding(horizontal = 6.dp, vertical = 1.dp))
                                if (route != "deepseek-account") {
                                    val credential = if (row["apiKeyEnv"].str() != null) row["credential"] else row["derivedCredential"]
                                    val configured = credential["configured"].bool()
                                    if (configured == true || (row["apiKeyEnv"].str() != null && configured == false)) {
                                        val status = if (configured == true) L.credentialConfigured() else L.credentialMissing()
                                        Box(Modifier.size(8.dp).background(if (configured == true) T.Ok else T.Err, CircleShape).semantics { contentDescription = status })
                                    }
                                }
                            }
                        }
                        row["error"].str()?.let { Text(it, color = T.Err, fontSize = 12.sp) }
                    }
                    if (!expanded) ModelFormButton(st("编辑", "Edit"), { open(row) }, enabled = online && "models.providers" in ops && "models.providerGet" in ops && !busy && !loading, compact = true)
                }
                if (editor["provider"]["provider"].str() == route) key(route) {
                    ProviderSettingsEditor(editor, null, protocols, emptyList(), writable, online = online, interactionEnabled = !busy, onBusy = { busy = it }, onClose = { changed ->
                        editor = null
                        if (changed) { message = st("已保存；以提供商状态确认是否生效", "Saved; check provider status for activation"); reload() }
                    })
                }
            }
        }
        if (!adding) {
            val borderColor = ThemeState.colors.BorderL3
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth()
                    .alpha(if (canAdd) 1f else 0.4f)
                    .drawBehind {
                        drawRoundRect(borderColor, cornerRadius = CornerRadius(12.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
                    }
                    .clickable(enabled = canAdd) {
                        adding = true; message = null
                    }
                    .heightIn(min = 44.dp).padding(8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(D.plus, null, Modifier.size(16.dp), tint = T.Ink)
                Spacer(Modifier.width(6.dp))
                Text(st("添加模型提供商", "Add model provider"), color = T.Ink, fontSize = 14.sp)
            }
            if (!loading && !addOptions.offered) SettingsMessage(st("主机未提供可新增的模型提供商配置。", "The Host has not exposed an add-provider configuration."))
            else if (!loading && !addOptions.enabled(protocols)) SettingsMessage(st("当前没有可添加的目录提供商或自定义 API 协议。", "No catalog providers or custom API protocols are available to add."))
        } else ModelProviderCard(true) {
            AddModelProvider(addOptions.catalog, addOptions.customNamespace, protocols,
                providerTakenRoutes(rows, addOptions.customNamespace).toList(), writable, online = online, interactionEnabled = !busy,
                onBusy = { busy = it }, onClose = { changed ->
                    adding = false
                    if (changed) { message = st("已保存；以提供商状态确认是否生效", "Saved; check provider status for activation"); reload() }
                })
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ModelProviderCard(editing: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth()
            .background(if (editing) T.Fill2 else T.SettingsCard, T.R12)
            .then(if (editing) Modifier else Modifier.border(0.5.dp, T.SettingsCardStroke, T.R12))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
internal fun AddModelProvider(rows: List<JsonElement>, namespace: JsonElement?, protocols: List<String>, taken: List<String>, writable: Boolean, onBusy: (Boolean) -> Unit, onClose: (Boolean) -> Unit, interactionEnabled: Boolean = true, online: Boolean = true) {
    val ops by Pocket.ops.collectAsState()
    val scope = rememberCoroutineScope()
    var custom by remember { mutableStateOf(rows.isEmpty()) }
    var target by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun setBusy(value: Boolean) { busy = value; onBusy(value) }
    fun select(route: String) {
        if (!online || "models.providerGet" !in ops || busy) return
        setBusy(true)
        scope.launch {
            try { target = checkNotNull(Pocket.clientOrNull()) { L.unavailable() }.modelsProviderGet(route); error = null }
            catch (t: Throwable) { error = settingsFailure(t) }
            finally { setBusy(false) }
        }
    }
    LaunchedEffect(online, ops) {
        if (online && target == null) rows.firstOrNull()?.get("provider").str()?.let(::select)
    }
    if (rows.isNotEmpty() && namespace != null) {
        Row(Modifier.background(T.Fill, T.R12).padding(3.dp)) {
            listOf(false to st("第三方模型提供商", "Third-party provider"), true to st("自定义模型 API", "Custom model API")).forEach { (mode, title) ->
                Box(
                    Modifier.weight(1f)
                        .background(if (custom == mode) T.Input else androidx.compose.ui.graphics.Color.Transparent, T.R8)
                        .then(if (custom == mode) Modifier.border(0.5.dp, ThemeState.colors.BorderL3, T.R8) else Modifier)
                        .selectable(selected = custom == mode, enabled = interactionEnabled && !busy && (!mode || protocols.isNotEmpty()), role = Role.Tab) { custom = mode }
                        .heightIn(min = 32.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(title, fontSize = 13.sp, color = if (custom == mode) T.Ink else T.Text2) }
            }
        }
    }
    Text(
        if (custom) st("连接中转站、自部署服务或其他兼容 OpenAI / Anthropic 协议的接口，需填写 API 地址、协议和模型。", "Connect a gateway, self-hosted service, or another OpenAI / Anthropic-compatible endpoint; provide its URL, protocol, and models.")
        else st("从内置目录中选择模型提供商，填入其 API 密钥即可使用。", "Choose a model provider from the installed catalog and enter its API key."),
        color = T.Text3, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp),
    )
    SettingsMessage(error, true)
    if (!custom) ModelFormField(st("模型提供商", "Model provider")) {
        ModelSettingsSelect(target["provider"]["provider"].str().orEmpty(), rows.mapNotNull { it["provider"].str() }, online && "models.providerGet" in ops && interactionEnabled && !busy,
            label = { route -> providerOptionLabel(rows.firstOrNull { it["provider"].str() == route }, route) },
            onChange = ::select)
    }
    // Both panels stay composed so switching modes retains their write-only drafts.
    if (namespace != null) ProviderSettingsEditor(null, namespace, protocols, taken, writable, online = online, visible = custom, interactionEnabled = interactionEnabled, onBusy = ::setBusy, onClose = onClose)
    target?.let { snapshot -> key(snapshot["provider"]["provider"].str()) {
        ProviderSettingsEditor(snapshot, null, protocols, taken, writable, online = online, visible = !custom, interactionEnabled = interactionEnabled, onBusy = ::setBusy, onClose = onClose)
    } }
    if (!custom && target == null) ModelFormButton(st("取消", "Cancel"), { onClose(false) }, enabled = !online || (interactionEnabled && !busy))
}

@Composable
internal fun ProviderSettingsEditor(
    initial: JsonElement?, customNamespace: JsonElement?, protocols: List<String>, taken: List<String>, writable: Boolean,
    visible: Boolean = true, onBusy: (Boolean) -> Unit, onClose: (Boolean) -> Unit, interactionEnabled: Boolean = true, online: Boolean = true,
) {
    val ops by Pocket.ops.collectAsState()
    val connected by rememberUpdatedState(online)
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
    var revisionFresh by remember { mutableStateOf(online) }
    LaunchedEffect(online) {
        if (!online) revisionFresh = false
    }
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
    val editable = online && revisionFresh && writable && interactionEnabled && knownLayout && namespace != null && "models.settingsMutate" in ops && !busy && !removalWritten
    val profileEditable = editable && !(initial == null && committed)
    val keyWritable = writable && credential["writable"].bool() != false && "credentials.set" in ops && !removalWritten
    val keyFailure = providerKeyFailure(keyDraft)
    val modelFailure = if (overridden || initial == null) validateProviderModels(models) else null
    val routeFailure = if (initial == null && !committed) providerRouteFailure(route, takenRoutes) else null
    val endpointFailure = if (baseURL.isNotBlank() && !account && !providerEndpointValid(baseURL)) st("请输入有效的 HTTP(S) 地址", "Enter a valid HTTP(S) endpoint") else null
    val creationReady = initial != null || committed || (routeFailure == null && baseURL.isNotBlank() && api in protocols && models.isNotEmpty())
    fun mutate(action: suspend () -> Unit) {
        if (!online || busy) return
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
            routeFailure?.let { error(it) }
            check(baseURL.isNotBlank() && api in protocols && models.isNotEmpty()) { st("请填写端点、协议和至少一个模型", "Provide an endpoint, protocol, and at least one model") }
        }
        endpointFailure?.let { error(it) }
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
        if (initial == null || row["configured"].bool() == false) {
            check(confirmed["provider"]["provider"].str() == route && confirmed["provider"]["configured"].bool() == true) {
                st("配置已写入，但尚未回读确认提供商；草稿已保留", "Configuration written, but provider is not confirmed; draft retained")
            }
        }
        if (!account && key.isNotEmpty()) {
            val keyState = confirmed["provider"]["credential"] ?: confirmed["provider"]["derivedCredential"]
            check(keyState["configured"].bool() == true) { st("写入已返回，但尚未回读确认密钥；草稿已保留", "Write returned, but key is not confirmed; draft retained") }
        }
        keyDraft = ""
        onClose(true)
    }
    // Catalog queries name only the installed route; they do not contact its endpoint.
    LaunchedEffect(initial, online) {
        if (online && initial != null && ns == "llm-pi-ai" && !custom && "models.discover" in ops) {
            try {
                val result = checkNotNull(Pocket.clientOrNull()).modelsDiscover(ns, jsonOf("provider" to route))
                requireSettingsSuccess(result)
                catalog = result["models"].arr().orEmpty()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                error = settingsFailure(t)
            }
        }
    }
    if (!visible) return
    SettingsMessage(row["error"].str(), true)
    SettingsMessage(error, true)
    if (committed && error != null) SettingsMessage(st("提供商配置已写入；密钥或回读确认失败。草稿已保留，可继续重试。", "Provider configuration was written; key storage or confirmation failed. The draft is retained for retry."))
    if ((error != null || !revisionFresh) && !removalWritten) TextButton({
        mutate {
            val client = checkNotNull(Pocket.clientOrNull()) { L.unavailable() }
            if (initial == null && !committed) {
                val refreshed = providerCreationBaseline(client.modelsProviders(), ns)
                namespace = refreshed.namespace
                revision = refreshed.revision
                takenRoutes = refreshed.taken
                baseline = settingsPathValue(refreshed.namespace["user"], path)
                revisionFresh = connected && Pocket.clientOrNull() === client
                check(route !in takenRoutes) {
                    st("该 Provider ID 已被占用；草稿已保留，请更换 ID 后保存。", "This Provider ID is now occupied; the draft is retained. Choose another ID before saving.")
                }
            } else {
                val refreshed = client.modelsProviderGet(route)
                val view = refreshed["namespace"] ?: error(L.unavailable())
                namespace = view
                revision = (view["revision"] as? JsonPrimitive)?.longOrNull
                baseline = settingsPathValue(view["user"], path)
                credential = refreshed["provider"]["credential"] ?: refreshed["provider"]["derivedCredential"]
                revisionFresh = connected && Pocket.clientOrNull() === client
            }
        }
    }, enabled = online && interactionEnabled && !busy && (if (initial == null && !committed) "models.providers" in ops else "models.providerGet" in ops), modifier = Modifier.padding(horizontal = 8.dp)) { Text(st("回读最新配置并保留草稿", "Refresh configuration and keep draft")) }
    if (!knownLayout) SettingsMessage(st("此提供商由主机配置管理。", "This provider is managed in Host configuration."))
    if (initial == null) {
        ModelFormField("Provider ID") {
            ModelFormInput(route, { route = it }, "Provider ID", "acme-gateway", enabled = profileEditable)
            Text(st("以小写字母开头，仅含小写字母、数字和连字符。", "Start with a lowercase letter; use lowercase letters, numbers, and hyphens."), color = T.Text3, fontSize = 12.sp)
            if (route.isNotEmpty() && routeFailure != null) Text(routeFailure, color = T.Err, fontSize = 12.sp)
        }
        ModelFormField(st("显示名称", "Display name")) {
            ModelFormInput(displayName, { displayName = it }, st("显示名称", "Display name"), route, enabled = profileEditable)
        }
        ModelFormField(st("API 地址", "Base URL")) {
            ModelFormInput(baseURL, { baseURL = it }, st("API 地址", "Base URL"),
                if (api == "anthropic-messages") "https://gateway.example" else "https://gateway.example/v1", enabled = profileEditable)
            if (endpointFailure != null) Text(endpointFailure, color = T.Err, fontSize = 12.sp)
        }
        ModelFormField(st("API 协议", "API protocol")) { ModelSettingsSelect(api, protocols, profileEditable, ::protocolLabel) { api = it } }
    }
    if (!account && knownLayout) ModelFormField(st("API 密钥", "API key")) {
        ModelFormInput(keyDraft, { keyDraft = it }, st("API 密钥", "API key"),
            if (credential["configured"].bool() == true) st("已配置；留空保留现有密钥", "Configured; leave blank to keep") else st("输入 API 密钥", "Enter API key"),
            secret = true, enabled = online && keyWritable && interactionEnabled && !busy)
        if (online && !keyWritable) Text(st("密钥由只读来源管理", "API key is managed by a read-only source"), color = T.Text3, fontSize = 12.sp)
        if (keyFailure != null) Text(st("请输入密钥本身，不要包含引号、环境变量赋值或非 ASCII 字符。", "Enter the key alone, without quotes, environment assignments, or non-ASCII characters."), color = T.Err, fontSize = 12.sp)
    }
    if (initial != null && !account && knownLayout) {
        HorizontalDivider(color = T.Border, thickness = 0.5.dp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.clickable { expanded = !expanded }.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (expanded) D.chevronDown else D.chevronRight, null, Modifier.size(12.dp), tint = T.Text2)
            Spacer(Modifier.width(6.dp))
            Text(st("自定义设置", "Custom settings"), color = T.Text2, fontSize = 12.sp)
        }
    }
    if (expanded && knownLayout) {
        if (initial != null) {
            if (custom) ModelFormField(st("显示名称", "Display name")) {
                ModelFormInput(displayName, { displayName = it }, st("显示名称", "Display name"), route, enabled = profileEditable)
            }
            if (!account) ModelFormField(st("API 地址", "Base URL")) {
                ModelFormInput(baseURL, { baseURL = it }, st("API 地址", "Base URL"),
                    if (deepseek) "https://api.deepseek.com/anthropic" else effective["baseURL"].str() ?: if (api == "anthropic-messages") "https://gateway.example" else "https://gateway.example/v1", enabled = profileEditable)
                if (endpointFailure != null) Text(endpointFailure, color = T.Err, fontSize = 12.sp)
                if (deepseek) Text(st("请填写兼容 Anthropic Messages 协议的 API 地址。", "Enter an API endpoint compatible with the Anthropic Messages protocol."), color = T.Text3, fontSize = 12.sp)
            }
            if (custom) ModelFormField(st("API 协议", "API protocol")) { ModelSettingsSelect(api, protocols, profileEditable, ::protocolLabel) { api = it } }
        }
        NativeModelList(models, deepseek, overridden || initial == null, profileEditable, catalog, effective,
            onChange = { models = it; overridden = true; modelsTouched = true },
            onReset = if (initial == null) null else ({ models = JsonArray(inherited); overridden = false; modelsTouched = true }),
            onDiscover = if (deepseek) null else ({ mutate {
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
            } }), discoverEnabled = profileEditable && keyFailure == null && "models.discover" in ops)
    }
    SettingsMessage(modelFailure, true)
    if (initial == null && !committed && routeFailure == null && endpointFailure == null && modelFailure == null) {
        SettingsMessage(when {
            baseURL.isBlank() -> st("请填写 API 地址", "Enter an API endpoint")
            api !in protocols -> st("请选择主机支持的 API 协议", "Choose an API protocol supported by the Host")
            models.isEmpty() -> st("请至少添加一个模型", "Add at least one model")
            else -> null
        })
    }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
        ModelFormButton(st("取消", "Cancel"), { onClose(committed) }, enabled = !online || (interactionEnabled && !busy))
        Spacer(Modifier.width(8.dp))
        ModelFormButton(
            if (initial == null) { if (busy) st("创建中", "Creating") else st("创建提供商", "Create provider") }
            else { if (busy) st("保存中", "Saving") else st("保存", "Save") },
            ::save, enabled = editable && creationReady && endpointFailure == null && modelFailure == null && keyFailure == null, primary = true,
        )
    }
    if (row["removable"].bool() == true) TextButton({ confirmation = true }, enabled = online && !busy && (editable || removalWritten), modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (removalWritten) st("重试删除收尾", "Retry removal cleanup") else st("删除提供商", "Remove provider"), color = T.Err) }
    if (confirmation) AlertDialog(onDismissRequest = { if (!online || !busy) confirmation = false }, title = { Text(st("删除提供商？", "Remove provider?")) }, text = { Column {
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
    } }, enabled = online && !busy && (editable || removalWritten)) { Text(L.delete()) } }, dismissButton = { TextButton({ confirmation = false }, enabled = !online || !busy) { Text(st("取消", "Cancel")) } })
    candidates?.let { found -> ModelCandidatePicker(found, models, { candidates = null }) { selected ->
        models = JsonArray(models + selected.map(::adoptedModel)); overridden = true; modelsTouched = true; candidates = null
    } }
}
