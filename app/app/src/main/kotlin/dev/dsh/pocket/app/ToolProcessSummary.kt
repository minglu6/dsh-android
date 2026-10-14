package dev.dsh.pocket.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal enum class ToolProcessIcon { Browse, Search, Command, Write, Edit, Code, Generic, Users, Checklist, Clock, Goal, Plugin, Agent, Branch }
internal data class ToolProcessSummary(val title: String, val summary: String?, val icon: ToolProcessIcon)

/** Official ui-tool title registry; unknown tools keep their wire name in the generic summary. */
private val toolTitles = mapOf(
    "bash" to ("运行命令" to "Bash"), "pwsh" to ("运行命令" to "PowerShell"),
    "read" to ("读取" to "Read"), "read_image" to ("读取图片" to "Read image"),
    "web_fetch" to ("读取" to "Read"), "web_search" to ("搜索" to "Search"),
    "grep" to ("搜索" to "Search"), "glob" to ("搜索" to "Search"),
    "write" to ("写入" to "Write"), "edit" to ("编辑" to "Edit"), "run_code" to ("代码" to "Code"),
    "cordis_package_inspect" to ("查询 Cordis 环境" to "Query Cordis environment"),
    "cordis_runtime_inspect" to ("查询 Cordis 环境" to "Query Cordis environment"),
    "cordis_run" to ("运行 Cordis 插件" to "Run Cordis Plugin"),
    "cordis_stop" to ("停止 Cordis 插件" to "Stop Cordis Plugin"),
    "cordis_undefine" to ("移除 Cordis 插件" to "Remove Cordis Plugin"),
    "todo_write" to ("更新任务清单" to "Update todo list"), "ask_user_question" to ("询问用户" to "Ask user"),
    "create_goal" to ("创建目标" to "Create goal"), "get_goal" to ("查看目标" to "Get goal"),
    "update_goal" to ("更新目标" to "Update goal"),
    "schedule_create" to ("创建定时任务" to "Create schedule"), "schedule_list" to ("查看定时任务" to "List schedules"),
    "schedule_delete" to ("删除定时任务" to "Delete schedule"), "schedule_update" to ("修改定时任务" to "Update schedule"),
    "cordis_inspect_list" to ("检查提供方" to "Inspect providers"), "cordis_inspect_query" to ("查询运行时" to "Query runtime"),
    "cordis_inspect_self" to ("检查动态插件" to "Inspect dynamic plugins"),
    "workflow" to ("运行工作流" to "Run workflow"), "ralph" to ("运行循环工作流" to "Run loop workflow"),
    "session_event_read" to ("读取事件" to "Read event"), "session_event_search" to ("搜索事件" to "Search events"),
    "session_event_trace" to ("追踪事件" to "Trace event"), "session_search" to ("搜索会话" to "Search sessions"),
    "session_trace" to ("追踪会话" to "Trace session"), "list_subagent_models" to ("查看可用模型" to "List available models"),
    "subagent" to ("创建子智能体" to "Create subagent"), "list_agents" to ("查看子智能体" to "List subagents"),
    "send_message" to ("发送消息" to "Send message"), "interrupt_agent" to ("中断智能体" to "Interrupt agent"),
    "job_list" to ("查看后台任务" to "List background jobs"), "job_output" to ("读取任务输出" to "Read job output"),
    "job_kill" to ("取消后台任务" to "Cancel background job"), "terminal_open" to ("创建终端" to "Open terminal"),
    "terminal_read" to ("读取终端" to "Read terminal"), "terminal_list" to ("查看终端" to "List terminals"),
    "terminal_signal" to ("发送终端信号" to "Signal terminal"), "terminal_close" to ("关闭终端" to "Close terminal"),
    "lsp" to ("查询代码符号" to "Query code symbols"), "spawn_teammate" to ("创建队友" to "Spawn teammate"),
    "team_task_create" to ("创建团队任务" to "Create team task"), "team_task_get" to ("读取团队任务" to "Read team task"),
    "team_task_update" to ("更新团队任务" to "Update team task"), "team_task_list" to ("查看团队任务" to "List team tasks"),
    "wait_agent" to ("等待子智能体" to "Wait for subagent"),
)
private val agentLine = Regex("^(\\S+) \\[([^\\]]+)\\](?: parent=(\\S+) depth=(\\d+))?(?: — (.*))?$")
private val jobLine = Regex("^(\\S+) \\[([^\\]]+)\\] (\\S+) — (.*)$")
private val jobStatusSuffix = Regex("\\n\\[status: ([^,\\]\\n]+)(?:, [^\\]\\n]+)?\\]$")
private val terminalLine = Regex("^(\\S+)(?: \\((.*?)\\))? \\[([^\\]]+)\\] (running|exited code=(\\S+) signal=(\\S+))(?: pid=(\\d+))?$")
private val teammateTools = setOf("spawn_teammate", "list_agents", "send_message", "interrupt_agent", "wait_agent")
private val searchTools = setOf("grep", "glob", "web_search")
private val browseTools = setOf("read", "read_image", "web_fetch", "cordis_package_inspect", "cordis_runtime_inspect")
private val inspectTools = setOf("cordis_inspect_list", "cordis_inspect_query", "cordis_inspect_self")
private val commandKeys = listOf("description", "command")
private val pathKeys = listOf("path", "file_path", "url")
private val searchKeys = listOf("query", "pattern", "url")
private val codeKeys = listOf("description")
private val targetKeys = listOf("target", "agent_id", "sessionId", "job_id")

