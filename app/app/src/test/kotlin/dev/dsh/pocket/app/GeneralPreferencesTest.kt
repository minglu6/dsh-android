package dev.dsh.pocket.app

import android.content.Context
import dev.dsh.pocket.protocol.jsonOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class GeneralPreferencesTest {
    @Test fun collapseTimingIsKeptWithinTheClientAndResetForANewInstance() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val previous = storage.getString("collapseTiming", null)
        try {
            storage.edit().putString("collapseTiming", "NextInput").commit()
            val current = AppPrefs(context)
            assertEquals(CollapseTiming.Completion, current.collapseTiming)
            current.collapseTiming = CollapseTiming.NextInput
            assertEquals(CollapseTiming.NextInput, current.collapseTiming)
            assertEquals(CollapseTiming.Completion, AppPrefs(context).collapseTiming)
            current.collapseTiming = CollapseTiming.Completion
            assertEquals("NextInput", storage.getString("collapseTiming", null))
        } finally {
            storage.edit().putString("collapseTiming", previous).commit()
        }
    }

    @Test fun appearancePersistsLocallyAndIgnoresHostThemeEvents() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val previous = storage.getString("appearance", null)
        try {
            storage.edit().putString("appearance", "Dark").commit()
            val prefs = AppPrefs(context)
            var mutations = 0
            val host = installHostSettingsFixture(prefs)
            host.desktopReplace("ui-theme", jsonOf("preference" to "light", "fontSize" to 22))
            assertEquals(Appearance.Dark, prefs.appearance)
            prefs.appearance = Appearance.Light
            assertEquals(Appearance.Light, prefs.appearance)
            assertEquals("Light", storage.getString("appearance", null))
            prefs.hostSettings.bind({
                jsonOf("writable" to true, "namespaces" to listOf(
                    jsonOf("ns" to "ui-theme", "revision" to 9, "value" to jsonOf("preference" to "dark")),
                    jsonOf("ns" to "ui-chat", "revision" to 3, "value" to jsonOf("transcriptView" to "verbose")),
                ))
            }, { _, _, _ ->
                mutations++
                error("appearance must not mutate Host")
            })
            assertEquals(Appearance.Light, prefs.appearance)
            prefs.appearance = Appearance.System
            assertEquals(Appearance.System, prefs.appearance)
            assertEquals("System", storage.getString("appearance", null))
            assertNull(prefs.hostSettings.error)
            prefs.hostSettings.refresh()
            assertEquals(Appearance.System, prefs.appearance)
            assertEquals(TranscriptView.Verbose, prefs.transcriptView)
            assertEquals(0, mutations)
            assertNull(prefs.hostSettings.error)
            assertEquals(Appearance.System, AppPrefs(context).appearance)
        } finally {
            storage.edit().putString("appearance", previous).commit()
        }
    }

    @Test fun savingAppearanceDoesNotCallHostMutate() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val previous = storage.getString("appearance", null)
        try {
            storage.edit().remove("appearance").commit()
            val prefs = AppPrefs(context)
            var mutations = 0
            prefs.hostSettings.bind({
                jsonOf("writable" to true, "namespaces" to listOf(
                    jsonOf("ns" to "ui-theme", "revision" to 4, "value" to jsonOf("preference" to "dark")),
                    jsonOf("ns" to "ui-chat", "revision" to 1, "value" to jsonOf("transcriptView" to "standard")),
                ))
            }, { _, _, _ ->
                mutations++
                error("appearance must not mutate Host")
            })
            assertEquals(Appearance.System, prefs.appearance)
            assertNull(prefs.hostSettings.error)
            prefs.appearance = Appearance.Dark
            assertEquals(Appearance.Dark, prefs.appearance)
            assertEquals(0, mutations)
            assertNull(prefs.hostSettings.error)
            assertEquals("Dark", storage.getString("appearance", null))
        } finally {
            storage.edit().putString("appearance", previous).commit()
        }
    }

    @Test fun appearanceAndFontsSaveOfflineWithoutHostError() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val keys = listOf("appearance", "fontSize", "textFontFamily")
        val previous = keys.associateWith { storage.all[it] }
        try {
            storage.edit().apply { keys.forEach { remove(it) } }.commit()
            val prefs = AppPrefs(context)
            prefs.hostSettings.clear()
            assertFalse(prefs.hostSettings.online)
            prefs.appearance = Appearance.Dark
            prefs.fontSize = 18
            prefs.textFontFamily = "serif"
            assertEquals(Appearance.Dark, prefs.appearance)
            assertEquals(18, prefs.fontSize)
            assertEquals("serif", prefs.textFontFamily)
            assertNull(prefs.hostSettings.error)
            assertFalse(prefs.hostSettings.online)
        } finally {
            storage.edit().apply {
                previous.forEach { (key, value) ->
                    when (value) { is String -> putString(key, value); is Int -> putInt(key, value); else -> remove(key) }
                }
            }.commit()
        }
    }

    @Test fun legacyMobileSharedValuesAreNeverLoadedOrUploadedToHost() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val keys = listOf("transcriptView", "performanceUsage", "busySend", "defaultPermission")
        val previous = keys.associateWith { storage.getString(it, null) }
        try {
            storage.edit().putString("transcriptView", "Verbose")
                .putString("performanceUsage", "Detailed").putString("busySend", "Steer")
                .putString("defaultPermission", "danger-full-access").commit()
            val prefs = AppPrefs(context)
            assertEquals(TranscriptView.Standard, prefs.transcriptView)
            assertEquals(PerformanceUsage.Compact, prefs.performanceUsage)
            assertEquals(BusySend.Queue, prefs.busySend)
            assertEquals("workspace-write", prefs.defaultPermission)
            prefs.transcriptView = TranscriptView.Verbose
            assertEquals(TranscriptView.Standard, prefs.transcriptView)
            installHostSettingsFixture(prefs)
            assertEquals(TranscriptView.Standard, prefs.transcriptView)
            prefs.transcriptView = TranscriptView.Verbose
            assertEquals(TranscriptView.Verbose, prefs.transcriptView)
            assertEquals("Verbose", storage.getString("transcriptView", null))
            prefs.hostSettings.clear()
            assertEquals(TranscriptView.Standard, prefs.transcriptView)
        } finally {
            storage.edit().apply { previous.forEach { (key, value) -> putString(key, value) } }.commit()
        }
    }
}
