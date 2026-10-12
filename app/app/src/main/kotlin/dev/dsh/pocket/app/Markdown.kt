package dev.dsh.pocket.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** Pure-Kotlin AST blocks for tests and Compose rendering (desktop MarkdownText parity). */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class BulletList(val items: List<List<MdBlock>>, val tight: Boolean) : MdBlock
    data class OrderedList(val items: List<List<MdBlock>>, val start: Int, val tight: Boolean) : MdBlock
    data class Quote(val children: List<MdBlock>) : MdBlock
    data class Code(val language: String?, val literal: String, val incomplete: Boolean = false) : MdBlock
    /** headers/rows: list of rows; each row is list of cells; each cell is inline spans. */
    data class Table(val headers: List<List<List<MdSpan>>>, val rows: List<List<List<MdSpan>>>) : MdBlock
    data object Rule : MdBlock
}

sealed interface MdSpan {
    data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false) : MdSpan
    data class Code(val text: String) : MdSpan
    data class Link(val text: String, val dest: String) : MdSpan
    /** Desktop file chip: `</>` + path basename (local Markdown destinations). */
    data class FileChip(val path: String, val label: String) : MdSpan
}

private val MD_PARSER: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create()))
    .build()

private val FILE_PATH = Regex("""^(?:[A-Za-z]:[\\/]|~[/\\]|[.]{0,2}[/\\])?[^\s`]+?\.[A-Za-z0-9]{1,12}$""")
private val FILE_EXT = Regex("""\.(ya?ml|json|jsonl|toml|md|txt|kt|kts|java|js|jsx|ts|tsx|mjs|cjs|py|rs|go|c|h|cpp|hpp|cs|swift|rb|php|sh|bash|zsh|css|scss|html|xml|gradle|properties|proto|sql|svg|png|jpe?g|gif|webp|pdf)$""", RegexOption.IGNORE_CASE)

/**
 * Parse GFM markdown into [MdBlock]s. Handles streaming partial text: an unclosed
 * top-level fence is emitted as an incomplete code block (desktop MarkdownText stream path).
 */
fun parseMarkdown(source: String, streaming: Boolean = false): List<MdBlock> {
    if (source.isEmpty()) return emptyList()
    val (stable, liveFence) = splitStreamingFence(source, streaming)
    val doc = MD_PARSER.parse(stable.ifEmpty { "\n" })
    val blocks = ArrayList<MdBlock>()
    var n = doc.firstChild
    while (n != null) {
        convertBlock(n)?.let { blocks += it }
        n = n.next
    }
    if (liveFence != null) {
        val lang = liveFence.substringBefore('\n', missingDelimiterValue = "").trim()
            .takeIf { it.isNotEmpty() && !it.contains(' ') && liveFence.contains('\n') }
        val body = if (liveFence.contains('\n')) liveFence.substringAfter('\n') else liveFence
        blocks += MdBlock.Code(lang, body, incomplete = true)
    }
    return blocks
}

/**
 * Sample used by unit tests — table + list + inline code + file link, matching the
 * desktop chat screenshot content shape.
 */
val SAMPLE_MARKDOWN = """
**模型配置** (来自 [cordis.patch.yml](cordis.patch.yml))：

| 配置项 | 值 |
| --- | --- |
| Provider | `qoder-cn` |
| Model | `auto` |
| Reasoning | `high` |
| VPC Instance | `sungrow-of-enterprise` |

解读：

- `model: auto` —— 由路由自动选择模型
- `provider: qoder-cn` —— 国内提供方
- `reasoningEffort` 为 `high`

当 `model` 为 `auto` 时，`reasoningEffort` 影响深度。
""".trimIndent()

/** Split off an unclosed top-level ``` fence so the stable prefix still parses. */
internal fun splitStreamingFence(source: String, streaming: Boolean): Pair<String, String?> {
    if (!streaming) return source to null
    var i = 0
    var openAt = -1
    while (i < source.length) {
        if (source.startsWith("```", i) && (i == 0 || source[i - 1] == '\n')) {
            val lineEnd = source.indexOf('\n', i).let { if (it < 0) source.length else it }
            if (openAt < 0) {
                openAt = i
                i = lineEnd + 1
            } else {
                openAt = -1
                i = lineEnd + 1
            }
        } else {
            val next = source.indexOf('\n', i)
            i = if (next < 0) source.length else next + 1
        }
    }
    if (openAt < 0) return source to null
    val fenceBody = source.substring(openAt + 3)
    return source.take(openAt).trimEnd() to fenceBody
}

