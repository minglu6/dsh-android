package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.*

data class AgentTeam(
    val leadSessionId: String,
    val members: List<AgentTeamMember>,
    val tasks: List<AgentTeamTask>,
    val failure: String? = null,
)

data class AgentTeamMember(
    val id: String,
    val name: String,
    val role: String,
    val phase: String,
    val status: String,
    val current: Boolean,
    val address: SessionAddress?,
    val model: String? = null,
    val error: String? = null,
) {
    val canOpen: Boolean get() = !current && phase == "active" && address != null
}

data class AgentTeamTask(
    val id: String,
    val revision: Long,
    val subject: String,
    val description: String,
    val status: String,
    val blockedBy: List<String>,
    val writeScopes: List<String>,
    val ownerName: String?,
    val ready: Boolean,
    val writeScopeWarnings: List<String>,
)

data class SubagentEntry(
    val id: String,
    val label: String,
    val mode: String,
    val address: SessionAddress?,
    val status: String,
    val current: Boolean,
    val title: String? = null,
) {
    val canOpen: Boolean get() = address != null && !current
}

/** Shared sequenced Host projections, independent from the currently followed transcript. */
internal class AgentTeamStore {
    private data class Value(val seq: Long, val value: JsonElement?)
    private val values = mutableMapOf<String, MutableMap<String, Value>>()
    private val projectionKeys = setOf("agentTeam", "subagentCatalog", "modelSelection")
    private val childModes = setOf("one-shot", "continuable", "unknown")
    fun clear() = values.clear()
    fun resetSequences() {
        values.values.forEach { fields -> fields.replaceAll { _, value -> value.copy(seq = -1) } }
    }
    fun baseline(id: String, projection: JsonElement?) {
        val seq = projection["asOfSeq"].long() ?: -1L
        val fields = projection["values"] as? JsonObject ?: return
        // A full baseline also clears projections omitted by the Host.
        (values[id]?.keys.orEmpty() + fields.keys).toSet().forEach { key ->
            put(id, key, seq, fields[key])
        }
    }
    fun put(id: String, key: String, seq: Long, value: JsonElement?): Boolean {
        if (key !in projectionKeys) return false
        val fields = values.getOrPut(id) { mutableMapOf() }
        val previous = fields[key]
        if (seq < (previous?.seq ?: -1L)) return false
        fields[key] = Value(seq, value)
        return previous?.value != value
    }
    fun address(id: String): SessionAddress? {
        values.forEach { (parent, fields) ->
            val child = fields["subagentCatalog"]?.value.arr().orEmpty().firstOrNull { it["id"].str() == id }
            val mode = child["mode"].str()
            if (mode in childModes) return SessionAddress(id, parent, mode)
        }
        return null
    }
    fun subagents(current: SessionAddress?, sessions: List<SessionSummary>): List<SubagentEntry>? {
        current ?: return null
        val parent = current.parentSessionId ?: current.sessionId
        val catalog = values[parent]?.get("subagentCatalog")?.value.arr() ?: return null
        return catalog.mapNotNull { row ->
            val id = row["id"].str() ?: return@mapNotNull null
            val mode = row["mode"].str() ?: return@mapNotNull null
            SubagentEntry(id, row["label"].str() ?: id, mode, address(id),
                if (sessions.firstOrNull { it.sessionId == id }?.running == true) "running" else "inactive",
                id == current.sessionId, sessions.firstOrNull { it.sessionId == id }?.title)
        }
    }
    fun team(current: SessionAddress?, sessions: List<SessionSummary>): AgentTeam? {
        current ?: return null
        val lead = current.parentSessionId ?: current.sessionId
        val team = values[lead]?.get("agentTeam")?.value as? JsonObject ?: return null
        val members = team["members"].arr().orEmpty().mapNotNull { row ->
            val id = row["id"].str() ?: return@mapNotNull null
            val role = row["role"].str() ?: return@mapNotNull null
            val phase = row["phase"].str() ?: return@mapNotNull null
            AgentTeamMember(id, row["name"].str() ?: id, role, phase,
                if (phase == "active") if (sessions.firstOrNull { it.sessionId == id }?.running == true) "running" else "inactive" else phase,
                id == current.sessionId,
                if (role == "lead" && id == lead) SessionAddress(id) else address(id),
                values[id]?.get("modelSelection")?.value?.get("next")?.get("model").str(), row["error"].str())
        }
        fun JsonElement.strings(key: String) = this[key].arr().orEmpty().mapNotNull { it.str() }
        val tasks = team["tasks"].arr().orEmpty().mapNotNull { row ->
            val id = row["id"].str() ?: return@mapNotNull null
            val status = row["status"].str() ?: return@mapNotNull null
            if (status == "deleted") return@mapNotNull null
            AgentTeamTask(id, row["revision"].long() ?: 0, row["subject"].str().orEmpty(),
                row["description"].str().orEmpty(), status, row.strings("blockedBy"), row.strings("writeScopes"),
                row["ownerName"].str(), row["ready"].bool() ?: false, row.strings("writeScopeWarnings"))
        }
        return AgentTeam(lead, members, tasks, team["failure"].str())
    }
}
