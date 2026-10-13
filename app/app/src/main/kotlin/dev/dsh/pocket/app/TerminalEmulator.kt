package dev.dsh.pocket.app

import androidx.compose.ui.graphics.Color

/** Syntax/ANSI palette. Terminal default foreground and background follow live DSH aliases. */
object TokyoNight {
    val Bg = Color(0xFF1A1B26)
    val Fg = Color(0xFFC0CAF5)
    val Cursor = Color(0xFFC0CAF5)
    val Selection = Color(0xFF33467C)
    /** ANSI 0–15. */
    val Ansi = listOf(
        Color(0xFF15161E), // black
        Color(0xFFF7768E), // red
        Color(0xFF9ECE6A), // green
        Color(0xFFE0AF68), // yellow
        Color(0xFF7AA2F7), // blue
        Color(0xFFBB9AF7), // magenta
        Color(0xFF7DCFFF), // cyan
        Color(0xFFA9B1D6), // white
        Color(0xFF414868), // bright black
        Color(0xFFF7768E),
        Color(0xFF9ECE6A),
        Color(0xFFE0AF68),
        Color(0xFF7AA2F7),
        Color(0xFFBB9AF7),
        Color(0xFF7DCFFF),
        Color(0xFFC0CAF5),
    )
}

data class TermCell(
    val ch: String = " ",
    val fg: Int = -1,
    val bg: Int = -1,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val wide: Boolean = false,
    val spacer: Boolean = false,
) {
    fun fgColor(defaultForeground: Color): Color {
        val base = when {
            fg in 0..15 -> TokyoNight.Ansi[fg]
            fg in 16..255 -> xterm256(fg)
            else -> defaultForeground
        }
        return if (dim) base.copy(alpha = 0.65f) else base
    }

    fun bgColor(defaultBackground: Color): Color = when {
        bg in 0..15 -> TokyoNight.Ansi[bg]
        bg in 16..255 -> xterm256(bg)
        else -> defaultBackground
    }
}

private fun xterm256(n: Int): Color {
    if (n < 16) return TokyoNight.Ansi[n]
    if (n < 232) {
        val i = n - 16
        fun c(v: Int) = ((if (v == 0) 0 else 55 + v * 40) / 255f)
        return Color(c(i / 36), c((i / 6) % 6), c(i % 6))
    }
    val g = (8 + (n - 232) * 10) / 255f
    return Color(g, g, g)
}

/**
 * Fixed-grid VT100 / xterm-ish emulator for host PTY streams.
 * Pure Kotlin (no Android UI deps beyond Compose Color for palette helpers).
 */
class AnsiTerminal(cols: Int = 80, rows: Int = 24) {
    var cols: Int = cols.coerceAtLeast(2); private set
    var rows: Int = rows.coerceAtLeast(1); private set
    /** Bumps on every mutation so Compose can collect revisions. */
    var revision: Long = 0L; private set

    private var cells = Array(this.rows) { Array(this.cols) { TermCell() } }
    /** Zero for hard lines, otherwise the content boundary before an automatic wrap. */
    private var wrapped = IntArray(this.rows)
    var alternateScreen = false; private set
    private var primaryScreen: TerminalGrid? = null
    private var primaryX = 0
    private var primaryY = 0
    private var mouseMode = 0
    private var sgrMouse = false
    var cursorX = 0; private set
    var cursorY = 0; private set
    private var savedX = 0
    private var savedY = 0
    private var fg = -1
    private var bg = -1
    private var bold = false
    private var dim = false
    private var underline = false
    private var inverse = false
    private var originMode = false
    private var autoWrap = true
    private var insertMode = false
    private var cursorVisible = true
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private enum class State { Ground, Esc, Csi, Osc, Dcs, SosPmApc }
    private var state = State.Ground
    private val params = StringBuilder()
    private var csiPrivate = false
    private var osc = StringBuilder()
    private var utf8Hi: Char? = null
    /** After ESC ( ) * +, skip one charset-designation byte. */
    private var skipCharset = false

    fun cell(row: Int, col: Int): TermCell =
        if (row in 0 until rows && col in 0 until cols) cells[row][col] else TermCell()

    fun isCursorVisible(): Boolean = cursorVisible

    internal fun snapshot() = TerminalGrid(
        Array(rows) { cells[it].copyOf() }, wrapped.copyOf(),
    )

