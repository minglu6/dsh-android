package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.*

/** In-process Host transport fixture for UI tests; production never has an offline write path. */
internal class HostSettingsFixture(private val settings: HostSettings) {
    private val values = mutableMapOf<String, JsonObject>()
    private val revisions = mutableMapOf<String, Int>()
    init {
        SharedSetting.entries.forEach { values[it.namespace] = JsonObject(emptyMap()) }
        values["locale"] = jsonOf("preference" to "zh")
        values["ui-theme"] = jsonOf("preference" to "light")
        settings.bind(::describe, ::mutate)
    }
    fun describe(): JsonElement = buildJsonObject {
        put("writable", true)
        put("namespaces", JsonArray(values.map { (ns, value) -> buildJsonObject {
            put("ns", ns); put("revision", revisions[ns] ?: 0); put("value", value)
        } }))
    }
    private fun mutate(ns: String, ops: JsonArray, revision: Int): JsonElement {
        check(revision == (revisions[ns] ?: 0)) { "settings/conflict" }
        ops.forEach { operation ->
            val field = operation["path"].arr()!!.single().str()!!
            values[ns] = JsonObject(values.getValue(ns) + (field to operation["value"]!!))
        }
        revisions[ns] = revision + 1
        return describe()["namespaces"].arr()!!.first { it["ns"].str() == ns }
    }
    fun desktopSet(setting: SharedSetting, value: JsonElement) {
        mutate(setting.namespace, buildJsonArray { add(jsonOf("op" to "set", "path" to listOf(setting.field), "value" to value)) }, revisions[setting.namespace] ?: 0)
        settings.refresh()
    }
}

internal fun installHostSettingsFixture(prefs: AppPrefs = Pocket.prefs): HostSettingsFixture = HostSettingsFixture(prefs.hostSettings)
