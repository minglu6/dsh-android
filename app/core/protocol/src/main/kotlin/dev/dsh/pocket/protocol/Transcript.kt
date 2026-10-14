package dev.dsh.pocket.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** Session attachment reference from a user message image block. */
data class ImageRef(val attachmentId: String, val mediaType: String?)

/** One file declared by `deliverables/presented`. */
data class DeliverableFile(val path: String, val description: String? = null)

/** Pending input awaiting its own Host turn, separate from the transcript. */
data class QueuedPrompt(
    val id: String,
    val text: String,
    val images: Int = 0,
    val files: Int = 0,
    val sending: Boolean = false,
)

/** One rendered row of a conversation. */
sealed interface Item {
    val key: String
    /** [pending]: echoed locally or still in dsh's inbox (dsh writes user/message only when the step starts). */
    data class User(
        override val key: String,
        val text: String,
        val images: Int = 0,
        val pending: Boolean = false,
        /** Session-event time (ms), for trajectory swimlane / message clock. */
        val at: Long? = null,
        /** Desktop `messageSource` JSON for the Trajectory 来源 tab. */
        val messageSourceJson: String? = null,
        /** Durable event seq (for `sessions.fork` atSeq). */
        val seq: Long? = null,
        /** Image attachment refs from content blocks (`type=image`). */
        val imageRefs: List<ImageRef> = emptyList(),
        val requestId: String? = null,
        val messageId: String? = null,
    ) : Item
    data class Reasoning(
        override val key: String, val text: String, val durationMs: Long?, val live: Boolean,
        val turn: Int? = null, val step: Int? = null, val seq: Long? = null,
    ) : Item
    data class Text(
        override val key: String,
        val text: String,
        val live: Boolean,
        /** Session-event time (ms), for message clock. */
        val at: Long? = null,
        /** Durable event seq (for `sessions.fork` atSeq). */
        val seq: Long? = null,
        /** Assistant `message.id` for message-feedback (like/dislike). */
        val messageId: String? = null,
        val turn: Int? = null,
        val step: Int? = null,
    ) : Item
    data class Tool(
        override val key: String, val callId: String, val name: String, val arguments: String,
        val status: ToolStatus, val result: String? = null, val error: String? = null,
        val startedAt: Long? = null, val endedAt: Long? = null,
        /** Desktop `schemaDetail`: pretty JSON of `{name,description,parameters}` from `request/header`. */
        val schemaJson: String? = null,
        /** Turn/step from the owning step (desktop inspector header 「第 N 轮 · 第 M 步」). */
        val turn: Int? = null,
        val step: Int? = null,
        /**
         * Desktop PTC `parentCallId` — when set, layout emits `kind: 'subtool'`
         * (`tool/ptc-dispatch-start` / `tool/ptc-dispatch`).
         */
        val parentCallId: String? = null,
    ) : Item
    data class Notice(override val key: String, val text: String, val turn: Int? = null) : Item
    /**
     * Injected user-role / developer context (runtime-context, skills, memory, time sampled, …).
     * Shown on the Trajectory tab as 上下文; omitted from the chat transcript.
     */
    data class Context(
        override val key: String,
        val text: String,
        val sourceKind: String? = null,
        val at: Long? = null,
        /** Desktop `messageSource` JSON for the Trajectory 来源 tab. */
        val messageSourceJson: String? = null,
    ) : Item
    /**
     * `system/message` surface prompt for the Trajectory 系统 row
     * (desktop `layout.initialSystemPrompt` / `layout.systemPromptUpdated`).
     */
    data class System(
        override val key: String,
        val label: String,
        val text: String,
        val at: Long? = null,
        /** Previous system prompt text for the 差异 tab (desktop `previousPromptDetail`). */
        val previousText: String? = null,
        /** Pretty JSON array of tools from the latest `request/header` (桌面 工具 tab). */
        val toolsJson: String? = null,
        /** Previous tools JSON for diff (added/removed names). */
        val previousToolsJson: String? = null,
    ) : Item
    /**
     * Desktop `kind: 'compacted'` from `compaction/start|summary|end`
     * (`trajectory-compaction-definition.ts`).
     */
    data class Compacted(
        override val key: String,
        val compactionId: String,
        val status: CompactionStatus,
        val summary: String? = null,
        val error: String? = null,
        val interrupted: Boolean = false,
        val startedAt: Long? = null,
        val endedAt: Long? = null,
        val turn: Int? = null,
    ) : Item
    /** Files presented via `deliverables/presented`. */
    data class Deliverable(
        override val key: String,
        val files: List<DeliverableFile>,
        val turn: Int? = null,
    ) : Item
    /**
     * Turn file-change announcement from `workspace/changes`.
     * [count]/[name] may stay sparse until Host summary is available ([count] 0 = unknown).
     */
    data class Changes(
        override val key: String,
        val turn: Int,
        val count: Int = 0,
        val name: String? = null,
        /** Event seq for `workspaceChanges.summary`. */
        val seq: Long? = null,
    ) : Item
}

/** Desktop compaction request status. */
enum class CompactionStatus { Running, Ok, Error }

/** One entry of the agent todo list (`todo/write` / projections.todos). */
data class TodoItem(val content: String, val status: String)

enum class ToolStatus { Running, AwaitingApproval, Ok, Error }

/** Parsed desktop ToolSchema (`name` + `description` + object `parameters`). */
data class ParsedToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * Desktop `parseToolSchema`: require name/description strings and object parameters.
 * @return null when the payload is missing or not a tool schema.
 */
fun parseToolSchema(value: String): ParsedToolSchema? {
    val parsed = runCatching { PocketJson.parseToJsonElement(value) }.getOrNull()?.obj() ?: return null
    val name = parsed["name"].str() ?: return null
    val description = parsed["description"].str() ?: return null
    val parameters = parsed["parameters"].obj() ?: return null
    return ParsedToolSchema(name, description, parameters)
}

private val PrettyToolJson = Json { prettyPrint = true; ignoreUnknownKeys = true; explicitNulls = false }

/** Pretty-print one EpochHeader tool entry for `Item.Tool.schemaJson` / Schema tab. */
fun prettyToolSchema(tool: JsonElement?): String? {
    val obj = tool.obj() ?: return null
    if (obj["name"].str() == null) return null
    return runCatching {
        PrettyToolJson.encodeToString(JsonElement.serializer(), obj)
    }.getOrNull()
}

/**
 * Desktop `formatDurationMillis` / `unit.milliseconds`: integer ms with thousands separators.
 * Returns `—` when absent or non-finite (desktop em dash).
 */
fun formatDurationMillis(milliseconds: Double?): String {
    if (milliseconds == null || !milliseconds.isFinite()) return "—"
    val integer = kotlin.math.round(milliseconds).toLong().toString()
    val withCommas = integer.reversed().chunked(3).joinToString(",").reversed()
    return "$withCommas 毫秒"
}

/** Desktop `formatElapsedSeconds`: cell seconds → millisecond label. */
fun formatElapsedSeconds(seconds: Double?): String =
    formatDurationMillis(if (seconds == null) null else seconds * 1000.0)

/**
 * Desktop TrajectoryTable `formatStartedAt`: local `YYYY-MM-DD HH:mm:ss.SSS`.
 * Returns `不可用` when absent (timing.notAvailable).
 */
