package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

// Bound the library parser/serializer's recursion before calling either. Rendering itself
// walks a flat visible row list, so expanding a branch never nests Compose calls recursively.
internal const val TRAJECTORY_JSON_MAX_DEPTH = 128
private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

internal data class TrajectoryJsonDocument(val source: String, val root: JsonElement?, val depthLimited: Boolean)

internal fun trajectoryJsonDocument(source: String): TrajectoryJsonDocument {
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in source) {
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > TRAJECTORY_JSON_MAX_DEPTH) return TrajectoryJsonDocument(source, null, true)
            '}', ']' -> depth--
        }
    }
    val root = try { Json.parseToJsonElement(source) } catch (_: IllegalArgumentException) { null }
    if (root != null) {
        // JsonElement's parser also accepts some unquoted primitives. They are not valid
        // JSON, and must remain readable original text rather than being labelled JSON.
        val pending = ArrayDeque<JsonElement>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            when (val value = pending.removeLast()) {
                is JsonObject -> pending.addAll(value.values)
                is JsonArray -> pending.addAll(value)
                is JsonPrimitive -> if (!value.isString && value.content != "null" && value.content != "true" &&
                    value.content != "false" && !jsonNumber.matches(value.content)) return TrajectoryJsonDocument(source, null, false)
            }
        }
    }
    return TrajectoryJsonDocument(source, root, false)
}

internal data class TrajectoryJsonRow(
    val path: String,
    val label: String?,
    val value: JsonElement,
    val depth: Int,
    val closing: Boolean = false,
    val copyPath: String = "$",
) {
    val expandable: Boolean get() = when (value) {
        is JsonObject -> value.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
        else -> false
    }
}

internal fun trajectoryJsonRows(root: JsonElement, expanded: Set<String>): List<TrajectoryJsonRow> {
    val rows = mutableListOf<TrajectoryJsonRow>()
    val pending = ArrayDeque<TrajectoryJsonRow>()
    pending.add(TrajectoryJsonRow("", null, root, 0))
    while (pending.isNotEmpty()) {
        val row = pending.removeLast()
        rows.add(row)
        if (row.closing || !row.expandable || row.path !in expanded) continue
        pending.add(row.copy(label = null, closing = true))
        when (val value = row.value) {
            is JsonObject -> value.entries.toList().asReversed().forEach { (name, child) ->
                val segment = name.replace("~", "~0").replace("/", "~1")
                pending.add(TrajectoryJsonRow("${row.path}/$segment", name.ifEmpty { "\"\"" }, child, row.depth + 1,
                    copyPath = trajectoryJsonPropertyPath(row.copyPath, name)))
            }
            is JsonArray -> value.indices.reversed().forEach { index ->
                pending.add(TrajectoryJsonRow("${row.path}/$index", index.toString(), value[index], row.depth + 1,
                    copyPath = "${row.copyPath}[$index]"))
            }
            else -> Unit
        }
    }
    return rows
}

internal fun trajectoryJsonRowText(row: TrajectoryJsonRow, expanded: Boolean): String {
    val value = when (val element = row.value) {
        is JsonObject -> when {
            row.closing -> "}"
            element.isEmpty() -> "{}"
            expanded -> "{"
            else -> trajectoryJsonPreview(element)
        }
        is JsonArray -> when {
            row.closing -> "]"
            element.isEmpty() -> "[]"
            expanded -> "["
            else -> trajectoryJsonPreview(element)
        }
        else -> element.toString()
    }
    return row.label?.let { "$it: $value" } ?: value
}

/** Desktop JsonTree previews four object fields / five array entries, to depth two. */
internal fun trajectoryJsonPreview(value: JsonElement): String {
    fun preview(element: JsonElement, depth: Int): String = when (element) {
        is JsonObject -> if (depth >= 2) "{…}" else element.entries.asSequence().take(4)
            .joinToString(", ", "{", if (element.size > 4) ", …}" else "}") { (name, child) ->
                "$name: ${preview(child, depth + 1)}"
            }
        is JsonArray -> if (depth >= 2) "[…]" else element.asSequence().take(5)
            .joinToString(", ", "[", if (element.size > 5) ", …]" else "]") { preview(it, depth + 1) }
        else -> element.toString()
    }
    return preview(value, 0)
}

private val jsonPathIdentifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
internal fun trajectoryJsonPropertyPath(parent: String, name: String): String =
    if (jsonPathIdentifier.matches(name)) "$parent.$name" else "$parent[${JsonPrimitive(name)}]"

internal enum class TrajectoryJsonCopyMode(val label: String) {
    Value("复制值"), Json("复制 JSON"), Path("复制属性路径"),
    PrettyJson("复制格式化 JSON"), CompactJson("复制紧凑 JSON"),
}

