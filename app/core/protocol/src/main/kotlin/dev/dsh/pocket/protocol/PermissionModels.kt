package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Host presentation is authoritative, including names outside the built-in presets. */
data class PermissionOption(val value: String, val name: String, val description: String? = null)

/** Live options and persistent-default eligibility are separate Host capabilities. */
data class PermissionCatalog(
    val options: List<PermissionOption>,
    val defaultOptions: List<PermissionOption>,
    val defaultPreset: String,
) {
    fun find(value: String): PermissionOption? = options.firstOrNull { it.value == value }
    fun allowsDefault(value: String): Boolean = defaultOptions.any { it.value == value }

    companion object {
        /** Reject incomplete responses rather than silently manufacturing a supported roster. */
        fun parse(value: JsonElement?): PermissionCatalog {
            val obj = value as? JsonObject ?: malformed("catalog must be an object")
            val options = optionList(obj["options"], "options")
            val defaults = optionList(obj["defaultOptions"], "defaultOptions")
            val defaultPreset = requiredString(obj["defaultPreset"], "defaultPreset")
            if (options.any { it.value == "custom" }) malformed("custom is not selectable")
            if (defaults.any { it.value == "auto" || it.value == "custom" }) {
                malformed("current-session permissions cannot be defaults")
            }
            val values = options.mapTo(mutableSetOf()) { it.value }
            if (defaults.any { it.value !in values }) malformed("defaultOptions must be selectable")
            if (defaults.none { it.value == defaultPreset }) malformed("defaultPreset must be eligible")
            return PermissionCatalog(options, defaults, defaultPreset)
        }
    }
}

/** Typed selection carried by pocket's existing `current` response envelope. */
data class PermissionSelection(val currentValue: String) {
    companion object {
        fun parse(value: JsonElement?): PermissionSelection {
            val obj = value as? JsonObject ?: malformed("selection must be an object")
            return PermissionSelection(requiredString(obj["current"], "current"))
        }
    }
}

private fun optionList(value: JsonElement?, field: String): List<PermissionOption> {
    val array = value as? JsonArray ?: malformed("$field must be an array")
    val seen = mutableSetOf<String>()
    return array.mapIndexed { index, element ->
        val obj = element as? JsonObject ?: malformed("$field[$index] must be an object")
        val id = requiredString(obj["value"], "$field[$index].value")
        if (!seen.add(id)) malformed("$field contains duplicate value $id")
        val name = stringValue(obj["name"], "$field[$index].name")
        val description = if ("description" in obj) {
            stringValue(obj["description"], "$field[$index].description")
        } else null
        PermissionOption(id, name, description)
    }
}

private fun stringValue(value: JsonElement?, field: String): String {
    val primitive = value as? JsonPrimitive ?: malformed("$field must be a string")
    if (!primitive.isString) malformed("$field must be a string")
    return primitive.content
}

private fun requiredString(value: JsonElement?, field: String): String =
    stringValue(value, field).also { if (it.isEmpty()) malformed("$field must not be empty") }

private fun malformed(message: String): Nothing = throw PocketError("invalid-response", "permissions: $message")