fun formatStartedAt(timestampMs: Long?): String {
    if (timestampMs == null) return "不可用"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
    fun two(n: Int) = n.toString().padStart(2, '0')
    fun three(n: Int) = n.toString().padStart(3, '0')
    val y = cal.get(java.util.Calendar.YEAR)
    val mo = two(cal.get(java.util.Calendar.MONTH) + 1)
    val d = two(cal.get(java.util.Calendar.DAY_OF_MONTH))
    val h = two(cal.get(java.util.Calendar.HOUR_OF_DAY))
    val mi = two(cal.get(java.util.Calendar.MINUTE))
    val s = two(cal.get(java.util.Calendar.SECOND))
    val ms = three(cal.get(java.util.Calendar.MILLISECOND))
    return "$y-$mo-$d $h:$mi:$s.$ms"
}

/** Desktop `stateOf` for tool / subtool cells. */
enum class TrajectoryRecordState { Complete, Running, Error }

fun trajectoryToolState(isError: Boolean, hasOutput: Boolean): TrajectoryRecordState = when {
    isError -> TrajectoryRecordState.Error
    !hasOutput -> TrajectoryRecordState.Running
    else -> TrajectoryRecordState.Complete
}

/** Desktop status.failed / status.pending / status.completed. */
fun trajectoryStatusLabel(state: TrajectoryRecordState): String = when (state) {
    TrajectoryRecordState.Error -> "失败"
    TrajectoryRecordState.Running -> "等待中"
    TrajectoryRecordState.Complete -> "已完成"
}

data class Usage(val turns: Int, val steps: Int, val inputTokens: Long, val cacheReadTokens: Long, val outputTokens: Long) {
    val cacheHitPercent: Int? get() = if (inputTokens > 0) ((cacheReadTokens * 100) / inputTokens).toInt() else null
}

/** Per-step token counts from `assistant/message` usage (desktop trajectory cell fields). */
data class StepUsage(
    val inputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
)

/**
 * One settled assistant step for the Trajectory tab (desktop ui-trajectory message cell).
 * Captured on `step/start` + `assistant/message`; chat [Item]s stay separate for the transcript.
 */
data class StepRecord(
    val seq: Long,
    val turn: Int,
    val step: Int,
    val stepStartTime: Long?,
    val messageTime: Long?,
    /** Desktop `assistantMetrics.firstTokenTime` (ms). */
    val firstTokenTime: Long? = null,
    val replyText: String,
    val reasoningText: String,
    val usage: StepUsage,
    val provider: String?,
    val model: String?,
    val toolCallIds: List<String>,
    /** Desktop interrupted assistant → cell `isError`. */
    val interrupted: Boolean = false,
    /** Desktop `requestConfig` JSON from the latest `request/header` before this step. */
    val requestConfigJson: String? = null,
    /** Desktop `llm/retry` attempt counter for this step (1-based when present). */
    val retry: Int? = null,
    val maxRetries: Int? = null,
    val retryDelayMs: Long? = null,
    val lastAttemptError: String? = null,
)

/**
 * Desktop `TrajectoryRequestHeaderState` snapshot from one `request/header` event.
 * Used by the Request inspector 选项 tab and to attach config to later steps.
 */
data class RequestHeaderFact(
    val seq: Long,
    val time: Long?,
    val turn: Int?,
    val step: Int?,
    val provider: String?,
    val model: String?,
    /** Pretty JSON of `header.config` (desktop AssistantRequestConfig). */
    val configJson: String?,
    val reason: String? = null,
)

/** Host turn boundaries, independent of assistant steps and local run clocks. */
data class TurnRecord(
    val turn: Int,
    val startSeq: Long? = null,
    val endSeq: Long? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: String? = null,
) {
    val closed: Boolean get() = endSeq != null
    val durationMs: Long? get() {
        if (!closed || endReason == "aborted" || endReason == "error") return null
        val start = startedAt ?: return null
        val end = endedAt ?: return null
        return (end - start).coerceAtLeast(1000)
    }
}

private fun TurnRecord.withEarlier(earlier: TurnRecord): TurnRecord = copy(
    startSeq = earlier.startSeq ?: startSeq,
    startedAt = earlier.startedAt ?: startedAt,
    endSeq = endSeq ?: earlier.endSeq,
    endedAt = endedAt ?: earlier.endedAt,
    endReason = endReason ?: earlier.endReason,
)

/**
 * Folds dsh SessionFollowFrames (snapshot/resume/event/assistant-stream, passed through by
 * dsh-plugin-pocket) into [Item]s. Pure and deterministic: no Android dependencies.
 */
class TranscriptReducer {
    private val committed = ArrayList<Item>()
    private val toolIndex = HashMap<String, Int>()
    /** Preserve unmatched endpoints so history pages can complete actual call/result pairs. */
    private val toolTimings = HashMap<String, ToolCallTiming>()
    private val approvalCall = HashMap<String, String>()
    private val userByRpc = HashMap<String, Int>()
    private data class InboxReceipt(val target: String, val index: Int?, val seq: Long)
    private data class LocalEcho(val prompt: QueuedPrompt, val queued: Boolean, val receipt: InboxReceipt? = null)
    private val localEchoes = LinkedHashMap<String, LocalEcho>()
    private val userByMessage = HashMap<String, Int>()
    private val inbox = mapOf("next-turn" to ArrayList<JsonElement>(), "next-step" to ArrayList<JsonElement>())
    private var inboxSeq = -1L
    /** Admission can precede a delayed projection containing the same occurrence. */
    private val retiredMessages = HashSet<String>()
    private val retiredRequests = HashSet<String>()
    private val stepStartTimes = HashMap<String, Long>()
    private val settledSteps = ArrayList<StepRecord>()
    private val turnFacts = LinkedHashMap<Int, TurnRecord>()
    private val headerFacts = ArrayList<RequestHeaderFact>()
    /**
     * Latest tool catalog from `request/header` (`EpochHeader.tools`), keyed by tool name.
     * Desktop `captureSchemas` / `attachToolSchema` maps callId → schema at layout time;
     * we attach the pretty JSON on each tool upsert (same lookup by name).
     */
    private val toolsByName = HashMap<String, String>()
    /** Ordered pretty tool schemas for the system 工具 tab (desktop ToolCatalog). */
    private var toolsCatalogJson: String? = null
    private var previousToolsCatalogJson: String? = null
    /** Last emitted system prompt body (for 差异 / previousPromptDetail). */
    private var lastSystemPromptText: String? = null
    /** Whether a trajectory 系统 row has already been emitted (initial vs updated label). */
    private var systemPromptSeen = false
    /** Open compaction requests keyed by compactionId. */
    private val compactIndex = HashMap<String, Int>()
    /** Pending `llm/retry` facts for the open step (desktop assistant retry state). */
    private var openRetry: Int? = null
    private var openMaxRetries: Int? = null
    private var openRetryDelayMs: Long? = null
    private var openLastAttemptError: String? = null
    /** Latest request/header config to stamp onto the next assistant step. */
    private var latestRequestConfigJson: String? = null
    private var latestRequestProvider: String? = null
    private var latestRequestModel: String? = null
    private var live: LiveAttempt? = null
    var cursor: Long = -1; private set
    /** Lowest event seq currently folded (for history.page beforeSeq). */
    var firstSeq: Long = -1; private set
    /** Whether older history remains above the current window (from snapshot/page). */
    var hasMore: Boolean = false; private set
    var title: String? = null; private set
    var running = false; private set
    private var turns = 0
    private var steps = 0
    /** Most recent `step/start` coordinates (fallback when tool events omit turn/step). */
    private var openTurn: Int? = null
    private var openStep: Int? = null
    private var inputTokens = 0L
    private var cacheRead = 0L
    private var outputTokens = 0L
    /** Latest whole-list todo snapshot (`todo/write` / projections.values.todos). */
    private var todos: List<TodoItem> = emptyList()
    /** Host `plan` projection (`plan/mode` + projections.values.plan.active). */
    private var planActive: Boolean = false
    private var planProjectionSeq = -1L
    private val composerProjections = ComposerProjectionStore()

