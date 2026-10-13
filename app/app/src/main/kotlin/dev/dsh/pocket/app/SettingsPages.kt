package dev.dsh.pocket.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.jsonOf
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

internal fun st(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

internal fun settingsPathValue(root: JsonElement?, path: List<String>): JsonElement? =
    path.fold(root) { value, key -> value[key] }

/** Name only fields owned by this form; never replace a secret-bearing parent. */
internal fun providerFieldOps(path: List<String>, before: JsonElement?, after: JsonObject): JsonArray =
    JsonArray(after.mapNotNull { (key, value) ->
        if (before[key] == value || (before[key] == null && value == JsonNull)) null
        else if (value == JsonNull) jsonOf("op" to "unset", "path" to path + key)
        else buildJsonObject { put("op", "set"); put("path", JsonArray((path + key).map(::JsonPrimitive))); put("value", value) }
    })

internal fun validateProviderModels(models: JsonArray): String? {
    val ids = mutableSetOf<String>()
    models.forEachIndexed { index, model ->
        val id = model["id"].str()?.trim()
        if (id.isNullOrEmpty()) return "Model ${index + 1}: id"
        if (!ids.add(id)) return "Model ${index + 1}: duplicate id"
        val name = model["name"]
        if (name != null && (name.str().isNullOrEmpty())) return "Model ${index + 1}: name"
        for (field in listOf("contextWindow", "maxTokens")) {
            val value = model[field] ?: continue
            val primitive = value as? JsonPrimitive
            if (primitive == null || primitive.isString || (primitive.longOrNull ?: 0) <= 0)
                return "Model ${index + 1}: $field"
        }
    }
    return null
}

internal fun providerProtocolChoices(envelope: JsonElement?): List<String> {
    val refs = envelope["refs"] as? JsonObject
    fun resolve(raw: JsonElement?): JsonElement? {
        val id = (raw as? JsonPrimitive)?.content ?: (raw["uid"] as? JsonPrimitive)?.content
        return if (id != null && refs != null) refs[id] else raw
    }
    fun at(raw: JsonElement?, path: List<String>, visited: Set<JsonElement>): List<JsonElement> {
        val node = resolve(raw) ?: return emptyList()
        if (node in visited) return emptyList()
        if (path.isEmpty()) return listOf(node)
        return when (node["type"].str()) {
            "object" -> at(node["dict"][path.first()], path.drop(1), visited + node)
            "dict" -> at(node["inner"], path.drop(1), visited + node)
            "intersect", "union" -> node["list"].arr().orEmpty().flatMap { at(it, path, visited + node) }
            else -> emptyList()
        }
    }
    return at(envelope, listOf("providers", "__probe__", "api"), emptySet()).flatMap { node ->
        node["list"].arr().orEmpty().mapNotNull { resolve(it)["value"].str() }
    }.distinct()
}

internal fun settingsFailure(t: Throwable, secret: String = ""): String {
    if (t is CancellationException) throw t
    val message = t.message ?: L.unavailable()
    return if (secret.isBlank()) message else message.replace(secret, "••••••")
}

internal fun requireSettingsSuccess(result: JsonElement) {
    if (result["ok"].bool() == false || result["application"].str() == "failed") {
        error(result["error"]["message"].str() ?: result["message"].str() ?: L.unavailable())
    }
}

@Composable
internal fun SettingsMessage(message: String?, error: Boolean = false) {
    message?.let { Text(it, color = if (error) T.Err else T.Text3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) }
}

/**
 * Shared native settings chrome. Plugin management
 * and automation live in their own screens, following the official Host/UI
 * contracts; the pocket bridge remains the only remote transport.
 */

@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(T.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) {
                Icon(D.chevronLeft, st("返回", "Back"), tint = T.Ink)
            }
            Text(title, fontSize = 20.sp, color = T.Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
    }
}

/** Desktop settings card fill/stroke (`--dsw-alias-settings-card-*`) + radius-lg. */
@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(T.R16)
            .background(T.SettingsCard)
            .border(1.dp, T.SettingsCardStroke, T.R16),
        content = content,
    )
}

@Composable
internal fun SettingsNavRow(
    title: String,
    icon: ImageVector? = null,
    value: String? = null,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = T.Ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(title, fontSize = 16.sp, color = T.Ink, modifier = Modifier.weight(1f))
        value?.let {
            Text(it, fontSize = 15.sp, color = T.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
            Spacer(Modifier.width(6.dp))
        }
        Icon(D.chevronRight, null, tint = T.Text3, modifier = Modifier.size(20.dp))
    }
    if (divider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Border)
}

