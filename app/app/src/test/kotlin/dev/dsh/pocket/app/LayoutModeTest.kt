package dev.dsh.pocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutModeTest {
    @Test fun autoUses600DpBreakpoint() {
        assertFalse(useTwoPaneLayout(360, LayoutMode.Auto))
        assertFalse(useTwoPaneLayout(599, LayoutMode.Auto))
        assertTrue(useTwoPaneLayout(600, LayoutMode.Auto))
        assertTrue(useTwoPaneLayout(900, LayoutMode.Auto))
    }

    @Test fun phoneAndDesktopForceMode() {
        assertFalse(useTwoPaneLayout(900, LayoutMode.Phone))
        assertTrue(useTwoPaneLayout(360, LayoutMode.Desktop))
    }

    @Test fun previewKindDetectsMedia() {
        assertEquals(PreviewKind.Image, previewKindFor("shots/a.PNG"))
        assertEquals(PreviewKind.Pdf, previewKindFor("/tmp/doc.pdf"))
        assertEquals(PreviewKind.Text, previewKindFor("src/Main.kt"))
    }

    @Test fun extractWorkspacePathsFromToolJson() {
        val paths = extractWorkspacePaths("""{"path":"bridge/a.mjs","file_path":"docs/x.md"}""")
        assertTrue(paths.contains("bridge/a.mjs"))
        assertTrue(paths.contains("docs/x.md"))
    }
}