    val composerMetrics: ComposerMetrics get() = composerProjections.metrics(settledSteps, toolTimings.values)

    /** Connection generations have incomparable Host projection watermarks. */
    fun clearComposerProjections() = composerProjections.clear()

    val usage get() = Usage(turns, steps, inputTokens, cacheRead, outputTokens)
    fun stepRecords(): List<StepRecord> = settledSteps.toList()
    fun turnRecords(): List<TurnRecord> = turnFacts.values.sortedBy { it.turn }
    fun requestHeaders(): List<RequestHeaderFact> = headerFacts.toList()
    fun todos(): List<TodoItem> = todos
    val planMode: Boolean get() = planActive

    fun items(): List<Item> = committed + (live?.items() ?: emptyList())

    fun queueMessages(): List<QueuedPrompt> {
        val host = inbox.getValue("next-turn").filterNot { message ->
            val rpc = message["source"]["rpcId"].str()
            isRetired(message) || (rpc != null && localEchoes[rpc]?.queued == false)
        }.map(::queuedPrompt)
        val admitted = inbox.values.flatten().mapNotNull { it["source"]["rpcId"].str() }.toSet()
        return host + localEchoes.values.filter { it.queued && it.prompt.id !in admitted }.map { it.prompt }
    }

    /** Apply one follow frame; returns true when visible state changed. */
    fun apply(frame: JsonElement?): Boolean {
        when (frame["type"].str()) {
            "snapshot" -> {
                val keep = localEchoes.toMap()
                val knownTurns = turnFacts.toMap()
                reset()
                localEchoes.putAll(keep)
                records(frame["records"])
                for ((id, echo) in localEchoes.toMap()) {
                    if (id in retiredRequests || userByRpc[id]?.let { !(committed[it] as Item.User).pending } == true) {
                        localEchoes.remove(id)
                    } else if (!echo.queued) addEcho(echo.prompt)
                }
                readProjections(frame["projections"], frame["cursor"].long() ?: cursor)
                cursor = frame["cursor"].long() ?: cursor
                // A replacement window can omit the admitted user/message entirely.
                // Only retire acknowledged echoes covered by a newer inbox baseline;
                // an unacknowledged send or a claim at this exact watermark still waits.
                val inboxBaseline = frame["projections"]["values"]["inbox"]
                val inboxBaselineSeq = frame["projections"]["asOfSeq"].long() ?: cursor
                if (inboxBaseline is JsonObject) {
                    for ((id, echo) in localEchoes.toMap()) {
                        val receipt = echo.receipt ?: continue
                        if (receipt.seq < inboxBaselineSeq && inbox.values.none { messages ->
                                messages.any { it["source"]["rpcId"].str() == id }
                            }) discardEcho(id)
                    }
                }
                hasMore = frame["hasMore"].bool() ?: false
                firstSeq = minSeq(frame["records"])
                baseline(frame["assistantStream"])
                // A replacement tail snapshot can omit the start already received live.
                // Keep endpoints only for identities still present, not old invisible turns.
                turnFacts.replaceAll { turn, fact ->
                    val known = knownTurns[turn]?.takeIf { (it.startSeq ?: -1) <= cursor }
                    val earlier = if (known != null && known.endSeq != null && known.endSeq > cursor) {
                        known.copy(endSeq = null, endedAt = null, endReason = null)
                    } else known
                    earlier?.let { fact.withEarlier(it) } ?: fact
                }
            }
            "resume" -> {
                records(frame["records"])
                readProjections(frame["projections"], frame["cursor"].long() ?: cursor)
                cursor = maxOf(cursor, frame["cursor"].long() ?: cursor)
                baseline(frame["assistantStream"])
            }
            "event" -> event(frame["event"])
            "projection" -> {
                val key = frame["key"].str() ?: return false
                val seq = frame["seq"].long() ?: return false
                if (key == "plan") {
                    if (seq < planProjectionSeq) return false
                    planProjectionSeq = seq
                    planActive = frame["value"]["active"].bool() ?: false
                } else if (key == "inbox") {
                    return readInbox(frame["value"], seq)
                } else return composerProjections.apply(key, frame["value"], seq)
            }
            "assistant-stream" -> stream(frame["frame"])
            else -> return false
        }
        return true
    }

    /**
     * Prepend older `history.page` records (seqs below [firstSeq]), same pattern as
     * desktop dsh loading earlier turns. Updates [hasMore] from the page reply.
     */
    fun prepend(records: JsonElement?, pageHasMore: Boolean): Boolean {
        val filtered = records.arr().orEmpty().filter { r ->
            val seq = r["event"]["seq"].long() ?: return@filter false
            firstSeq < 0 || seq < firstSeq
        }
        hasMore = pageHasMore
        if (filtered.isEmpty()) return false

        val prior = TranscriptReducer()
        val max = filtered.mapNotNull { it["event"]["seq"].long() }.maxOrNull() ?: 0L
        prior.apply(buildJsonObject {
            put("type", JsonPrimitive("snapshot"))
            put("cursor", JsonPrimitive(max))
            put("hasMore", JsonPrimitive(false))
            put("records", JsonArray(filtered))
            put("projections", buildJsonObject {})
        })
        for ((turn, earlier) in prior.turnFacts) {
            val later = turnFacts[turn]
            turnFacts[turn] = later?.withEarlier(earlier) ?: earlier
        }
        for ((id, earlier) in prior.toolTimings) {
            val later = toolTimings[id]
            if (later == null) {
                toolTimings[id] = earlier
            } else {
                later.callStartedAt = later.callStartedAt ?: earlier.callStartedAt
                later.resultAt = earlier.resultAt ?: later.resultAt
            }
        }
        val older = prior.items()
        val olderSteps = prior.stepRecords()
        val shift = older.size
        if (shift > 0) {
            committed.addAll(0, older)
            rebuildIndexes()
            // User identities are rebuilt along with tool/compaction indexes.
        }
        if (olderSteps.isNotEmpty()) {
            settledSteps.addAll(0, olderSteps)
        }
        val olderHeaders = prior.requestHeaders()
        if (olderHeaders.isNotEmpty()) {
            headerFacts.addAll(0, olderHeaders)
        }
        val min = filtered.mapNotNull { it["event"]["seq"].long() }.minOrNull()
        if (min != null) firstSeq = if (firstSeq < 0) min else minOf(firstSeq, min)
        return shift > 0 || olderSteps.isNotEmpty() || olderHeaders.isNotEmpty() ||
            prior.toolTimings.isNotEmpty() || prior.turnFacts.isNotEmpty()
    }

    private fun rebuildIndexes() {
        toolIndex.clear()
        compactIndex.clear()
        userByRpc.clear()
        userByMessage.clear()
        committed.forEachIndexed { i, item ->
            when (item) {
                is Item.Tool -> if (item.callId.isNotEmpty()) toolIndex[item.callId] = i
                is Item.Compacted -> compactIndex[item.compactionId] = i
                is Item.User -> {
                    item.requestId?.let { userByRpc[it] = i }
                    item.messageId?.let { userByMessage[it] = i }
                }
                else -> {}
            }
        }
    }

    private fun minSeq(arr: JsonElement?): Long {
        val seqs = arr.arr()?.mapNotNull { it["event"]["seq"].long() }.orEmpty()
        return seqs.minOrNull() ?: -1
    }