private val containerCopyModes = listOf(TrajectoryJsonCopyMode.PrettyJson, TrajectoryJsonCopyMode.CompactJson, TrajectoryJsonCopyMode.Path)
private val primitiveCopyModes = listOf(TrajectoryJsonCopyMode.Value, TrajectoryJsonCopyMode.Json, TrajectoryJsonCopyMode.Path)
internal fun trajectoryJsonCopyModes(value: JsonElement): List<TrajectoryJsonCopyMode> =
    if (value is JsonObject || value is JsonArray) containerCopyModes else primitiveCopyModes

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val trajectoryPrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

internal fun trajectoryJsonCopyText(value: JsonElement, path: String, mode: TrajectoryJsonCopyMode): String = when (mode) {
    TrajectoryJsonCopyMode.Path -> path
    TrajectoryJsonCopyMode.PrettyJson -> trajectoryPrettyJson.encodeToString(JsonElement.serializer(), value)
    TrajectoryJsonCopyMode.Value -> if (value is JsonPrimitive && value.isString) value.content else value.toString()
    TrajectoryJsonCopyMode.Json, TrajectoryJsonCopyMode.CompactJson -> value.toString()
}

/** Shared by tool overview/payload/result/schema and request overview/options. */
@Composable
internal fun TrajectoryJsonValue(source: String, modifier: Modifier = Modifier, error: Boolean = false) {
    val document = remember(source) { trajectoryJsonDocument(source) }
    var expanded by remember(source) { mutableStateOf(setOf("")) }
    val rows = remember(document, expanded) { document.root?.let { trajectoryJsonRows(it, expanded) } }
    Column(modifier.fillMaxWidth().clip(T.R12)
        .background(if (error) T.Err.copy(alpha = 0.06f) else T.Fill2).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TrajectoryCopyButton(source, "trajectory-json-copy", document.root)
        }
        SelectableOutput {
            Column(Modifier.fillMaxWidth()) {
                if (rows == null) {
                    Text(source, style = T.Mono.copy(lineHeight = 20.sp, color = if (error) T.Err else T.Ink),
                        modifier = Modifier.fillMaxWidth().testTag("trajectory-json-text"))
                } else rows.forEach { row ->
                    key(row.path, row.closing) {
                        val open = row.path in expanded
                        Row(Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(10) * 10).dp),
                            verticalAlignment = Alignment.Top) {
                            if (row.expandable && !row.closing) {
                                Box(Modifier.size(28.dp).testTag("trajectory-json-toggle:${row.path}")
                                    .semantics { contentDescription = if (open) "收起" else "展开" }
                                    .clickable(role = Role.Button) {
                                        expanded = if (open) expanded - row.path else expanded + row.path
                                    }, contentAlignment = Alignment.Center) {
                                    Icon(if (open) D.chevronDown else D.chevronRight, null, Modifier.size(12.dp), tint = T.Text3)
                                }
                            } else Spacer(Modifier.width(28.dp))
                            Text(trajectoryJsonRowText(row, open),
                                style = T.Mono.copy(fontSize = 12.sp, lineHeight = 20.sp, color = if (error) T.Err else T.Ink),
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                                    .testTag("trajectory-json-${if (row.closing) "close" else "row"}:${row.path}"))
                            if (!row.closing && row.path.isNotEmpty()) {
                                TrajectoryCopyButton(source, "trajectory-json-copy:${row.path}", row.value, row.copyPath)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrajectoryCopyButton(source: String, tag: String, value: JsonElement? = null, path: String = "$") {
    val clipboard = LocalClipboardManager.current
    var copyResult by remember(source, value, path) { mutableStateOf<String?>(null) }
    var copySequence by remember(source, value, path) { mutableIntStateOf(0) }
    var menuOpen by remember(source, value, path) { mutableStateOf(false) }
    val modes = value?.let(::trajectoryJsonCopyModes)
    val defaultMode = modes?.first()
    LaunchedEffect(copySequence) { if (copySequence > 0) { delay(1500); copyResult = null } }
    fun copy(mode: TrajectoryJsonCopyMode?) {
        menuOpen = false
        copyResult = try {
            val text = if (value != null && mode != null) trajectoryJsonCopyText(value, path, mode) else source
            clipboard.setText(AnnotatedString(text))
            "复制成功"
        } catch (_: Exception) {
            "复制失败"
        }
        copySequence++
    }
    Box {
        Box(Modifier.size(28.dp).testTag(tag)
            .semantics { contentDescription = copyResult ?: defaultMode?.label ?: "复制" }
            .combinedClickable(
                role = Role.Button,
                onClick = { copy(defaultMode) },
                onLongClick = if (modes == null) null else ({ menuOpen = true }),
            ), contentAlignment = Alignment.Center) {
            Icon(if (copyResult == "复制成功") D.check else D.copy, null, Modifier.size(14.dp), tint = T.Text3)
        }
        DropdownMenu(menuOpen, { menuOpen = false }, containerColor = T.Surface, shape = T.R12) {
            modes?.forEach { mode ->
                DropdownMenuItem(text = { Text(mode.label) }, onClick = { copy(mode) })
            }
        }
    }
}