    /** A tap is a click in mouse-aware apps, otherwise horizontal movement on this row only. */
    internal fun tapInput(row: Int, column: Int): String? {
        if (row !in 0 until rows || column !in 0 until cols) return null
        if (mouseMode != 0) {
            val x = column + 1
            val y = row + 1
            if (sgrMouse) return "\u001b[<0;$x;${y}M" +
                if (mouseMode == 9) "" else "\u001b[<0;$x;${y}m"
            // The transport writes UTF-8 strings: legacy mouse bytes must remain ASCII.
            if (x > 95 || y > 95) return null
            val position = "${(x + 32).toChar()}${(y + 32).toChar()}"
            return "\u001b[M $position" + if (mouseMode == 9) "" else "\u001b[M#$position"
        }
        if (alternateScreen || row != cursorY) return null
        val end = (cells[row].indexOfLast { !it.spacer && it.ch != " " } + 1)
            .let { if (it > 0 && cells[row][it - 1].wide) it + 1 else it }
            .coerceAtLeast(cursorX).coerceAtMost(cols)
        var target = column.coerceAtMost(end)
        if (target < cols && cells[row][target].spacer) target--
        val start = cursorX.coerceIn(0, cols)
        val distance = (minOf(start, target) until maxOf(start, target)).count { !cells[row][it].spacer }
        if (distance == 0) return null
        return (if (target < start) "\u001b[D" else "\u001b[C").repeat(distance)
    }

    private fun setAlternateScreen(active: Boolean) {
        if (alternateScreen == active) return
        if (active) {
            primaryScreen = snapshot()
            primaryX = cursorX
            primaryY = cursorY
            cells = Array(rows) { Array(cols) { TermCell() } }
            wrapped = IntArray(rows)
            cursorX = 0
            cursorY = 0
        } else {
            primaryScreen?.let { saved ->
                cells = Array(rows) { r -> Array(cols) { c -> saved.cell(r, c) } }
                wrapped = IntArray(rows) { saved.wrapped.getOrElse(it) { 0 }.coerceAtMost(cols) }
            }
            cursorX = primaryX.coerceIn(0, cols - 1)
            cursorY = primaryY.coerceIn(0, rows - 1)
            primaryScreen = null
        }
        alternateScreen = active
    }

