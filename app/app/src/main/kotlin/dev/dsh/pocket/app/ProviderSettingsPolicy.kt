package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.bool
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.serialization.json.*

internal data class ProviderAddOptions(
    val catalog: List<JsonElement>,
    val customNamespace: JsonElement?,
    val catalogOffered: Boolean,
) {
    val offered: Boolean get() = catalogOffered || customNamespace != null
    fun enabled(protocols: List<String>): Boolean = catalog.isNotEmpty() || (customNamespace != null && protocols.isNotEmpty())
}

/** Catalog adoption does not depend on the adapter that owns custom API routes. */
internal fun providerAddOptions(rows: List<JsonElement>, namespaces: List<JsonElement>): ProviderAddOptions {
    val registered = namespaces.mapNotNull { it["ns"].str() }.toSet()
    val configurable = rows.filter { it["settingsNs"].str() in registered }
    return ProviderAddOptions(
        catalog = configurable.filter { it["configured"].bool() == false },
        customNamespace = namespaces.firstOrNull { it["ns"].str() == "llm-pi-ai" },
        catalogOffered = configurable.isNotEmpty(),
    )
}

internal fun providerTakenRoutes(rows: List<JsonElement>, namespace: JsonElement?): Set<String> {
    val taken = rows.mapNotNull { it["provider"].str() }.toMutableSet()
    for (layer in listOf("user", "value")) {
        (namespace[layer]["providers"] as? JsonObject)?.forEach { (route, profile) ->
            if (profile != JsonNull) taken += route
        }
    }
    return taken
}

internal fun providerRouteFailure(route: String, taken: Set<String>): String? = when {
    route.isEmpty() -> st("请填写 Provider ID", "Enter a Provider ID")
    !Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$").matches(route) ->
        st("Provider ID 以字母开头，仅含小写字母、数字与连字符", "Use a lowercase Provider ID beginning with a letter")
    route in taken -> st("该 Provider ID 已被占用", "This Provider ID is already in use")
    else -> null
}

internal fun providerEndpointValid(value: String): Boolean {
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull()
    return uri?.scheme in listOf("http", "https") && !uri?.host.isNullOrBlank()
}
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
    return ProviderCreationBaseline(namespace, revision, providerTakenRoutes(listed["rows"].arr().orEmpty(), namespace))
}
