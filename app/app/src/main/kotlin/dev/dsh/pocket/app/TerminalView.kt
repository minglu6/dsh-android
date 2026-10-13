package dev.dsh.pocket.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/** Bundled MesloLGL DZ Nerd Font Regular (Ghostty / Powerlevel10k glyphs). */
val MesloNerdFamily: FontFamily = FontFamily(Font(R.font.meslolgl_dz_nerd_regular, FontWeight.Normal))
private fun termText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh
private const val IME_ANCHOR = "\u200B"
private fun emptyTerminalInput() = TextFieldValue(IME_ANCHOR, selection = TextRange(1))

/** The sentinel lets soft keyboards send backspace even after the last committed keystroke. */
internal fun terminalCommittedInput(text: String): String =
    if (text.isEmpty()) "\u007f" else text.removePrefix(IME_ANCHOR).replace("\n", "\r")

/** Fixed-grid PTY surface; defaults follow official label-primary/bg-base aliases. */
@Composable
fun TerminalView(
    terminal: AnsiTerminal,
    revision: Long,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onResize: (cols: Int, rows: Int) -> Unit,
    onInput: (String) -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val resolver = LocalFontFamilyResolver.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    var ime by remember { mutableStateOf(emptyTerminalInput()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var selecting by remember { mutableStateOf(false) }
    val foreground = T.Ink
    val background = T.Bg
    val fontSize = Pocket.prefs.terminalFontSize.sp
    val style = remember(fontSize, foreground) {
        TextStyle(fontFamily = MesloNerdFamily, fontSize = fontSize, lineHeight = fontSize, color = foreground,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
    }
    val cellW = remember(measurer, resolver, density, style) {
        measurer.measure("M", style, density = density, fontFamilyResolver = resolver).size.width.toFloat().coerceAtLeast(1f)
    }
    val cellH = remember(cellW, density, fontSize) {
        with(density) { fontSize.toPx() * 1.35f }.coerceAtLeast(cellW * 1.6f)
    }
    LaunchedEffect(enabled) { if (!enabled) { keyboard?.hide(); ime = emptyTerminalInput() } }
    LaunchedEffect(size, cellW, cellH, enabled) {
        if (size.width <= 0 || size.height <= 0 || !enabled) return@LaunchedEffect
        val pad = with(density) { 8.dp.toPx() }
        val cols = max(2, ((size.width - pad * 2) / cellW).toInt())
        val rows = max(1, ((size.height - pad * 2) / cellH).toInt())
        if (cols != terminal.cols || rows != terminal.rows) onResize(cols, rows)
    }
    fun send(value: String) { if (enabled) onInput(value) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { selecting = !selecting; if (selecting) keyboard?.hide() }) {
                Text(if (selecting) termText("退出选择", "Exit selection") else termText("选择文本", "Select text"))
            }
            TextButton(onClick = { focus.requestFocus(); keyboard?.show() }, enabled = enabled) { Text(termText("键盘", "Keyboard")) }
            TextButton(onClick = { clipboard.getText()?.text?.let(::send) }, enabled = enabled) { Text(termText("粘贴", "Paste")) }
            listOf("Esc" to "\u001b", "Tab" to "\t", "Ctrl+C" to "\u0003", "Ctrl+D" to "\u0004", "Ctrl+L" to "\u000c").forEach { (label, value) ->
                TextButton(onClick = { send(value) }, enabled = enabled) { Text(label) }
            }
            listOf(Triple("←", "\u001b[D", termText("左移光标", "Cursor left")), Triple("↑", "\u001b[A", termText("上一条命令", "Previous command")),
                Triple("↓", "\u001b[B", termText("下一条命令", "Next command")), Triple("→", "\u001b[C", termText("右移光标", "Cursor right")),
                Triple("⌫", "\u007f", termText("退格", "Backspace")), Triple("↵", "\r", termText("回车", "Enter"))).forEach { (label, value, description) ->
                TextButton(onClick = { send(value) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) { Text(label) }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clip(T.R12).background(background).onSizeChanged { size = it }) {
            BasicTextField(
                value = ime,
                onValueChange = { next ->
                    if (next.composition != null) ime = next
                    else {
                        val input = terminalCommittedInput(next.text)
                        if (input.isNotEmpty()) send(input)
                        ime = emptyTerminalInput()
                    }
                },
                enabled = enabled,
                modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus)
                    .semantics { contentDescription = termText("终端键盘输入", "Terminal keyboard input") }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else {
                            val sequence = when (event.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_ENTER -> "\r"
                                android.view.KeyEvent.KEYCODE_DEL -> "\u007f"
                                android.view.KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
                                android.view.KeyEvent.KEYCODE_ESCAPE -> "\u001b"
                                android.view.KeyEvent.KEYCODE_TAB -> "\t"
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
                                else -> if (event.isCtrlPressed) event.nativeKeyEvent.getUnicodeChar(0).takeIf { it in 64..127 }?.let { (it and 31).toChar().toString() } else null
                            }
                            if (sequence != null) { send(sequence); true } else false
                        }
                    },
                textStyle = style.copy(color = background),
                cursorBrush = SolidColor(background),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.None, autoCorrectEnabled = false),
            )
            if (selecting) {
                val output = remember(terminal, revision) { terminal.plainText().ifBlank { " " } }
                SelectableOutput(Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Text(output, style = style, softWrap = false)
                }
            } else {
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    val canvasWidth = with(density) { max(size.width.toFloat(), terminal.cols * cellW + 16.dp.toPx()).toDp() }
                    Canvas(Modifier.width(canvasWidth).fillMaxHeight().padding(8.dp)
                        .semantics { contentDescription = termText("终端输出，长按选择文本", "Terminal output; long press to select text") }
                        .pointerInput(enabled) {
                            detectTapGestures(onTap = { if (enabled) { focus.requestFocus(); keyboard?.show() } }, onLongPress = { selecting = true; keyboard?.hide() })
                        }) {
                        @Suppress("UNUSED_EXPRESSION") revision
                        drawTerminal(terminal, measurer, cellW, cellH, style, foreground, background, enabled)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawTerminal(
    terminal: AnsiTerminal,
    measurer: TextMeasurer,
    cellW: Float,
    cellH: Float,
    style: TextStyle,
    foreground: Color,
    background: Color,
    enabled: Boolean,
) {
    for (r in 0 until terminal.rows) {
        var c = 0
        while (c < terminal.cols) {
            val cell = terminal.cell(r, c)
            if (cell.spacer) { c++; continue }
            val w = if (cell.wide) 2 else 1
            var fg = cell.fgColor(foreground)
            var bg = cell.bgColor(background)
            if (cell.inverse) { val old = fg; fg = bg; bg = old }
            val x = c * cellW
            val y = r * cellH
            if (bg != background) drawRect(bg, Offset(x, y), Size(cellW * w, cellH))
            val cursor = enabled && terminal.isCursorVisible() && terminal.cursorX == c && terminal.cursorY == r
            if (cursor) { drawRect(fg, Offset(x, y), Size(cellW * w, cellH)); fg = bg }
            if (cell.ch != " " && cell.ch.isNotEmpty()) {
                val layout = measurer.measure(cell.ch, style.copy(color = fg, fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal))
                drawText(layout, topLeft = Offset(x, y))
                if (cell.underline) drawLine(fg, Offset(x, y + cellH - 1f), Offset(x + cellW * w, y + cellH - 1f), strokeWidth = 1f)
            }
            c += w
        }
    }
}
