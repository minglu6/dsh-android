package dev.dsh.pocket.protocol

import kotlinx.serialization.json.*

val PocketJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull ?: long()?.toInt()
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) when (v) {
        null -> {}
        is JsonElement -> put(k, v)
        is String -> put(k, v)
        is Number -> put(k, v)
        is Boolean -> put(k, v)
        is List<*> -> put(k, JsonArray(v.map { it as? JsonElement ?: JsonPrimitive(it.toString()) }))
        else -> put(k, v.toString())
    }
}
