package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
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
internal fun TrajectoryJsonValue(
    source: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    collapsedStringLines: Int = 3,
) {
    val document = remember(source) { trajectoryJsonDocument(source) }
    var expanded by remember(source) { mutableStateOf(setOf("")) }
    val rows = remember(document, expanded) { document.root?.let { trajectoryJsonRows(it, expanded) } }
    val colors = trajectoryJsonColors(error)
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
                        val string = (row.value as? JsonPrimitive)?.takeIf { it.isString }
                        if (string != null) {
                            TrajectoryJsonStringField(row, string.content, collapsedStringLines, colors,
                                Modifier.padding(start = (row.depth.coerceAtMost(10) * 10).dp))
                        } else Row(Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(10) * 10).dp),
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
                            Text(trajectoryJsonStyledRow(row, open, colors),
                                style = T.Mono.copy(fontSize = 12.sp, lineHeight = 20.sp, color = colors.punctuation),
                                maxLines = if (row.expandable && !open) 1 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
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

private data class TrajectoryJsonColors(val property: Color, val string: Color, val keyword: Color, val punctuation: Color)

@Composable
private fun trajectoryJsonColors(error: Boolean): TrajectoryJsonColors = when {
    error -> TrajectoryJsonColors(T.Err, T.Err, T.Err, T.Err)
    ThemeState.dark -> TrajectoryJsonColors(Color(0xFF5DB0D7), Color(0xFFF28B82), Color(0xFF99C8FF), Color(0xFFE8EAED))
    else -> TrajectoryJsonColors(Color(0xFF881391), Color(0xFFC41A16), Color(0xFF1C00CF), Color(0xFF202124))
}

// Linear scan: Regex.findAll on long escaped strings StackOverflows the JVM matcher.
private fun trajectoryJsonStyledRow(row: TrajectoryJsonRow, expanded: Boolean, colors: TrajectoryJsonColors): AnnotatedString {
    val text = trajectoryJsonRowText(row.copy(label = null), expanded)
    return buildAnnotatedString {
        row.label?.let { withStyle(SpanStyle(color = colors.property)) { append("$it: ") } }
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"') {
                val startIdx = i
                i++
                while (i < text.length) {
                    when (text[i]) {
                        '\\' -> i = (i + 2).coerceAtMost(text.length)
                        '"' -> { i++; break }
                        else -> i++
                    }
                }
                withStyle(SpanStyle(color = colors.string)) { append(text.substring(startIdx, i)) }
                continue
            }
            if (ch.isDigit() || ch == '-' || ch == 't' || ch == 'f' || ch == 'n') {
                val boundaryOk = i == 0 || !isJsonTokenChar(text[i - 1])
                val keyword = when {
                    text.startsWith("true", i) -> "true"
                    text.startsWith("false", i) -> "false"
                    text.startsWith("null", i) -> "null"
                    else -> null
                }
                if (boundaryOk && keyword != null) {
                    val endIdx = i + keyword.length
                    if (endIdx >= text.length || !isJsonTokenChar(text[endIdx])) {
                        withStyle(SpanStyle(color = colors.keyword)) { append(keyword) }
                        i = endIdx
                        continue
                    }
                }
                if (boundaryOk && (ch == '-' || ch.isDigit())) {
                    var endIdx = i
                    if (text[endIdx] == '-') endIdx++
                    if (endIdx < text.length && text[endIdx].isDigit()) {
                        while (endIdx < text.length && text[endIdx].isDigit()) endIdx++
                        if (endIdx < text.length && text[endIdx] == '.') {
                            val frac = endIdx + 1
                            if (frac < text.length && text[frac].isDigit()) {
                                endIdx = frac
                                while (endIdx < text.length && text[endIdx].isDigit()) endIdx++
                            }
                        }
                        if (endIdx < text.length && (text[endIdx] == 'e' || text[endIdx] == 'E')) {
                            var exp = endIdx + 1
                            if (exp < text.length && (text[exp] == '+' || text[exp] == '-')) exp++
                            if (exp < text.length && text[exp].isDigit()) {
                                endIdx = exp
                                while (endIdx < text.length && text[endIdx].isDigit()) endIdx++
                            }
                        }
                        if (endIdx >= text.length || !isJsonTokenChar(text[endIdx])) {
                            withStyle(SpanStyle(color = colors.keyword)) { append(text.substring(i, endIdx)) }
                            i = endIdx
                            continue
                        }
                    }
                }
            }
            append(ch)
            i++
        }
    }
}

private fun isJsonTokenChar(ch: Char): Boolean =
    ch.isLetterOrDigit() || ch == '_' || ch == '$'

/** Collapsed JSON stays escaped; expansion shows the original, independently scrollable string. */
@Composable
private fun TrajectoryJsonStringField(
    row: TrajectoryJsonRow,
    value: String,
    collapsedStringLines: Int,
    colors: TrajectoryJsonColors,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(value) { mutableStateOf(false) }
    var truncated by remember(value, collapsedStringLines) { mutableStateOf(false) }
    var wrapped by remember(value) { mutableStateOf(false) }
    val style = T.Mono.copy(fontSize = 12.sp, lineHeight = 20.sp, color = colors.punctuation)
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (expanded) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.label?.let { "$it:" }.orEmpty(), style = style.copy(color = colors.property),
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(28.dp).testTag("trajectory-json-string-wrap:${row.path}")
                    .semantics { contentDescription = if (wrapped) L.unwrapLines() else L.wrapLines() }
                    .clickable(role = Role.Button) {
                        wrapped = !wrapped
                        Pocket.prefs.trajectoryJsonStringWrapping = wrapped
                    }, contentAlignment = Alignment.Center) {
                    Icon(D.wrapLines, null, Modifier.size(14.dp), tint = if (wrapped) T.Link else T.Text3)
                }
                Box(Modifier.size(28.dp).testTag("trajectory-json-string-collapse:${row.path}")
                    .semantics { contentDescription = "收起" }
                    .clickable(role = Role.Button) { expanded = false }, contentAlignment = Alignment.Center) {
                    Icon(D.chevronDown, null, Modifier.size(14.dp).rotate(180f), tint = T.Text3)
                }
                TrajectoryCopyButton(value, "trajectory-json-copy:${row.path}", row.value, row.copyPath)
            }
            Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(value, style = style, softWrap = wrapped,
                    modifier = Modifier.testTag("trajectory-json-string-raw:${row.path}")
                        .then(if (wrapped) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())))
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(trajectoryJsonStyledRow(row, false, colors), style = style,
                    maxLines = collapsedStringLines, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { truncated = it.hasVisualOverflow },
                    modifier = Modifier.weight(1f).testTag("trajectory-json-row:${row.path}"))
                TrajectoryCopyButton(value, "trajectory-json-copy:${row.path}", row.value, row.copyPath)
            }
            if (truncated) {
                Text("… 展开", style = style.copy(color = T.Text3),
                    modifier = Modifier.testTag("trajectory-json-string-expand:${row.path}")
                        .clickable(role = Role.Button) {
                            wrapped = Pocket.prefs.trajectoryJsonStringWrapping
                            expanded = true
                        }.padding(vertical = 4.dp))
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
