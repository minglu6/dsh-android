package dev.dsh.pocket.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AnsiTest {
    @Test fun terminalDefaultColorsFollowThemeWithoutOverwritingAnsiColors() {
        val lightInk = androidx.compose.ui.graphics.Color.Black
        val lightBackground = androidx.compose.ui.graphics.Color.White
        assertEquals(lightInk, TermCell().fgColor(lightInk))
        assertEquals(lightBackground, TermCell().bgColor(lightBackground))
        assertEquals(TokyoNight.Ansi[1], TermCell(fg = 1).fgColor(lightInk))
    }

    @Test fun terminalImeKeepsBackspaceAndNormalizesEnter() {
        assertEquals("\u007f", terminalCommittedInput(""))
        assertEquals("", terminalCommittedInput("\u200B"))
        assertEquals("pwd\r", terminalCommittedInput("\u200Bpwd\n"))
        assertEquals("中文", terminalCommittedInput("\u200B中文"))
    }

    @Test fun sgrColorsAndReset() {
        val t = AnsiTerminal(20, 3)
        t.write("\u001B[32mhi\u001B[0m!")
        assertEquals("hi!", t.plainText().lines().first())
        assertEquals(2, t.cell(0, 0).fg) // green
        assertEquals(-1, t.cell(0, 2).fg) // reset
    }

    @Test fun carriageReturnOverwrites() {
        val t = AnsiTerminal(20, 2)
        t.write("abcd\rXY")
        assertEquals("XYcd", t.plainText().lines().first())
    }

    @Test fun eraseLineFromCursor() {
        val t = AnsiTerminal(20, 2)
        t.write("hello\r\u001B[Kworld")
        assertEquals("world", t.plainText().lines().first())
    }

    @Test fun cursorUpAndOverwrite() {
        val t = AnsiTerminal(20, 4)
        t.write("one\r\ntwo\u001B[1A\rXX")
        assertEquals("XXe", t.plainText().lines().first())
        assertEquals("two", t.plainText().lines()[1])
    }

    @Test fun cupAndEraseDisplay() {
        val t = AnsiTerminal(10, 4)
        t.write("aaaa\r\nbbbb\r\ncccc")
        t.write("\u001B[1;1H\u001B[0J")
        assertEquals("", t.plainText().trim())
    }

    @Test fun oscTitleIgnored() {
        val t = AnsiTerminal(20, 2)
        t.write("\u001B]0;title\u0007$ ")
        assertEquals("$", t.plainText().trim())
    }

    @Test fun wideCharOccupiesTwoCells() {
        val t = AnsiTerminal(10, 2)
        t.write("中")
        assertTrue(t.cell(0, 0).wide)
        assertTrue(t.cell(0, 1).spacer)
        assertEquals(2, t.cursorX)
    }

    @Test fun snapshotReplacesViewport() {
        val t = AnsiTerminal(20, 3)
        t.write("old")
        t.applySnapshot("\u001B[2J\u001B[Hnew")
        assertEquals("new", t.plainText().trim())
    }

    @Test fun resizePreservesOriginCells() {
        val t = AnsiTerminal(4, 2)
        t.write("ab")
        t.resize(8, 3)
        assertEquals("a", t.cell(0, 0).ch)
        assertEquals("b", t.cell(0, 1).ch)
        assertEquals(8, t.cols)
        assertEquals(3, t.rows)
    }

    @Test fun charWidthAsciiAndCjk() {
        assertEquals(1, charWidth("A"))
        assertEquals(2, charWidth("中"))
        assertEquals(0, charWidth("\u0007"))
    }

    @Test fun tapMovesByCharactersNotWideCellsAndNeverNavigatesHistory() {
        val t = AnsiTerminal(20, 3)
        t.write("old\r\n$ a中e\u0301😀z")
        assertNull(t.tapInput(0, 1))
        assertNull(t.tapInput(-1, 1))
        assertNull(t.tapInput(1, 20))
        assertEquals("\u001b[D".repeat(4), t.tapInput(1, 3))
        assertEquals(t.tapInput(1, 3), t.tapInput(1, 4))
        assertNull(t.tapInput(1, 19)) // Padding after the current end is not editable.
        t.write("\u001b[3D")
        assertEquals("\u001b[C".repeat(2), t.tapInput(1, 19))
    }

    @Test fun mouseClicksHonorSgrLegacyAndAlternateScreenModes() {
        val t = AnsiTerminal(120, 4)
        t.write("shell\u001b[?1049h")
        assertTrue(t.alternateScreen)
        assertNull(t.tapInput(0, 0))
        t.write("\u001b[?1000;1006h")
        assertEquals("\u001b[<0;111;3M\u001b[<0;111;3m", t.tapInput(2, 110))
        t.write("\u001b[?1006l")
        assertEquals("\u001b[M \"#\u001b[M#\"#", t.tapInput(2, 1))
        assertNull(t.tapInput(2, 110)) // Do not corrupt legacy mouse bytes through UTF-8.
        t.write("\u001b[?1000l\u001b[?9h")
        assertEquals("\u001b[M \"#", t.tapInput(2, 1))
        t.write("\u001b[?9l\u001b[?1049l")
        assertFalse(t.alternateScreen)
        assertEquals("shell", t.plainText())
        assertEquals("\u001b[D".repeat(5), t.tapInput(0, 0))
    }

    @Test fun selectionKeepsUnicodeAndDistinguishesSoftWrapFromNewline() {
        val t = AnsiTerminal(6, 4)
        t.write("ab中e\u0301😀z\r\nnext")
        val grid = t.snapshot()
        assertEquals(2, grid.position(0, 3)) // The trailing half of 中 selects its full grapheme.
        assertEquals("中", grid.selectedText(2, 2))
        assertEquals("e\u0301😀z", grid.selectedText(4, 8))
        assertEquals("ab中e\u0301😀z\nnext", grid.selectedText(0, 15))
        assertEquals("ab中e\u0301😀z\nnext", grid.selectedText(15, 0))
        t.write("changed")
        assertEquals("ab中e\u0301😀z\nnext", grid.selectedText(0, 15))
    }

    @Test fun selectionRetainsHardNewlinesAndWordAtTouchedLocation() {
        val t = AnsiTerminal(8, 3)
        t.write("one two\r\n三😀")
        val grid = t.snapshot()
        assertEquals(4..6, grid.wordAt(grid.position(0, 5)))
        assertEquals("two\n三😀", grid.selectedText(4, 11))
        assertEquals("😀", grid.selectedText(grid.position(1, 3), grid.position(1, 3)))
    }

    @Test fun snapshotResetDropsPreviousTerminalModesAndParserState() {
        val t = AnsiTerminal(4, 3)
        t.write("old\u001b7\u001b[?7l\u001b[?25l\u001b[4h\u001b[?1049h\u001b[?1000;1006h\u001b(")
        t.applySnapshot("abcde")
        assertEquals("abcd\ne", t.plainText())
        assertTrue(t.isCursorVisible())
        assertFalse(t.alternateScreen)
        assertNull(t.tapInput(0, 0))
        t.write("\u001b8X")
        assertEquals("Xbcd\ne", t.plainText())
    }
}