    /** Visible lines as plain text (for tests / accessibility). */
    fun plainText(): String = buildString {
        for (r in 0 until rows) {
            if (r > 0) append('\n')
            val line = StringBuilder()
            var c = 0
            while (c < cols) {
                val cell = cells[r][c]
                if (cell.spacer) { c++; continue }
                line.append(cell.ch)
                c += if (cell.wide) 2 else 1
            }
            append(line.trimEnd())
        }
    }.trimEnd()

    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceAtLeast(2)
        val nr = newRows.coerceAtLeast(1)
        if (nc == cols && nr == rows) return
        val next = Array(nr) { r ->
            Array(nc) { c ->
                if (r < rows && c < cols) cells[r][c] else TermCell()
            }
        }
        cells = next
        wrapped = wrapped.copyOf(nr)
        cols = nc
        rows = nr
        scrollTop = 0
        scrollBottom = rows - 1
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        bump()
    }

    fun clear() {
        cells = Array(rows) { Array(cols) { TermCell() } }
        wrapped = IntArray(rows)
        alternateScreen = false
        primaryScreen = null
        mouseMode = 0
        sgrMouse = false
        savedX = 0; savedY = 0
        originMode = false; autoWrap = true; insertMode = false; cursorVisible = true
        skipCharset = false
        cursorX = 0
        cursorY = 0
        fg = -1; bg = -1; bold = false; dim = false; underline = false; inverse = false
        state = State.Ground
        params.clear(); osc.clear(); utf8Hi = null; csiPrivate = false
        scrollTop = 0; scrollBottom = rows - 1
        bump()
    }

    /** Replace the viewport from a follow `snapshot.screen` (ANSI or plain). */
    fun applySnapshot(screen: String) {
        clear()
        write(screen)
    }

    fun write(data: String) {
        var i = 0
        while (i < data.length) {
            val ch = data[i]
            if (skipCharset) {
                skipCharset = false
                i++
                continue
            }
            when (state) {
                State.Ground -> when (ch) {
                    '\u001B' -> state = State.Esc
                    '\u0007' -> Unit
                    '\b' -> backspace()
                    '\t' -> tab()
                    '\n' -> lineFeed()
                    '\r' -> carriageReturn()
                    '\u000E', '\u000F' -> Unit
                    else -> {
                        if (ch.isHighSurrogate()) {
                            utf8Hi = ch
                        } else if (ch.isLowSurrogate()) {
                            val hi = utf8Hi
                            utf8Hi = null
                            if (hi != null) putChar("$hi$ch") else putChar("\uFFFD")
                        } else {
                            utf8Hi = null
                            if (ch.code >= 0x20) putChar(ch.toString())
                        }
                    }
                }
                State.Esc -> when (ch) {
                    '[' -> { state = State.Csi; params.clear(); csiPrivate = false }
                    ']' -> { state = State.Osc; osc.clear() }
                    'P' -> state = State.Dcs
                    'X', '^', '_' -> state = State.SosPmApc
                    '7' -> { savedX = cursorX; savedY = cursorY; state = State.Ground }
                    '8' -> { cursorX = savedX; cursorY = savedY; state = State.Ground }
                    'c' -> { hardReset(); state = State.Ground }
                    'D' -> { lineFeed(); state = State.Ground }
                    'E' -> { carriageReturn(); lineFeed(); state = State.Ground }
                    'M' -> { reverseIndex(); state = State.Ground }
                    '(', ')', '*', '+' -> { skipCharset = true; state = State.Ground }
                    else -> state = State.Ground
                }
                State.Csi -> when {
                    ch == '?' && params.isEmpty() -> csiPrivate = true
                    ch in '0'..'9' || ch == ';' || ch == ':' -> params.append(ch)
                    ch in ' '..'/' -> params.append(ch) // intermediate
                    ch in '@'..'~' -> { execCsi(ch); state = State.Ground }
                    else -> state = State.Ground
                }
                State.Osc -> when (ch) {
                    '\u0007' -> state = State.Ground
                    '\u001B' -> { /* ST may follow as ESC \ */ }
                    '\\' -> state = State.Ground
                    else -> osc.append(ch)
                }
                State.Dcs, State.SosPmApc -> when (ch) {
                    '\u001B' -> Unit
                    '\\' -> state = State.Ground
                    '\u0007' -> state = State.Ground
                    else -> Unit
                }
            }
            i++
        }
        bump()
    }

    private fun hardReset() {
        clear()
        autoWrap = true
        insertMode = false
        cursorVisible = true
        originMode = false
    }

    private fun bump() { revision++ }

    private fun styleCell(ch: String, wide: Boolean) = TermCell(
        ch = ch, fg = fg, bg = bg, bold = bold, dim = dim,
        underline = underline, inverse = inverse, wide = wide,
    )

    private fun putChar(s: String) {
        val w = charWidth(s)
        if (w <= 0) {
            if (s.codePointAt(0) >= 0x20 && cursorX > 0) {
                val previous = (cursorX - 1).coerceAtMost(cols - 1).let { if (cells[cursorY][it].spacer) it - 1 else it }
                if (previous >= 0) cells[cursorY][previous] = cells[cursorY][previous].let { it.copy(ch = it.ch + s) }
            }
            return
        }
        if (autoWrap && cursorX + w > cols) {
            wrapped[cursorY] = cursorX
            carriageReturn()
            lineFeed(softWrap = true)
        }
        if (cursorX >= cols) cursorX = cols - 1
        if (insertMode) insertBlanks(w)
        cells[cursorY][cursorX] = styleCell(s, wide = w == 2)
        if (w == 2) {
            if (cursorX + 1 < cols) cells[cursorY][cursorX + 1] = TermCell(spacer = true)
            cursorX += 2
        } else {
            cursorX++
        }
        if (!autoWrap && cursorX >= cols) cursorX = cols - 1
    }

    private fun backspace() {
        if (cursorX > 0) cursorX--
    }

    private fun tab() {
        cursorX = ((cursorX / 8) + 1) * 8
        if (cursorX >= cols) cursorX = cols - 1
    }

    private fun carriageReturn() { cursorX = 0 }

    private fun lineFeed(softWrap: Boolean = false) {
        if (!softWrap) wrapped[cursorY] = 0
        if (cursorY == scrollBottom) scrollUp(1)
        else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        if (cursorY == scrollTop) scrollDown(1)
        else if (cursorY > 0) cursorY--
    }

    private fun copyRow(from: Int, to: Int) {
        for (c in 0 until cols) cells[to][c] = cells[from][c]
        wrapped[to] = wrapped[from]
    }

    private fun clearRow(r: Int) {
        for (c in 0 until cols) cells[r][c] = TermCell()
        wrapped[r] = 0
    }

    private fun scrollUp(n: Int) {
        repeat(n) {
            for (r in scrollTop until scrollBottom) copyRow(r + 1, r)
            clearRow(scrollBottom)
        }
    }

    private fun scrollDown(n: Int) {
        repeat(n) {
            for (r in scrollBottom downTo scrollTop + 1) copyRow(r - 1, r)
            clearRow(scrollTop)
        }
    }

    private fun paramsList(): List<Int> {
        if (params.isEmpty()) return emptyList()
        return params.split(';').map { it.toIntOrNull() ?: 0 }
    }

    private fun p(i: Int, def: Int = 1): Int {
        val list = paramsList()
        val v = list.getOrNull(i) ?: def
        return if (v == 0) def else v
    }

    private fun execCsi(final: Char) {
        if (csiPrivate) {
            when (final) {
                'h' -> paramsList().forEach { when (it) {
                    7 -> autoWrap = true
                    25 -> cursorVisible = true
                    9, 1000, 1002, 1003 -> mouseMode = it
                    1006 -> sgrMouse = true
                    47, 1047, 1049 -> setAlternateScreen(true)
                } }
                'l' -> paramsList().forEach { when (it) {
                    7 -> autoWrap = false
                    25 -> cursorVisible = false
                    9, 1000, 1002, 1003 -> if (mouseMode == it) mouseMode = 0
                    1006 -> sgrMouse = false
                    47, 1047, 1049 -> setAlternateScreen(false)
                } }
            }
            return
        }
        when (final) {
            'A' -> cursorY = (cursorY - p(0)).coerceAtLeast(0)
            'B' -> cursorY = (cursorY + p(0)).coerceAtMost(rows - 1)
            'C' -> cursorX = (cursorX + p(0)).coerceAtMost(cols - 1)
            'D' -> cursorX = (cursorX - p(0)).coerceAtLeast(0)
            'E' -> { cursorY = (cursorY + p(0)).coerceAtMost(rows - 1); cursorX = 0 }
            'F' -> { cursorY = (cursorY - p(0)).coerceAtLeast(0); cursorX = 0 }
            'G' -> cursorX = (p(0) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                val row = (p(0) - 1).coerceIn(0, rows - 1)
                val col = (p(1, 1) - 1).coerceIn(0, cols - 1)
                cursorY = row; cursorX = col
            }
            'J' -> eraseDisplay(paramsList().firstOrNull() ?: 0)
            'K' -> eraseLine(paramsList().firstOrNull() ?: 0)
            'L' -> insertLines(p(0))
            'M' -> deleteLines(p(0))
            '@' -> insertBlanks(p(0))
            'P' -> deleteChars(p(0))
            'X' -> eraseChars(p(0))
            'd' -> cursorY = (p(0) - 1).coerceIn(0, rows - 1)
            'm' -> sgr(paramsList().ifEmpty { listOf(0) })
            'r' -> {
                val top = (p(0) - 1).coerceIn(0, rows - 1)
                val bot = (paramsList().getOrNull(1) ?: rows).let { if (it == 0) rows else it } - 1
                scrollTop = top.coerceAtMost(bot)
                scrollBottom = bot.coerceIn(scrollTop, rows - 1)
            }
            's' -> { savedX = cursorX; savedY = cursorY }
            'u' -> { cursorX = savedX; cursorY = savedY }
            'h' -> paramsList().forEach { if (it == 4) insertMode = true }
            'l' -> paramsList().forEach { if (it == 4) insertMode = false }
            'S' -> scrollUp(p(0))
            'T' -> scrollDown(p(0))
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorY + 1 until rows) clearRow(r)
            }
            1 -> {
                for (r in 0 until cursorY) clearRow(r)
                eraseLine(1)
            }
            2, 3 -> for (r in 0 until rows) clearRow(r)
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (c in cursorX until cols) cells[cursorY][c] = TermCell()
            1 -> for (c in 0..cursorX) cells[cursorY][c] = TermCell()
            2 -> clearRow(cursorY)
        }
    }

    private fun insertLines(n: Int) {
        repeat(n) {
            for (r in scrollBottom downTo cursorY + 1) copyRow(r - 1, r)
            clearRow(cursorY)
        }
    }

    private fun deleteLines(n: Int) {
        repeat(n) {
            for (r in cursorY until scrollBottom) copyRow(r + 1, r)
            clearRow(scrollBottom)
        }
    }

    private fun insertBlanks(n: Int) {
        val row = cells[cursorY]
        for (c in cols - 1 downTo cursorX + n) {
            if (c - n >= cursorX) row[c] = row[c - n]
        }
        for (c in cursorX until (cursorX + n).coerceAtMost(cols)) row[c] = TermCell()
    }

    private fun deleteChars(n: Int) {
        val row = cells[cursorY]
        for (c in cursorX until cols) {
            val src = c + n
            row[c] = if (src < cols) row[src] else TermCell()
        }
    }

    private fun eraseChars(n: Int) {
        for (c in cursorX until (cursorX + n).coerceAtMost(cols)) cells[cursorY][c] = TermCell()
    }

    private fun sgr(ps: List<Int>) {
        var i = 0
        while (i < ps.size) {
            when (val p = ps[i]) {
                0 -> { fg = -1; bg = -1; bold = false; dim = false; underline = false; inverse = false }
                1 -> bold = true
                2 -> dim = true
                4 -> underline = true
                7 -> inverse = true
                22 -> { bold = false; dim = false }
                24 -> underline = false
                27 -> inverse = false
                in 30..37 -> fg = p - 30
                39 -> fg = -1
                in 40..47 -> bg = p - 40
                49 -> bg = -1
                in 90..97 -> fg = p - 90 + 8
                in 100..107 -> bg = p - 100 + 8
                38, 48 -> {
                    val isFg = p == 38
                    if (i + 1 < ps.size) when (ps[i + 1]) {
                        5 -> if (i + 2 < ps.size) {
                            val n = ps[i + 2]
                            if (isFg) fg = n else bg = n
                            i += 2
                        }
                        2 -> if (i + 4 < ps.size) {
                            // Approximate truecolor → nearest 256 cube; store as 16–231
                            val r = ps[i + 2]; val g = ps[i + 3]; val b = ps[i + 4]
                            val n = 16 + 36 * (r / 51) + 6 * (g / 51) + (b / 51)
                            if (isFg) fg = n else bg = n
                            i += 4
                        }
                    }
                }
            }
            i++
        }
    }
}

