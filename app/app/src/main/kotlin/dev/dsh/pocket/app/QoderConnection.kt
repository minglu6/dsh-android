package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.PocketError
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.jsonOf
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal data class QoderConnectionFields(val vpcInstance: String, val baseURL: String, val openApiUrl: String)
internal data class QoderConnectionDraft(
    val fields: QoderConnectionFields,
    val original: QoderConnectionFields,
    val ref: String,
    val path: List<String>,
    val revision: Long,
    val pat: String = "",
)

/** Same profile-over-root resolution as the installed Qoder Models footer. Never reads a PAT. */
internal fun qoderConnectionDraft(config: JsonElement?, revision: Long): QoderConnectionDraft {
    val profile = config["providers"]["qoder-cn"] as? JsonObject
    fun value(field: String) = profile[field].str() ?: config[field].str().orEmpty()
    val fields = QoderConnectionFields(value("vpcInstance"), value("baseURL"), value("openApiUrl"))
    return QoderConnectionDraft(fields, fields,
        profile["apiKeyEnv"].str() ?: config["apiKeyEnv"].str() ?: "QODERCN_PERSONAL_ACCESS_TOKEN",
        if (profile == null) emptyList() else listOf("providers", "qoder-cn"), revision)
}

internal fun qoderConnectionOperations(draft: QoderConnectionDraft): JsonArray {
    val fields = listOf(
        Triple("vpcInstance", draft.fields.vpcInstance, draft.original.vpcInstance),
        Triple("baseURL", draft.fields.baseURL, draft.original.baseURL),
        Triple("openApiUrl", draft.fields.openApiUrl, draft.original.openApiUrl),
    )
    return JsonArray(fields.mapNotNull { (field, value, original) ->
        value.trim().takeIf { it != original }?.let { jsonOf("op" to "set", "path" to draft.path + field, "value" to it) }
    })
}

internal data class QoderConnectionSave(val draft: QoderConnectionDraft, val error: QoderText? = null, val settingsSaved: Boolean = false)

/** Fence even PAT-only writes; a partial save adopts the committed revision before retry. */
internal suspend fun saveQoderConnection(
    draft: QoderConnectionDraft,
    writable: Boolean,
    describe: suspend (String) -> JsonElement,
    mutate: suspend (JsonArray, Long) -> JsonElement,
    store: suspend (String, String) -> JsonElement,
): QoderConnectionSave {
    var retained = draft
    var settingsSaved = false
    fun failure(error: QoderText) = QoderConnectionSave(retained, error, settingsSaved)
    if (!writable) return failure(QoderText.ReadOnly)
    val pat = draft.pat.trim()
    if (pat.isNotEmpty()) {
        try {
            val info = describe(draft.ref)
            if (info["writable"].bool() == null) return failure(QoderText.LoadFailed)
            if (info["writable"].bool() != true) return failure(QoderText.CredentialReadOnly)
        } catch (t: Exception) {
            if (t is CancellationException) throw t
            return failure(QoderText.LoadFailed)
        }
    }
    try {
        val ops = qoderConnectionOperations(draft)
        val written = mutate(ops, draft.revision)
        if (written["ok"].bool() == false) {
            return failure(if (written["error"]["code"].str() == "settings/conflict") QoderText.Conflict else QoderText.SettingsFailed)
        }
        val revision = (written["revision"] as? JsonPrimitive)?.longOrNull
            ?: return failure(QoderText.SettingsFailed)
        val config = written["value"] as? JsonObject ?: return failure(QoderText.SettingsFailed)
        settingsSaved = ops.isNotEmpty()
        retained = qoderConnectionDraft(config, revision).copy(pat = draft.pat)
    } catch (t: Exception) {
        if (t is CancellationException) throw t
        return failure(if (t is PocketError && t.code == "settings/conflict") QoderText.Conflict else QoderText.SettingsFailed)
    }
    if (pat.isNotEmpty()) {
        try {
            val info = store(retained.ref, pat)
            if (info["configured"].bool() != true || info["ref"].str() != retained.ref) return failure(QoderText.CredentialFailed)
        } catch (t: Exception) {
            if (t is CancellationException) throw t
            return failure(QoderText.CredentialFailed)
        }
    }
    return QoderConnectionSave(retained.copy(pat = ""), settingsSaved = settingsSaved)
}
