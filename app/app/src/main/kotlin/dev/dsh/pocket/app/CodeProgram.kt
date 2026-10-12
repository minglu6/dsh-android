package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.obj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Desktop `code-program.ts` PTC entry point name. */
const val PTC_TOOL_NAME = "run_code"

/**
 * Desktop `CodeProgram`: validated source + language hint from recorded
 * `run_code` arguments and the schema visible at call time.
 */
data class CodeProgram(
    val rawInput: String,
    val source: String,
    val description: String,
    val language: String?,
)

/**
 * Desktop `codeProgram(cell)` — only for tool/subtool rows named `run_code`
 * with a string `code` argument. Language is read from schema property
 * description (TypeScript vs Python), never guessed from source.
 */
fun codeProgram(toolName: String?, inputDetail: String?, schemaDetail: String?): CodeProgram? {
    if (toolName != PTC_TOOL_NAME || inputDetail.isNullOrBlank()) return null
    val args = runCatching { PocketJson.parseToJsonElement(inputDetail).obj() }.getOrNull() ?: return null
    val codeEl = args["code"] as? JsonPrimitive ?: return null
    val code = codeEl.contentOrNull ?: return null
    val description = when (val d = args["description"]) {
        is JsonPrimitive -> d.contentOrNull?.trim().orEmpty().ifEmpty { firstNonEmptyLine(code) }
        null -> firstNonEmptyLine(code)
        else -> return null
    }
    return CodeProgram(
        rawInput = inputDetail,
        source = code,
        description = description,
        language = recordedLanguage(schemaDetail),
    )
}

private fun firstNonEmptyLine(code: String): String =
    code.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

private fun recordedLanguage(schemaRaw: String?): String? {
    val schema = schemaRaw?.let { runCatching { PocketJson.parseToJsonElement(it).obj() }.getOrNull() } ?: return null
    val properties = (schema["parameters"] as? JsonObject)?.get("properties") as? JsonObject ?: return null
    val description = ((properties["code"] as? JsonObject)?.get("description") as? JsonPrimitive)?.contentOrNull ?: return null
    val typescript = Regex("\\bTypeScript\\b", RegexOption.IGNORE_CASE).containsMatchIn(description)
    val python = Regex("\\bPython\\b", RegexOption.IGNORE_CASE).containsMatchIn(description)
    if (typescript == python) return null
    return if (typescript) "typescript" else "python"
}
