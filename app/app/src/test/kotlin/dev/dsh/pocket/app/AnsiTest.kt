package dev.dsh.pocket.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnsiTest {
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
}
