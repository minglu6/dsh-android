package dev.dsh.pocket.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/** Bundled MesloLGL DZ Nerd Font Regular (Ghostty / Powerlevel10k glyphs). */
val MesloNerdFamily: FontFamily = FontFamily(Font(R.font.meslolgl_dz_nerd_regular, FontWeight.Normal))

private val TermStyle = TextStyle(
    fontFamily = MesloNerdFamily,
    fontSize = 12.sp,
    lineHeight = 12.sp,
    color = TokyoNight.Fg,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/**
 * Fixed-grid Tokyo Night terminal surface, sized by the caller's bounded modifier.
 * Keystrokes go straight to the PTY (no local echo); soft keyboard via a hidden field.
 */
@Composable
fun TerminalView(
    terminal: AnsiTerminal,
    revision: Long,
    modifier: Modifier = Modifier,
    onResize: (cols: Int, rows: Int) -> Unit,
    onInput: (String) -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val resolver = LocalFontFamilyResolver.current
    val focus = remember { FocusRequester() }
    var ime by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Canvas cannot select; long-press opens a Selectable plain-text overlay.
    var selecting by remember { mutableStateOf(false) }

    val cellW = remember(measurer, resolver) {
        measurer.measure("M", TermStyle, density = density, fontFamilyResolver = resolver).size.width.toFloat().coerceAtLeast(1f)
    }
    // Row pitch ≈ glyph height — avoid list-like gaps between terminal lines.
    val cellH = remember(cellW, density) {
        with(density) { 12.sp.toPx() * 1.35f }.coerceAtLeast(cellW * 1.6f)
    }

    LaunchedEffect(size, cellW, cellH) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val pad = with(density) { 12.dp.toPx() }
        val cols = max(2, ((size.width - pad * 2) / cellW).toInt())
        val rows = max(1, ((size.height - pad * 2) / cellH).toInt())
        if (cols != terminal.cols || rows != terminal.rows) onResize(cols, rows)
    }

    Box(
        modifier
            .fillMaxWidth()
            .clip(T.R12)
            .background(TokyoNight.Bg)
            .onSizeChanged { size = it }
            .pointerInput(selecting) {
                detectTapGestures(
                    onTap = {
                        if (selecting) selecting = false
                        else focus.requestFocus()
                    },
                    onLongPress = { selecting = true },
                )
            },
    ) {
        // Hidden IME capture — never shown; PTY provides echo.
        BasicTextField(
            value = ime,
            onValueChange = { next ->
                when {
                    next.length > ime.length -> {
                        val added = next.substring(ime.length).replace("\n", "\r")
                        if (added.isNotEmpty()) onInput(added)
                    }
                    next.length < ime.length -> onInput("\u007f".repeat(ime.length - next.length))
                }
                ime = ""
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focus),
            textStyle = TermStyle.copy(color = TokyoNight.Bg),
            cursorBrush = SolidColor(TokyoNight.Bg),
            singleLine = false,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.None,
                autoCorrectEnabled = false,
            ),
        )

        if (selecting) {
            @Suppress("UNUSED_EXPRESSION") revision
            SelectableOutput(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                Text(
                    terminal.plainText().ifBlank { " " },
                    style = TermStyle,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "轻触退出选择",
                color = TokyoNight.Ansi[8],
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        } else {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                @Suppress("UNUSED_EXPRESSION") revision
                drawTerminal(terminal, measurer, cellW, cellH)
            }
        }
    }
}

private fun DrawScope.drawTerminal(
    terminal: AnsiTerminal,
    measurer: TextMeasurer,
    cellW: Float,
    cellH: Float,
) {
    for (r in 0 until terminal.rows) {
        var c = 0
        while (c < terminal.cols) {
            val cell = terminal.cell(r, c)
            if (cell.spacer) { c++; continue }
            val w = if (cell.wide) 2 else 1
            var fg = cell.fgColor()
            var bg = cell.bgColor()
            if (cell.inverse) {
                val t = fg; fg = bg; bg = t
            }
            val x = c * cellW
            val y = r * cellH
            if (bg != TokyoNight.Bg) {
                drawRect(bg, Offset(x, y), Size(cellW * w, cellH))
            }
            if (cell.ch != " " && cell.ch.isNotEmpty()) {
                val layout = measurer.measure(
                    text = cell.ch,
                    style = TermStyle.copy(
                        color = fg,
                        fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
                drawText(layout, topLeft = Offset(x, y))
                if (cell.underline) {
                    drawLine(fg, Offset(x, y + cellH - 1f), Offset(x + cellW * w, y + cellH - 1f), strokeWidth = 1f)
                }
            }
            c += w
        }
    }
    if (terminal.isCursorVisible()) {
        drawRect(
            TokyoNight.Cursor.copy(alpha = 0.85f),
            Offset(terminal.cursorX * cellW, terminal.cursorY * cellH),
            Size(cellW, cellH),
        )
    }
}