/** Label above control — phone adaptation of desktop settings field layout. */
@Composable
fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 13.sp, color = T.Text3, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
fun SettingsTextField(value: String, onChange: (String) -> Unit, hint: String = "", mono: Boolean = false, secret: Boolean = false, enabled: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(T.R12)
            .background(T.Input)
            .border(0.5.dp, T.Border, T.R12)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = T.Caption, fontSize = 15.sp)
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            enabled = enabled,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
            textStyle = (if (mono) T.Mono else MaterialTheme.typography.bodyMedium).copy(
                fontSize = 15.sp,
                color = T.Ink,
            ),
            cursorBrush = SolidColor(T.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ErrorOrEmpty(message: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = T.Text3, fontSize = 14.sp)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onRetry) { Text(L.retry(), color = T.Accent) }
        }
    }
}


internal fun transcriptText(value: TranscriptView): String = when (value) {
    TranscriptView.Compact -> st("简洁", "Compact")
    TranscriptView.Standard -> st("标准", "Standard")
    TranscriptView.Detailed -> st("详细", "Detailed")
    TranscriptView.Verbose -> st("完全展开", "Expanded")
}

internal fun collapseText(value: CollapseTiming): String = when (value) {
    CollapseTiming.Completion -> st("回答结束后", "After completion")
    CollapseTiming.NextInput -> st("下次有新消息时", "On the next input")
}

@Composable
internal fun AppearanceCards(current: Appearance, onPick: (Appearance) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Appearance.entries.forEach { value ->
            val label = when (value) { Appearance.Light -> L.light(); Appearance.Dark -> L.dark(); Appearance.System -> L.followSystem() }
            val icon = when (value) { Appearance.Light -> D.light; Appearance.Dark -> D.dark; Appearance.System -> D.followSystem }
            Column(
                Modifier.weight(1f).heightIn(min = 82.dp).clip(T.R20)
                    .background(if (current == value) T.Fill2 else Color.Transparent)
                    .border(0.5.dp, if (current == value) Color(0xFFADB2B8) else T.BorderStrong, T.R20)
                    .semantics { selected = current == value }
                    .clickable(role = Role.Button) { onPick(value) }
                    .padding(horizontal = 4.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, null, tint = T.Ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, color = T.Ink, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 2)
            }
        }
    }
}

@Composable
internal fun SettingsFontRow(title: String, description: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, color = T.Ink, fontSize = 16.sp)
        Text(description, color = T.Text3, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            IconButton({ onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.size(48.dp)) { Icon(D.chevronDown, st("减小字号", "Decrease font size"), tint = if (value > range.first) T.Ink else T.Text3) }
            Text("$value sp", color = T.Ink, modifier = Modifier.padding(horizontal = 12.dp))
            IconButton({ onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.size(48.dp)) { Icon(D.chevronDown, st("增大字号", "Increase font size"), tint = if (value < range.last) T.Ink else T.Text3, modifier = Modifier.rotate(180f)) }
        }
    }
}

