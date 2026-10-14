package dev.dsh.pocket.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
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
            // Code is authored text, not evidence that a path names a file.
            is Code -> out += MdSpan.Code(c.literal)
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
fun MarkdownView(text: String, streaming: Boolean = false, modifier: Modifier = Modifier, bodyColor: Color = T.Ink, bodyFontSize: TextUnit = Pocket.prefs.fontSize.sp) {
    val blocks = remember(text, streaming) { parseMarkdown(text, streaming) }
    // One SelectionContainer for the whole message: paragraphs, lists, tables, fences.
    SelectableOutput(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            blocks.forEachIndexed { i, block ->
                if (i > 0) Spacer(Modifier.height(blockGap(blocks[i - 1], block)))
                MdBlockView(block, bodyFontSize, bodyColor)
            }
        }
    }
}

private fun blockGap(prev: MdBlock, cur: MdBlock): androidx.compose.ui.unit.Dp = when {
    prev is MdBlock.Heading && (cur is MdBlock.BulletList || cur is MdBlock.OrderedList) -> 8.dp
    else -> 16.dp
}

@Composable
private fun MdBlockView(block: MdBlock, bodySp: TextUnit, bodyColor: Color) {
    when (block) {
        is MdBlock.Paragraph -> InlineRichText(block.spans, TextStyle(fontSize = bodySp, lineHeight = (bodySp.value + 10).sp, color = bodyColor))
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 28.sp; 2 -> 24.sp; 3 -> 20.sp; 4 -> 17.sp; else -> bodySp
            }
            InlineRichText(
                block.spans,
                TextStyle(fontSize = size, lineHeight = (size.value + 8).sp, fontWeight = FontWeight.Bold, color = bodyColor),
            )
        }
        is MdBlock.BulletList -> Column {
            block.items.forEachIndexed { idx, item ->
                if (idx > 0) Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    val markerColor = T.Text2
                    val density = LocalDensity.current
                    val markerBaselineOffset = with(density) { bodySp.toPx() * 0.35f }
                    Canvas(
                        Modifier.width(18.dp).height(with(density) { (bodySp.value + 10).sp.toDp() })
                            .alignBy { (it.measuredHeight / 2f + markerBaselineOffset).roundToInt() },
                    ) {
                        // A CSS disc, not a font-dependent bullet glyph. Align
                        // its optical center to the first text line's x-height.
                        drawCircle(markerColor, radius = bodySp.toPx() * 0.15f,
                            center = androidx.compose.ui.geometry.Offset(5.dp.toPx(), size.height / 2f))
                    }
                    Column(Modifier.weight(1f).alignBy(FirstBaseline)) {
                        item.forEach { MdBlockView(it, bodySp, bodyColor) }
                    }
                }
            }
        }
        is MdBlock.OrderedList -> Column {
            block.items.forEachIndexed { idx, item ->
                if (idx > 0) Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("${block.start + idx}.", color = T.Text2, fontSize = bodySp, modifier = Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) { item.forEach { MdBlockView(it, bodySp, bodyColor) } }
                }
            }
        }
        is MdBlock.Quote -> {
            val ruleColor = T.Caption
            Column(
                Modifier.fillMaxWidth().drawBehind {
                    drawRect(ruleColor, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
                }.padding(start = 16.dp),
            ) {
                block.children.forEachIndexed { i, child ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    MdBlockView(child, bodySp, bodyColor)
                }
            }
        }
        is MdBlock.Code -> FencedCode(block.language, block.literal, block.incomplete)
        is MdBlock.Table -> GfmTable(block, bodySp, bodyColor)
        is MdBlock.Rule -> HorizontalDivider(color = T.Border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** Desktop CodeToolbar: language + wrap/copy icon actions (tooltips only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FencedCode(language: String?, literal: String, incomplete: Boolean) {
    var wrapped by remember(Pocket.prefs.codeWordWrap) { mutableStateOf(Pocket.prefs.codeWordWrap) }
    val display = if (wrapped) literal.breakAnywhere() else literal
    val highlighted = remember(display, language) { highlightCode(display, language) }
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1000)
            copied = false
        }
    }
    // Selection comes from enclosing MarkdownView SelectableOutput; exclude toolbar icons.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(T.R12)
            .background(TokyoNight.Bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language?.takeIf { it.isNotBlank() } ?: "code",
                style = T.Mono.copy(color = TokyoNight.Ansi[8], fontSize = 11.sp),
                modifier = Modifier.weight(1f),
            )
            NonSelectable {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val wrapTip = if (wrapped) L.unwrapLines() else L.wrapLines()
                    CodeHeaderIcon(
                        icon = if (wrapped) D.nowrap else D.wrap,
                        label = wrapTip,
                        ariaLabel = L.wrapLines(),
                    ) { wrapped = !wrapped }
                    CodeHeaderIcon(
                        icon = if (copied) D.check else D.copy,
                        label = if (copied) L.copied() else L.copy(),
                    ) {
                        if (!copied) {
                            clip.setText(AnnotatedString(literal))
                            copied = true
                        }
                    }
                }
            }
        }
        Text(
            highlighted,
            style = TextStyle(
                fontFamily = LocalFonts.code,
                fontSize = Pocket.prefs.codeFontSize.sp,
                lineHeight = (Pocket.prefs.codeFontSize + 7).sp,
                color = TokyoNight.Fg,
            ),
            softWrap = wrapped,
            modifier = if (wrapped) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
        )
        if (incomplete) {
            Spacer(Modifier.height(4.dp))
            Text("…", color = TokyoNight.Ansi[8], fontFamily = LocalFonts.code, fontSize = Pocket.prefs.codeFontSize.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeHeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    ariaLabel: String = label,
    onClick: () -> Unit,
) {
    val tipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label, fontSize = 12.sp) } },
        state = tipState,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(T.R8)
                .clickable(onClick = onClick)
                .semantics { contentDescription = ariaLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = TokyoNight.Ansi[8], modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun GfmTable(table: MdBlock.Table, bodySp: TextUnit, bodyColor: Color) {
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(
        fontFamily = LocalFonts.text,
        fontSize = bodySp,
        lineHeight = (bodySp.value + 8).sp,
        color = bodyColor,
    )
    val rows = remember(table) { table.headers + table.rows }
    val columns = rows.maxOfOrNull { it.size } ?: return
    if (columns == 0) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Desktop's narrow tables fill the message; wide tables retain their
        // natural widths and scroll. Short labels must not steal prose space.
        val widths = remember(rows, maxWidth, style, density) {
            val minimum = List(columns) { i ->
                100f + (if (i == 0) 0f else 16f) + (if (i == columns - 1) 0f else 16f)
            }
            val preferred = List(columns) { i ->
                val textWidth = rows.maxOf { row ->
                    val text = row.getOrNull(i).orEmpty().joinToString("") { span ->
                        when (span) {
                            is MdSpan.Text -> span.text
                            is MdSpan.Code -> span.text
                            is MdSpan.Link -> span.text
                            is MdSpan.FileChip -> "</> ${span.label}"
                        }
                    }
                    with(density) { measurer.measure(text, style, softWrap = false).size.width.toDp().value }
                }
                textWidth.coerceIn(100f, 320f) + minimum[i] - 100f
            }
            if (columns >= 4) preferred.map { it.dp } else {
                val available = maxWidth.value.coerceAtLeast(minimum.sum())
                val extra = available - minimum.sum()
                val flexibility = preferred.sum() - minimum.sum()
                List(columns) { i ->
                    (minimum[i] + if (flexibility > 0f) {
                        extra * (preferred[i] - minimum[i]) / flexibility
                    } else extra / columns).dp
                }
            }
        }
        Column(Modifier.horizontalScroll(scroll)) {
            Column(Modifier.width(widths.fold(0.dp) { sum, width -> sum + width })) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        widths.forEachIndexed { i, width ->
                            Box(
                                Modifier.width(width).padding(
                                    start = if (i == 0) 0.dp else 16.dp,
                                    end = if (i == columns - 1) 0.dp else 16.dp,
                                    top = 10.dp,
                                    bottom = 10.dp,
                                ),
                            ) {
                                InlineRichText(row.getOrNull(i).orEmpty(), style)
                            }
                        }
                    }
                    // A bounded width is essential: fillMaxWidth inside an
                    // unbounded horizontal scroller otherwise draws no rule.
                    HorizontalDivider(color = T.Border, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun InlineRichText(spans: List<MdSpan>, style: TextStyle) {
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    val openFile = LocalOpenFilePreview.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textStyle = style.copy(fontFamily = LocalFonts.text)
    val codeStyle = TextStyle(
        fontFamily = LocalFonts.code,
        fontSize = (style.fontSize.value * 0.875f).sp,
        lineHeight = (style.fontSize.value + 5).sp,
        color = style.color,
    )
    // Measure and draw the exact same typography; Material Text's inherited
    // line height/font can otherwise exceed the inline placeholder and clip.
    val fileStyle = textStyle.copy(color = T.Link, fontWeight = FontWeight.Medium)
    val fileIconSize = with(density) { (style.fontSize.value * 1.1f).sp.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inlineContent = mutableMapOf<String, InlineTextContent>()
        val annotated = buildAnnotatedString {
            spans.forEachIndexed { i, span ->
                when (span) {
                    is MdSpan.Text -> withStyle(
                        SpanStyle(
                            // Android system fonts may map CSS's 600 to regular
                            // (observed on OnePlus); request native bold semantics.
                            fontWeight = if (span.bold) FontWeight.Bold else style.fontWeight,
                            fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal,
                        ),
                    ) { append(span.text) }
                    is MdSpan.Link -> withLink(
                        LinkAnnotation.Clickable(
                            "link-$i",
                            TextLinkStyles(SpanStyle(color = T.Link, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.None)),
                        ) {
                            runCatching { uri.openUri(span.dest) }.recoverCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(span.dest)))
                            }
                        },
                    ) { append(span.text) }
                    is MdSpan.Code, is MdSpan.FileChip -> {
                        val isCode = span is MdSpan.Code
                        val label = when (span) {
                            is MdSpan.Code -> span.text
                            is MdSpan.FileChip -> span.label
                            else -> error("Not inline content")
                        }
                        val chipStyle = if (isCode) codeStyle else fileStyle
                        val padding = if (isCode) 11.dp else fileIconSize + 5.dp
                        val measured = measurer.measure(
                            label,
                            chipStyle,
                            constraints = Constraints(maxWidth = with(density) {
                                (maxWidth - padding).coerceAtLeast(1.dp).roundToPx()
                            }),
                            maxLines = if (isCode) Int.MAX_VALUE else 1,
                        )
                        val width = with(density) { (measured.size.width.toDp() + padding).coerceAtMost(maxWidth).toSp() }
                        val height = with(density) {
                            (if (isCode) measured.size.height.toDp() + 1.dp
                            else measured.size.height.toDp().coerceAtLeast(fileIconSize)).toSp()
                        }
                        val id = "chip-$i"
                        inlineContent[id] = InlineTextContent(
                            Placeholder(width, height, if (isCode) PlaceholderVerticalAlign.Center else PlaceholderVerticalAlign.TextCenter),
                        ) {
                            when (span) {
                                is MdSpan.Code -> BasicText(
                                    span.text,
                                    style = codeStyle,
                                    modifier = Modifier.clip(T.R8).background(ThemeState.colors.MarkdownInlineCode)
                                        .border(0.5.dp, ThemeState.colors.BorderL1, T.R8)
                                        .padding(horizontal = 5.5.dp, vertical = 0.5.dp),
                                )
                                is MdSpan.FileChip -> Row(
                                    Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painterResource(R.drawable.dsh_code_brackets),
                                        contentDescription = null,
                                        tint = T.Link,
                                        modifier = Modifier.size(fileIconSize),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    BasicText(
                                        span.label,
                                        style = fileStyle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                else -> Unit
                            }
                        }
                        if (span is MdSpan.FileChip && openFile != null) {
                            withLink(LinkAnnotation.Clickable(
                                "file-$i",
                                TextLinkStyles(SpanStyle(textDecoration = TextDecoration.None)),
                            ) { openFile(span.path) }) {
                                appendInlineContent(id, label)
                            }
                        } else {
                            val start = length
                            appendInlineContent(id, label)
                            if (isCode && measured.lineCount > 1) {
                                // CSS inline-flex occupies its own line when
                                // wider than the paragraph. Compose's fixed
                                // lineHeight otherwise overlaps a tall slot.
                                addStyle(ParagraphStyle(lineHeight = height), start, length)
                            }
                        }
                    }
                }
            }
        }
        // One text layout owns wrapping, selection and baselines across style
        // boundaries. A FlowRow treats the rest of a paragraph as one box.
        Text(annotated, style = textStyle, inlineContent = inlineContent, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun FileLinkChip(path: String, label: String = path.substringAfterLast('/').substringAfterLast('\\')) {
    val open = LocalOpenFilePreview.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(T.R8)
            .background(T.Fill)
            .then(if (open != null) Modifier.clickable { open(path) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text("</>", color = T.Link, fontFamily = LocalFonts.code, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(5.dp))
        Text(label, color = T.Link, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
