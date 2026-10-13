package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.obj
import kotlinx.serialization.json.JsonPrimitive

val PRESET_LABEL = linkedMapOf(
    "standard" to ("标准模式" to "均衡的通用智能体，适合大多数任务"),
    "ptc" to ("PTC 模式" to "以程序化工具调用编排复杂流程"),
    "minimal" to ("极简模式" to "最少工具与提示，响应更快"),
    "cordis" to ("创造模式" to "面向 Cordis 插件开发的创作环境"),
)
fun presetLabel(id: String?) = PRESET_LABEL[id ?: "standard"]?.first ?: id ?: "标准模式"

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
fun todoStatusLabel(status: String): String = when (status) {
    "completed" -> "已完成"
    "in_progress" -> "进行中"
    else -> "待处理"
}
