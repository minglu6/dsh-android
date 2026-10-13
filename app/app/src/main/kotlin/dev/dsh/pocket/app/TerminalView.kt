package dev.dsh.pocket.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlin.math.floor

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
    platform: String? = null,
    onResize: (cols: Int, rows: Int) -> Unit,
    onInput: (String) -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val resolver = LocalFontFamilyResolver.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    val selectionFocus = remember { FocusRequester() }
    var ime by remember { mutableStateOf(emptyTerminalInput()) }
    var inputRequest by remember { mutableIntStateOf(0) }
    var pendingPaste by remember { mutableStateOf<String?>(null) }
    var selectionGrid by remember { mutableStateOf<TerminalGrid?>(null) }
    var selectionAnchor by remember { mutableIntStateOf(0) }
    var selectionEnd by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var size by remember { mutableStateOf(IntSize.Zero) }
    val selecting = selectionGrid != null
    val isMac = platform == "darwin"
    val foreground = T.Ink
    val background = T.Bg
    val fontSize = Pocket.prefs.terminalFontSize.sp
    val fontFamily = LocalFonts.terminal
    val style = remember(fontSize, fontFamily, foreground) {
        TextStyle(fontFamily = fontFamily, fontSize = fontSize, lineHeight = fontSize, color = foreground,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
    }
    val cellW = remember(measurer, resolver, density, style) {
        measurer.measure("M", style, density = density, fontFamilyResolver = resolver).size.width.toFloat().coerceAtLeast(1f)
    }
    val cellH = remember(cellW, density, fontSize) {
        with(density) { fontSize.toPx() * 1.35f }.coerceAtLeast(cellW * 1.6f)
    }
    LaunchedEffect(selecting) {
        if (selecting) selectionFocus.requestFocus()
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            keyboard?.hide()
            focusManager.clearFocus()
            ime = emptyTerminalInput()
            pendingPaste = null
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
        selectionGrid = null
        if (enabled) inputRequest++
    }
    fun selectOutput(point: Offset) {
        val grid = terminal.snapshot()
        val range = grid.wordAt(grid.position(floor(point.y / cellH).toInt(), floor(point.x / cellW).toInt()))
        selectionAnchor = range.first
        selectionEnd = range.last
        selectionGrid = grid
        ime = emptyTerminalInput()
        focusManager.clearFocus()
        keyboard?.hide()
    }
    fun copySelection() {
        selectionGrid?.let { clipboard.setText(AnnotatedString(it.selectedText(selectionAnchor, selectionEnd))) }
    }
    fun paste() {
        if (!enabled || selecting) return
        val text = clipboard.getText()?.text ?: return
        if (text.isEmpty()) return
        if ('\n' in text || '\r' in text) pendingPaste = text else send(text)
    }
    BackHandler(enabled = selecting) { selectionGrid = null }
    val currentSelecting by rememberUpdatedState(selecting)
    val beginSelection by rememberUpdatedState<(Offset) -> Unit>({ selectOutput(it) })
    val extendSelection by rememberUpdatedState<(Offset) -> Unit>({ point ->
        selectionGrid?.let { grid ->
            selectionEnd = grid.position(floor(point.y / cellH).toInt(), floor(point.x / cellW).toInt())
        }
    })
    val tapTerminal by rememberUpdatedState<(Offset) -> Unit>({ point ->
        if (!selecting && enabled) {
            terminal.tapInput(floor(point.y / cellH).toInt(), floor(point.x / cellW).toInt())?.let { send(it) }
            startInput()
        }
    })

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
        Column(Modifier.fillMaxWidth().background(T.Surface).padding(horizontal = 8.dp, vertical = 2.dp)) {
            val inputEnabled = enabled && !selecting && pendingPaste == null
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TerminalKey("Esc", Modifier.weight(1f), inputEnabled, onClick = { send("\u001b") })
                TerminalKey("Tab", Modifier.weight(1f), inputEnabled, onClick = { send("\t") })
                TerminalKey("↑", Modifier.weight(1f), inputEnabled, description = "Cursor up", onClick = { send("\u001b[A") })
                TerminalKey("↓", Modifier.weight(1f), inputEnabled, description = "Cursor down", onClick = { send("\u001b[B") })
                TerminalKey("", Modifier.weight(1f), enabled || selecting, active = imeVisible,
                    icon = if (selecting) D.check else Icons.Outlined.Keyboard,
                    description = if (selecting) "Done" else if (imeVisible) "Hide keyboard" else "Show keyboard",
                    onClick = {
                        if (selecting) { selectionGrid = null; if (enabled) startInput() }
                        else if (imeVisible) keyboard?.hide() else startInput()
                    })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TerminalKey("Ctrl+C", Modifier.weight(1f), inputEnabled || (!isMac && selecting),
                    onClick = { if (selecting && !isMac) copySelection() else send("\u0003") })
                TerminalKey("Ctrl+D", Modifier.weight(1f), inputEnabled, onClick = { send("\u0004") })
                TerminalKey(if (isMac) "Cmd+V" else "Ctrl+V", Modifier.weight(1f), inputEnabled,
                    description = "Paste", onClick = { paste() })
                TerminalKey(if (isMac) "Cmd+C" else "Copy", Modifier.weight(1f), selecting,
                    description = "Copy selection", onClick = { copySelection() })
            }
        }
        HorizontalDivider(color = T.Border, thickness = 0.5.dp)
        Box(Modifier.fillMaxWidth().weight(1f).clip(T.R12).background(background).onSizeChanged { size = it }
            .onPreviewKeyEvent { event ->
                if (selecting && event.type == KeyEventType.KeyDown) {
                    if (event.key == Key.C && (if (isMac) event.isMetaPressed else event.isCtrlPressed)) copySelection()
                    else if (event.key == Key.Escape) selectionGrid = null
                    true
                } else false
            }
            .focusRequester(selectionFocus).focusable(enabled = selecting)) {
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
                        if (event.type != KeyEventType.KeyDown) false
                        else if (event.key == Key.V && (if (isMac) event.isMetaPressed else event.isCtrlPressed)) {
                            paste(); true
                        } else if (event.key == Key.C && (if (isMac) event.isMetaPressed else event.isCtrlPressed && selecting)) {
                            copySelection(); true
                        } else {
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
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState(), enabled = !selecting)) {
                val canvasWidth = with(density) { max(size.width.toFloat(), (selectionGrid?.cols ?: terminal.cols) * cellW + 16.dp.toPx()).toDp() }
                Canvas(Modifier.width(canvasWidth).fillMaxHeight().padding(8.dp)
                    .semantics { contentDescription = termText("终端输出，长按选择文本", "Terminal output; long press to select text") }
                    .pointerInput(terminal, cellW, cellH) {
                        detectTapGestures(onTap = { tapTerminal(it) })
                    }
                    .pointerInput(terminal, cellW, cellH) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pressed = if (currentSelecting) down else awaitLongPressOrCancellation(down.id)
                            if (pressed != null) {
                                if (!currentSelecting) beginSelection(pressed.position)
                                else extendSelection(pressed.position)
                                pressed.consume()
                                drag(pressed.id) { change ->
                                    extendSelection(change.position)
                                    change.consume()
                                }
                            }
                        }
                    }) {
                    @Suppress("UNUSED_EXPRESSION") revision
                    drawTerminal(terminal, measurer, cellW, cellH, style, foreground, background,
                        enabled && !selecting, selectionGrid, selectionAnchor, selectionEnd)
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
    // Keycaps deliberately use only keyboard notation / English on every locale.
    active: Boolean = false,
    description: String = label,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).focusProperties { canFocus = false }
            .semantics { contentDescription = description },
        shape = T.R8,
        color = if (active) T.Tint else Color.Transparent,
        contentColor = if (!enabled) T.Text3.copy(alpha = 0.5f) else if (active) T.Accent else T.Text2,
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp).clearAndSetSemantics {},
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp))
            else Text(label, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    selection: TerminalGrid?,
    selectionAnchor: Int,
    selectionEnd: Int,
) {
    val rows = selection?.rows ?: terminal.rows
    val cols = selection?.cols ?: terminal.cols
    for (r in 0 until rows) {
        var c = 0
        while (c < cols) {
            val cell = selection?.cell(r, c) ?: terminal.cell(r, c)
            if (cell.spacer) { c++; continue }
            val w = if (cell.wide) 2 else 1
            var fg = cell.fgColor(foreground)
            var bg = cell.bgColor(background)
            if (cell.inverse) { val old = fg; fg = bg; bg = old }
            val selected = selection != null &&
                r * cols + c in minOf(selectionAnchor, selectionEnd)..maxOf(selectionAnchor, selectionEnd)
            if (selected) { bg = TokyoNight.Selection; fg = Color.White }
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
