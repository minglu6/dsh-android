package dev.dsh.pocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelLabelTest {
    @Test fun officialApiAndAccountFlashShareTheReleaseMarker() {
        for (provider in listOf("deepseek-official", "deepseek-account")) {
            assertEquals("1017", modelReleaseBadge(provider, "deepseek-flash", "DeepSeek-V4.1-Flash"))
        }
    }

    @Test fun thirdPartyAndRenamedModelsNeverClaimTheOfficialRelease() {
        assertNull(modelReleaseBadge("custom", "deepseek-flash", "DeepSeek-V4.1-Flash"))
        assertNull(modelReleaseBadge("deepseek-official", "deepseek-pro", "DeepSeek-V4.1-Flash"))
        assertNull(modelReleaseBadge("deepseek-account", "deepseek-flash", "DeepSeek-V4-Flash"))
        assertNull(modelReleaseBadge("deepseek-official", "deepseek-flash", "My Flash"))
        assertNull(modelReleaseBadge(null, null, null))
    }
}
