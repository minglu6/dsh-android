package dev.dsh.pocket.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.serialization.json.*
import java.math.BigDecimal

internal fun providerKeyFailure(draft: String): String? {
    if (draft.isEmpty()) return null
    val value = draft.trim()
    if (value.isEmpty()) return "keyBlank"
    if (value.any { it.code !in 33..126 } || Regex("^[A-Z][A-Z0-9_]*=[^=]").containsMatchIn(value) ||
        (value.length > 1 && value.first() in "\"'`" && value.last() == value.first())) return "keyIllegalCharacters"
    return null
}

internal fun providerKeyRef(route: String) = "${route.uppercase().replace(Regex("[^A-Z0-9]+"), "_")}_API_KEY"

internal fun modelCapacity(text: String): JsonElement? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val match = Regex("^(\\d+(?:\\.\\d+)?)([km])?$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
        ?: return JsonPrimitive(text)
    return try {
        val scale = when (match.groupValues[2].lowercase()) { "k" -> 1000; "m" -> 1000000; else -> 1 }
        JsonPrimitive(BigDecimal(match.groupValues[1]).multiply(BigDecimal(scale)).longValueExact())
    } catch (_: ArithmeticException) { JsonPrimitive(text) }
}

internal fun patchModel(model: JsonElement, field: String, value: JsonElement?): JsonObject =
    JsonObject((model as? JsonObject).orEmpty().toMutableMap().apply {
        if (value == null) remove(field) else put(field, value)
    })

internal fun modelInputSelection(model: JsonElement, deepseek: Boolean, fallback: List<String>): List<String> =
    model[if (deepseek) "inputModalities" else "input"].arr()?.mapNotNull { it.str() }?.takeIf { it.isNotEmpty() }
        ?: fallback.ifEmpty { listOf("text") }

internal fun changeModelInput(model: JsonElement, deepseek: Boolean, selection: List<String>): JsonObject {
    require(selection.isNotEmpty())
    val next = patchModel(model, if (deepseek) "inputModalities" else "input", JsonArray(selection.map(::JsonPrimitive)))
    return if (deepseek && "image" !in selection) JsonObject(next - "imagePixelBudget" - "imageMaxBytes") else next
}

internal fun adoptedModel(candidate: JsonElement): JsonObject = buildJsonObject {
    for (field in listOf("id", "name", "contextWindow", "maxTokens")) candidate[field]?.let { put(field, it) }
    (candidate["inputModalities"] ?: candidate["input"])?.let { put("input", it) }
}

internal fun protocolLabel(protocol: String) = when (protocol) {
    "openai-completions" -> "OpenAI Chat Completions"
    "openai-responses" -> "OpenAI Responses"
    "anthropic-messages" -> "Anthropic Messages"
    else -> protocol
}
internal fun providerDisplayName(row: JsonElement?, route: String): String {
    val configuredName = row["profile"]["displayName"].str()?.takeIf { it.isNotBlank() }
    val name = configuredName ?: row["displayName"].str() ?: route
    if (route == "deepseek-account" && configuredName == null && row["declared"] != JsonPrimitive(true) &&
        (name == route || name == "DeepSeek Account")) {
        return st("DeepSeek 账号", "DeepSeek Account")
    }
    return name
}

internal fun providerOptionLabel(row: JsonElement?, route: String): String {
    val name = providerDisplayName(row, route)
    return if (name == route) route else "$name ($route)"
}

/** Desktop model forms use compact inputs rather than Material floating labels. */
@Composable
internal fun ModelFormField(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = T.Text2, fontSize = 12.sp, lineHeight = 18.sp)
        content()
    }
}

@Composable
internal fun ModelFormInput(
    value: String, onChange: (String) -> Unit, label: String, hint: String = "",
    enabled: Boolean = true, secret: Boolean = false, modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value, onChange, enabled = enabled, singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = if (enabled) T.Ink else T.Text3, fontSize = 14.sp, lineHeight = 22.sp),
        cursorBrush = SolidColor(T.Ink),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label }.onFocusChanged { focused = it.isFocused }
            .background(T.Surface, T.R8).border(0.5.dp, if (focused) T.Accent else T.BorderStrong, T.R8)
            .heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 5.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = ThemeState.colors.LabelDimmed, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 1)
                inner()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelFormButton(
    text: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false,
    compact: Boolean = false, add: Boolean = false, modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        OutlinedButton(
            onClick, enabled = enabled, modifier = modifier.heightIn(min = if (compact) 28.dp else 36.dp),
            shape = if (compact) T.R4 else T.R8,
            border = if (primary) null else BorderStroke(0.5.dp, ThemeState.colors.BorderL3),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (primary) T.PrimaryButton else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (primary) T.OnPrimary else T.Ink,
                disabledContainerColor = if (primary) T.PrimaryButton.copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color.Transparent,
                disabledContentColor = if (primary) T.OnPrimary.copy(alpha = 0.6f) else T.Text3,
            ),
            contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 14.dp, vertical = 4.dp),
        ) {
            if (add) { Icon(D.plus, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            Text(text, fontSize = if (compact) 12.sp else 14.sp, lineHeight = if (compact) 18.sp else 22.sp)
        }
    }
}