private fun parseToolJson(raw: String?): JsonElement? = raw?.let { runCatching { PocketJson.parseToJsonElement(it) }.getOrNull() }
private fun firstToolLine(text: String) = text.substringBefore('\n')
// Tool summaries follow the recorded JSON schema, not string coercion of numbers/booleans.
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

internal fun toolProcessSummary(tool: Item.Tool, en: Boolean = false): ToolProcessSummary {
    fun tr(zh: String, english: String) = if (en) english else zh
    val args = parseToolJson(tool.arguments) as? JsonObject
    val title = toolTitles[tool.name]?.let { if (en) it.second else it.first } ?: tr("工具调用", "Tool call")
    val icon = when {
        tool.name in teammateTools -> ToolProcessIcon.Users
        tool.name.startsWith("schedule_") -> ToolProcessIcon.Clock
        tool.name.endsWith("_goal") -> ToolProcessIcon.Goal
        tool.name.startsWith("job_") || tool.name.startsWith("team_task_") || tool.name == "todo_write" -> ToolProcessIcon.Checklist
        tool.name.startsWith("terminal_") -> ToolProcessIcon.Code
        tool.name.startsWith("session_") || tool.name == "lsp" -> ToolProcessIcon.Search
        tool.name == "workflow" || tool.name == "ralph" -> ToolProcessIcon.Branch
        tool.name in inspectTools -> ToolProcessIcon.Plugin
        tool.name == "subagent" || tool.name == "list_subagent_models" -> ToolProcessIcon.Agent
        tool.name in browseTools -> ToolProcessIcon.Browse
        tool.name in searchTools -> ToolProcessIcon.Search
        tool.name == "bash" || tool.name == "pwsh" -> ToolProcessIcon.Command
        tool.name == "write" -> ToolProcessIcon.Write
        tool.name == "edit" -> ToolProcessIcon.Edit
        tool.name == "run_code" -> ToolProcessIcon.Code
        else -> ToolProcessIcon.Generic
    }
    if (tool.status == ToolStatus.Preparing) {
        return ToolProcessSummary(preparingToolTitle(tool.name, en), null, icon)
    }
    if (tool.status == ToolStatus.Error) {
        return ToolProcessSummary(title, firstToolLine(tool.error ?: tool.result ?: tr("调用失败", "Tool failed")), icon)
    }
    val result = tool.result
    val recordedSummary = if (tool.status == ToolStatus.Ok && result != null && args != null) {
        controlSummary(tool.name, args, result, parseToolJson(result), en)
    } else null
    if (recordedSummary != null) return ToolProcessSummary(title, recordedSummary.takeIf(String::isNotEmpty), icon)
    val keys = when (tool.name) {
        "bash", "pwsh" -> commandKeys
        "read", "read_image", "web_fetch", "write", "edit", "cordis_package_inspect", "cordis_runtime_inspect" -> pathKeys
        "grep", "glob", "web_search" -> searchKeys
        "run_code" -> codeKeys
        else -> emptyList()
    }
    val queries = if (tool.name in searchTools) args?.get("queries").arr()?.mapNotNull { it.str()?.takeIf(String::isNotEmpty)?.let(::firstToolLine) } else null
    val argumentSummary = when {
        args == null -> firstToolLine(tool.arguments)
        !queries.isNullOrEmpty() -> queries.joinToString(", ")
        else -> (keys.firstNotNullOfOrNull { args[it].str()?.takeIf(String::isNotEmpty) }
            ?: args.values.firstNotNullOfOrNull { it.str()?.takeIf(String::isNotEmpty) })?.let(::firstToolLine).orEmpty()
    }
    val summary = if (tool.name !in toolTitles) tool.name + if (argumentSummary.isEmpty()) "" else " · $argumentSummary" else argumentSummary
    return ToolProcessSummary(title, summary.takeIf(String::isNotEmpty), icon)
}

