package dev.dsh.pocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Locale + nav gating for desktop settings parity pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPagesTest {
    @Before fun init() {
        Pocket.init(RuntimeEnvironment.getApplication())
        Pocket.prefs.language = AppLanguage.Zh
    }

    @Test fun modelsLocaleZh() {
        assertEquals("模型", L.modelsNav())
        assertEquals("API 密钥已配置", L.credentialConfigured())
        assertEquals("填入各提供商的 API 密钥即可使用其模型。", L.modelsIntro())
        assertEquals("自动化任务", L.scheduleNav())
        assertEquals("凭证", L.credentialsNav())
    }

    @Test fun pluginsAndScheduleLocaleEn() {
        Pocket.prefs.language = AppLanguage.En
        assertEquals("Plugins", L.pluginsNav())
        assertEquals("Install, enable, and configure plugins", L.pluginsIntro())
        assertEquals("Scheduled tasks", L.scheduleNav())
        assertEquals("Inactive", L.statusInactive())
        assertEquals("Credentials", L.credentialsNav())
        assertEquals("••••••••", L.maskedSecret())
    }

    @Test fun settingsNavOpsGate() {
        Pocket.ops.value = emptySet()
        assertFalse(Pocket.hasOp("models.providers"))
        Pocket.ops.value = setOf("models.providers", "plugins.list", "schedule.catalog", "credentials.list")
        assertTrue(Pocket.hasOp("models.providers"))
        assertTrue(Pocket.hasOp("plugins.list"))
        assertTrue(Pocket.hasOp("schedule.catalog"))
        assertTrue(Pocket.hasOp("credentials.list"))
    }
}
