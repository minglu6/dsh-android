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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.jsonOf
import dev.dsh.pocket.protocol.str
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
    CompositionLocalProvider(LocalContentColor provides T.Ink) {
        Column(
            Modifier
                .fillMaxSize()
                .background(T.Surface2)
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
    CollapseTiming.Completion -> st("回答结束后", "On completion")
    CollapseTiming.NextInput -> st("下次有新消息时", "On next message")
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
internal fun SettingsFontRow(
    title: String,
    description: String,
    value: Int,
    range: IntRange,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = T.Ink, fontSize = 14.sp)
            Text(description, color = T.Text3, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Box(Modifier.width(72.dp).height(56.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(36.dp).clip(T.R8).background(T.Fill2))
            Text("$value", color = T.Ink, fontSize = 14.sp)
            Column(Modifier.width(32.dp).align(Alignment.CenterEnd)) {
                // Keep the desktop pill size; enlarge the arrows' transparent, disjoint touch areas.
                repeat(2) { index ->
                    val delta = if (index == 0) 1 else -1
                    val enabled = value + delta in range
                    Box(
                        Modifier.size(width = 32.dp, height = 28.dp)
                            .semantics { contentDescription = "$title：${if (delta > 0) st("增大字号", "Increase font size") else st("减小字号", "Decrease font size")}" }
                            .clickable(enabled = enabled, role = Role.Button) { onChange(value + delta) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(width = 17.dp, height = 12.dp).clip(T.R4)
                            .background(T.Surface.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
                            Icon(D.chevronDown, null, tint = if (enabled) T.Ink else T.Caption,
                                modifier = Modifier.size(10.dp).rotate(if (delta > 0) 180f else 0f))
                        }
                    }
                }
            }
        }
        Text("px", color = T.Text2, fontSize = 14.sp)
        if (onToggle != null) {
            IconButton(onToggle, modifier = Modifier.size(40.dp).testTag("fontSettingsToggle")
                .semantics { stateDescription = if (expanded == true) st("已展开", "Expanded") else st("已折叠", "Collapsed") }) {
                Icon(D.chevronDown, st("更多字体设置", "More font settings"), tint = T.Text2,
                    modifier = Modifier.size(14.dp).rotate(if (expanded == true) 180f else 0f))
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Border)
}

@Composable
internal fun SettingsFontFamilyRow(title: String, description: String, value: String, tag: String, onChange: (String) -> Unit) {
    val host by Pocket.active.collectAsState()
    var draft by remember(host) { mutableStateOf(TextFieldValue(value)) }
    var dirty by remember(host) { mutableStateOf(false) }
    var hadFocus by remember(host) { mutableStateOf(false) }
    LaunchedEffect(value, host) {
        if (!dirty) draft = TextFieldValue(value)
        else if (normalizeFontFamily(draft.text) == value) dirty = false
    }
    val focus = LocalFocusManager.current
    fun commit() {
        if (draft.composition != null) return
        if (!dirty) return
        val normalized = normalizeFontFamily(draft.text)
        draft = TextFieldValue(normalized)
        if (normalized == value) { dirty = false; return }
        onChange(normalized)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = T.Ink, fontSize = 14.sp)
            Text(description, color = T.Text3, fontSize = 12.sp, lineHeight = 18.sp)
        }
        BasicTextField(
            value = draft,
            onValueChange = { if (it.text.length <= 200) { draft = it; dirty = true } },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = T.Ink, fontSize = 14.sp),
            cursorBrush = SolidColor(T.Accent),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (draft.composition == null) { commit(); focus.clearFocus() } }),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(tag)
                .semantics { contentDescription = title }
                .onFocusChanged { state ->
                    if (hadFocus && !state.isFocused) commit()
                    hadFocus = state.isFocused
                }
                .onPreviewKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyUp && draft.composition == null) {
                        commit(); focus.clearFocus(); true
                    } else false
                },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().background(T.Surface, T.R8).border(0.5.dp, T.BorderStrong, T.R8).padding(horizontal = 10.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart) {
                    if (draft.text.isEmpty()) Text(st("默认", "Default"), fontSize = 14.sp, color = T.Caption)
                    inner()
                }
            },
        )
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Border)
}

/** Keep long setting names and values out of the trailing control's touch target. */
@Composable
internal fun GeneralPreferenceRow(
    title: String,
    description: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp, color = T.Ink)
            description?.let { Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = T.Text3) }
        }
        value?.let {
            Text(it, fontSize = 14.sp, color = T.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 136.dp))
        }
        if (trailing != null) trailing()
        else if (onClick != null) Icon(D.chevronDown, null, tint = T.Text2, modifier = Modifier.size(18.dp))
    }
    if (divider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = T.Border)
}