    private fun reset() {
        committed.clear(); toolIndex.clear(); compactIndex.clear(); approvalCall.clear(); userByRpc.clear(); localEchoes.clear()
        userByMessage.clear()
        inbox.values.forEach { it.clear() }
        inboxSeq = -1L
        toolTimings.clear()
        turnFacts.clear()
        stepStartTimes.clear(); settledSteps.clear(); headerFacts.clear(); toolsByName.clear()
        toolsCatalogJson = null; previousToolsCatalogJson = null; lastSystemPromptText = null
        systemPromptSeen = false; live = null
        openRetry = null; openMaxRetries = null; openRetryDelayMs = null; openLastAttemptError = null
        latestRequestConfigJson = null; latestRequestProvider = null; latestRequestModel = null
        cursor = -1; firstSeq = -1; hasMore = false
        turns = 0; steps = 0; openTurn = null; openStep = null
        inputTokens = 0; cacheRead = 0; outputTokens = 0; running = false
        todos = emptyList()
        planActive = false
        planProjectionSeq = -1L
    }

    private fun readProjections(p: JsonElement?, atSeq: Long) {
        composerProjections.baseline(p, atSeq)
        val values = p["values"]
        values["title"].str()?.let { title = it }
        values["inbox"]?.let { readInbox(it, p["asOfSeq"].long() ?: atSeq, baseline = true) }
        val usage = values["tokenUsage"]
        if (usage is JsonObject) {
            val uncached = usage["uncachedInputTokens"].long() ?: 0
            val cr = usage["cacheReadTokens"].long() ?: 0
            cacheRead = cr; inputTokens = uncached + cr + (usage["cacheWriteTokens"].long() ?: 0)
            outputTokens = usage["outputTokens"].long() ?: 0
        }
        values["todos"].arr()?.let { arr ->
            todos = arr.mapNotNull { row ->
                val content = row["content"].str()?.trim().orEmpty()
                val status = row["status"].str() ?: return@mapNotNull null
                if (content.isEmpty()) null else TodoItem(content, status)
            }
        }
        values["plan"]?.let { plan ->
            val seq = p["asOfSeq"].long() ?: atSeq
            if (seq >= planProjectionSeq) {
                planProjectionSeq = seq
                planActive = plan["active"].bool() ?: false
            }
        }
    }

    private fun records(arr: JsonElement?) { arr.arr()?.forEach { r -> if (r["type"].str() == "event") event(r["event"]) } }

    private fun baseline(b: JsonElement?) {
        val a = b["activeAttempt"].obj() ?: return
        val l = LiveAttempt(
            a["attemptId"].str() ?: "live",
            a["turn"].long()?.toInt() ?: openTurn,
            a["step"].long()?.toInt() ?: openStep,
        )
        a["stream"].arr()?.forEach { rec -> l.record(rec) }
        live = l
        openTurn = l.turn ?: openTurn
        openStep = l.step ?: openStep
        l.turn?.let { turnFacts.getOrPut(it) { TurnRecord(it) } }
        running = true
    }

