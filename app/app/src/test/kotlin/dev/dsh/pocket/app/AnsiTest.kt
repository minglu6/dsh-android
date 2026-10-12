package dev.dsh.pocket.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AnsiTest {
    @Test fun stripsCsiAndKeepsText() {
        assertEquals("hello\nworld", stripAnsi("\u001B[32mhello\u001B[0m\r\nworld"))
        assertEquals("$ ", stripAnsi("\u001B]0;title\u0007$ "))
    }
}
