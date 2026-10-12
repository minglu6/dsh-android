package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus

/**
 * Desktop `@deepseek-ai/dsh-client-ui-chat` presentation policy
 * (`POLICIES` + `collapseTiming` in ui-chat client.js).
 */
data class PresentationPolicy(
    val mode: TranscriptView,
    val foldCompletedTurns: Boolean,
    /** collapsed | history | none */
    val stepGrouping: String,
    val liveProcessDetail: Boolean,
    val settledReasoningPreview: Boolean,
    val collapseTiming: CollapseTiming,
)

fun presentationPolicyFor(
    mode: TranscriptView = TranscriptView.Detailed,
    collapseTiming: CollapseTiming = CollapseTiming.Completion,
): PresentationPolicy = when (mode) {
    TranscriptView.Compact -> PresentationPolicy(
        mode, foldCompletedTurns = true, stepGrouping = "collapsed",
        liveProcessDetail = false, settledReasoningPreview = false, collapseTiming,
    )
    TranscriptView.Standard -> PresentationPolicy(
        mode, foldCompletedTurns = true, stepGrouping = "collapsed",
        liveProcessDetail = true, settledReasoningPreview = true, collapseTiming,
    )
    TranscriptView.Detailed -> PresentationPolicy(
        mode, foldCompletedTurns = true, stepGrouping = "history",
        liveProcessDetail = true, settledReasoningPreview = true, collapseTiming,
    )
    TranscriptView.Verbose -> PresentationPolicy(
        mode, foldCompletedTurns = false, stepGrouping = "none",
        liveProcessDetail = false, settledReasoningPreview = true, collapseTiming,
    )
}

/** One chat-flow row after desktop-style turn-process folding. */
sealed interface ChatRow {
    val key: String

    data class User(val item: Item.User) : ChatRow {
        override val key get() = item.key
    }

    data class Answer(val item: Item.Text) : ChatRow {
        override val key get() = item.key
    }

    data class Notice(val item: Item.Notice) : ChatRow {
        override val key get() = item.key
    }

    /** Verbose / live detail: one tool card. */
    data class Tool(val item: Item.Tool) : ChatRow {
        override val key get() = item.key
    }

    /** Verbose / live detail: one reasoning disclosure. */
    data class Reasoning(val item: Item.Reasoning) : ChatRow {
        override val key get() = item.key
    }

    /**
     * Collapsed (or expandable) process group — desktop step/turn process seat.
     * Members stay visible on the 轨迹 tab; chat only shows this summary unless expanded.
     */
    data class Process(
        override val key: String,
        val tools: List<Item.Tool>,
        val reasoning: List<Item.Reasoning>,
        /** Desktop `processTitle` / live activity label. */
        val title: String,
        val closed: Boolean,
        /** Default disclosure: false when foldCompletedTurns hides settled process. */
        val defaultOpen: Boolean,
        val durationMs: Long? = null,
    ) : ChatRow
}

/** Map tool name → desktop `activity()` kind (`process-activity.js`). */
fun toolActivityKind(name: String): String = when {
    name == "read" -> "read"
    name == "read_image" -> "readImage"
    name == "grep" || name == "glob" || name.endsWith("_inspect") -> "search"
    name == "write" -> "write"
    name == "edit" || name == "apply_patch" -> "edit"
    name in setOf("bash", "pwsh", "exec_command", "write_stdin") || name.startsWith("terminal_") -> "commands"
    name == "run_code" -> "code"
    name == "web_search" -> "webSearch"
    name == "web_fetch" -> "webFetch"
    name == "subagent" || name.startsWith("subagent_") -> "subagents"
    name in setOf("todo_write", "create_goal", "update_goal", "get_goal") -> "plan"
    name == "ask_user_question" || name == "request_user_input" -> "questions"
    else -> "tools"
}

private val DONE_LABEL = mapOf(
    "thinking" to "已完成分析",
    "read" to "已读取文件",
    "readImage" to "已读取图片",
    "write" to "已写入文件",
    "search" to "已搜索代码",
    "edit" to "修改了文件",
    "commands" to "执行了命令",
    "code" to "运行了代码",
    "webSearch" to "已搜索网页",
    "webFetch" to "已访问网页",
    "subagents" to "已协调子智能体",
    "plan" to "更新了计划",
    "questions" to "向用户提出了问题",
    "tools" to "已调用工具",
)

