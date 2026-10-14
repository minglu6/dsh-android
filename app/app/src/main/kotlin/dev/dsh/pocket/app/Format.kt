package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.obj
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun presetLabel(id: String?, roster: List<JsonObject>): String? {
    if (id.isNullOrBlank()) return null
    return roster.firstOrNull { it["id"].str() == id }?.let { presetName(it) } ?: id
}

/** Official registry/display fold: an explicit name owns its copy, even for a shipped id. */
internal fun presetName(row: JsonElement?): String {
    row["name"].str()?.let { return it }
    return when (val id = row["id"].str()) {
        "standard" -> st("标准模式", "Standard mode")
        "ptc" -> st("PTC 模式", "PTC mode")
        "minimal" -> st("极简模式", "Minimal mode")
        "cordis" -> st("创造模式", "Creator mode")
        else -> id.orEmpty()
    }
}

internal fun presetDescription(row: JsonElement?): String? {
    if (row["name"].str() != null) return row["description"].str()
    return when (row["id"].str()) {
        "standard" -> st("处理代码、文件和资料，适合大多数任务。Agent 会按需使用检索、编辑和终端等工具。",
            "Work with code, files, and information. Suitable for most tasks, with search, editing, terminal commands, and other tools available as needed.")
        "ptc" -> st("包含标准模式的所有能力，更适合批量调用工具，并对结果进行筛选、整理、去重、统计或汇总的任务。",
            "Includes all Standard mode capabilities. Better suited to tasks that call tools in batches and then filter, organize, deduplicate, count, or summarize the results.")
        "minimal" -> st("Agent 仅使用终端工具完成任务，适合测试和对比其基础表现。",
            "The agent works using only a terminal tool. Useful for testing and comparing its basic performance.")
        "cordis" -> st("用对话定制 DSH：让 Agent 编写插件，添加新功能或界面；也能组合工具和提示词，创建自己的模式。",
            "Customize DSH through conversation. Let the agent write plugins that add features or UI, or combine tools and prompts to create your own mode.")
        else -> row["description"].str()
    }
}

private val ARG_KEYS = listOf("command", "cmd", "path", "file_path", "filePath", "file", "pattern", "query", "url", "glob", "name")

/** The one argument worth showing on a one-line tool card. */
fun primaryArg(tool: String, arguments: String): String? {
    val o = runCatching { PocketJson.parseToJsonElement(arguments).obj() }.getOrNull() ?: return arguments.take(80).ifBlank { null }
    val k = ARG_KEYS.firstOrNull { o[it] is JsonPrimitive } ?: o.keys.firstOrNull { o[it] is JsonPrimitive } ?: return null
    val v = (o[k] as JsonPrimitive).content
    return if (k == "pattern" || k == "query") "\"$v\"" else v
}

fun prettyJson(s: String): String = runCatching {
    kotlinx.serialization.json.Json { prettyPrint = true }.let { it.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), PocketJson.parseToJsonElement(s)) }
}.getOrDefault(s)

fun seconds(ms: Long) = "${(ms / 1000).coerceAtLeast(1)}"

/** Compact token count for trajectory / stats columns (`17730` → `17.7k`). */
fun kfmt(n: Long) = if (n >= 1000) "${"%.1f".format(n / 1000.0)}k" else "$n"

/**
 * Elapsed wall time for the trajectory time column (desktop duration style with `s` suffix).
 * Under 10s: two decimals; otherwise one (`2.20s`, `14.6s`).
 */
fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000.0
    return if (ms < 10_000) "${"%.2f".format(seconds)}s" else "${"%.1f".format(seconds)}s"
}

/**
 * Desktop `formatMessageClock` / `clock.md` / `clock.ymd`:
 * same calendar day → `HH:mm`; same year → `{m}月{d}日 HH:mm`; else `{y}年{m}月{d}日`.
 */
fun formatMessageClock(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
    val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
    fun two(n: Int) = n.toString().padStart(2, '0')
    val hm = "${two(cal.get(java.util.Calendar.HOUR_OF_DAY))}:${two(cal.get(java.util.Calendar.MINUTE))}"
    val sameDay = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
        && cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return hm
    val m = cal.get(java.util.Calendar.MONTH) + 1
    val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val sameYear = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
    val date = if (sameYear) "${m}月${d}日"
    else "${cal.get(java.util.Calendar.YEAR)}年${m}月${d}日"
    return "$date $hm"
}

/** Desktop todo status labels (`todo.status.*`). */
fun todoStatusLabel(status: String, language: AppLanguage = AppLanguage.Zh): String = when (status) {
    "completed" -> if (language == AppLanguage.En) "Completed" else "已完成"
    "in_progress" -> if (language == AppLanguage.En) "In progress" else "进行中"
    else -> if (language == AppLanguage.En) "Pending" else "待处理"
}

/** Desktop TodoPanel order and zero-count suppression. */
internal fun todoProgressLabel(todos: List<dev.dsh.pocket.protocol.TodoItem>, language: AppLanguage): String {
    var done = 0
    var active = 0
    var pending = 0
    for (item in todos) when (item.status) {
        "completed" -> done++
        "in_progress" -> active++
        else -> pending++
    }
    val english = language == AppLanguage.En
    return buildString {
        if (done > 0) append(done).append(' ').append(if (english) "completed" else "已完成")
        if (active > 0) {
            if (isNotEmpty()) append("\u2002·\u2002")
            append(active).append(' ').append(if (english) "in progress" else "进行中")
        }
        if (pending > 0) {
            if (isNotEmpty()) append("\u2002·\u2002")
            append(pending).append(' ').append(if (english) "pending" else "待处理")
        }
    }
}
