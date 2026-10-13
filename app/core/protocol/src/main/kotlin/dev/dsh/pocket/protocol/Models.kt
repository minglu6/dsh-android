package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class PocketError(val code: String, message: String, val details: JsonElement? = null) : Exception("$code: $message")

data class SessionSummary(
    val sessionId: String,
    val title: String?,
    val cwd: String?,
    val running: Boolean,
    val blank: Boolean,
    val updatedAt: Long,
    val parentSessionId: String?,
    /** Optional flags when the Host includes them on list/search rows. */
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
    val origin: String? = null,
    val searchSnippet: String? = null,
) {
    companion object {
        fun parse(e: JsonElement?): SessionSummary? {
            val id = e["sessionId"].str() ?: return null
            return SessionSummary(
                sessionId = id,
                title = e["projections"]["values"]["title"].str() ?: e["title"].str(),
                cwd = e["cwd"].str(),
                running = e["running"].bool() ?: false,
                blank = e["blank"].bool() ?: false,
                updatedAt = e["updatedAt"].long() ?: 0,
                parentSessionId = e["parentSessionId"].str(),
                archived = e["archived"].bool(),
                pinned = e["pinned"].bool(),
                origin = e["origin"].str(),
                searchSnippet = e["snippet"].str(),
            )
        }
    }
}

data class WorkspaceView(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
    val createdAt: String?,
    val updatedAt: String?,
) {
    companion object {
        fun parse(e: JsonElement?): WorkspaceView? {
            val id = e["workspaceId"].str() ?: return null
            val path = e["path"].str() ?: return null
            return WorkspaceView(id, path, e["title"].str() ?: path,
                e["sessionIds"].arr().orEmpty().mapNotNull { it.str() },
                e["createdAt"].str(), e["updatedAt"].str())
        }
    }
}

data class ReasoningLevel(val id: String, val name: String, val description: String?)
data class ModelInfo(val provider: String, val id: String, val name: String, val description: String?, val efforts: List<ReasoningLevel>, val defaultEffort: String?, val hasReasoning: Boolean = efforts.isNotEmpty() || defaultEffort != null)
data class ModelGroup(val id: String, val name: String, val models: List<ModelInfo>)
data class ModelCatalogFailure(val id: String, val name: String, val message: String)
data class ModelCatalog(
    val groups: List<ModelGroup>,
    val defaultProvider: String?,
    val defaultModel: String?,
    val failures: List<ModelCatalogFailure> = emptyList(),
    val defaultEffort: String? = null,
) {
    fun find(provider: String?, model: String?) = groups.flatMap { it.models }.firstOrNull { it.provider == provider && it.id == model }
    companion object {
        fun parse(e: JsonElement?): ModelCatalog = ModelCatalog(
            groups = e["groups"].arr().orEmpty().map { g ->
                val gid = g["id"].str() ?: ""
                ModelGroup(gid, g["name"].str() ?: gid, g["models"].arr().orEmpty().map { m ->
                    ModelInfo(
                        provider = gid, id = m["id"].str() ?: "", name = m["name"].str() ?: m["id"].str() ?: "",
                        description = m["description"].str(),
                        efforts = m["reasoning"]["efforts"].arr().orEmpty().map { r -> ReasoningLevel(r["id"].str() ?: "", r["name"].str() ?: r["id"].str() ?: "", r["description"].str()) },
                        defaultEffort = m["reasoning"]["defaultEffort"].str(),
                        hasReasoning = m["reasoning"] is JsonObject,
                    )
                })
            },
            defaultProvider = e["default"]["provider"].str(),
            defaultModel = e["default"]["model"].str(),
            failures = e["failures"].arr().orEmpty().map { failure ->
                val id = failure["id"].str().orEmpty()
                ModelCatalogFailure(id, failure["name"].str() ?: id, failure["message"].str().orEmpty())
            },
            defaultEffort = e["default"]["reasoningEffort"].str(),
        )
    }
}

data class Approval(val approvalId: String, val sessionId: String?, val toolName: String, val callId: String?, val reason: String?) {
    companion object {
        fun parse(e: JsonObject) = Approval(
            e["approvalId"].str() ?: "", e["sessionId"].str(), e["toolName"].str() ?: "", e["callId"].str(),
            e["displayReason"]["zh"].str() ?: e["reason"].str() ?: e["displayReason"]["en"].str(),
        )
    }
}

/** One image part for `prompt` (base64 payload, no data: URL prefix). */
data class PromptImage(val mediaType: String, val data: String, val name: String? = null)

/** Staged non-image file receipt for `prompt` (`{type:'file', receiptId}`). */
data class PromptFile(val receiptId: String)