@Composable
internal fun AgentPresetSettingsScreen(onBack: () -> Unit) {
    val host by Pocket.active.collectAsState()
    val ops by Pocket.ops.collectAsState()
    val scope = rememberCoroutineScope()
    var data by remember(host) { mutableStateOf<JsonElement?>(null) }
    var loading by remember(host) { mutableStateOf(true) }
    var busy by remember(host) { mutableStateOf(false) }
    var error by remember(host) { mutableStateOf<String?>(null) }
    var selected by remember(host) { mutableStateOf<String?>(null) }
    var details by remember(host) { mutableStateOf<JsonElement?>(null) }
    var saved by remember(host) { mutableStateOf(false) }
    var composition by remember(host) { mutableStateOf<String?>(null) }
    var compositionError by remember(host) { mutableStateOf<String?>(null) }
    var compositionLoading by remember(host) { mutableStateOf(false) }
    suspend fun loadComposition(row: JsonElement) {
        compositionLoading = true
        compositionError = null
        try {
            composition = Pocket.readPreset(row["id"].str().orEmpty())["content"].str()
                ?: error(st("主机未返回预设组成", "Host did not return the preset composition"))
        } catch (t: Throwable) { compositionError = settingsFailure(t) }
        finally { compositionLoading = false }
    }
    LaunchedEffect(details) {
        composition = null
        compositionError = null
        if ("presets.read" in ops) details?.let { loadComposition(it) }
    }
    suspend fun load() {
        data = if ("presets.settings" in ops) Pocket.presetsSettings()
        else {
            check("presets.list" in ops) { L.unavailable() }
            val roster = checkNotNull(Pocket.clientOrNull()) { L.unavailable() }.call("presets.list")
            buildJsonObject { put("presets", roster["presets"] ?: JsonArray(emptyList())); put("writable", false) }
        }
    }
    fun reload() {
        if (busy) return
        loading = true
        error = null
        scope.launch {
            try { load() } catch (t: Throwable) { error = settingsFailure(t) }
            finally { loading = false }
        }
    }
    LaunchedEffect(host, ops) {
        try { load() } catch (t: Throwable) { error = settingsFailure(t) }
        finally { loading = false }
    }
    val rows = data["presets"].arr().orEmpty()
    val current = rows.find { it["isDefault"].bool() == true }
    val writable = data["writable"].bool() == true && data["namespace"]["revision"] != null && "models.settingsMutate" in ops
    SettingsScaffold(st("Agent 预设", "Agent presets"), onBack = { if (!busy) onBack() }, actions = {
        IconButton(::reload, enabled = !busy && !loading) { Icon(D.refresh, L.retry(), tint = T.Ink) }
    }) {
        SettingsMessage(st("默认预设由主机提供，仅影响之后创建的会话。已有会话保持原预设。", "The Host default applies to new conversations. Existing conversations keep their preset."))
        SettingsMessage(current?.let { st("当前默认：", "Current default: ") + presetName(it) })
        SettingsMessage(error, true)
        if (saved) SettingsMessage(st("默认预设已保存并回读确认", "Default preset saved and confirmed"))
        when {
            loading -> ErrorOrEmpty(L.loading())
            rows.isEmpty() -> ErrorOrEmpty(L.unavailable(), ::reload)
            else -> SettingsCard {
                rows.forEach { row ->
                    val id = row["id"].str().orEmpty()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(id == (selected ?: current?.get("id").str()), { selected = id; saved = false }, enabled = writable && !busy && row["broken"].str() == null)
                            Text(presetName(row), color = T.Ink, modifier = Modifier.weight(1f))
                            TextButton({ details = row }) { Text(st("详情", "Details")) }
                        }
                        presetDescription(row)?.let { Text(it, color = T.Text3, fontSize = 13.sp) }
                        row["broken"].str()?.let { Text(it, color = T.Err, fontSize = 12.sp) }
                    }
                }
            }
        }
        if (!loading && !writable) SettingsMessage(st("当前主机仅支持查看预设，默认设置为只读", "This Host allows viewing presets only; the default is read-only"))
        Button({
            val id = selected ?: return@Button
            val revision = (data["namespace"]["revision"] as? JsonPrimitive)?.longOrNull ?: return@Button
            if (busy) return@Button
            busy = true; error = null
            scope.launch {
                try {
                    val client = checkNotNull(Pocket.clientOrNull()) { L.unavailable() }
                    requireSettingsSuccess(client.modelsSettingsMutate("agent-preset-registry", JsonArray(listOf(jsonOf("op" to "set", "path" to listOf("selectedDefault"), "value" to id))), revision))
                    load()
                    check(data["presets"].arr().orEmpty().any { it["id"].str() == id && it["isDefault"].bool() == true }) { st("已保存，但主机当前默认值未改变，可能存在更高优先级覆盖", "Saved, but the Host default is unchanged; a higher-priority override may be active") }
                    selected = null; saved = true
                } catch (t: Throwable) { error = settingsFailure(t) }
                finally { busy = false }
            }
        }, enabled = writable && !busy && selected != null && rows.any { it["id"].str() == selected && it["broken"].str() == null } && selected != current?.get("id").str(), modifier = Modifier.fillMaxWidth().padding(18.dp).heightIn(min = 48.dp)) { Text(if (busy) L.loading() else st("设为默认", "Set as default")) }
    }
    details?.let { row ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(presetName(row)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(row["id"].str().orEmpty(), style = T.Mono, color = T.Text3)
                Text(presetDescription(row) ?: st("主机未提供描述", "No description supplied by the Host"))
                if (row["isDefault"].bool() == true) Text(st("当前主机默认预设", "Current Host default"))
                row["broken"].str()?.let { Text(it, color = T.Err, fontSize = 12.sp) }
                if (compositionLoading) Text(L.loading(), color = T.Text3)
                compositionError?.let {
                    Text(it, color = T.Err)
                    TextButton({ scope.launch { loadComposition(row) } }, enabled = !compositionLoading) { Text(L.retry()) }
                }
                composition?.let { content ->
                    Spacer(Modifier.height(12.dp))
                    Text(st("预设组成（只读）", "Preset composition (read-only)"), color = T.Text3)
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(content, style = T.Mono.copy(fontSize = Pocket.prefs.codeFontSize.sp), color = T.Ink)
                    }
                }
            } },
            confirmButton = { TextButton({ details = null }) { Text(st("关闭", "Close")) } },
        )
    }
}
