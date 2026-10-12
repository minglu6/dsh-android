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
val PERMISSION_LABEL = linkedMapOf(
    "read-only" to ("仅可查看" to "只能读取文件，不会修改任何内容"),
    "workspace-write" to ("工作区内修改" to "可在工作区内读写文件并执行命令"),
    "danger-full-access" to ("完全权限" to "不受限制地访问文件系统与网络"),
)
fun presetLabel(id: String?) = PRESET_LABEL[id ?: "standard"]?.first ?: id ?: "标准模式"
fun permissionLabel(id: String?) = PERMISSION_LABEL[id ?: "workspace-write"]?.first ?: id ?: "工作区内修改"

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

fun footerText(turns: Int, steps: Int, hit: Int?) = buildString {
    append("$turns 轮 $steps 步")
    if (hit != null) append(" · 缓存命中 $hit%")
}
