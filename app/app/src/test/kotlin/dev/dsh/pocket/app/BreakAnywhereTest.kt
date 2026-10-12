package dev.dsh.pocket.app

import kotlin.test.Test
import kotlin.test.assertTrue

class BreakAnywhereTest {
    @Test fun insertsBreaksInLongPaths() {
        val path = "/Users/felix/repo/deepseek-harness/packages/client/ui-trajectory/src/client/Foo.ts"
        val out = path.breakAnywhere()
        assertTrue(out.contains('\u200B'), "expected ZWSP soft-break opportunities")
        assertTrue(out.replace("\u200B", "") == path, "ZWSP must not alter visible characters")
    }

    @Test fun breaksLongTokensWithoutSeparators() {
        val token = "a".repeat(40)
        val out = token.breakAnywhere(every = 10)
        assertTrue(out.count { it == '\u200B' } >= 3)
        assertTrue(out.replace("\u200B", "") == token)
    }
}