/** Derive counts/receipts only from recognized successful recorded outputs, as ui-tool does. */
private fun controlSummary(name: String, args: JsonObject, text: String, json: JsonElement?, en: Boolean): String? {
    fun tr(zh: String, english: String) = if (en) english else zh
    fun count(n: Int, zh: String, english: String) = if (en) "$n $english" else "$n 个$zh"
    fun parsedLines(pattern: Regex): Int? {
        if (text.isEmpty()) return null
        val lines = text.split('\n')
        return lines.size.takeIf { lines.all(pattern::matches) }
    }
    val target = targetKeys.firstNotNullOfOrNull { args[it].str()?.takeIf(String::isNotEmpty) }.orEmpty()
    return when (name) {
        "list_agents" -> when {
            json is JsonArray -> count(json.size, "智能体", "agents")
            text == "(no subagents)" -> count(0, "智能体", "agents")
            else -> parsedLines(agentLine)?.let { count(it, "智能体", "agents") }
        }
        "job_list" -> if (text == "(no background jobs)") count(0, "后台任务", "background jobs") else parsedLines(jobLine)?.let { count(it, "后台任务", "background jobs") }
        "terminal_list" -> if (text == "(no terminal sessions)") count(0, "终端", "terminals") else parsedLines(terminalLine)?.let { count(it, "终端", "terminals") }
        "wait_agent" -> if (json is JsonObject && json["timedOut"].bool() != null) {
            if (json["noProgress"] is JsonObject && json["noProgress"]["message"].str() != null) tr("没有正在运行的子智能体", "No active subagents")
            else tr("子智能体状态", "Subagent activity") + " · " + if (json["timedOut"].bool() == true) tr("等待超时", "Wait timed out") else tr("检测到变化", "Change detected")
        } else null
        "team_task_list" -> (json?.get("tasks") as? JsonArray)?.let { count(it.size, "团队任务", "team tasks") }
        "team_task_create", "team_task_get", "team_task_update" -> json?.get("subject").str()
        "spawn_teammate" -> if (json?.get("member") is JsonObject) args["name"].str() else null
        "send_message" -> when {
            json?.get("status").str() == "queued" -> "$target · ${tr("已入队", "Queued")}"
            json?.get("status").str() == "accepted" || text == "message delivered to agent $target" -> "$target · ${tr("已送达", "Delivered")}"
            else -> null
        }
        "interrupt_agent" -> if (json?.get("previousStatus").str() != null || text == "interrupt requested for agent $target") "$target · ${tr("已请求中断", "Interrupt requested")}" else null
        "job_output" -> jobStatusSuffix.find(text)?.groupValues?.get(1)?.let { "$target · $it" }
        "job_kill" -> when {
            text == "requested cancellation of job $target" -> "$target · ${tr("已请求取消", "Cancellation requested")}"
            text.startsWith("job $target had already finished ") -> "$target · ${tr("已结束", "Already finished")}"
            else -> null
        }
        "get_goal", "create_goal", "update_goal" -> if (json is JsonObject && json.containsKey("goal")) {
            json["goal"]["objective"].str() ?: if (json["goal"] == kotlinx.serialization.json.JsonNull) tr("当前没有目标", "No current goal") else null
        } else null
        "subagent" -> if (text.startsWith("started background subagent job ") || text.startsWith("started subagent ")) args["prompt"].str()?.let { "$it · ${tr("已启动", "Started")}" } else tr("智能体回复", "Agent response")
        "list_subagent_models" -> args["model"].str() ?: args["provider"].str() ?: tr("可用模型", "Available models")
        else -> null
    }
}

@Composable
internal fun toolProcessIcon(icon: ToolProcessIcon): ImageVector = when (icon) {
    ToolProcessIcon.Browse -> D.browse
    ToolProcessIcon.Search -> D.search
    ToolProcessIcon.Command -> D.api
    ToolProcessIcon.Write -> D.edit
    ToolProcessIcon.Edit -> D.edit
    ToolProcessIcon.Code -> D.code
    ToolProcessIcon.Generic -> D.sparkle
    ToolProcessIcon.Users -> D.users
    ToolProcessIcon.Checklist -> D.checklist
    ToolProcessIcon.Clock -> D.clock
    ToolProcessIcon.Goal -> D.goal
    ToolProcessIcon.Plugin -> D.cordis
    ToolProcessIcon.Agent -> D.agentPreset
    ToolProcessIcon.Branch -> D.branch
}
