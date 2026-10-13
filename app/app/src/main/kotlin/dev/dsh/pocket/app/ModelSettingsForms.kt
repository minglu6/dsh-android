package dev.dsh.pocket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    model[if (deepseek) "inputModalities" else "input"].arr()?.mapNotNull { it.str() }
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

@Composable
internal fun ModelSettingsSelect(value: String, choices: List<String>, enabled: Boolean, label: (String) -> String = { it }, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(label(value).ifBlank { st("请选择", "Select") }, modifier = Modifier.weight(1f))
            Icon(D.chevronDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(label(choice)) }, onClick = { onChange(choice); expanded = false }) }
        }
    }
}

@Composable
internal fun NativeModelList(
    models: List<JsonElement>, deepseek: Boolean, overridden: Boolean, enabled: Boolean,
    catalog: List<JsonElement>, defaults: JsonElement?, onChange: (JsonArray) -> Unit, onReset: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var capacities by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    fun patch(index: Int, field: String, value: JsonElement?) {
        onChange(JsonArray(models.mapIndexed { at, model -> if (at == index) patchModel(model, field, value) else model }))
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(st("模型选项", "Model options"), color = T.Ink)
        Text(if (overridden) st("自定义模型", "Customized models") else st("继承默认模型", "Inherited models"), color = T.Text3, fontSize = 12.sp)
        if (onReset != null && overridden) TextButton({ capacities = emptyMap(); expanded = emptySet(); onReset() }, enabled = enabled) { Text(st("恢复默认模型", "Restore default models")) }
        models.forEachIndexed { index, model ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st("模型 ${index + 1}", "Model ${index + 1}"), color = T.Text2, modifier = Modifier.weight(1f))
                    TextButton({
                        onChange(JsonArray(models.filterIndexed { at, _ -> at != index }))
                        capacities = capacities.mapNotNull { (key, value) ->
                            val at = key.substringBefore(':').toInt()
                            if (at == index) null else "${if (at > index) at - 1 else at}:${key.substringAfter(':')}" to value
                        }.toMap()
                        expanded = expanded.filter { it != index }.map { if (it > index) it - 1 else it }.toSet()
                    }, enabled = enabled) { Text(L.delete(), color = T.Err) }
                }
                OutlinedTextField(model["id"].str().orEmpty(), { patch(index, "id", JsonPrimitive(it)) }, label = { Text(st("模型 ID", "Model ID")) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model["name"].str().orEmpty(), { patch(index, "name", it.takeIf(String::isNotEmpty)?.let(::JsonPrimitive)) }, label = { Text(st("显示名称（可选）", "Display name (optional)")) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                TextButton({ expanded = if (index in expanded) expanded - index else expanded + index }) { Text(st("容量与输入类型", "Capacity and input types")); Icon(if (index in expanded) D.chevronDown else D.chevronRight, null) }
                if (index in expanded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (field in listOf("contextWindow", "maxTokens")) {
                            val buffer = "$index:$field"
                            OutlinedTextField(
                                capacities[buffer] ?: (model[field] as? JsonPrimitive)?.content.orEmpty(),
                                { text -> capacities = capacities + (buffer to text); patch(index, field, modelCapacity(text)) },
                                label = { Text(if (field == "contextWindow") st("上下文窗口", "Context window") else st("最大输出 token", "Max output tokens")) },
                                placeholder = { Text((defaults[if (field == "contextWindow") "defaultContextWindow" else "maxTokens"] as? JsonPrimitive)?.content ?: if (field == "contextWindow") "256K" else "32K") },
                                singleLine = true, enabled = enabled, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    val installed = catalog.find { it["id"].str() == model["id"].str() }
                    val fallback = (installed["inputModalities"] ?: installed["input"] ?: defaults["defaultInput"]).arr().orEmpty().mapNotNull { it.str() }
                    val selected = modelInputSelection(model, deepseek, fallback)
                    Text(st("输入类型", "Input types"), color = T.Text3, modifier = Modifier.padding(top = 12.dp))
                    Row {
                        for (input in listOf("text", "image")) {
                            val checked = input in selected
                            val canToggle = enabled && (!checked || selected.size > 1)
                            Row(Modifier.weight(1f).clickable(enabled = canToggle) {
                                val next = if (checked) selected - input else selected + input
                                onChange(JsonArray(models.mapIndexed { at, entry -> if (at == index) changeModelInput(entry, deepseek, next) else entry }))
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, null, enabled = canToggle)
                                Text(if (input == "text") st("文本", "Text") else st("图片", "Image"), color = T.Ink)
                            }
                        }
                    }
                }
                HorizontalDivider(color = T.Border, modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (models.isEmpty()) Text(st("尚无自定义模型", "No custom models"), color = T.Text3, modifier = Modifier.padding(vertical = 12.dp))
        TextButton({ onChange(JsonArray(models + buildJsonObject { put("id", "") })) }, enabled = enabled) { Text(st("添加模型", "Add model")) }
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
