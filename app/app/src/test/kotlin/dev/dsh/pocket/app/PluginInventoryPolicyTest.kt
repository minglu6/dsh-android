package dev.dsh.pocket.app

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginInventoryPolicyTest {
    private fun row(value: String) = Json.parseToJsonElement(value)

    @Test
    fun presetSelectionFollowsExplicitThenDefaultThenFirst() {
        val first = row("""{"id":"first"}""")
        val default = row("""{"id":"standard","isDefault":true}""")
        assertSame(default, inventorySelectedPreset(listOf(first, default), null))
        assertSame(default, inventorySelectedPreset(listOf(first, default), "removed"))
        assertSame(first, inventorySelectedPreset(listOf(first, default), "first"))
        assertSame(first, inventorySelectedPreset(listOf(first), null))
        assertEquals(null, inventorySelectedPreset(emptyList(), null))
    }

    @Test
    fun moduleFallbackIsShortenedButLocalizedTitleIsPreserved() {
        assertEquals("ui-plugins", inventoryTitle(row("""{"moduleName":"@deepseek-ai/dsh-client-ui-plugins"}"""), false))
        assertEquals("timer", inventoryTitle(row("""{"moduleName":"@deepseek-ai/cordis-plugin-timer"}"""), false))
        val localized = row("""{"moduleName":"pkg","meta":{"title":{"zh":"插件名称","en":"Plugin name"}}}""")
        assertEquals("插件名称", inventoryTitle(localized, false))
        assertEquals("Plugin name", inventoryTitle(localized, true))
    }

    @Test
    fun searchMatchesIdentityAndLocalizedDescriptionWithTrimmedCaseInsensitiveQuery() {
        val plugin = row("""{"entryId":"include:custom-tools","moduleName":"@vendor/Plugin","meta":{"title":{"zh":"测试","en":"Test"},"description":{"zh":"会话工具","en":"Session tools"}}}""")
        assertTrue(inventoryMatches(plugin, "  CUSTOM-TOOLS ", false))
        assertTrue(inventoryMatches(plugin, "@VENDOR", false))
        assertTrue(inventoryMatches(plugin, "会话", false))
        assertTrue(inventoryMatches(plugin, "SESSION", true))
        assertTrue(inventoryMatches(plugin, " ", true))
        assertFalse(inventoryMatches(plugin, "missing", true))
    }

    @Test
    fun presetProvidedStatusRequiresEnabledCompositionAndDisabledGlobalRow() {
        val enabled = row("""{"id":"on","rows":[{"moduleName":"tool","enabled":true},{"moduleName":"tool","enabled":true}]}""")
        val disabled = row("""{"id":"off","rows":[{"moduleName":"tool","enabled":false}]}""")
        val conditional = row("""{"id":"conditional","rows":[{"moduleName":"tool","enabled":"conditional"}]}""")
        val presets = listOf(enabled, disabled, conditional)
        assertEquals(listOf(enabled), inventoryProviders(row("""{"moduleName":"tool","enabled":false}"""), presets))
        assertTrue(inventoryProviders(row("""{"moduleName":"tool","enabled":true}"""), presets).isEmpty())
        assertTrue(inventoryProviders(row("""{"moduleName":"tool","enabled":false,"fiberPhase":"failed"}"""), presets).isEmpty())
        assertTrue(inventoryProviders(row("""{"moduleName":"other","enabled":false}"""), presets).isEmpty())
    }

    @Test
    fun conditionalAndFailedStatesAreNotCollapsedIntoDisabled() {
        assertEquals("conditional", inventoryEnablement(row("""{"enabled":"conditional"}""")))
        assertEquals("disabled", inventoryEnablement(row("""{"enabled":false}""")))
        assertEquals("enabled", inventoryEnablement(row("""{"enabled":true}""")))
        assertEquals("preset", inventoryEnablement(row("""{"enabled":false}"""), true))
        assertEquals("failed", inventoryEnablement(row("""{"enabled":false,"fiberPhase":"failed"}"""), true))
    }

    @Test
    fun newlyPublishedOfficialBundlesRemainVisibleOnMainList() {
        assertTrue(pluginOptionalVisible("@deepseek-ai/dsh-new-official-plugin", expanded = false))
        assertFalse(pluginOptionalVisible("@deepseek-ai/dsh-experimental-session-search", expanded = false))
        assertTrue(pluginOptionalVisible("@deepseek-ai/dsh-experimental-session-search", expanded = true))
    }

    @Test
    fun optionalOrderingMatchesDesktopIncludingMoreGroup() {
        val primary = pluginOptionalRank("@deepseek-ai/dsh-experimental-agent-team-profile")
        val fresh = pluginOptionalRank("@deepseek-ai/dsh-new-official-plugin")
        val more = pluginOptionalRank("@deepseek-ai/dsh-experimental-tool-worktree")
        val last = pluginOptionalRank("@deepseek-ai/dsh-subagent-claude-code")
        assertTrue(primary < fresh)
        assertTrue(fresh < more)
        assertTrue(more < last)
    }
}