private val LIVE_LABEL = mapOf(
    "thinking" to "深度求索中",
    "read" to "正在读取文件",
    "readImage" to "正在读取图片",
    "write" to "正在写入文件",
    "search" to "正在搜索代码",
    "edit" to "正在修改文件",
    "commands" to "正在执行命令",
    "code" to "正在运行代码",
    "webSearch" to "正在搜索网页",
    "webFetch" to "正在访问网页",
    "subagents" to "正在协调子智能体",
    "plan" to "正在更新计划",
    "questions" to "正在提问",
    "tools" to "正在调用工具",
)

/**
 * Desktop `processTitle(summary, t)` — top three activity kinds, no counts.
 * zh: joinTwo = "{first}并{second}", sharedPrefix = "已", comma = "，", more = "{title}等".
 */
fun processTitle(kinds: List<String>): String {
    if (kinds.isEmpty()) return DONE_LABEL.getValue("thinking")
    val labels = kinds.take(3).map { DONE_LABEL[it] ?: DONE_LABEL.getValue("tools") }
    val first = labels[0]
    if (labels.size == 1) return first
    fun continuation(label: String) = label.replaceFirstChar { it.lowercase() }
    if (labels.size == 2) {
        val second = labels[1]
        val prefix = "已"
        val trimmed = if (first.startsWith(prefix) && second.startsWith(prefix)) second.removePrefix(prefix) else second
        return "${first}并${continuation(trimmed)}"
    }
    val title = listOf(first) + labels.drop(1).map(::continuation)
    val joined = title.joinToString("，")
    return if (kinds.size > 3) "${joined}等" else joined
}

fun rankedActivityKinds(tools: List<Item.Tool>): List<String> {
    val order = LinkedHashMap<String, Int>()
    val seen = HashSet<String>()
    for (t in tools) {
        if (!seen.add(t.callId.ifBlank { t.key })) continue
        val kind = toolActivityKind(t.name)
        order[kind] = (order[kind] ?: 0) + 1
    }
    return order.entries.sortedByDescending { it.value }.map { it.key }
}

fun liveProcessTitle(tools: List<Item.Tool>, reasoning: List<Item.Reasoning>): String {
    val liveTool = tools.lastOrNull { it.status == ToolStatus.Running || it.status == ToolStatus.AwaitingApproval }
    if (liveTool != null) return LIVE_LABEL[toolActivityKind(liveTool.name)] ?: LIVE_LABEL.getValue("tools")
    if (reasoning.any { it.live }) return LIVE_LABEL.getValue("thinking")
    return processTitle(rankedActivityKinds(tools))
}

private data class ProcessBuf(
    val tools: MutableList<Item.Tool> = ArrayList(),
    val reasoning: MutableList<Item.Reasoning> = ArrayList(),
) {
    val empty get() = tools.isEmpty() && reasoning.isEmpty()
    fun key() = (tools.firstOrNull()?.key ?: reasoning.firstOrNull()?.key ?: "proc") + "-group"
}

/**
 * Fold flat transcript [Item]s into chat rows using the desktop presentation policy.
 *
 * Compact / Standard / Detailed: settled tool+reasoning process collapses into one
 * disclosure (desktop turn/step process folding). Verbose keeps every tool/reasoning row.
 * Answers always stay visible. Trajectory is unchanged (still reads raw [Item]s).
 */
fun foldChatItems(
    items: List<Item>,
    policy: PresentationPolicy,
    sessionRunning: Boolean,
): List<ChatRow> {
    if (items.isEmpty()) return emptyList()
    val out = ArrayList<ChatRow>()
    // Split into turns on User; track whether a later User exists (collapseTiming).
    data class Turn(val user: Item.User?, val body: List<Item>)
    val turns = ArrayList<Turn>()
    var user: Item.User? = null
    var body = ArrayList<Item>()
    fun flushTurn() {
        if (user != null || body.isNotEmpty()) turns += Turn(user, body)
        user = null
        body = ArrayList()
    }
    for (item in items) {
        when (item) {
            is Item.User -> {
                flushTurn()
                user = item
            }
            is Item.Context, is Item.System -> Unit // chat-hidden (desktop parity)
            else -> body += item
        }
    }
    flushTurn()

    turns.forEachIndexed { turnIndex, turn ->
        turn.user?.let { out += ChatRow.User(it) }
        val isLatest = turnIndex == turns.lastIndex
        val deferCollapse = policy.collapseTiming == CollapseTiming.NextInput && isLatest
        emitTurnBody(out, turn.body, policy, sessionRunning && isLatest, deferCollapse)
    }
    return out
}

