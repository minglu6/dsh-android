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

    @Test fun legacyMobileSharedValuesAreNeverLoadedOrUploadedToHost() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val keys = listOf("appearance", "transcriptView", "performanceUsage", "busySend", "defaultPermission")
        val previous = keys.associateWith { storage.getString(it, null) }
        try {
            storage.edit().putString("appearance", "Dark").putString("transcriptView", "Verbose")
                .putString("performanceUsage", "Detailed").putString("busySend", "Steer")
                .putString("defaultPermission", "danger-full-access").commit()
            val prefs = AppPrefs(context)
            assertEquals(Appearance.System, prefs.appearance)
            assertEquals(TranscriptView.Standard, prefs.transcriptView)
            assertEquals(PerformanceUsage.Compact, prefs.performanceUsage)
            assertEquals(BusySend.Queue, prefs.busySend)
            assertEquals("workspace-write", prefs.defaultPermission)
            prefs.appearance = Appearance.Light
            assertEquals(Appearance.System, prefs.appearance)
            installHostSettingsFixture(prefs)
            assertEquals(Appearance.Light, prefs.appearance)
            assertEquals(TranscriptView.Standard, prefs.transcriptView)
            prefs.appearance = Appearance.System
            assertEquals(Appearance.System, prefs.appearance)
            assertEquals("Dark", storage.getString("appearance", null))
            prefs.hostSettings.clear()
            assertEquals(TranscriptView.Standard, prefs.transcriptView)
        } finally {
            storage.edit().apply { previous.forEach { (key, value) -> putString(key, value) } }.commit()
        }
    }
}
