package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonObject

/** Durable child identity is owned by its parent; a child id alone is not a root address. */
data class SessionAddress(
    val sessionId: String,
    val parentSessionId: String? = null,
    val subagentMode: String? = null,
) {
    init {
        require(sessionId.isNotBlank())
        require(parentSessionId == null && subagentMode == null ||
            !parentSessionId.isNullOrBlank() && parentSessionId != sessionId &&
            (subagentMode == "one-shot" || subagentMode == "continuable" || subagentMode == "unknown"))
    }
    val isSubagent: Boolean get() = parentSessionId != null
    val canControl: Boolean get() = !isSubagent || subagentMode == "continuable"
    fun arguments(): JsonObject = jsonOf("sessionId" to sessionId,
        "parentSessionId" to parentSessionId, "subagentMode" to subagentMode)
    fun requireControl() {
        if (!canControl) throw PocketError("subagent/read-only", "此子智能体仅支持查看历史")
    }
}
