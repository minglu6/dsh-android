package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One rendered row of a conversation. */
sealed interface Item {
    val key: String
    data class User(override val key: String, val text: String, val images: Int = 0) : Item
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

/**
 * Folds dsh SessionFollowFrames (snapshot/resume/event/assistant-stream, passed through by
 * dsh-plugin-pocket) into [Item]s. Pure and deterministic: no Android dependencies.
 */
class TranscriptReducer {
    private val committed = ArrayList<Item>()
    private val toolIndex = HashMap<String, Int>()
    private val approvalCall = HashMap<String, String>()
    private var live: LiveAttempt? = null
    var cursor: Long = -1; private set
    var title: String? = null; private set
    var running = false; private set
    private var turns = 0
    private var steps = 0
    private var inputTokens = 0L
    private var cacheRead = 0L
    private var outputTokens = 0L

    val usage get() = Usage(turns, steps, inputTokens, cacheRead, outputTokens)

    fun items(): List<Item> = committed + (live?.items() ?: emptyList())

    /** Apply one follow frame; returns true when visible state changed. */
    fun apply(frame: JsonElement?): Boolean {
        when (frame["type"].str()) {
            "snapshot" -> { reset(); records(frame["records"]); readProjections(frame["projections"]); cursor = frame["cursor"].long() ?: cursor; baseline(frame["assistantStream"]) }
            "resume" -> { records(frame["records"]); readProjections(frame["projections"]); cursor = maxOf(cursor, frame["cursor"].long() ?: cursor); baseline(frame["assistantStream"]) }
            "event" -> event(frame["event"])
            "assistant-stream" -> stream(frame["frame"])
            else -> return false
        }
        return true
    }

    private fun reset() {
        committed.clear(); toolIndex.clear(); approvalCall.clear(); live = null; cursor = -1
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
            "user/message" -> {
                val msg = if (data["content"] != null) data else data["message"]
                // Runtime-context / time snapshots are user-role messages injected by dsh, not typed by the user.
                val kind = msg["source"]["kind"].str()
                if (kind != null && kind != "user") return
                val blocks = msg["content"].arr().orEmpty()
                committed += Item.User("u$seq", blocks.filter { it["type"].str() == "text" }.joinToString("\n") { it["text"].str().orEmpty() }, blocks.count { it["type"].str() == "image" })
            }
            "assistant/message" -> {
                steps++
                live = null
                val timing = reasoningTiming(data["stream"])
                data["message"]["content"].arr().orEmpty().forEachIndexed { i, b ->
                    when (b["type"].str()) {
                        "reasoning" -> committed += Item.Reasoning("r$seq.$i", b["text"].str().orEmpty(), timing, live = false)
                        "text" -> b["text"].str()?.takeIf { it.isNotBlank() }?.let { committed += Item.Text("t$seq.$i", it, live = false) }
                        "tool-call" -> upsertTool(b["id"].str() ?: "c$seq.$i", b["name"].str().orEmpty(), b["arguments"].str().orEmpty(), e["time"].long())
                    }
                }
                data["usage"]?.let { u ->
                    outputTokens += u["outputTokens"].long() ?: 0
                    inputTokens += (u["inputTokens"].long() ?: 0) + (u["cacheReadTokens"].long() ?: 0)
                    cacheRead += u["cacheReadTokens"].long() ?: 0
                }
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
            "end" -> if (f["outcome"]["kind"].str() == "abandoned") live = null
        }
    }

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
