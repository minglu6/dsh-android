package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandMenuPolicyTest {
    @Before fun state() {
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
    }

    private fun row(json: String) = PocketJson.parseToJsonElement(json) as JsonObject
    private fun names(rows: List<JsonObject>) = rows.map { it["name"].str() }

    @Test fun emptyQueryUsesDesktopSectionsThenUnlistedCatalogOrder() {
        val rows = listOf("deploy", "export", "feedback", "compact", "goal", "plan", "permission", "other")
            .map { row("""{"name":"$it"}""") } + listOf(
                row("""{"name":"file","client":"file"}"""),
                row("""{"name":"model","client":"model"}"""),
            )
        assertEquals(listOf("file", "goal", "plan", "feedback", "compact", "permission", "model", "export", "deploy", "other"),
            names(commandMenuRows(rows, "", true)))
    }

    @Test fun inlineFiltersOnlyInputHintsAndRenamedBuiltinsKeepCatalogTailOrder() {
        val rows = listOf(
            row("""{"name":"team-plan","definitionId":"@deepseek-ai/dsh-plan-mode"}"""),
            row("""{"name":"goal","input":{"hint":"objective"}}"""),
            row("""{"name":"compact"}"""),
            row("""{"name":"custom","input":{}}"""),
        )
        assertEquals(listOf("compact", "team-plan", "custom"), names(commandMenuRows(rows, "", false)))
        assertEquals("计划", commandMenuLabel(rows.first()))
    }

    @Test fun searchRanksNamesAndLocalizedLabelsButNeverDescriptions() {
        val rows = listOf(
            row("""{"name":"unrelated","description":"compact"}"""),
            row("""{"name":"team-compact"}"""),
            row("""{"name":"compact-now"}"""),
            row("""{"name":"compress","definitionId":"@deepseek-ai/dsh-command-compact"}"""),
        )
        assertEquals(listOf("compact-now", "team-compact"), names(commandMenuRows(rows, "compact", true)))
        assertEquals(listOf("compact-now", "team-compact"), names(commandMenuRows(rows, "cmpct", true)))
        assertEquals(listOf("compress"), names(commandMenuRows(rows, "压缩", true)))
    }
}
