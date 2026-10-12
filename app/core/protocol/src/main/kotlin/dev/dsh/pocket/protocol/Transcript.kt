package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One rendered row of a conversation. */
sealed interface Item {
    val key: String
    /** [pending]: echoed locally or still in dsh's inbox (dsh writes user/message only when the step starts). */
    data class User(override val key: String, val text: String, val images: Int = 0, val pending: Boolean = false) : Item
    data class Reasoning(override val key: String, val text: String, val durationMs: Long?, val live: Boolean) : Item
    data class Text(override val key: String, val text: String, val live: Boolean) : Item
    data class Tool(
        override val key: String, val callId: String, val name: String, val arguments: String,
        val status: ToolStatus, val result: String? = null, val error: String? = null,
        val startedAt: Long? = null, val endedAt: Long? = null,
    ) : Item
    data class Notice(override val key: String, val text: String) : Item
}

enum class ToolStatus { Running, AwaitingApproval, Ok, Error }

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
    val replyText: String,
    val reasoningText: String,
    val usage: StepUsage,
    val provider: String?,
    val model: String?,
    val toolCallIds: List<String>,
)

/**
 * Folds dsh SessionFollowFrames (snapshot/resume/event/assistant-stream, passed through by
 * dsh-plugin-pocket) into [Item]s. Pure and deterministic: no Android dependencies.
 */
class TranscriptReducer {
    private val committed = ArrayList<Item>()
    private val toolIndex = HashMap<String, Int>()
    private val approvalCall = HashMap<String, String>()
    private val userByRpc = HashMap<String, Int>()
    private val localEchoes = ArrayList<Pair<Int, String>>()
    private val stepStartTimes = HashMap<String, Long>()
    private val settledSteps = ArrayList<StepRecord>()
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
    private var inputTokens = 0L
    private var cacheRead = 0L
    private var outputTokens = 0L

    val usage get() = Usage(turns, steps, inputTokens, cacheRead, outputTokens)
    fun stepRecords(): List<StepRecord> = settledSteps.toList()

    fun items(): List<Item> = committed + (live?.items() ?: emptyList())

