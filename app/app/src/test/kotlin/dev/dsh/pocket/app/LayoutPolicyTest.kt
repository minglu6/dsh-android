package dev.dsh.pocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPolicyTest {
    @Test fun autoReservesConversationWidthBesideSidebar() {
        assertFalse(useTwoPaneLayout(360))
        assertFalse(useTwoPaneLayout(759))
        assertTrue(useTwoPaneLayout(760))
        assertTrue(useTwoPaneLayout(900))
    }

    @Test fun previewKindDetectsMedia() {
        assertEquals(PreviewKind.Image, previewKindFor("shots/a.PNG"))
        assertEquals(PreviewKind.Pdf, previewKindFor("/tmp/doc.pdf"))
        assertEquals(PreviewKind.Text, previewKindFor("src/Main.kt"))
    }
}