private fun emitTurnBody(
    out: MutableList<ChatRow>,
    body: List<Item>,
    policy: PresentationPolicy,
    turnLive: Boolean,
    deferCollapse: Boolean,
) {
    if (policy.stepGrouping == "none" || !policy.foldCompletedTurns && policy.mode == TranscriptView.Verbose) {
        for (item in body) emitPlain(out, item)
        return
    }

    var buf = ProcessBuf()
    fun flushProcess(forceOpen: Boolean, closed: Boolean) {
        if (buf.empty) return
        val tools = buf.tools.toList()
        val reasoning = buf.reasoning.toList()
        val live = tools.any { it.status == ToolStatus.Running || it.status == ToolStatus.AwaitingApproval } ||
            reasoning.any { it.live }
        // Compact hides live process detail (desktop liveProcessDetail=false).
        if (live && !policy.liveProcessDetail && policy.mode == TranscriptView.Compact) {
            out += ChatRow.Process(
                key = buf.key(),
                tools = tools,
                reasoning = reasoning,
                title = liveProcessTitle(tools, reasoning),
                closed = false,
                defaultOpen = false,
                durationMs = reasoning.mapNotNull { it.durationMs }.maxOrNull(),
            )
            buf = ProcessBuf()
            return
        }
        if (live && policy.liveProcessDetail && policy.stepGrouping == "history" && turnLive) {
            // Detailed + open turn: show historical grouping style but keep live rows expanded.
            for (r in reasoning) out += ChatRow.Reasoning(r)
            for (t in tools) out += ChatRow.Tool(t)
            buf = ProcessBuf()
            return
        }
        val settled = closed && !live
        val fold = policy.foldCompletedTurns && settled && !forceOpen
        val title = if (live) liveProcessTitle(tools, reasoning)
        else if (tools.isEmpty()) "已思考"
        else processTitle(rankedActivityKinds(tools))
        val duration = reasoning.mapNotNull { it.durationMs }.maxOrNull()
            ?: tools.mapNotNull { t ->
                val a = t.startedAt; val b = t.endedAt
                if (a != null && b != null) b - a else null
            }.sum().takeIf { it > 0 }
        out += ChatRow.Process(
            key = buf.key(),
            tools = tools,
            reasoning = reasoning,
            title = title,
            closed = settled,
            defaultOpen = !fold || deferCollapse,
            durationMs = duration,
        )
        buf = ProcessBuf()
    }

    for (item in body) {
        when (item) {
            is Item.Tool -> buf.tools += item
            is Item.Reasoning -> buf.reasoning += item
            is Item.Text -> {
                val liveMembers = buf.tools.any { it.status == ToolStatus.Running || it.status == ToolStatus.AwaitingApproval } ||
                    buf.reasoning.any { it.live }
                flushProcess(forceOpen = false, closed = !liveMembers && !item.live)
                out += ChatRow.Answer(item)
            }
            is Item.Notice -> {
                flushProcess(forceOpen = false, closed = !turnLive)
                out += ChatRow.Notice(item)
            }
            else -> Unit
        }
    }
    val liveMembers = buf.tools.any { it.status == ToolStatus.Running || it.status == ToolStatus.AwaitingApproval } ||
        buf.reasoning.any { it.live }
    flushProcess(forceOpen = false, closed = !liveMembers && !turnLive)
}

private fun emitPlain(out: MutableList<ChatRow>, item: Item) {
    when (item) {
        is Item.Tool -> out += ChatRow.Tool(item)
        is Item.Reasoning -> out += ChatRow.Reasoning(item)
        is Item.Text -> out += ChatRow.Answer(item)
        is Item.Notice -> out += ChatRow.Notice(item)
        else -> Unit
    }
}

/** Whether a process group should render collapsed under [policy] (unit-test helper). */
fun processCollapsedByDefault(row: ChatRow.Process, policy: PresentationPolicy): Boolean =
    policy.foldCompletedTurns && row.closed && !row.defaultOpen
