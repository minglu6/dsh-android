package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.serialization.json.*

internal data class ProviderRemovalProgress(val configurationRemoved: Boolean = false, val credentialRemoved: Boolean = false)

/** A rejected configuration CAS must never remove a still-live provider's key. */
internal suspend fun removeProviderSafely(
    progress: ProviderRemovalProgress,
    onProgress: (ProviderRemovalProgress) -> Unit,
    removeConfiguration: suspend () -> Unit,
    confirmRemoval: suspend () -> Unit,
    clearManagedCredential: suspend () -> Unit,
) {
    var current = progress
    if (!current.configurationRemoved) {
        removeConfiguration()
        current = current.copy(configurationRemoved = true)
        onProgress(current)
    }
    confirmRemoval()
    if (!current.credentialRemoved) {
        clearManagedCredential()
        current = current.copy(credentialRemoved = true)
        onProgress(current)
    }
}

internal data class ProviderCreationBaseline(val namespace: JsonElement, val revision: Long, val taken: Set<String>)

/** Refresh only conflict metadata; editable provider and secret drafts remain owned by the form. */
internal fun providerCreationBaseline(listed: JsonElement, ns: String): ProviderCreationBaseline {
    val namespace = listed["namespaces"].arr().orEmpty().firstOrNull { it["ns"].str() == ns }
        ?: error("Missing provider namespace: $ns")
    val revision = (namespace["revision"] as? JsonPrimitive)?.longOrNull
        ?.takeIf { it >= 0 } ?: error("Missing provider configuration revision")
    val taken = listed["rows"].arr().orEmpty().mapNotNull { it["provider"].str() }.toMutableSet()
    for (layer in listOf("user", "value")) {
        (namespace[layer]["providers"] as? JsonObject)?.forEach { (route, profile) ->
            if (profile != JsonNull) taken += route
        }
    }
    return ProviderCreationBaseline(namespace, revision, taken)
}
