package dev.dsh.pocket.app

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import dev.dsh.pocket.protocol.jsonOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class FontPreferencesTest {
    @Test fun normalizesDesktopFamilyListsWithoutChangingPriority() {
        assertEquals("serif, monospace", normalizeFontFamily("  serif , monospace  "))
        assertEquals("\"Noto Sans\", sans-serif", normalizeFontFamily(" 'Noto   Sans' , sans-serif"))
        assertEquals("", normalizeFontFamily(" , \"'<>\\\u0000, "))
        val normalized = normalizeFontFamily("MesloLGL DZ Nerd Font, ui-monospace")
        assertEquals(normalized, normalizeFontFamily(normalized))
    }

    @Test fun resolvesGenericFontsAndBuiltInDefaultsForEachRole() {
        assertEquals(FontFamily.Default, LocalFonts.resolve("", false))
        assertEquals(MesloNerdFamily, LocalFonts.resolve("", true))
        assertEquals(FontFamily.Serif, LocalFonts.resolve("serif, monospace", false))
        assertEquals(FontFamily.Monospace, LocalFonts.resolve("ui-monospace", true))
        assertEquals(FontFamily.SansSerif, LocalFonts.resolve("system-ui", false))
    }

    @Test fun unavailableFamilyFallsThroughToNextLocalFamilyOrBuiltIn() {
        assertEquals(FontFamily.Serif, LocalFonts.resolve("\"dsh-missing-font-9dcdb\", serif", false))
        assertEquals(MesloNerdFamily, LocalFonts.resolve("\"dsh-missing-font-9dcdb\"", true))
    }

    @Test fun storesIndependentNormalizedFamiliesAndClampsEverySizeLocallyWithoutHostMutation() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val keys = listOf("textFontFamily", "codeFontFamily", "terminalFontFamily", "fontSize", "codeFontSize", "terminalFontSize")
        val before = keys.associateWith { storage.all[it] }
        try {
            storage.edit().apply { keys.forEach { remove(it) } }.commit()
            val prefs = AppPrefs(context)
            var mutations = 0
            prefs.hostSettings.bind({
                jsonOf("writable" to true, "namespaces" to listOf(
                    jsonOf("ns" to "ui-theme", "revision" to 6, "value" to jsonOf(
                        "textFontFamily" to "cursive",
                        "codeFontFamily" to "serif",
                        "terminalFontFamily" to "fantasy",
                        "fontSize" to 99,
                        "codeFontSize" to 99,
                        "terminalFontSize" to 99,
                    )),
                ))
            }, { _, _, _ ->
                mutations++
                error("font prefs must not mutate Host")
            })
            assertEquals("", prefs.textFontFamily)
            assertEquals(14, prefs.fontSize)
            prefs.textFontFamily = " serif , monospace "
            prefs.codeFontFamily = "MesloLGL DZ Nerd Font"
            prefs.terminalFontFamily = "monospace"
            val reopened = AppPrefs(context)
            assertEquals("serif, monospace", reopened.textFontFamily)
            assertEquals("\"MesloLGL DZ Nerd Font\"", reopened.codeFontFamily)
            assertEquals("monospace", reopened.terminalFontFamily)
            prefs.fontSize = 100; prefs.codeFontSize = 100; prefs.terminalFontSize = 100
            assertEquals(listOf(22, 16, 20), listOf(prefs.fontSize, prefs.codeFontSize, prefs.terminalFontSize))
            prefs.hostSettings.refresh()
            assertEquals("serif, monospace", prefs.textFontFamily)
            assertEquals(22, prefs.fontSize)
            prefs.fontSize = 0; prefs.codeFontSize = 0; prefs.terminalFontSize = 0
            assertEquals(listOf(10, 10, 10), listOf(prefs.fontSize, prefs.codeFontSize, prefs.terminalFontSize))
            prefs.textFontFamily = " "; prefs.codeFontFamily = ""; prefs.terminalFontFamily = ","
            assertEquals(listOf("", "", ""), listOf(prefs.textFontFamily, prefs.codeFontFamily, prefs.terminalFontFamily))
            assertEquals(0, mutations)
            assertNull(prefs.hostSettings.error)
            assertEquals("", storage.getString("textFontFamily", null))
            assertEquals(10, storage.getInt("fontSize", -1))
        } finally {
            storage.edit().apply {
                before.forEach { (key, value) ->
                    when (value) { is String -> putString(key, value); is Int -> putInt(key, value); else -> remove(key) }
                }
            }.commit()
        }
    }
}