internal fun parseFileLink(destination: String): String? {
    val hash = destination.indexOf('#')
    val dest = if (hash < 0) destination else destination.substring(0, hash)
    if (dest.contains('?')) return null
    val path = try {
        java.net.URLDecoder.decode(dest, "UTF-8")
    } catch (_: Exception) {
        return null
    }
    if (path.isEmpty() || path.any { it.code in 0..0x1f || it.code == 0x7f }) return null
    if (path.startsWith("//") || path.startsWith("\\\\")) return null
    if (Regex("""^[a-z][a-z\d+.-]*:""", RegexOption.IGNORE_CASE).containsMatchIn(path) &&
        !Regex("""^[a-z]:[\\/]""", RegexOption.IGNORE_CASE).containsMatchIn(path)
    ) return null
    return path
}

private fun looksLikeFilePath(value: String): Boolean {
    val v = value.trim()
    if (v != value || v.length < 3 || v.length > 200) return false
    if (v.contains(' ') || v.contains('`')) return false
    if (parseFileLink(v) == null && !FILE_PATH.matches(v)) return false
    return FILE_EXT.containsMatchIn(v) || v.contains('/') || v.contains('\\')
}

private fun convertBlock(node: Node): MdBlock? = when (node) {
    is Paragraph -> MdBlock.Paragraph(inlineSpans(node))
    is Heading -> MdBlock.Heading(node.level, inlineSpans(node))
    is BulletList -> MdBlock.BulletList(listItems(node), tight = node.isTight)
    is OrderedList -> MdBlock.OrderedList(listItems(node), start = node.markerStartNumber, tight = node.isTight)
    is BlockQuote -> MdBlock.Quote(childBlocks(node))
    is FencedCodeBlock -> MdBlock.Code(node.info?.takeIf { it.isNotBlank() }, node.literal.trimEnd('\n'))
    is IndentedCodeBlock -> MdBlock.Code(null, node.literal.trimEnd('\n'))
    is ThematicBreak -> MdBlock.Rule
    is TableBlock -> convertTable(node)
    else -> null
}

private fun childBlocks(parent: Node): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    var c = parent.firstChild
    while (c != null) {
        convertBlock(c)?.let { out += it }
        c = c.next
    }
    return out
}

private fun listItems(list: Node): List<List<MdBlock>> {
    val items = ArrayList<List<MdBlock>>()
    var c = list.firstChild
    while (c != null) {
        if (c is ListItem) items += childBlocks(c).ifEmpty {
            listOf(MdBlock.Paragraph(inlineSpans(c)))
        }
        c = c.next
    }
    return items
}

private fun convertTable(table: TableBlock): MdBlock.Table {
    val headers = ArrayList<List<List<MdSpan>>>()
    val rows = ArrayList<List<List<MdSpan>>>()
    var section = table.firstChild
    while (section != null) {
        when (section) {
            is TableHead -> {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) headers += tableCells(row)
                    row = row.next
                }
            }
            is TableBody -> {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) rows += tableCells(row)
                    row = row.next
                }
            }
        }
        section = section.next
    }
    return MdBlock.Table(headers, rows)
}

private fun tableCells(row: TableRow): List<List<MdSpan>> {
    val cells = ArrayList<List<MdSpan>>()
    var c = row.firstChild
    while (c != null) {
        if (c is TableCell) cells += inlineSpans(c)
        c = c.next
    }
    return cells
}

private fun inlineSpans(parent: Node, bold: Boolean = false, italic: Boolean = false): List<MdSpan> {
    val out = ArrayList<MdSpan>()
    var c = parent.firstChild
    while (c != null) {
        when (c) {
            is MdText -> out += MdSpan.Text(c.literal, bold, italic)
            is SoftLineBreak, is HardLineBreak -> out += MdSpan.Text("\n", bold, italic)
            is StrongEmphasis -> out += inlineSpans(c, bold = true, italic = italic)
            is Emphasis -> out += inlineSpans(c, bold = bold, italic = true)
            is Code -> {
                val lit = c.literal
                val path = lit.takeIf { looksLikeFilePath(it) }?.let { parseFileLink(it) ?: it }
                out += if (path != null) MdSpan.FileChip(path, path.substringAfterLast('/').substringAfterLast('\\'))
                else MdSpan.Code(lit)
            }
            is Link -> {
                val dest = c.destination.orEmpty()
                val label = plainText(c).ifBlank { dest }
                val file = parseFileLink(dest)
                out += if (file != null) MdSpan.FileChip(file, label.ifBlank { file.substringAfterLast('/').substringAfterLast('\\') })
                else MdSpan.Link(label, dest)
            }
            else -> out += inlineSpans(c, bold, italic)
        }
        c = c.next
    }
    return out
}

