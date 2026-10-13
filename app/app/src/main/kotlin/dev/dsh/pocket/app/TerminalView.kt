package dev.dsh.pocket.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.text.style.TextOverflow
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
    var inputRequest by remember { mutableIntStateOf(0) }
    var moreOpen by remember { mutableStateOf(false) }
    var pendingPaste by remember { mutableStateOf<String?>(null) }
    var selectionText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
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
    LaunchedEffect(enabled) {
        if (!enabled) {
            keyboard?.hide()
            focusManager.clearFocus()
            ime = emptyTerminalInput()
            pendingPaste = null
            moreOpen = false
        }
    }
    LaunchedEffect(inputRequest) {
        if (inputRequest > 0 && enabled) { focus.requestFocus(); keyboard?.show() }
    }
    LaunchedEffect(size, cellW, cellH, enabled) {
        if (size.width <= 0 || size.height <= 0 || !enabled) return@LaunchedEffect
        val pad = with(density) { 8.dp.toPx() }
        val cols = max(2, ((size.width - pad * 2) / cellW).toInt())
        val rows = max(1, ((size.height - pad * 2) / cellH).toInt())
        if (cols != terminal.cols || rows != terminal.rows) onResize(cols, rows)
    }
    fun send(value: String) { if (enabled && !selecting && pendingPaste == null) onInput(value) }
    fun startInput() {
        selecting = false
        if (enabled) inputRequest++
    }
    fun selectOutput() {
        selectionText = terminal.plainText()
        selecting = true
        ime = emptyTerminalInput()
        focusManager.clearFocus()
        keyboard?.hide()
    }
    fun paste() {
        val text = clipboard.getText()?.text ?: return
        if (text.isEmpty()) return
        if ('\n' in text || '\r' in text) pendingPaste = text else send(text)
    }
    BackHandler(enabled = selecting) { selecting = false }

    pendingPaste?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingPaste = null },
            title = { Text(termText("粘贴多行内容？", "Paste multiple lines?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(termText("换行可能立即执行命令，请确认内容后再发送。", "Line breaks may execute commands immediately. Review before sending."))
                    Text(text, style = style, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                        .background(T.Bg, T.R8).padding(12.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(enabled = enabled, onClick = { pendingPaste = null; send(text) }) {
                    Text(termText("确认粘贴", "Paste"))
                }
            },
            dismissButton = { TextButton(onClick = { pendingPaste = null }) { Text(termText("取消", "Cancel")) } },
        )
    }

    Column(modifier) {
        Column(Modifier.fillMaxWidth().background(T.Surface).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selecting) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(termText("长按文字选择", "Hold text to select"), color = T.Text3, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TerminalKey(termText("复制全部", "Copy all"), enabled = selectionText.isNotEmpty(),
                        onClick = { clipboard.setText(AnnotatedString(selectionText)) })
                    TerminalKey(termText("完成", "Done"), onClick = { selecting = false })
                    TerminalKey(termText("键盘", "Type"), enabled = enabled, onClick = { startInput() })
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TerminalKey("Esc", Modifier.weight(1f), enabled, description = termText("Esc · 退出当前操作", "Esc · Escape"), onClick = { send("\u001b") })
                    TerminalKey("Tab", Modifier.weight(1f), enabled, description = termText("Tab · 补全", "Tab · Complete"), onClick = { send("\t") })
                    TerminalKey("Ctrl+C", Modifier.weight(1.25f), enabled, subtitle = termText("中断", "Interrupt"), onClick = { send("\u0003") })
                    TerminalKey(termText("粘贴", "Paste"), Modifier.weight(1f), enabled, onClick = { paste() })
                    TerminalKey(termText("键盘", "Keys"), Modifier.weight(1f), enabled, active = imeVisible,
                        description = if (imeVisible) termText("收起键盘", "Hide keyboard") else termText("打开键盘", "Show keyboard"),
                        onClick = { if (imeVisible) keyboard?.hide() else startInput() })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TerminalKey("←", Modifier.weight(1f), enabled, description = termText("左移光标", "Cursor left"), onClick = { send("\u001b[D") })
                    TerminalKey("↑", Modifier.weight(1f), enabled, description = termText("上一条命令", "Previous command"), onClick = { send("\u001b[A") })
                    TerminalKey("↓", Modifier.weight(1f), enabled, description = termText("下一条命令", "Next command"), onClick = { send("\u001b[B") })
                    TerminalKey("→", Modifier.weight(1f), enabled, description = termText("右移光标", "Cursor right"), onClick = { send("\u001b[C") })
                    TerminalKey(termText("选择", "Select"), Modifier.weight(1f),
                        description = termText("选择文本", "Select text"), onClick = { selectOutput() })
                    Box(Modifier.weight(1f)) {
                        TerminalKey("⋯", Modifier.fillMaxWidth(), enabled,
                            description = termText("更多按键", "More keys"), onClick = { moreOpen = true })
                        DropdownMenu(moreOpen, { moreOpen = false }, containerColor = T.Surface) {
                            DropdownMenuItem(text = { Text(termText("退格", "Backspace")) }, enabled = enabled,
                                onClick = { moreOpen = false; send("\u007f") })
                            DropdownMenuItem(text = { Text(termText("回车", "Enter")) }, enabled = enabled,
                                onClick = { moreOpen = false; send("\r") })
                            DropdownMenuItem(text = { Text(termText("Ctrl+L · 清屏", "Ctrl+L · Clear screen")) }, enabled = enabled,
                                onClick = { moreOpen = false; send("\u000c") })
                            DropdownMenuItem(text = { Text(termText("Ctrl+D · EOF / 可能退出", "Ctrl+D · EOF / may exit")) }, enabled = enabled,
                                onClick = { moreOpen = false; send("\u0004") })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = T.Border, thickness = 0.5.dp)
        Box(Modifier.fillMaxWidth().weight(1f).clip(T.R12).background(background).onSizeChanged { size = it }) {
            BasicTextField(
                value = ime,
                onValueChange = { next ->
                    if (next.composition != null) ime = next
                    else {
                        val input = terminalCommittedInput(next.text)
                        // Keyboard clipboard panels commit text here, bypassing our Paste button.
                        if (input.length > 1 && '\r' in input) pendingPaste = input
                        else if (input.isNotEmpty()) send(input)
                        ime = emptyTerminalInput()
                    }
                },
                enabled = enabled && !selecting,
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
                SelectableOutput(Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    Text(selectionText.ifBlank { " " }, style = style, softWrap = false)
                }
            } else {
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    val canvasWidth = with(density) { max(size.width.toFloat(), terminal.cols * cellW + 16.dp.toPx()).toDp() }
                    Canvas(Modifier.width(canvasWidth).fillMaxHeight().padding(8.dp)
                        .semantics { contentDescription = termText("终端输出，长按选择文本", "Terminal output; long press to select text") }
                        .pointerInput(enabled) {
                            detectTapGestures(onTap = { startInput() }, onLongPress = { selectOutput() })
                        }) {
                        @Suppress("UNUSED_EXPRESSION") revision
                        drawTerminal(terminal, measurer, cellW, cellH, style, foreground, background, enabled)
                    }
                }
            }
        }
    }
}

/** A compact key face inside a full-height touch target; clicks keep the IME input focused. */
@Composable
private fun TerminalKey(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null,
    active: Boolean = false,
    description: String = label,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).focusProperties { canFocus = false }
            .semantics { contentDescription = if (subtitle == null) description else "$description · $subtitle" },
        shape = T.R8,
        color = if (active) T.Tint else T.Surface2,
        contentColor = if (!enabled) T.Text3 else if (active) T.Accent else T.Ink,
        border = BorderStroke(0.5.dp, if (active) T.Accent.copy(alpha = 0.4f) else T.Border),
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp).clearAndSetSemantics {},
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
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