    private fun event(e: JsonElement?) {
        if (e == null || e["ignorable"].bool() == true) return
        val seq = e["seq"].long() ?: return
        if (seq <= cursor) return
        cursor = seq
        val data = e["data"]
        when (e["type"].str()) {
            "turn/start" -> {
                turns++
                running = true
                openTurn = data["turn"].long()?.toInt()
                openStep = null
                openTurn?.let { turn ->
                    val fact = turnFacts[turn] ?: TurnRecord(turn)
                    turnFacts[turn] = fact.copy(startSeq = seq, startedAt = e["time"].long())
                }
            }
            "turn/end" -> {
                running = false; live = null
                val reason = data["reason"]
                val turn = data["turn"].long()?.toInt() ?: openTurn
                turn?.let {
                    val fact = turnFacts[it] ?: TurnRecord(it)
                    turnFacts[it] = fact.copy(
                        endSeq = seq, endedAt = e["time"].long(), endReason = reason["kind"].str(),
                    )
                }
                openTurn = null
                openStep = null
                when (reason["kind"].str()) {
                    "error" -> {
                        val err = reason["error"]
                        val code = err["code"].str()
                        val msg = when (code) {
                            "AUTH_ERROR", "INVALID_API_KEY" -> "API 密钥无效"
                            "QUOTA_EXCEEDED", "RATE_LIMIT" -> "当前请求的额度已用尽"
                            else -> err["message"].str()?.takeIf { it.isNotBlank() } ?: "本轮运行失败"
                        }
                        committed += Item.Notice("te$seq", msg, turn = turn)
                    }
                    "aborted" -> {
                        val hook = reason["reason"]
                        if (hook["kind"].str() == "hook" && hook["reason"].str() == "deepseek-account/signed-out") {
                            committed += Item.Notice("te$seq", "任务已因退出 DeepSeek 登录而停止。", turn = turn)
                        }
                    }
                    "max-tokens", "max_tokens" -> {
                        committed += Item.Notice("mt$seq", "已达到输出 token 上限", turn = turn)
                        committed += Item.Notice("mth$seq", "回答被截断，已有输出保留在对话中。发送“继续”可让模型接着输出。", turn = turn)
                    }
                }
            }
            "todo/write" -> {
                todos = data["todos"].arr().orEmpty().mapNotNull { row ->
                    val content = row["content"].str()?.trim().orEmpty()
                    val status = row["status"].str() ?: return@mapNotNull null
                    if (content.isEmpty()) null else TodoItem(content, status)
                }
            }
            "plan/mode" -> {
                if (seq >= planProjectionSeq) {
                    planProjectionSeq = seq
                    planActive = data["active"].bool() ?: planActive
                }
            }
            "step/start" -> {
                val ownerTurn = data["turn"].long()?.toInt() ?: openTurn
                val turn = ownerTurn ?: turns
                val step = data["step"].long()?.toInt() ?: (steps + 1)
                openTurn = ownerTurn
                openStep = step
                ownerTurn?.let { turnFacts.getOrPut(it) { TurnRecord(it) } }
                openRetry = null; openMaxRetries = null; openRetryDelayMs = null; openLastAttemptError = null
                e["time"].long()?.let { stepStartTimes["$turn:$step"] = it }
            }
            // Desktop ui-trajectory: index EpochHeader.tools + config for Request inspector.
            "request/header" -> indexRequestHeader(e, data, seq)
            "llm/retry" -> {
                // Trajectory Request inspector: keep retry facts on the open step.
                openRetry = data["retry"].long()?.toInt() ?: ((openRetry ?: 0) + 1)
                openMaxRetries = data["maxRetries"].long()?.toInt()
                    ?: data["maximum"].long()?.toInt()
                    ?: openMaxRetries
                openRetryDelayMs = data["delayMs"].long() ?: data["retryDelayMs"].long() ?: openRetryDelayMs
                openLastAttemptError = data["error"]["message"].str()
                    ?: data["error"]["reason"].str()
                    ?: data["error"].str()
                    ?: openLastAttemptError
                // Chat notice (desktop message.retry.*).
                val retry = openRetry
                val maximum = openMaxRetries
                val seconds = openRetryDelayMs?.let { (it / 1000).coerceAtLeast(0) }
                    ?: data["delaySeconds"].long()
                val label = "等待重试模型请求"
                val text = if (retry != null && maximum != null && seconds != null) {
                    "$label（$retry/$maximum） · ${seconds}s"
                } else label
                committed += Item.Notice("n$seq", text, turn = openTurn)
            }
            "user/message" -> {
                val msg = if (data["content"] != null) data else data["message"]
                retireMessage(msg)
                if (isTyped(msg)) upsertUser(msg, "u$seq", pending = false, at = e["time"].long(), seq = seq)
                else upsertContext(msg, "x$seq", at = e["time"].long())
            }
            "developer/message" -> {
                val msg = data["message"] ?: data
                upsertContext(msg, "d$seq", at = e["time"].long())
            }
            "system/message" -> upsertSystemPrompt(e, data, seq)
            "agent/inbox/spliced" -> spliceInbox(data, seq, e["time"].long())
            "assistant/message" -> {
                steps++
                val firstTokenFromLive = live?.firstTokenTime
                val ownerTurn = data["turn"].long()?.toInt() ?: live?.turn ?: openTurn
                val turn = ownerTurn ?: turns
                val step = data["step"].long()?.toInt() ?: live?.step ?: openStep ?: steps
                live = null
                val messageTime = e["time"].long()
                ownerTurn?.let { turnFacts.getOrPut(it) { TurnRecord(it) } }
                val stepStartTime = stepStartTimes.remove("$turn:$step")
                val timing = reasoningTiming(data["stream"])
                val firstTokenTime = firstTokenFromLive
                    ?: assistantStreamFirstTokenTime(data["stream"])
                val replyParts = ArrayList<String>()
                val reasoningParts = ArrayList<String>()
                val toolCallIds = ArrayList<String>()
                val messageId = data["message"]["id"].str()
                data["message"]["content"].arr().orEmpty().forEachIndexed { i, b ->
                    when (b["type"].str()) {
                        "reasoning" -> {
                            val text = b["text"].str().orEmpty()
                            reasoningParts += text
                            committed += Item.Reasoning(
                                "r$seq.$i", text, timing, live = false,
                                turn = ownerTurn, step = step, seq = seq,
                            )
                        }
                        "text" -> b["text"].str()?.takeIf { it.isNotBlank() }?.let {
                            replyParts += it
                            committed += Item.Text(
                                "t$seq.$i", it, live = false, at = messageTime, seq = seq, messageId = messageId,
                                turn = ownerTurn, step = step,
                            )
                        }
                        "tool-call" -> {
                            val id = b["id"].str() ?: "c$seq.$i"
                            toolCallIds += id
                            upsertTool(
                                id, b["name"].str().orEmpty(), b["arguments"].str().orEmpty(), e["time"].long(),
                                turn = ownerTurn, step = step,
                            )
                        }
                    }
                }
                val u = data["usage"]
                val stepUsage = StepUsage(
                    inputTokens = u["inputTokens"].long(),
                    cacheReadTokens = u["cacheReadTokens"].long(),
                    cacheWriteTokens = u["cacheWriteTokens"].long(),
                    outputTokens = u["outputTokens"].long(),
                    reasoningTokens = u["reasoningTokens"].long(),
                )
                u?.let {
                    outputTokens += it["outputTokens"].long() ?: 0
                    inputTokens += (it["inputTokens"].long() ?: 0) + (it["cacheReadTokens"].long() ?: 0) + (it["cacheWriteTokens"].long() ?: 0)
                    cacheRead += it["cacheReadTokens"].long() ?: 0
                }
                val source = data["message"]["source"]
                val interrupted = data["interrupted"].bool() == true
                settledSteps += StepRecord(
                    seq = seq,
                    turn = turn,
                    step = step,
                    stepStartTime = stepStartTime,
                    messageTime = messageTime,
                    firstTokenTime = firstTokenTime,
                    replyText = replyParts.joinToString("\n\n"),
                    reasoningText = reasoningParts.joinToString("\n\n"),
                    usage = stepUsage,
                    provider = source["provider"].str() ?: latestRequestProvider,
                    model = source["model"].str() ?: latestRequestModel,
                    toolCallIds = toolCallIds,
                    interrupted = interrupted,
                    requestConfigJson = latestRequestConfigJson,
                    retry = openRetry,
                    maxRetries = openMaxRetries,
                    retryDelayMs = openRetryDelayMs,
                    lastAttemptError = openLastAttemptError,
                )
                openRetry = null; openMaxRetries = null; openRetryDelayMs = null; openLastAttemptError = null
                // Chat locale message.stopped = 已停止. Trajectory marks interrupted on StepRecord
                // (assistant cell isError) — do not invent a 系统 Context/System row here.
                if (interrupted) committed += Item.Notice("i$seq", "已停止", turn = ownerTurn)
            }
            "tool/call" -> {
                val id = data["callId"].str() ?: "c$seq"
                val time = e["time"].long()
                toolTimings.getOrPut(id) { ToolCallTiming() }.callStartedAt = time
                upsertTool(
                    id,
                    data["name"].str().orEmpty(),
                    data["arguments"].str().orEmpty(),
                    time,
                    turn = data["turn"].long()?.toInt() ?: openTurn,
                    step = data["step"].long()?.toInt() ?: openStep,
                )
            }
            "tool/result" -> {
                val id = data["message"]["toolCallId"].str()
                    ?: data["message"]["source"]["callId"].str()
                    ?: return
                val timing = toolTimings.getOrPut(id) { ToolCallTiming() }
                timing.resultAt = timing.resultAt ?: e["time"].long()
                val text = data["message"]["content"].arr().orEmpty().filter { it["type"].str() == "text" }.joinToString("\n") { it["text"].str().orEmpty() }
                val err = data["error"]
                val isErr = err != null || data["message"]["isError"].bool() == true
                updateTool(id) { it.copy(status = if (isErr) ToolStatus.Error else ToolStatus.Ok, result = text, error = err?.let { x -> x["reason"].str() ?: x["code"].str() }, endedAt = e["time"].long()) }
            }
            // Desktop PTC subtools (`trajectory-tool-definition` dispatch edges).
            "tool/ptc-dispatch-start" -> upsertPtcDispatch(data, e["time"].long(), settled = false)
            "tool/ptc-dispatch" -> upsertPtcDispatch(data, e["time"].long(), settled = true)
            "compaction/start" -> upsertCompactionStart(data, e["time"].long())
            "compaction/summary" -> upsertCompactionSummary(data)
            "compaction/end" -> upsertCompactionEnd(data, e["time"].long())
            "approval/asked" -> data["callId"].str()?.let { id ->
                data["id"].str()?.let { approvalCall[it] = id }
                updateTool(id) { it.copy(status = ToolStatus.AwaitingApproval) }
            }
            "approval/decided" -> {
                val outcome = data["outcome"].str()
                val callId = data["id"].str()?.let { approvalCall.remove(it) }
                val known = callId?.let { toolIndex[it] }
                (known ?: committed.indices.reversed().firstOrNull { (committed[it] as? Item.Tool)?.status == ToolStatus.AwaitingApproval })?.let { i ->
                    val t = committed[i] as Item.Tool
                    committed[i] = if (outcome == "allowed-once") t.copy(status = ToolStatus.Running) else t.copy(status = ToolStatus.Error, error = outcome)
                }
            }
            "llm/retry-started" -> committed += Item.Notice("ns$seq", "正在重试模型请求")
            "deliverables/presented" -> {
                val files = data["files"].arr().orEmpty().mapNotNull { f ->
                    val path = f["path"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    DeliverableFile(path, f["description"].str())
                }
                if (files.isNotEmpty()) {
                    committed += Item.Deliverable(
                        key = "dl$seq",
                        files = files,
                        turn = data["turn"].long()?.toInt(),
                    )
                }
            }
            "workspace/changes" -> {
                val turn = data["turn"].long()?.toInt() ?: return
                // Event carries only turn; Host summary may enrich count/name later.
                val count = data["total"].long()?.toInt()
                    ?: data["count"].long()?.toInt()
                    ?: data["files"].arr()?.size
                    ?: 0
                val name = data["files"].arr()?.singleOrNull()?.let { f ->
                    f["display"].str() ?: f["path"].str()?.substringAfterLast('/')
                }
                committed += Item.Changes(key = "ch$seq", turn = turn, count = count, name = name, seq = seq)
            }
        }
    }

    /** Only what a person typed: dsh also injects user-role context (runtime-context, time, skills, memory plugins). */
    private fun isTyped(msg: JsonElement?): Boolean {
        val kind = msg["source"]["kind"].str()
        if (kind != null) return kind == "user"
        val text = msg["content"].arr()?.firstOrNull { it["type"].str() == "text" }?.get("text").str().orEmpty()
        return msg["source"] == null && !text.trimStart().startsWith("<")
    }

    private fun messageText(msg: JsonElement?): String {
        val blocks = msg["content"].arr().orEmpty()
        return blocks.filter { it["type"].str() == "text" }.joinToString("\n") { it["text"].str().orEmpty() }
    }

    private fun upsertContext(msg: JsonElement?, key: String, at: Long?) {
        val text = messageText(msg)
        if (text.isBlank()) return
        committed += Item.Context(
            key, text,
            sourceKind = msg["source"]["kind"].str(),
            at = at,
            messageSourceJson = prettySource(msg["source"]),
        )
    }

    /**
     * Desktop ui-trajectory emits a 系统 cell from `system/message` appends
     * (`初始系统提示词` / `系统提示词已更新`). Replacements without append are ignored.
     */
    private fun upsertSystemPrompt(e: JsonElement?, data: JsonElement?, seq: Long) {
        val surface = e["surfaceOp"]
        // Desktop only publishes 系统 cells for append surface ops (object replace = history rewrite).
        if (surface is JsonObject) return
        if (surface != null && surface.str() != null && surface.str() != "append") return
        val msg = data["message"] ?: data
        val text = messageText(msg)
        if (text.isBlank()) return
        val label = if (systemPromptSeen) "系统提示词已更新" else "初始系统提示词"
        val previous = lastSystemPromptText.takeIf { systemPromptSeen }
        systemPromptSeen = true
        lastSystemPromptText = text
        committed += Item.System(
            "sys$seq", label, text, at = e["time"].long(),
            previousText = previous,
            toolsJson = toolsCatalogJson,
            previousToolsJson = previousToolsCatalogJson.takeIf { previous != null },
        )
    }

    private fun imageRefsOf(blocks: List<JsonElement>): List<ImageRef> = blocks.mapNotNull { b ->
        if (b["type"].str() != "image") return@mapNotNull null
        val att = b["attachment"]
        val id = att["attachmentId"].str() ?: b["attachmentId"].str() ?: return@mapNotNull null
        ImageRef(id, att["mediaType"].str() ?: b["mediaType"].str())
    }

    private fun upsertUser(msg: JsonElement?, key: String, pending: Boolean, at: Long? = null, seq: Long? = null) {
        val blocks = msg["content"].arr().orEmpty()
        val text = blocks.filter { it["type"].str() == "text" }.joinToString("\n") { it["text"].str().orEmpty() }
        val imageRefs = imageRefsOf(blocks)
        val images = imageRefs.size.takeIf { it > 0 } ?: blocks.count { it["type"].str() == "image" }
        val sourceJson = prettySource(msg["source"])
        val rpc = msg["source"]["rpcId"].str()
        val messageId = msg["id"].str()
        val idx = rpc?.let { userByRpc[it] } ?: messageId?.let { userByMessage[it] }
        if (idx != null) {
            val old = committed[idx] as Item.User
            committed[idx] = old.copy(
                text = text, images = images, pending = pending && old.pending,
                at = at ?: old.at,
                messageSourceJson = sourceJson ?: old.messageSourceJson,
                seq = seq ?: old.seq,
                imageRefs = imageRefs.ifEmpty { old.imageRefs },
                requestId = rpc ?: old.requestId,
                messageId = messageId ?: old.messageId,
            )
            if (rpc != null) userByRpc[rpc] = idx
            if (messageId != null) userByMessage[messageId] = idx
        } else {
            if (rpc != null) userByRpc[rpc] = committed.size
            if (messageId != null) userByMessage[messageId] = committed.size
            committed += Item.User(
                key, text, images, pending, at = at, messageSourceJson = sourceJson, seq = seq, imageRefs = imageRefs,
                requestId = rpc, messageId = messageId,
            )
        }
    }

    private fun prettySource(source: JsonElement?): String? {
        val obj = source.obj() ?: return null
        return runCatching {
            PrettyToolJson.encodeToString(JsonElement.serializer(), obj)
        }.getOrNull()
    }

    /** The same request identity is sent on the wire; text is never a deduplication key. */
    fun echo(
        text: String,
        requestId: String = UUID.randomUUID().toString(),
        mode: String = "queue",
        images: Int = 0,
        files: Int = 0,
    ) {
        if (requestId in localEchoes || requestId in userByRpc || requestId in retiredRequests) return
        val prompt = QueuedPrompt(requestId, text, images, files, sending = true)
        val queued = running && mode == "queue"
        localEchoes[requestId] = LocalEcho(prompt, queued)
        if (!queued) addEcho(prompt)
    }

    private fun addEcho(prompt: QueuedPrompt) {
        if (prompt.id in userByRpc) return
        userByRpc[prompt.id] = committed.size
        committed += Item.User("local-${prompt.id}", prompt.text, prompt.images, pending = true, requestId = prompt.id)
    }

    fun discardEcho(requestId: String) {
        if (localEchoes.remove(requestId) == null) return
        val index = userByRpc[requestId]
        if (index != null && (committed[index] as Item.User).pending) {
            committed.removeAt(index)
            rebuildIndexes()
        }
    }

    private fun queuedPrompt(message: JsonElement): QueuedPrompt {
        val blocks = message["content"].arr().orEmpty()
        return QueuedPrompt(
            id = message["id"].str() ?: message["source"]["rpcId"].str().orEmpty(),
            text = blocks.filter { it["type"].str() == "text" }.joinToString("\n") { it["text"].str().orEmpty() },
            images = blocks.count { it["type"].str() == "image" },
            files = blocks.count { it["type"].str() == "file" },
        )
    }

    private fun isRetired(message: JsonElement?): Boolean =
        message["id"].str()?.let { it in retiredMessages } == true ||
            message["source"]["rpcId"].str()?.let { it in retiredRequests } == true

    private fun retireMessage(message: JsonElement?) {
        message["id"].str()?.let { retiredMessages += it }
        message["source"]["rpcId"].str()?.let { retiredRequests += it; localEchoes.remove(it) }
        // Keep the raw inbox positions until the corresponding durable splice arrives.
    }

    private fun readInbox(value: JsonElement?, seq: Long, baseline: Boolean = false): Boolean {
        if (seq < inboxSeq || (!baseline && seq == inboxSeq) || value !is JsonObject) return false
        inboxSeq = seq
        for ((target, messages) in inbox) {
            val previous = messages.toList()
            messages.clear()
            messages.addAll(value[target].arr().orEmpty())
            for (old in previous) {
                if (messages.none { sameMessage(it, old) }) {
                    // A projection alone cannot distinguish claim from cancellation.
                    // Keep transcript-owned local submissions until their durable event.
                    old["source"]["rpcId"].str()?.let { rpc ->
                        if (localEchoes[rpc]?.queued == true) localEchoes.remove(rpc)
                    }
                    if (target == "next-step") removePendingUser(old, preserveLocal = true)
                }
            }
            messages.forEachIndexed { index, message -> observeInboxMessage(target, message, seq, index) }
        }
        return true
    }

    private fun sameMessage(a: JsonElement, b: JsonElement): Boolean {
        val id = a["id"].str()
        if (id != null) return id == b["id"].str()
        val rpc = a["source"]["rpcId"].str()
        return rpc != null && rpc == b["source"]["rpcId"].str()
    }

    private fun observeInboxMessage(target: String, message: JsonElement, seq: Long, index: Int, at: Long? = null) {
        if (isRetired(message)) return
        val rpc = message["source"]["rpcId"].str()
        if (rpc != null && localEchoes[rpc]?.queued == true) localEchoes.remove(rpc)
        if (rpc != null) localEchoes[rpc]?.let { echo ->
            localEchoes[rpc] = echo.copy(receipt = InboxReceipt(target, index, seq))
        }
        if (target == "next-step" && isTyped(message)) {
            upsertUser(message, "inbox-${queuedPrompt(message).id}", pending = true, at = at, seq = seq)
        }
    }

    private fun spliceInbox(data: JsonElement?, seq: Long, at: Long?) {
        val target = data["target"].str() ?: return
        val messages = inbox[target] ?: return
        val start = data["start"].long()?.toInt() ?: return
        val count = data["removedCount"].long()?.toInt() ?: 0
        val inserted = data["inserted"].arr().orEmpty()
        for ((rpc, echo) in localEchoes.toMap()) {
            val receipt = echo.receipt ?: continue
            val index = receipt.index ?: continue
            if (receipt.target != target || receipt.seq >= seq) continue
            val removed = index >= start && index < start + count
            if (removed && data["outcome"].str() == "canceled") {
                discardEcho(rpc)
                retiredRequests += rpc
            } else {
                localEchoes[rpc] = echo.copy(receipt = InboxReceipt(
                    target, if (removed) null else if (index < start) index else index + inserted.size - count, seq,
                ))
            }
        }
        if (seq <= inboxSeq) return
        // A tail snapshot may omit the insertions; its full projection repairs the list.
        if (start < 0 || count < 0 || start > messages.size || count > messages.size - start) return
        inboxSeq = seq
        val removed = messages.subList(start, start + count).toList()
        messages.subList(start, start + count).clear()
        messages.addAll(start, inserted)
        for (message in removed) {
            if (data["outcome"].str() == "canceled") {
                message["source"]["rpcId"].str()?.let(::discardEcho)
                removePendingUser(message)
                retireMessage(message)
            } else {
                // Claimed next-turn inputs remain absent until user/message; next-step
                // and idle local echoes retain their existing transcript placement.
                message["id"].str()?.let { retiredMessages += it }
                message["source"]["rpcId"].str()?.let { rpc ->
                    if (localEchoes[rpc]?.queued == true) localEchoes.remove(rpc)
                }
            }
        }
        inserted.forEachIndexed { index, message ->
            message["id"].str()?.let { retiredMessages.remove(it) }
            message["source"]["rpcId"].str()?.let { retiredRequests.remove(it) }
            observeInboxMessage(target, message, seq, start + index, at)
        }
    }

    private fun removePendingUser(message: JsonElement, preserveLocal: Boolean = false) {
        val rpc = message["source"]["rpcId"].str()
        if (preserveLocal && rpc in localEchoes) return
        val index = rpc?.let { userByRpc[it] } ?: message["id"].str()?.let { userByMessage[it] } ?: return
        if ((committed[index] as Item.User).pending) {
            committed.removeAt(index)
            rebuildIndexes()
        }
    }

    /**
     * Index `request/header` → tools + config (desktop `indexTools` / `captureSchemas` /
     * Request inspector `requestConfig`).
     */
    private fun indexRequestHeader(e: JsonElement?, data: JsonElement?, seq: Long) {
        val header = data["header"] ?: data
        val tools = header["tools"].arr()
        if (tools != null) {
            val pretty = ArrayList<JsonElement>()
            for (tool in tools) {
                val name = tool["name"].str() ?: continue
                val schema = prettyToolSchema(tool) ?: continue
                toolsByName[name] = schema
                pretty += PocketJson.parseToJsonElement(schema)
            }
            if (pretty.isNotEmpty()) {
                previousToolsCatalogJson = toolsCatalogJson
                toolsCatalogJson = runCatching {
                    PrettyToolJson.encodeToString(JsonElement.serializer(), JsonArray(pretty))
                }.getOrNull()
            }
        }
        val config = header["config"]
        val configJson = config?.let {
            runCatching { PrettyToolJson.encodeToString(JsonElement.serializer(), it) }.getOrNull()
        }
        val provider = config["provider"].str()
        val model = config["model"].str()
        if (configJson != null) latestRequestConfigJson = configJson
        if (provider != null) latestRequestProvider = provider
        if (model != null) latestRequestModel = model
        headerFacts += RequestHeaderFact(
            seq = seq,
            time = e["time"].long(),
            turn = data["turn"].long()?.toInt() ?: openTurn,
            step = data["step"].long()?.toInt() ?: openStep,
            provider = provider,
            model = model,
            configJson = configJson,
            reason = data["reason"].str(),
        )
    }

    private fun upsertTool(
        id: String,
        name: String,
        args: String,
        time: Long?,
        turn: Int? = null,
        step: Int? = null,
        parentCallId: String? = null,
    ) {
        val schema = name.takeIf { it.isNotEmpty() }?.let { toolsByName[it] }
        val i = toolIndex[id]
        if (i != null) {
            val t = committed[i] as Item.Tool
            val resolvedName = name.ifEmpty { t.name }
            committed[i] = t.copy(
                name = resolvedName,
                arguments = args.ifEmpty { t.arguments },
                schemaJson = schema ?: toolsByName[resolvedName] ?: t.schemaJson,
                turn = turn ?: t.turn,
                step = step ?: t.step,
                parentCallId = parentCallId ?: t.parentCallId,
            )
        } else {
            toolIndex[id] = committed.size
            committed += Item.Tool(
                "k$id", id, name, args, ToolStatus.Running,
                startedAt = time, schemaJson = schema, turn = turn, step = step,
                parentCallId = parentCallId,
            )
        }
    }

    private fun updateTool(id: String, f: (Item.Tool) -> Item.Tool) {
        toolIndex[id]?.let { committed[it] = f(committed[it] as Item.Tool) }
    }

    /** Desktop `tool/ptc-dispatch-start` / `tool/ptc-dispatch` → subtool cells. */
    private fun upsertPtcDispatch(data: JsonElement?, time: Long?, settled: Boolean) {
        val subCallId = data["subCallId"].str() ?: return
        val parentCallId = data["parentCallId"].str() ?: return
        val name = data["name"].str().orEmpty()
        val argsEl = data["arguments"]
        val args = when {
            argsEl is JsonPrimitive -> argsEl.str().orEmpty()
            argsEl != null -> runCatching {
                PrettyToolJson.encodeToString(JsonElement.serializer(), argsEl)
            }.getOrDefault("{}")
            else -> "{}"
        }
        upsertTool(
            subCallId, name, args, time,
            turn = openTurn, step = openStep, parentCallId = parentCallId,
        )
        if (!settled) return
        val text = data["content"].arr().orEmpty()
            .filter { it["type"].str() == "text" }
            .joinToString("\n") { it["text"].str().orEmpty() }
        val err = data["error"]
        val isErr = data["isError"].bool() == true || err != null
        updateTool(subCallId) {
            it.copy(
                status = if (isErr) ToolStatus.Error else ToolStatus.Ok,
                result = text.ifBlank { null },
                error = err?.let { x -> x["reason"].str() ?: x["code"].str() },
                endedAt = time,
                parentCallId = parentCallId,
            )
        }
    }

    private fun upsertCompactionStart(data: JsonElement?, time: Long?) {
        val id = data["compactionId"].str() ?: return
        if (compactIndex.containsKey(id)) return
        compactIndex[id] = committed.size
        committed += Item.Compacted(
            key = "cmp$id",
            compactionId = id,
            status = CompactionStatus.Running,
            startedAt = time,
            turn = data["turn"].long()?.toInt() ?: openTurn,
        )
    }

    private fun upsertCompactionSummary(data: JsonElement?) {
        val id = data["compactionId"].str() ?: return
        val summary = data["summary"].str()
            ?: data["message"]["content"].arr().orEmpty()
                .filter { it["type"].str() == "text" }
                .joinToString("\n") { it["text"].str().orEmpty() }
                .ifBlank { null }
        compactIndex[id]?.let { i ->
            val c = committed[i] as Item.Compacted
            committed[i] = c.copy(summary = summary ?: c.summary)
        }
    }

    private fun upsertCompactionEnd(data: JsonElement?, time: Long?) {
        val id = data["compactionId"].str() ?: return
        val err = data["error"]
        val interrupted = err?.let { it["code"].str() == "interrupted" || it["reason"].str() == "interrupted" } == true
            || data["interrupted"].bool() == true
        val isErr = err != null
        compactIndex[id]?.let { i ->
            val c = committed[i] as Item.Compacted
            committed[i] = c.copy(
                status = if (isErr) CompactionStatus.Error else CompactionStatus.Ok,
                error = err?.let { x -> x["reason"].str() ?: x["message"].str() ?: x["code"].str() },
                interrupted = interrupted,
                endedAt = time,
            )
        }
    }

    private fun stream(f: JsonElement?) {
        when (f["type"].str()) {
            "start" -> {
                live = LiveAttempt(
                    f["attemptId"].str() ?: "live",
                    f["turn"].long()?.toInt() ?: openTurn,
                    f["step"].long()?.toInt() ?: openStep,
                )
                openTurn = live?.turn ?: openTurn
                openStep = live?.step ?: openStep
                live?.turn?.let { turnFacts.getOrPut(it) { TurnRecord(it) } }
                running = true
            }
            "chunk" -> {
                val l = live ?: LiveAttempt(f["attemptId"].str() ?: "live", openTurn, openStep).also { live = it }
                l.chunk(f["chunk"], f["time"].long())
            }
            // Cancel / abort ends the attempt with abandoned; clear running even if turn/end is delayed.
            "end" -> if (f["outcome"]["kind"].str() == "abandoned") { live = null; running = false }
        }
    }

    /** Host session status reported idle (e.g. after `cancel`); keep reducer in sync with chat UI. */
    fun markIdle() { running = false; live = null }

    companion object {
        /** Reasoning duration from compacted stream records (time0 + Σdt). */
        fun reasoningTiming(stream: JsonElement?): Long? {
            val recs = stream.arr()?.filter { it["type"].str() == "reasoning-chunks" }.orEmpty()
            if (recs.isEmpty()) return null
            val start = recs.mapNotNull { it["time0"].long() }.minOrNull() ?: return null
            val end = recs.maxOf { r -> (r["time0"].long() ?: 0) + r["dt"].arr().orEmpty().sumOf { it.long() ?: 0 } }
            return (end - start).coerceAtLeast(0)
        }

        /**
         * Desktop `assistantStreamFirstTokenTime`: earliest wall time of a
         * non-reasoning token-producing stream record.
         */
        fun assistantStreamFirstTokenTime(stream: JsonElement?): Long? {
            val recs = stream.arr().orEmpty()
            var earliest: Long? = null
            for (r in recs) {
                val type = r["type"].str() ?: continue
                if (type == "reasoning-chunks" || type == "reasoning-delta") continue
                val t = when (type) {
                    "text-chunks", "tool-call-chunks" -> r["time0"].long() ?: r["time"].long()
                    "chunk" -> r["time"].long()
                    else -> r["time"].long() ?: r["time0"].long()
                } ?: continue
                earliest = earliest?.let { minOf(it, t) } ?: t
            }
            return earliest
        }
    }
}

/** In-flight model attempt assembled from StreamChunks. */
private class LiveAttempt(val id: String, val turn: Int?, val step: Int?) {
    private class Block(val type: String, val sb: StringBuilder = StringBuilder(), var toolId: String? = null, var toolName: String? = null, var startedAt: Long? = null)
    private val blocks = sortedMapOf<Int, Block>()
    /** Desktop firstTokenTime — first non-reasoning delta wall time. */
    var firstTokenTime: Long? = null
        private set