@Composable
internal fun ModelSettingsSelect(value: String, choices: List<String>, enabled: Boolean, label: (String) -> String = { it }, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.widthIn(max = 240.dp).fillMaxWidth().background(T.Surface, T.R8).border(0.5.dp, T.BorderStrong, T.R8)
                .clickable(enabled = enabled && choices.isNotEmpty()) { expanded = true }
                .heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(value).ifBlank { st("请选择", "Select") }, color = if (enabled) T.Ink else T.Text3, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
            Icon(D.chevronDown, null, Modifier.size(14.dp), tint = T.Text3)
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(label(choice), fontSize = 14.sp) }, onClick = { onChange(choice); expanded = false }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeModelList(
    models: List<JsonElement>, deepseek: Boolean, overridden: Boolean, enabled: Boolean,
    catalog: List<JsonElement>, defaults: JsonElement?, onChange: (JsonArray) -> Unit, onReset: (() -> Unit)?,
    onDiscover: (() -> Unit)? = null, discoverEnabled: Boolean = enabled,
) {
    var expanded by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var capacities by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    fun patch(index: Int, field: String, value: JsonElement?) {
        onChange(JsonArray(models.mapIndexed { at, model -> if (at == index) patchModel(model, field, value) else model }))
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = T.Border, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st("模型目录", "Model catalog"), color = T.Text2, fontSize = 12.sp, lineHeight = 18.sp)
                if (!overridden) Text(st("正在使用适配器默认模型", "Using adapter default models"), color = T.Text3, fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (onReset != null && overridden) TextButton({
                capacities = emptyMap(); expanded = emptySet(); onReset()
            }, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.textButtonColors(contentColor = T.Text3)) {
                Text(st("恢复默认模型", "Restore default models"), fontSize = 12.sp)
            }
        }
        models.forEachIndexed { index, model ->
            Column(Modifier.fillMaxWidth().border(0.5.dp, T.BorderStrong, T.R12).padding(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModelFormInput(model["id"].str().orEmpty(), { patch(index, "id", JsonPrimitive(it)) },
                        st("模型 ID ${index + 1}", "Model ID ${index + 1}"), st("模型 ID", "Model ID"), enabled, modifier = Modifier.weight(1.4f))
                    ModelFormInput(model["name"].str().orEmpty(), { patch(index, "name", it.takeIf(String::isNotEmpty)?.let(::JsonPrimitive)) },
                        st("显示名称 ${index + 1}", "Display name ${index + 1}"), st("显示名称", "Display name"), enabled, modifier = Modifier.weight(1f))
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        IconButton({ expanded = if (index in expanded) expanded - index else expanded + index }, Modifier.size(28.dp)) {
                            Icon(if (index in expanded) D.chevronDown else D.chevronRight, st("模型选项 ${index + 1}", "Model options ${index + 1}"), Modifier.size(14.dp), tint = T.Text3)
                        }
                        IconButton({
                            onChange(JsonArray(models.filterIndexed { at, _ -> at != index }))
                            capacities = capacities.mapNotNull { (key, value) ->
                                val at = key.substringBefore(':').toInt()
                                if (at == index) null else "${if (at > index) at - 1 else at}:${key.substringAfter(':')}" to value
                            }.toMap()
                            expanded = expanded.filter { it != index }.map { if (it > index) it - 1 else it }.toSet()
                        }, Modifier.size(28.dp), enabled = enabled) {
                            Icon(D.trash, st("删除模型 ${index + 1}", "Remove model ${index + 1}"), Modifier.size(14.dp), tint = T.Text3)
                        }
                    }
                }
                if (index in expanded) {
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (field in listOf("contextWindow", "maxTokens")) {
                                val buffer = "$index:$field"
                                val label = if (field == "contextWindow") st("上下文窗口", "Context window") else st("最大输出 token 数", "Max output tokens")
                                Column(Modifier.weight(1f)) {
                                    ModelFormField(label) {
                                        ModelFormInput(
                                            capacities[buffer] ?: modelCapacitySpelling(model[field]),
                                            { text -> capacities = capacities + (buffer to text); patch(index, field, modelCapacity(text)) },
                                            "$label ${index + 1}",
                                            modelCapacitySpelling(defaults[if (field == "contextWindow") "defaultContextWindow" else "maxTokens"]).ifEmpty { if (field == "contextWindow") "256K" else "32K" },
                                            enabled,
                                        )
                                    }
                                }
                            }
                        }
                        val installed = catalog.find { it["id"].str() == model["id"].str() }
                        val fallback = (installed["inputModalities"] ?: installed["input"] ?: defaults["defaultInput"]).arr().orEmpty().mapNotNull { it.str() }
                        val selected = modelInputSelection(model, deepseek, fallback)
                        Text(st("输入类型", "Input types"), color = T.Text3, fontSize = 12.sp, lineHeight = 18.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (input in listOf("text", "image")) {
                                val checked = input in selected
                                val canToggle = enabled && (!checked || selected.size > 1)
                                Row(Modifier.heightIn(min = 32.dp).clickable(enabled = canToggle) {
                                    val next = if (checked) selected - input else selected + input
                                    onChange(JsonArray(models.mapIndexed { at, entry -> if (at == index) changeModelInput(entry, deepseek, next) else entry }))
                                }, verticalAlignment = Alignment.CenterVertically) {
                                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                        Checkbox(checked, null, enabled = canToggle, modifier = Modifier.size(20.dp), colors = CheckboxDefaults.colors(checkedColor = T.Ink, checkmarkColor = T.OnPrimary))
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (input == "text") st("文本", "Text") else st("图片", "Image"), color = T.Ink, fontSize = 14.sp, lineHeight = 22.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (models.isEmpty()) Text(st("尚未配置模型", "No models configured"), color = T.Text3, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onDiscover != null) ModelFormButton(st("获取可用模型", "Get available models"), onDiscover, discoverEnabled, compact = true)
            ModelFormButton(st("添加模型", "Add model"), { onChange(JsonArray(models + buildJsonObject { put("id", "") })) }, enabled, compact = true, add = true)
        }
    }
}

internal fun modelCapacitySpelling(value: JsonElement?): String {
    val number = (value as? JsonPrimitive)?.longOrNull ?: return (value as? JsonPrimitive)?.content.orEmpty()
    return when {
        number >= 1_000_000 -> BigDecimal.valueOf(number).divide(BigDecimal(1_000_000)).stripTrailingZeros().toPlainString() + "M"
        number >= 1_000 -> BigDecimal.valueOf(number).divide(BigDecimal(1_000)).stripTrailingZeros().toPlainString() + "K"
        else -> number.toString()
    }
}

@Composable
internal fun ModelCandidatePicker(candidates: List<JsonElement>, existing: List<JsonElement>, onDismiss: () -> Unit, onPick: (List<JsonElement>) -> Unit) {
    val known = existing.mapNotNull { it["id"].str() }.toSet()
    var selected by remember(candidates) { mutableStateOf(candidates.mapNotNull { it["id"].str() }.filter { it !in known }.toSet()) }
    var query by remember { mutableStateOf("") }
    PocketSheet(onDismiss) {
        SheetTitle(st("选择模型", "Select models"))
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text(st("搜索模型", "Search models")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                candidates.filter { query.isBlank() || (it["id"].str().orEmpty() + it["name"].str().orEmpty()).contains(query, true) }.forEach { model ->
                    val id = model["id"].str().orEmpty()
                    Row(Modifier.fillMaxWidth().clickable(enabled = id !in known) { selected = if (id in selected) selected - id else selected + id }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(id in selected, null, enabled = id !in known)
                        Text(model["name"].str()?.let { "$it ($id)" } ?: id)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss) { Text(st("取消", "Cancel")) }
                TextButton(
                    { onPick(candidates.filter { it["id"].str() in selected && it["id"].str() !in known }) },
                    enabled = selected.isNotEmpty(),
                ) { Text(st("添加所选模型", "Add selected models")) }
            }
        }
    }
}
