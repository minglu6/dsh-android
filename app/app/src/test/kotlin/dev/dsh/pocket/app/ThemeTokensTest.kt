package dev.dsh.pocket.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desktop design-platform.css @ d743267 — sampled alias values. */
class ThemeTokensTest {
    @Test fun lightAliasMatchesDesktopCss() {
        val c = DswColors.Light
        assertEquals(Color(0xFFFFFFFF), c.BgBase)
        assertEquals(Color(0xFF0F1115), c.LabelPrimary)
        assertEquals(Color(0xFF61666B), c.LabelSecondary)
        assertEquals(Color(0xFF81858C), c.LabelTertiary)
        assertEquals(Color(0xFFADB2B8), c.LabelCaption)
        assertEquals(Color(0xFF4176E6), c.ButtonInfoFill)
        assertEquals(Color(0xFFE4EDFD), c.StateBusinessTertiary)
        assertEquals(Color(0xFF0F1115), c.ButtonPrimaryFill)
        assertEquals(Color(0xFFEC1313), c.StateErrorPrimary)
        assertEquals(Color(0xFF22C55E), c.StateSuccessPrimary)
        assertEquals(Color(0xFFF9FAFB), c.SpecificsidebarFill)
    }

    @Test fun darkAliasMatchesDesktopCss() {
        val c = DswColors.Dark
        assertEquals(Color(0xFF151517), c.BgBase)
        assertEquals(Color(0xFF232324), c.BgLayer1)
        assertEquals(Color(0xFFF9FAFB), c.LabelPrimary)
        assertEquals(Color(0xFFCFD3D6), c.LabelSecondary)
        assertEquals(Color(0xFF7AAAFF), c.ButtonInfoFill)
        assertEquals(Color(0xFFF9FAFB), c.ButtonPrimaryFill)
        assertEquals(Color(0xFFF25A5A), c.StateErrorPrimary)
        assertEquals(Color(0xFF34415B), c.StateBusinessTertiary)
        assertEquals(Color(0xFF1B1B1C), c.SpecificsidebarFill)
    }

    @Test fun resolveDarkAppearanceRespectsPref() {
        assertTrue(resolveDarkAppearance(Appearance.Dark, systemDark = false))
        assertFalse(resolveDarkAppearance(Appearance.Light, systemDark = true))
        assertTrue(resolveDarkAppearance(Appearance.System, systemDark = true))
        assertFalse(resolveDarkAppearance(Appearance.System, systemDark = false))
    }

    @Test fun themeStateMapsChromeTokens() {
        ThemeState.setDark(false)
        assertEquals(DswColors.Light.LabelPrimary, T.Ink)
        assertEquals(DswColors.Light.ButtonInfoFill, T.Accent)
        ThemeState.setDark(true)
        assertEquals(DswColors.Dark.LabelPrimary, T.Ink)
        assertEquals(DswColors.Dark.ButtonInfoFill, T.Accent)
        ThemeState.setDark(false)
    }
}
