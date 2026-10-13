package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TurnRecord

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

/** Original assistant-block order within one Host step. */
data class ProcessStep(val key: String, val step: Int?, val members: List<Item>)

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
        /** Original order, never reasoning-first/tool-second. */
        val members: List<Item> = emptyList(),
        val steps: List<ProcessStep> = emptyList(),
        val turnSummary: Boolean = false,
        val canCollapse: Boolean = true,
    ) : ChatRow

    data class Deliverable(val item: Item.Deliverable) : ChatRow {
        override val key get() = item.key
    }

    data class Changes(val item: Item.Changes) : ChatRow {
        override val key get() = item.key
    }
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
    "thinking" to "正在分析请求",
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

/** Desktop elapsed label: floor seconds, retain zero minutes/seconds at larger units. */
fun formatRunDuration(milliseconds: Long): String {
    val total = (milliseconds / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return buildString {
        if (hours > 0) append("${hours}小时")
        if (total >= 60) append("${minutes}分")
        append("${seconds}秒")
    }
}

private fun Item.processTurn(): Int? = when (this) {
    is Item.Tool -> turn
    is Item.Reasoning -> turn
    is Item.Text -> turn
    is Item.Notice -> turn
    is Item.Deliverable -> turn
    is Item.Changes -> turn
    else -> null
}

private fun Item.processStep(): Int? = when (this) {
    is Item.Tool -> step
    is Item.Reasoning -> step
    else -> null
}

private fun processSteps(members: List<Item>): List<ProcessStep> {
    val groups = ArrayList<ProcessStep>()
    var currentStep: Int? = null
    var current = ArrayList<Item>()
    fun flush() {
        if (current.isEmpty()) return
        groups += ProcessStep(current.first().key + "-step", currentStep, current)
        current = ArrayList()
    }
    for (item in members) {
        val step = item.processStep()
        if (current.isNotEmpty() && step != currentStep) flush()
        currentStep = step
        current += item
    }
    flush()
    return groups
}

/** Desktop tools disclosure spans consecutive tool-bearing steps, not reasoning-only steps. */
fun processDisplaySteps(steps: List<ProcessStep>): List<ProcessStep> {
    val groups = ArrayList<ProcessStep>()
    var toolMembers = ArrayList<Item>()
    var firstToolStep: ProcessStep? = null
    fun flushTools() {
        val first = firstToolStep ?: return
        groups += first.copy(members = toolMembers)
        toolMembers = ArrayList()
        firstToolStep = null
    }
    for (step in steps) {
        if (step.members.any { it is Item.Tool }) {
            if (firstToolStep == null) firstToolStep = step
            toolMembers.addAll(step.members)
        } else {
            flushTools()
            groups += step
        }
    }
    flushTools()
    return groups
}

/**
 * Fold by Host turn identity, not User rows (inbox and steering are not turn boundaries).
 * Only Host turn/end closes a known turn; neither assistant completion nor tool timings do.
 */
fun foldChatItems(
    items: List<Item>,
    policy: PresentationPolicy,
    sessionRunning: Boolean,
    turnRecords: List<TurnRecord> = emptyList(),
): List<ChatRow> {
    val out = ArrayList<ChatRow>()
    val facts = turnRecords.associateBy { it.turn }
    val membersByTurn = LinkedHashMap<Int, MutableList<Item>>()
    for (item in items) {
        val turn = item.processTurn() ?: continue
        val members = membersByTurn.getOrPut(turn) { ArrayList() }
        if (item is Item.Tool || item is Item.Reasoning) members += item
    }
    val latestTurn = membersByTurn.keys.maxOrNull()
    val latestInputSeq = items.filterIsInstance<Item.User>().mapNotNull { it.seq }.maxOrNull()
    val pendingInput = items.any { it is Item.User && it.pending }
    val closedTurns = turnRecords.filter { it.closed }.sortedBy { it.turn }
    var nextClosed = 0
    val plainOpenTurn = policy.stepGrouping == "none" ||
        policy.stepGrouping == "history" && policy.liveProcessDetail && sessionRunning
    val emitted = HashSet<Int>()
    val unowned = ArrayList<Item>()

    fun emitProcess(key: String, members: List<Item>, fact: TurnRecord?, deferCollapse: Boolean) {
        val tools = members.filterIsInstance<Item.Tool>()
        val reasoning = members.filterIsInstance<Item.Reasoning>()
        val closed = fact?.closed == true
        if (!closed && members.isEmpty()) return
        if (!closed && plainOpenTurn) {
            members.forEach { emitPlain(out, it) }
            return
        }
        val duration = fact?.durationMs
        val failed = fact?.endReason == "error" || fact?.endReason == "aborted"
        val canCollapse = members.isNotEmpty() && !failed && policy.foldCompletedTurns
        val title = if (closed) when (fact?.endReason) {
            "aborted" -> "已停止"
            "error" -> "处理失败"
            else -> duration?.let { "已完成，用时 ${formatRunDuration(it)}" } ?: "已完成"
        } else liveProcessTitle(tools, reasoning)
        out += ChatRow.Process(
            key = key,
            tools = tools,
            reasoning = reasoning,
            title = title,
            closed = closed,
            defaultOpen = !canCollapse || if (closed) deferCollapse else policy.mode != TranscriptView.Compact,
            durationMs = duration,
            members = members.toList(),
            steps = processSteps(members),
            turnSummary = closed,
            canCollapse = canCollapse,
        )
    }

    fun flushUnowned() {
        if (unowned.isEmpty()) return
        // Partial/legacy payloads have no proven turn identity or endpoints: no elapsed label.
        emitProcess(unowned.first().key + "-group", unowned, null, false)
        unowned.clear()
    }

    fun emitTurn(turn: Int) {
        if (!emitted.add(turn)) return
        val fact = facts[turn]
        val hasNextInput = latestTurn != null && latestTurn > turn || pendingInput ||
            fact?.endSeq?.let { end -> latestInputSeq != null && latestInputSeq > end } == true
        emitProcess(
            "turn-$turn-process", membersByTurn[turn].orEmpty(), fact,
            policy.collapseTiming == CollapseTiming.NextInput && !hasNextInput,
        )
    }

    for (item in items) {
        if (item is Item.Context || item is Item.System || item is Item.Compacted) continue
        val turn = item.processTurn()
        if (turn != null) {
            flushUnowned()
            // Also render empty closed turns preceding the next owned assistant content.
            while (nextClosed < closedTurns.size && closedTurns[nextClosed].turn < turn) {
                emitTurn(closedTurns[nextClosed++].turn)
            }
            if (facts[turn]?.closed != true && plainOpenTurn) {
                emitPlain(out, item)
                continue
            }
            emitTurn(turn)
            if (item is Item.Tool || item is Item.Reasoning) continue
        } else if (item is Item.Tool || item is Item.Reasoning) {
            unowned += item
            continue
        } else {
            flushUnowned()
        }
        emitPlain(out, item)
    }
    flushUnowned()
    while (nextClosed < closedTurns.size) emitTurn(closedTurns[nextClosed++].turn)
    return out
}

private fun emitPlain(out: MutableList<ChatRow>, item: Item) {
    when (item) {
        is Item.User -> out += ChatRow.User(item)
        is Item.Tool -> out += ChatRow.Tool(item)
        is Item.Reasoning -> out += ChatRow.Reasoning(item)
        is Item.Text -> out += ChatRow.Answer(item)
        is Item.Notice -> out += ChatRow.Notice(item)
        is Item.Deliverable -> out += ChatRow.Deliverable(item)
        is Item.Changes -> out += ChatRow.Changes(item)
        else -> Unit
    }
}

/** Whether a process group should render collapsed under [policy] (unit-test helper). */
fun processCollapsedByDefault(row: ChatRow.Process, policy: PresentationPolicy): Boolean =
    policy.foldCompletedTurns && row.closed && !row.defaultOpen

internal data class ComposerTrigger(val kind: Char, val start: Int, val end: Int, val query: String, val quoted: Boolean = false)

private val quotedReferenceTrigger = Regex("""(?:^|\s)(@"([^"]*))$""")

/** Trigger only a token at the caret, never an email address or a slash inside a path. */
internal fun composerTrigger(text: String, cursor: Int): ComposerTrigger? {
    val end = cursor.coerceIn(0, text.length)
    quotedReferenceTrigger.find(text.substring(0, end))?.let { match ->
        return ComposerTrigger('@', end - match.groupValues[1].length, end, match.groupValues[2], quoted = true)
    }
    var start = end
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    if (start == end) return null
    val kind = text[start]
    if (kind != '@' && kind != '/') return null
    if (kind == '/' && text.take(start).isNotBlank()) return null
    return ComposerTrigger(kind, start, end, text.substring(start + 1, end))
}

internal fun replaceComposerToken(text: String, cursor: Int, token: String): Pair<String, Int> {
    val at = cursor.coerceIn(0, text.length)
    val trigger = composerTrigger(text, at)
    val start = trigger?.start ?: at
    var end = trigger?.end ?: at
    if (trigger?.quoted == true) {
        val closingQuote = text.indexOf('"', end)
        if (closingQuote >= 0) end = closingQuote + 1
    } else if (trigger != null) while (end < text.length && !text[end].isWhitespace()) end++
    val inserted = when {
        token.isEmpty() -> ""
        token.startsWith("/") -> token + if (token.endsWith(" ")) "" else " "
        else -> "@${token.removePrefix("@")} "
    }
    val result = text.replaceRange(start, end, inserted)
    return result to start + inserted.length
}

/** Match the official browser-safe file-reference grammar; unrepresentable paths are not candidates. */
internal fun fileReferenceToken(path: String, directory: Boolean): String? {
    if (path.any { it == '"' || it.code in 0..31 || it.code in 127..159 }) return null
    val value = path.trimEnd('/') + if (directory) "/" else ""
    return if (value.any(Char::isWhitespace)) "@\"$value\"" else "@$value"
}