    /** Apply one follow frame; returns true when visible state changed. */
    fun apply(frame: JsonElement?): Boolean {
        when (frame["type"].str()) {
            "snapshot" -> {
                val keep = localEchoes.map { it.second }
                reset()
                records(frame["records"])
                keep.filter { t -> committed.none { it is Item.User && it.text == t } }.forEach(::echo)
                readProjections(frame["projections"])
                cursor = frame["cursor"].long() ?: cursor
                hasMore = frame["hasMore"].bool() ?: false
                firstSeq = minSeq(frame["records"])
                baseline(frame["assistantStream"])
            }
            "resume" -> {
                records(frame["records"])
                readProjections(frame["projections"])
                cursor = maxOf(cursor, frame["cursor"].long() ?: cursor)
                baseline(frame["assistantStream"])
            }
            "event" -> event(frame["event"])
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
        val older = prior.items()
        val olderSteps = prior.stepRecords()
        val shift = older.size
        if (shift > 0) {
            committed.addAll(0, older)
            rebuildIndexes()
            for (i in localEchoes.indices) {
                localEchoes[i] = (localEchoes[i].first + shift) to localEchoes[i].second
            }
        }
        if (olderSteps.isNotEmpty()) {
            settledSteps.addAll(0, olderSteps)
        }
        val min = filtered.mapNotNull { it["event"]["seq"].long() }.minOrNull()
        if (min != null) firstSeq = if (firstSeq < 0) min else minOf(firstSeq, min)
        return shift > 0 || olderSteps.isNotEmpty()
    }

    private fun rebuildIndexes() {
        toolIndex.clear()
        userByRpc.clear()
        committed.forEachIndexed { i, item ->
            when (item) {
                is Item.Tool -> if (item.callId.isNotEmpty()) toolIndex[item.callId] = i
                else -> {}
            }
        }
    }

    private fun minSeq(arr: JsonElement?): Long {
        val seqs = arr.arr()?.mapNotNull { it["event"]["seq"].long() }.orEmpty()
        return seqs.minOrNull() ?: -1
    }

    private fun reset() {
        committed.clear(); toolIndex.clear(); approvalCall.clear(); userByRpc.clear(); localEchoes.clear()
        stepStartTimes.clear(); settledSteps.clear(); live = null
        cursor = -1; firstSeq = -1; hasMore = false
        turns = 0; steps = 0; inputTokens = 0; cacheRead = 0; outputTokens = 0; running = false
    }

    private fun readProjections(p: JsonElement?) {
        val values = p["values"]
        values["title"].str()?.let { title = it }
        val usage = values["tokenUsage"]
        if (usage is JsonObject) {
            val uncached = usage["uncachedInputTokens"].long() ?: 0
            val cr = usage["cacheReadTokens"].long() ?: 0
            cacheRead = cr; inputTokens = uncached + cr; outputTokens = usage["outputTokens"].long() ?: 0
        }
    }

    private fun records(arr: JsonElement?) { arr.arr()?.forEach { r -> if (r["type"].str() == "event") event(r["event"]) } }

    private fun baseline(b: JsonElement?) {
        val a = b["activeAttempt"] ?: return
        val l = LiveAttempt(a["attemptId"].str() ?: "live")
        a["stream"].arr()?.forEach { rec -> l.record(rec) }
        live = l
    }

    private fun event(e: JsonElement?) {
        if (e == null || e["ignorable"].bool() == true) return
        val seq = e["seq"].long() ?: return
        if (seq <= cursor) return
        cursor = seq
        val data = e["data"]
        when (e["type"].str()) {
            "turn/start" -> { turns++; running = true }
            "turn/end" -> { running = false; live = null }
            "step/start" -> {
                val turn = data["turn"].long()?.toInt() ?: turns
                val step = data["step"].long()?.toInt() ?: (steps + 1)
                e["time"].long()?.let { stepStartTimes["$turn:$step"] = it }
            }
            "user/message" -> {
                val msg = if (data["content"] != null) data else data["message"]
                if (!isTyped(msg)) return
                upsertUser(msg, "u$seq", pending = false)
            }
            // A prompt enters the agent inbox (seq ~3) long before its user/message (written at step start).
            "agent/inbox/spliced" -> data["inserted"].arr().orEmpty().forEach { m -> if (isTyped(m)) upsertUser(m, "q$seq.${m["source"]["rpcId"].str()}", pending = true) }
            "assistant/message" -> {
                steps++
                live = null
                val turn = data["turn"].long()?.toInt() ?: turns
                val step = data["step"].long()?.toInt() ?: steps
                val messageTime = e["time"].long()
                val stepStartTime = stepStartTimes.remove("$turn:$step")
                val timing = reasoningTiming(data["stream"])
                val replyParts = ArrayList<String>()
                val reasoningParts = ArrayList<String>()
                val toolCallIds = ArrayList<String>()
                data["message"]["content"].arr().orEmpty().forEachIndexed { i, b ->
                    when (b["type"].str()) {
                        "reasoning" -> {
                            val text = b["text"].str().orEmpty()
                            reasoningParts += text
                            committed += Item.Reasoning("r$seq.$i", text, timing, live = false)
                        }
                        "text" -> b["text"].str()?.takeIf { it.isNotBlank() }?.let {
                            replyParts += it
                            committed += Item.Text("t$seq.$i", it, live = false)
                        }
                        "tool-call" -> {
                            val id = b["id"].str() ?: "c$seq.$i"
                            toolCallIds += id
                            upsertTool(id, b["name"].str().orEmpty(), b["arguments"].str().orEmpty(), e["time"].long())
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
                    inputTokens += (it["inputTokens"].long() ?: 0) + (it["cacheReadTokens"].long() ?: 0)
                    cacheRead += it["cacheReadTokens"].long() ?: 0
                }
                val source = data["message"]["source"]
                settledSteps += StepRecord(
                    seq = seq,
                    turn = turn,
                    step = step,
                    stepStartTime = stepStartTime,
                    messageTime = messageTime,
                    replyText = replyParts.joinToString("\n\n"),
                    reasoningText = reasoningParts.joinToString("\n\n"),
                    usage = stepUsage,
                    provider = source["provider"].str(),
                    model = source["model"].str(),
                    toolCallIds = toolCallIds,
                )
                if (data["interrupted"].bool() == true) committed += Item.Notice("i$seq", "已中断")
            }
            "tool/call" -> upsertTool(data["callId"].str() ?: "c$seq", data["name"].str().orEmpty(), data["arguments"].str().orEmpty(), e["time"].long())
            "tool/result" -> {
                val id = data["message"]["toolCallId"].str() ?: return
                val text = data["message"]["content"].arr().orEmpty().filter { it["type"].str() == "text" }.joinToString("\n") { it["text"].str().orEmpty() }
                val err = data["error"]
                val isErr = err != null || data["message"]["isError"].bool() == true
                updateTool(id) { it.copy(status = if (isErr) ToolStatus.Error else ToolStatus.Ok, result = text, error = err?.let { x -> x["reason"].str() ?: x["code"].str() }, endedAt = e["time"].long()) }
            }
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
            "llm/retry" -> committed += Item.Notice("n$seq", "请求重试中")
        }
    }

    /** Only what a person typed: dsh also injects user-role context (runtime-context, time, skills, memory plugins). */
    private fun isTyped(msg: JsonElement?): Boolean {
        val kind = msg["source"]["kind"].str()
        if (kind != null) return kind == "user"
        val text = msg["content"].arr()?.firstOrNull { it["type"].str() == "text" }?.get("text").str().orEmpty()
        return msg["source"] == null && !text.trimStart().startsWith("<")
    }

    private fun upsertUser(msg: JsonElement?, key: String, pending: Boolean) {
        val blocks = msg["content"].arr().orEmpty()
        val text = blocks.filter { it["type"].str() == "text" }.joinToString("\n") { it["text"].str().orEmpty() }
        val images = blocks.count { it["type"].str() == "image" }
        val rpc = msg["source"]["rpcId"].str()
        val at = rpc?.let { userByRpc[it] }
            ?: localEchoes.indexOfFirst { it.second == text }.takeIf { it >= 0 }?.let { localEchoes.removeAt(it).first }
        if (at != null) {
            val old = committed[at] as Item.User
            committed[at] = old.copy(text = text, images = images, pending = pending && old.pending)
            if (rpc != null) userByRpc[rpc] = at
        } else {
            if (rpc != null) userByRpc[rpc] = committed.size
            committed += Item.User(key, text, images, pending)
        }
    }

    /** Optimistic echo of a message the user just sent; reconciled with the server copy by text. */
    fun echo(text: String) {
        localEchoes += committed.size to text
        committed += Item.User("local-${committed.size}-${text.hashCode()}", text, pending = true)
    }

    private fun upsertTool(id: String, name: String, args: String, time: Long?) {
        val i = toolIndex[id]
        if (i != null) { val t = committed[i] as Item.Tool; committed[i] = t.copy(name = name.ifEmpty { t.name }, arguments = args.ifEmpty { t.arguments }) }
        else { toolIndex[id] = committed.size; committed += Item.Tool("k$id", id, name, args, ToolStatus.Running, startedAt = time) }
    }
    private fun updateTool(id: String, f: (Item.Tool) -> Item.Tool) { toolIndex[id]?.let { committed[it] = f(committed[it] as Item.Tool) } }

    private fun stream(f: JsonElement?) {
        when (f["type"].str()) {
            "start" -> { live = LiveAttempt(f["attemptId"].str() ?: "live"); running = true }
            "chunk" -> { val l = live ?: LiveAttempt(f["attemptId"].str() ?: "live").also { live = it }; l.chunk(f["chunk"], f["time"].long()) }
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
    }
}

/** In-flight model attempt assembled from StreamChunks. */
private class LiveAttempt(val id: String) {
    private class Block(val type: String, val sb: StringBuilder = StringBuilder(), var toolId: String? = null, var toolName: String? = null, var startedAt: Long? = null)
    private val blocks = sortedMapOf<Int, Block>()

    fun record(rec: JsonElement) {
        when (rec["type"].str()) {
            "chunk" -> chunk(rec["chunk"], rec["time"].long())
            "text-chunks" -> block(rec["index"].long()?.toInt() ?: 0, "text").sb.append(rec["texts"].arr().orEmpty().joinToString("") { it.str().orEmpty() })
            "reasoning-chunks" -> block(rec["index"].long()?.toInt() ?: 0, "reasoning").apply { startedAt = startedAt ?: rec["time0"].long() }.sb.append(rec["texts"].arr().orEmpty().joinToString("") { it.str().orEmpty() })
            "tool-call-chunks" -> block(rec["index"].long()?.toInt() ?: 0, "tool-call").apply { toolId = rec["id"].str(); toolName = rec["name"].str() ?: toolName }.sb.append(rec["args"].arr().orEmpty().joinToString("") { it.str().orEmpty() })
            else -> chunk(rec, null)
        }
    }

    fun chunk(c: JsonElement?, time: Long?) {
        val idx = c["index"].long()?.toInt() ?: 0
        when (c["type"].str()) {
            "block-start" -> block(idx, c["blockType"].str() ?: "text").startedAt = time
            "text-delta" -> block(idx, "text").sb.append(c["text"].str().orEmpty())
            "reasoning-delta" -> block(idx, "reasoning").apply { startedAt = startedAt ?: time }.sb.append(c["text"].str().orEmpty())
            "tool-call-delta" -> block(idx, "tool-call").apply { toolId = c["id"].str() ?: toolId; toolName = c["name"].str() ?: toolName }.sb.append(c["argumentsDelta"].str().orEmpty())
        }
    }

    private fun block(i: Int, type: String) = blocks.getOrPut(i) { Block(type) }

    fun items(): List<Item> = blocks.map { (i, b) ->
        when (b.type) {
            "reasoning" -> Item.Reasoning("live-$id-$i", b.sb.toString(), null, live = true)
            "tool-call" -> Item.Tool("live-$id-$i", b.toolId ?: "", b.toolName ?: "…", b.sb.toString(), ToolStatus.Running)
            else -> Item.Text("live-$id-$i", b.sb.toString(), live = true)
        }
    }.filter { it !is Item.Text || it.text.isNotEmpty() }
}
