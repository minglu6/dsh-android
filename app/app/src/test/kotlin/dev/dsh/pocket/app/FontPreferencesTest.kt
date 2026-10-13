package dev.dsh.pocket.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FontPreferencesTest {
    @Test fun normalizesDesktopFamilyListsWithoutChangingPriority() {
        assertEquals("serif, monospace", normalizeFontFamily("  serif , monospace  "))
        assertEquals("\"Noto Sans\", sans-serif", normalizeFontFamily(" 'Noto   Sans' , sans-serif"))
        assertEquals("", normalizeFontFamily(" , \"'<>\\\u0000, "))
        val normalized = normalizeFontFamily("MesloLGL DZ Nerd Font, ui-monospace")
        assertEquals(normalized, normalizeFontFamily(normalized))
    }

    @Test fun hostStoresIndependentNormalizedFamiliesAndClampsEverySizeWithoutLocalPersistence() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val keys = listOf("textFontFamily", "codeFontFamily", "terminalFontFamily", "fontSize", "codeFontSize", "terminalFontSize")
        val before = keys.associateWith { storage.all[it] }
        try {
            storage.edit().apply { keys.forEach { remove(it) } }.commit()
            val prefs = AppPrefs(context)
            val host = installHostSettingsFixture(prefs)
            prefs.textFontFamily = " serif , monospace "
            prefs.codeFontFamily = "MesloLGL DZ Nerd Font"
            prefs.terminalFontFamily = "monospace"
            val reopened = AppPrefs(context)
            assertEquals("", reopened.textFontFamily)
            reopened.hostSettings.bind({ host.describe() }, { _, _, _ -> error("read-only test client") })
            assertEquals("serif, monospace", reopened.textFontFamily)
            assertEquals("\"MesloLGL DZ Nerd Font\"", reopened.codeFontFamily)
            assertEquals("monospace", reopened.terminalFontFamily)
            prefs.fontSize = 100; prefs.codeFontSize = 100; prefs.terminalFontSize = 100
            assertEquals(listOf(22, 16, 20), listOf(prefs.fontSize, prefs.codeFontSize, prefs.terminalFontSize))
            prefs.fontSize = 0; prefs.codeFontSize = 0; prefs.terminalFontSize = 0
            assertEquals(listOf(10, 10, 10), listOf(prefs.fontSize, prefs.codeFontSize, prefs.terminalFontSize))
            prefs.textFontFamily = " "; prefs.codeFontFamily = ""; prefs.terminalFontFamily = ","
            assertEquals(listOf("", "", ""), listOf(prefs.textFontFamily, prefs.codeFontFamily, prefs.terminalFontFamily))
            assertEquals(emptyMap<String, Any>(), storage.all.filterKeys { it in keys })
        } finally {
            storage.edit().apply {
                before.forEach { (key, value) ->
                    when (value) { is String -> putString(key, value); is Int -> putInt(key, value); else -> remove(key) }
                }
            }.commit()
        }
    }
}