/** Immutable selection snapshot: output arriving during a drag cannot change copied text. */
internal class TerminalGrid(val cells: Array<Array<TermCell>>, val wrapped: IntArray) {
    val rows get() = cells.size
    val cols get() = cells[0].size
    fun cell(row: Int, col: Int) = cells.getOrNull(row)?.getOrNull(col) ?: TermCell()
    fun position(row: Int, col: Int): Int {
        val r = row.coerceIn(0, rows - 1)
        var c = col.coerceIn(0, cols - 1)
        if (cells[r][c].spacer && c > 0) c--
        return r * cols + c
    }
    fun wordAt(position: Int): IntRange {
        val row = position / cols
        var start = position % cols
        var end = start
        fun word(c: Int): Boolean {
            val index = if (cells[row][c].spacer && c > 0) c - 1 else c
            return cells[row][index].ch.any { it.isLetterOrDigit() || it == '_' }
        }
        if (word(start)) {
            while (start > 0 && word(start - 1)) start--
            while (end + 1 < cols && word(end + 1)) end++
        }
        return row * cols + start..row * cols + end
    }
    fun selectedText(anchor: Int, end: Int): String = buildString {
        val first = minOf(anchor, end)
        val last = maxOf(anchor, end)
        for (r in first / cols..last / cols) {
            val left = if (r == first / cols) first % cols else 0
            val right = if (r == last / cols) last % cols else cols - 1
            val line = buildString {
                val contentEnd = if (wrapped[r] > 0) minOf(right, wrapped[r] - 1) else right
                for (c in left..contentEnd) if (!cells[r][c].spacer) append(cells[r][c].ch)
            }
            append(if (right == cols - 1 && wrapped[r] == 0) line.trimEnd() else line)
            if (r < last / cols && wrapped[r] == 0) append('\n')
        }
    }
}

/** East-Asian / emoji width for a single terminal grapheme (BMP-oriented). */
internal fun charWidth(s: String): Int {
    if (s.isEmpty()) return 0
    val cp = s.codePointAt(0)
    if (cp < 0x20 || cp == 0x7F) return 0
    // Combining marks
    when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> return 0
    }
    // Surrogate / emoji / CJK wide ranges
    if (s.length >= 2 && s[0].isHighSurrogate()) return 2
    if (cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 ||
        cp in 0xF900..0xFAFF || cp in 0xFE10..0xFE6F || cp in 0xFF00..0xFF60 ||
        cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1FAFF || cp in 0x20000..0x3FFFD
    ) return 2
    return 1
}