private fun plainText(node: Node): String = buildString {
    var c = node.firstChild
    while (c != null) {
        when (c) {
            is MdText -> append(c.literal)
            is Code -> append(c.literal)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            else -> append(plainText(c))
        }
        c = c.next
    }
}

// region Compose

@Composable
fun MarkdownView(text: String, streaming: Boolean = false, modifier: Modifier = Modifier) {
    val blocks = remember(text, streaming) { parseMarkdown(text, streaming) }
    val bodySp = Pocket.prefs.fontSize.sp
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) Spacer(Modifier.height(blockGap(blocks[i - 1], block)))
            MdBlockView(block, bodySp)
        }
    }
}

private fun blockGap(prev: MdBlock, cur: MdBlock): androidx.compose.ui.unit.Dp = when {
    prev is MdBlock.Heading && (cur is MdBlock.BulletList || cur is MdBlock.OrderedList) -> 8.dp
    else -> 16.dp
}

@Composable
private fun MdBlockView(block: MdBlock, bodySp: TextUnit) {
    when (block) {
        is MdBlock.Paragraph -> InlineRichText(block.spans, TextStyle(fontSize = bodySp, lineHeight = (bodySp.value + 10).sp, color = T.Ink))
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 28.sp; 2 -> 24.sp; 3 -> 20.sp; 4 -> 17.sp; else -> bodySp
            }
            InlineRichText(
                block.spans,
                TextStyle(fontSize = size, lineHeight = (size.value + 8).sp, fontWeight = FontWeight.SemiBold, color = T.Ink),
            )
        }
        is MdBlock.BulletList -> Column {
            block.items.forEachIndexed { idx, item ->
                if (idx > 0) Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("•", color = T.Text2, fontSize = bodySp, modifier = Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) { item.forEach { MdBlockView(it, bodySp) } }
                }
            }
        }
        is MdBlock.OrderedList -> Column {
            block.items.forEachIndexed { idx, item ->
                if (idx > 0) Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("${block.start + idx}.", color = T.Text2, fontSize = bodySp, modifier = Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) { item.forEach { MdBlockView(it, bodySp) } }
                }
            }
        }
        is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(T.Caption))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                block.children.forEachIndexed { i, child ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    MdBlockView(child, bodySp)
                }
            }
        }
        is MdBlock.Code -> FencedCode(block.language, block.literal, block.incomplete)
        is MdBlock.Table -> GfmTable(block)
        is MdBlock.Rule -> HorizontalDivider(color = T.Border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun FencedCode(language: String?, literal: String, incomplete: Boolean) {
    val highlighted = remember(literal, language) { highlightCode(literal, language) }
    SelectionContainer {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(T.R12)
                .background(TokyoNight.Bg)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (!language.isNullOrBlank()) {
                Text(language, style = T.Mono.copy(color = TokyoNight.Ansi[8], fontFamily = MesloNerdFamily, fontSize = 11.sp))
                Spacer(Modifier.height(8.dp))
            }
            Text(
                highlighted,
                style = TextStyle(
                    fontFamily = MesloNerdFamily,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = TokyoNight.Fg,
                ),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
            if (incomplete) {
                Spacer(Modifier.height(4.dp))
                Text("…", color = TokyoNight.Ansi[8], fontFamily = MesloNerdFamily, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun GfmTable(table: MdBlock.Table) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        if (table.headers.isNotEmpty()) {
            table.headers.forEach { header ->
                TableRowView(header, header = true)
                HorizontalDivider(color = T.Border, thickness = 0.5.dp)
            }
        }
        table.rows.forEachIndexed { i, row ->
            TableRowView(row, header = false)
            if (i != table.rows.lastIndex) HorizontalDivider(color = T.Border, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun TableRowView(cells: List<List<MdSpan>>, header: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, cell ->
            Box(
                Modifier
                    .width(if (i == 0) 140.dp else 200.dp)
                    .padding(vertical = 10.dp, horizontal = if (i == 0) 0.dp else 12.dp),
            ) {
                InlineRichText(
                    cell,
                    TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                        color = T.Ink,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineRichText(spans: List<MdSpan>, style: TextStyle) {
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        for (s in spans) when (s) {
            is MdSpan.Text -> Text(
                s.text,
                style = style.copy(
                    fontWeight = if (s.bold) FontWeight.SemiBold else style.fontWeight,
                    fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
                ),
            )
            is MdSpan.Code -> InlineCodeChip(s.text, style.fontSize)
            is MdSpan.FileChip -> FileLinkChip(s.path, s.label)
            is MdSpan.Link -> Text(
                s.text,
                style = style.copy(color = T.Accent, fontWeight = FontWeight.Medium),
                modifier = Modifier.clickable {
                    runCatching { uri.openUri(s.dest) }.recoverCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.dest)))
                    }
                },
            )
        }
    }
}

@Composable
private fun InlineCodeChip(text: String, surrounding: TextUnit) {
    Text(
        text,
        style = TextStyle(
            fontFamily = MesloNerdFamily,
            fontSize = (surrounding.value * 0.875f).sp,
            color = T.Ink,
        ),
        modifier = Modifier
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .clip(T.R4)
            .background(T.Fill)
            .border(0.5.dp, T.Border, T.R4)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun FileLinkChip(path: String, label: String = path.substringAfterLast('/').substringAfterLast('\\')) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(T.R8)
            .background(T.Fill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text("</>", color = T.Accent, fontFamily = MesloNerdFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(5.dp))
        Text(label, color = T.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Lightweight Tokyo Night highlighter for fenced blocks (keywords / strings / comments / numbers).
 * Not Shiki — enough for desktop-parity coloring without shipping a JS runtime.
 */
fun highlightCode(source: String, language: String?): AnnotatedString {
    val keywords = KEYWORDS[language?.lowercase()?.substringBefore(' ') ?: ""]
        ?: KEYWORDS["js"]!!
    return buildAnnotatedString {
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) || (language in setOf("py", "python", "sh", "bash", "yml", "yaml") && source[i] == '#') -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    withStyle(SpanStyle(color = TokyoNight.Ansi[8])) { append(source.substring(i, end)) }
                    i = end
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    withStyle(SpanStyle(color = TokyoNight.Ansi[8])) { append(source.substring(i, end)) }
                    i = end
                }
                source[i] == '"' || source[i] == '\'' || source[i] == '`' -> {
                    val q = source[i]
                    var j = i + 1
                    while (j < source.length && source[j] != q) {
                        if (source[j] == '\\' && j + 1 < source.length) j += 2 else j++
                    }
                    if (j < source.length) j++
                    withStyle(SpanStyle(color = TokyoNight.Ansi[2])) { append(source.substring(i, j)) }
                    i = j
                }
                source[i].isDigit() -> {
                    var j = i + 1
                    while (j < source.length && (source[j].isDigit() || source[j] == '.' || source[j] == 'x')) j++
                    withStyle(SpanStyle(color = TokyoNight.Ansi[5])) { append(source.substring(i, j)) }
                    i = j
                }
                source[i].isLetter() || source[i] == '_' -> {
                    var j = i + 1
                    while (j < source.length && (source[j].isLetterOrDigit() || source[j] == '_')) j++
                    val word = source.substring(i, j)
                    val color = when {
                        word in keywords -> TokyoNight.Ansi[4]
                        else -> TokyoNight.Fg
                    }
                    withStyle(SpanStyle(color = color, fontWeight = if (word in keywords) FontWeight.Medium else FontWeight.Normal)) {
                        append(word)
                    }
                    i = j
                }
                else -> {
                    append(source[i]); i++
                }
            }
        }
    }
}

private val KEYWORDS = mapOf(
    "js" to setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "class", "new", "import", "export", "from", "async", "await", "try", "catch", "throw", "typeof", "instanceof", "null", "undefined", "true", "false"),
    "ts" to setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "class", "new", "import", "export", "from", "async", "await", "try", "catch", "throw", "type", "interface", "extends", "implements", "public", "private", "readonly", "true", "false", "null"),
    "tsx" to setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "class", "new", "import", "export", "from", "async", "await", "type", "interface", "true", "false", "null"),
    "jsx" to setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "class", "new", "import", "export", "from", "async", "await", "true", "false", "null"),
    "kt" to setOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "null", "true", "false", "suspend", "override", "private", "public", "internal", "data", "sealed", "import", "package"),
    "kts" to setOf("fun", "val", "var", "class", "object", "if", "else", "when", "for", "while", "return", "null", "true", "false", "import"),
    "java" to setOf("class", "interface", "public", "private", "protected", "static", "final", "void", "return", "if", "else", "for", "while", "new", "import", "package", "true", "false", "null", "try", "catch"),
    "py" to setOf("def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as", "None", "True", "False", "with", "try", "except", "raise", "async", "await", "lambda", "yield"),
    "python" to setOf("def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as", "None", "True", "False", "with", "try", "except", "raise", "async", "await"),
    "yaml" to setOf("true", "false", "null", "yes", "no"),
    "yml" to setOf("true", "false", "null", "yes", "no"),
    "sh" to setOf("if", "then", "else", "fi", "for", "do", "done", "while", "case", "esac", "function", "return", "export", "local"),
    "bash" to setOf("if", "then", "else", "fi", "for", "do", "done", "while", "case", "esac", "function", "return", "export", "local"),
    "json" to setOf("true", "false", "null"),
)

// endregion
