package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TurnRecord
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.arr
import dev.dsh.pocket.protocol.obj
import dev.dsh.pocket.protocol.str
import java.text.BreakIterator

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
    mode: TranscriptView = TranscriptView.Standard,
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

    data class Retry(val item: Item.Retry) : ChatRow {
        override val key get() = item.key
    }

    data class TurnFailure(val item: Item.TurnFailure) : ChatRow {
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
     * A contiguous process segment, or a memberless whole-Turn control when [turnSummary].
     * Whole-Turn visibility and local group disclosure are independent.
     */
    data class Process(
        override val key: String,
        val tools: List<Item.Tool>,
        val reasoning: List<Item.Reasoning>,
        /** Desktop `processTitle` / live activity label. */
        val title: String,
        val closed: Boolean,
        /** Local groups start closed; Turn controls can defer their outer folding until the next input. */
        val defaultOpen: Boolean,
        val durationMs: Long? = null,
        /** Original order, never reasoning-first/tool-second. */
        val members: List<Item> = emptyList(),
        val steps: List<ProcessStep> = emptyList(),
        val turnSummary: Boolean = false,
        val canCollapse: Boolean = true,
        val turn: Int? = null,
        /** False for Detailed live/deferred Turns and Verbose: render members without a header. */
        val grouped: Boolean = true,
        /** Outer Turn disclosure controls these sibling rows, never reparents their members. */
        val controlledKeys: Set<String> = emptySet(),
        val activityKind: String = "thinking",
        val detail: String? = null,
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
    "edit" to "正在编辑文件",
    "commands" to "正在运行命令",
    "code" to "正在运行代码",
    "webSearch" to "正在搜索网页",
    "webFetch" to "正在访问网页",
    "subagents" to "正在协调子智能体",
    "plan" to "正在更新计划",
    "questions" to "等待你的操作",
    "tools" to "正在调用工具",
)

private val DONE_LABEL_EN = mapOf(
    "thinking" to "Analysis completed", "read" to "Read files", "readImage" to "Read images",
    "write" to "Wrote files", "search" to "Searched code", "edit" to "Edited files",
    "commands" to "Ran commands", "code" to "Ran code", "webSearch" to "Searched the web",
    "webFetch" to "Visited web pages", "subagents" to "Coordinated subagents",
    "plan" to "Updated the plan", "questions" to "Asked questions", "tools" to "Called tools",
)

private val LIVE_LABEL_EN = mapOf(
    "thinking" to "Analyzing the request", "read" to "Reading files", "readImage" to "Reading images",
    "write" to "Writing files", "search" to "Searching code", "edit" to "Editing files",
    "commands" to "Running commands", "code" to "Running code", "webSearch" to "Searching the web",
    "webFetch" to "Visiting web pages", "subagents" to "Coordinating subagents",
    "plan" to "Updating the plan", "questions" to "Waiting for your action", "tools" to "Calling tools",
)

private val PREPARE_LABEL = mapOf(
    "read" to "准备读取文件", "readImage" to "准备读取图片", "write" to "准备写入文件",
    "search" to "准备搜索代码", "edit" to "准备编辑文件", "commands" to "准备运行命令",
    "code" to "准备运行代码", "webSearch" to "准备搜索网页", "webFetch" to "准备访问网页",
    "subagents" to "准备协调子智能体", "plan" to "准备更新计划", "questions" to "准备提问",
    "tools" to "准备调用工具",
)

private val PREPARE_LABEL_EN = mapOf(
    "read" to "Preparing to read files", "readImage" to "Preparing to read images",
    "write" to "Preparing to write files", "search" to "Preparing to search code",
    "edit" to "Preparing to edit files", "commands" to "Preparing to run commands",
    "code" to "Preparing to run code", "webSearch" to "Preparing to search the web",
    "webFetch" to "Preparing to visit web pages", "subagents" to "Preparing to coordinate subagents",
    "plan" to "Preparing to update the plan", "questions" to "Preparing questions",
    "tools" to "Preparing tool calls",
)

internal fun preparingToolTitle(name: String, en: Boolean = false): String =
    (if (en) PREPARE_LABEL_EN else PREPARE_LABEL).getValue(toolActivityKind(name))

/**
 * Desktop `processTitle(summary, t)` — top three activity kinds, no counts.
 * zh: joinTwo = "{first}并{second}", sharedPrefix = "已", comma = "，", more = "{title}等".
 */
fun processTitle(kinds: List<String>, en: Boolean = false): String {
    val dictionary = if (en) DONE_LABEL_EN else DONE_LABEL
    if (kinds.isEmpty()) return dictionary.getValue("thinking")
    val labels = kinds.take(3).map { dictionary[it] ?: dictionary.getValue("tools") }
    val first = labels[0]
    if (labels.size == 1) return first
    fun continuation(label: String) = label.replaceFirstChar { it.lowercase() }
    if (labels.size == 2) {
        val second = labels[1]
        val prefix = "已"
        val trimmed = if (!en && first.startsWith(prefix) && second.startsWith(prefix)) second.removePrefix(prefix) else second
        return "$first${if (en) " and " else "并"}${continuation(trimmed)}"
    }
    val title = listOf(first) + labels.drop(1).map(::continuation)
    val joined = title.joinToString(if (en) ", " else "，")
    return if (kinds.size > 3) joined + (if (en) ", etc." else "等") else joined
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

private fun runningTool(tools: List<Item.Tool>): Item.Tool? {
    var latest: Item.Tool? = null
    for (tool in tools) {
        if (tool.status != ToolStatus.Preparing && tool.status != ToolStatus.Running && tool.status != ToolStatus.AwaitingApproval) continue
        if (latest == null || (tool.startedAt ?: Long.MIN_VALUE) >= (latest.startedAt ?: Long.MIN_VALUE)) latest = tool
    }
    return latest
}
fun liveProcessTitle(tools: List<Item.Tool>, reasoning: List<Item.Reasoning>, en: Boolean = false): String {
    val tool = runningTool(tools)
    if (tool?.status == ToolStatus.Preparing) return preparingToolTitle(tool.name, en)
    val dictionary = if (en) LIVE_LABEL_EN else LIVE_LABEL
    return dictionary.getValue(tool?.let { toolActivityKind(it.name) } ?: "thinking")
}

private val detailWhitespace = Regex("\\s+")
private val detailParagraph = Regex("\\r?\\n[\\t ]*\\r?\\n")
private val detailKeys = listOf(
    "title", "description", "objective", "task", "task_name", "name", "question", "questions",
    "prompt", "message", "command", "cmd", "queries", "query", "pattern", "url", "uri",
    "file_path", "path", "target", "action", "status",
)

private fun normalizeProcessDetail(text: String): String {
    val normalized = text.replace(detailWhitespace, " ").trim()
    if (normalized.length <= 160) return normalized
    val iterator = BreakIterator.getCharacterInstance().apply { setText(normalized) }
    var end = iterator.first()
    repeat(159) {
        val next = iterator.next()
        if (next == BreakIterator.DONE) return normalized
        end = next
    }
    if (iterator.next() == BreakIterator.DONE || iterator.next() == BreakIterator.DONE) return normalized
    return normalized.substring(0, end).trimEnd() + "…"
}

private fun processDetail(tool: Item.Tool?, reasoning: List<Item.Reasoning>): String? {
    if (tool == null) return reasoning.asReversed().firstNotNullOfOrNull { block ->
        if (!block.live) null else block.text.split(detailParagraph).asReversed()
            .firstNotNullOfOrNull { normalizeProcessDetail(it.replace("**", "")).takeIf(String::isNotEmpty) }
    }
    val args = runCatching { PocketJson.parseToJsonElement(tool.arguments).obj() }.getOrNull()
    for (key in detailKeys) {
        val value = args?.get(key) ?: continue
        val text = if (key == "questions") {
            value.arr()?.firstNotNullOfOrNull { it.obj()?.get("question").str()?.takeIf(String::isNotBlank) }
        } else value.str() ?: value.arr()?.takeIf { values -> values.all { it.str() != null } }
            ?.joinToString(", ") { it.str().orEmpty() }
        val detail = text?.let(::normalizeProcessDetail)
        if (!detail.isNullOrEmpty()) return detail
    }
    return normalizeProcessDetail(tool.name).takeIf(String::isNotEmpty)
}

/** Desktop elapsed label: floor seconds, retain zero minutes/seconds at larger units. */
fun formatRunDuration(milliseconds: Long, en: Boolean = false): String {
    val total = (milliseconds / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return buildString {
        if (hours > 0) append("$hours${if (en) "h " else "小时"}")
        if (total >= 60) append("$minutes${if (en) "m " else "分"}")
        append("$seconds${if (en) "s" else "秒"}")
    }
}

private fun Item.processTurn(): Int? = when (this) {
    is Item.Tool -> turn
    is Item.Reasoning -> turn
    is Item.Text -> turn
    is Item.Notice -> turn
    is Item.Retry -> turn
    is Item.TurnFailure -> turn
    is Item.Deliverable -> turn
    is Item.Changes -> turn
    else -> null
}

private fun Item.processStep(): Int? = when (this) {
    is Item.Tool -> step
    is Item.Reasoning -> step
    is Item.Text -> step
    is Item.Retry -> step
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


/**
 * Segment in transcript order first. A response, independent input/notice or another Turn
 * settles the preceding group; only Host turn/end creates the separate whole-Turn control.
 */
fun foldChatItems(
    items: List<Item>,
    policy: PresentationPolicy,
    sessionRunning: Boolean,
    turnRecords: List<TurnRecord> = emptyList(),
    en: Boolean = false,
): List<ChatRow> {
    val facts = turnRecords.associateBy { it.turn }
    val visible = items.filterNot {
        it is Item.Context || it is Item.System || it is Item.Compacted ||
            it is Item.Reasoning && it.text.isBlank() || it is Item.Text && it.text.isBlank()
    }
    val positions = visible.withIndex().associate { it.value.key to it.index }
    val byTurn = visible.filter { it.processTurn() != null }.groupBy { it.processTurn()!! }
    val latestTurn = (byTurn.keys + facts.keys).maxOrNull()
    val latestInputSeq = visible.filterIsInstance<Item.User>().mapNotNull { it.seq }.maxOrNull()
    val lastInputIndex = visible.indexOfLast { it is Item.User }
    fun deferCollapse(turn: Int?): Boolean {
        if (turn == null || policy.collapseTiming != CollapseTiming.NextInput) return false
        val fact = facts[turn]
        val lastTurnIndex = byTurn[turn]?.lastOrNull()?.let { positions[it.key] } ?: -1
        val followingInput = lastInputIndex > lastTurnIndex ||
            fact?.endSeq?.let { end -> latestInputSeq != null && latestInputSeq > end } == true
        return turn == latestTurn && !followingInput
    }
    val out = ArrayList<ChatRow>()
    var pending = ArrayList<Item>()
    var pendingTurn: Int? = null
    fun flush(boundary: Boolean) {
        if (pending.isEmpty()) return
        val members = pending
        pending = ArrayList()
        val tools = members.filterIsInstance<Item.Tool>()
        val reasoning = members.filterIsInstance<Item.Reasoning>()
        val fact = facts[pendingTurn]
        val turnClosed = fact?.closed == true
        val closed = boundary || turnClosed
        val grouped = policy.stepGrouping == "collapsed" ||
            policy.stepGrouping == "history" && turnClosed && !deferCollapse(pendingTurn)
        val running = runningTool(tools)
        val ranked = if (closed) rankedActivityKinds(tools) else emptyList()
        out += ChatRow.Process(
            key = "${members.first().key}-group", tools = tools, reasoning = reasoning,
            title = if (closed) processTitle(ranked, en) else liveProcessTitle(tools, reasoning, en),
            closed = closed, defaultOpen = false, members = members, steps = processSteps(members),
            turn = pendingTurn, grouped = grouped, canCollapse = grouped,
            activityKind = if (closed) ranked.firstOrNull() ?: "thinking"
                else running?.let { toolActivityKind(it.name) } ?: "thinking",
            detail = if (!closed && policy.liveProcessDetail) processDetail(running, reasoning) else null,
        )
    }
    for (item in visible) {
        val turn = item.processTurn()
        if (pending.isNotEmpty() && pendingTurn != turn) flush(true)
        if (item is Item.Tool || item is Item.Reasoning) {
            pendingTurn = turn
            pending += item
        } else {
            flush(true)
            emitPlain(out, item)
        }
    }
    flush(false)

    fun rowTurn(row: ChatRow): Int? = when (row) {
        is ChatRow.Process -> row.turn
        is ChatRow.Answer -> row.item.turn
        is ChatRow.Notice -> row.item.turn
        is ChatRow.Retry -> row.item.turn
        is ChatRow.TurnFailure -> row.item.turn
        is ChatRow.Deliverable -> row.item.turn
        is ChatRow.Changes -> row.item.turn
        else -> null
    }
    val rowsByTurn = out.groupBy(::rowTurn)
    // Controls are anchored before a Turn's first visible output, never by moving its rows.
    val controls = LinkedHashMap<Int, ChatRow.Process>()
    for (fact in turnRecords.filter { it.closed }.sortedBy { it.turn }) {
        val owned = byTurn[fact.turn].orEmpty()
        val lastStep = owned.mapNotNull { it.processStep() }.maxOrNull()
        val lastStepHasTools = owned.any { it is Item.Tool && it.step == lastStep }
        val finalAnswer = owned.filterIsInstance<Item.Text>().lastOrNull {
            !it.live && it.step == lastStep && !lastStepHasTools
        }
        val finalPosition = finalAnswer?.let { positions[it.key] }
        val finalAnswerKeys = owned.filterIsInstance<Item.Text>().filter {
            finalAnswer != null && it.step == finalAnswer.step
        }.mapTo(HashSet()) { it.key }
        val ownedPositions = owned.mapNotNull { positions[it.key] }
        val first = ownedPositions.minOrNull()
        val last = ownedPositions.maxOrNull()
        val interleaved = first != null && last != null &&
            (first..last).any { visible[it] is Item.User }
        val controlled = rowsByTurn[fact.turn].orEmpty().filter { row ->
            when (row) {
                is ChatRow.Process -> true
                is ChatRow.Answer -> row.key !in finalAnswerKeys
                is ChatRow.Deliverable, is ChatRow.Changes ->
                    finalPosition == null || (positions[row.key] ?: Int.MAX_VALUE) < finalPosition
                else -> false
            }
        }.mapTo(LinkedHashSet()) { it.key }
        val failed = fact.endReason == "error" || fact.endReason == "aborted"
        val canCollapse = controlled.isNotEmpty() && !failed && !interleaved && policy.foldCompletedTurns
        val duration = fact.durationMs
        controls[fact.turn] = ChatRow.Process(
            key = "turn-${fact.turn}-process", tools = emptyList(), reasoning = emptyList(),
            title = when (fact.endReason) {
                "aborted" -> if (en) "Stopped" else "已停止"
                "error" -> if (en) "Failed" else "处理失败"
                else -> duration?.let { (if (en) "Completed in " else "已完成，用时 ") + formatRunDuration(it, en) }
                    ?: if (en) "Completed" else "已完成"
            },
            closed = true, defaultOpen = !canCollapse || deferCollapse(fact.turn), durationMs = duration,
            turnSummary = true, canCollapse = canCollapse, turn = fact.turn, controlledKeys = controlled,
        )
    }
    val result = ArrayList<ChatRow>(out.size + controls.size)
    for (row in out) {
        val turn = rowTurn(row)
        if (turn != null) {
            val preceding = controls.keys.takeWhile { it <= turn }
            for (closedTurn in preceding) result += controls.remove(closedTurn)!!
        } else if (row is ChatRow.User) {
            val userSeq = row.item.seq
            if (userSeq != null) {
                val preceding = controls.keys.filter { facts[it]?.endSeq?.let { end -> end < userSeq } == true }
                for (closedTurn in preceding) result += controls.remove(closedTurn)!!
            }
        }
        result += row
    }
    result.addAll(controls.values)
    return result
}

/** Apply only outer-Turn visibility; local process-group disclosure remains independent. */
fun visibleChatRows(rows: List<ChatRow>, openTurns: Map<String, Boolean> = emptyMap()): List<ChatRow> {
    val hidden = HashSet<String>()
    for (row in rows) {
        if (row is ChatRow.Process && row.turnSummary && row.canCollapse &&
            !(openTurns[row.key] ?: row.defaultOpen)) hidden.addAll(row.controlledKeys)
    }
    return if (hidden.isEmpty()) rows else rows.filterNot { it.key in hidden }
}

private fun emitPlain(out: MutableList<ChatRow>, item: Item) {
    when (item) {
        is Item.User -> out += ChatRow.User(item)
        is Item.Tool -> out += ChatRow.Tool(item)
        is Item.Reasoning -> out += ChatRow.Reasoning(item)
        is Item.Text -> out += ChatRow.Answer(item)
        is Item.Notice -> out += ChatRow.Notice(item)
        is Item.Retry -> out += ChatRow.Retry(item)
        is Item.TurnFailure -> out += ChatRow.TurnFailure(item)
        is Item.Deliverable -> out += ChatRow.Deliverable(item)
        is Item.Changes -> out += ChatRow.Changes(item)
        else -> Unit
    }
}


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