    fun record(rec: JsonElement) {
        when (rec["type"].str()) {
            "chunk" -> chunk(rec["chunk"], rec["time"].long())
            "text-chunks" -> {
                noteToken(rec["time0"].long() ?: rec["time"].long())
                block(rec["index"].long()?.toInt() ?: 0, "text").sb.append(rec["texts"].arr().orEmpty().joinToString("") { it.str().orEmpty() })
            }
            "reasoning-chunks" -> block(rec["index"].long()?.toInt() ?: 0, "reasoning").apply { startedAt = startedAt ?: rec["time0"].long() }.sb.append(rec["texts"].arr().orEmpty().joinToString("") { it.str().orEmpty() })
            "tool-call-chunks" -> {
                noteToken(rec["time0"].long() ?: rec["time"].long())
                block(rec["index"].long()?.toInt() ?: 0, "tool-call").apply { toolId = rec["id"].str(); toolName = rec["name"].str() ?: toolName }.sb.append(rec["args"].arr().orEmpty().joinToString("") { it.str().orEmpty() })
            }
            else -> chunk(rec, null)
        }
    }

    fun chunk(c: JsonElement?, time: Long?) {
        val idx = c["index"].long()?.toInt() ?: 0
        when (c["type"].str()) {
            "block-start" -> block(idx, c["blockType"].str() ?: "text").startedAt = time
            "text-delta" -> {
                noteToken(time)
                block(idx, "text").sb.append(c["text"].str().orEmpty())
            }
            "reasoning-delta" -> block(idx, "reasoning").apply { startedAt = startedAt ?: time }.sb.append(c["text"].str().orEmpty())
            "tool-call-delta" -> {
                noteToken(time)
                block(idx, "tool-call").apply { toolId = c["id"].str() ?: toolId; toolName = c["name"].str() ?: toolName }.sb.append(c["argumentsDelta"].str().orEmpty())
            }
        }
    }

    private fun noteToken(time: Long?) {
        if (firstTokenTime == null && time != null) firstTokenTime = time
    }

    private fun block(i: Int, type: String) = blocks.getOrPut(i) { Block(type) }

    fun items(): List<Item> = blocks.map { (i, b) ->
        when (b.type) {
            "reasoning" -> Item.Reasoning("live-$id-$i", b.sb.toString(), null, live = true, turn = turn, step = step)
            "tool-call" -> Item.Tool(
                "live-$id-$i", b.toolId ?: "", b.toolName ?: "…", b.sb.toString(), ToolStatus.Running,
                turn = turn, step = step,
            )
            else -> Item.Text("live-$id-$i", b.sb.toString(), live = true, turn = turn, step = step)
        }
    }.filter { it !is Item.Text || it.text.isNotEmpty() }
}