/** One question in a `user-question` push / ask_user_question batch. */
data class UserQuestionOption(val label: String, val description: String? = null)

data class UserQuestionItem(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<UserQuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    /** When `kind` is `plan-review`, [approveLabel] is the option that approves. */
    val intentKind: String? = null,
    val approveLabel: String? = null,
) {
    companion object {
        fun parse(e: JsonElement?): UserQuestionItem? {
            val id = e["id"].str() ?: return null
            val q = e["question"].str() ?: return null
            val intent = e["intent"]
            return UserQuestionItem(
                id = id,
                question = q,
                detail = e["detail"].str(),
                header = e["header"].str(),
                options = e["options"].arr().orEmpty().mapNotNull { o ->
                    val label = o["label"].str() ?: return@mapNotNull null
                    UserQuestionOption(label, o["description"].str())
                },
                multiSelect = e["multiSelect"].bool() == true,
                intentKind = intent["kind"].str(),
                approveLabel = intent["approve"].str(),
            )
        }
    }
}

/** Open / continued user-question card from pocket push `user-question`. */
data class UserQuestion(
    val questionId: String,
    val sessionId: String?,
    val callId: String?,
    val questions: List<UserQuestionItem>,
    val state: String? = null,
) {
    companion object {
        fun parse(e: JsonObject): UserQuestion? {
            val questionId = e["questionId"].str() ?: e["callId"].str() ?: return null
            val questions = e["questions"].arr().orEmpty().mapNotNull(UserQuestionItem::parse)
            if (questions.isEmpty()) return null
            return UserQuestion(
                questionId = questionId,
                sessionId = e["sessionId"].str(),
                callId = e["callId"].str() ?: questionId,
                questions = questions,
                state = e["wait"]?.let { "open" } ?: e["state"].str(),
            )
        }
    }
}

/** One message-feedback rating from `feedback.get`. */
data class MessageFeedback(
    val messageId: String,
    val rating: String?,
    val version: String? = null,
    val category: String? = null,
    val note: String? = null,
) {
    companion object {
        fun parse(e: JsonElement?): MessageFeedback? {
            val id = e["messageId"].str() ?: return null
            return MessageFeedback(
                messageId = id,
                rating = e["rating"].str(),
                version = e["version"].str(),
                category = e["category"].str(),
                note = e["note"].str(),
            )
        }
    }
}

/** `@` file mention candidate from `references.files`. */
data class FileReference(val path: String, val kind: String? = null) {
    companion object {
        fun parse(e: JsonElement?): FileReference? {
            val path = e["path"].str() ?: e["mention"].str() ?: return null
            return FileReference(path, e["kind"].str())
        }
    }
}

/** `@` session / subagent mention candidate from `references.sessions`. */
data class SessionReference(val mention: String, val title: String?, val kind: String? = null) {
    companion object {
        fun parse(e: JsonElement?): SessionReference? {
            val mention = e["mention"].str() ?: e["sessionId"].str()?.let { "@$it" } ?: return null
            return SessionReference(mention, e["title"].str(), e["kind"].str())
        }
    }
}

/** Upload receipt from `attachments.upload`. */
data class UploadReceipt(val receiptId: String, val name: String? = null, val attachmentId: String? = null) {
    companion object {
        fun parse(e: JsonElement?): UploadReceipt? {
            val id = e["receiptId"].str() ?: return null
            return UploadReceipt(id, e["file"]["name"].str() ?: e["name"].str(), e["file"]["attachmentId"].str())
        }
    }
}

/**
 * Pairing payload for LAN / Tailscale WebSocket.
 *
 * Happy path: `dshpocket://pair?h=<host:port>&c=<6-digit>`
 * Legacy iroh forms (`n=` / `nodeId#code`) are rejected — re-pair from the desktop UI.
 * Parsing lives in [PairingUriPolicy] (shared contract with the plugin).
 */
data class PairingLink(val host: String, val port: Int, val code: String) {
    companion object {
        fun parse(text: String): PairingLink? = PairingUriPolicy.parse(text)
    }
}

/** Parse `host:port` or `[ipv6]:port` (optional `ws://` / `wss://` prefix). */
fun parseHostPort(s: String): Pair<String, Int>? {
    val t = s.trim().removePrefix("ws://").removePrefix("wss://")
    val match = Regex("^\\[?([^\\[\\]]+?)]?:(\\d{1,5})$").find(t) ?: return null
    val port = match.groupValues[2].toInt().takeIf { it in 1..65535 } ?: return null
    val host = match.groupValues[1].takeIf { it.isNotBlank() } ?: return null
    return host to port
}
