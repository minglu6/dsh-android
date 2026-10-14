package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Collections

/** Exact provider-reported accounting for every billed attempt in a completed turn. */
data class TurnTokenUsage(
    val uncachedInputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val routes: List<TurnTokenUsageRoute>? = null,
)

data class TurnTokenUsageRoute(val provider: String, val model: String)

private const val MaxSafeTokenCount = 9_007_199_254_740_991L

private fun count(value: JsonElement?): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.doubleOrNull ?: return null
    if (!number.isFinite() || number < 0 || number > MaxSafeTokenCount.toDouble()) return null
    return number.toLong().takeIf { it.toDouble() == number }
}

private fun sum(a: Long, b: Long): Long? = if (a > MaxSafeTokenCount - b) null else a + b

private data class AttemptUsage(
    val input: Long,
    val output: Long,
    val total: Long,
    val read: Long?,
    val write: Long?,
    val reasoning: Long?,
    val route: TurnTokenUsageRoute?,
)

private fun normalizeUsage(value: JsonElement?, route: TurnTokenUsageRoute?): AttemptUsage? {
    val input = count(value["inputTokens"]) ?: return null
    val output = count(value["outputTokens"]) ?: return null
    val read = count(value["cacheReadTokens"])
    val write = count(value["cacheWriteTokens"])
    val reasoning = count(value["reasoningTokens"])
    if (value["cacheReadTokens"] != null && read == null) return null
    if (value["cacheWriteTokens"] != null && write == null) return null
    if (value["reasoningTokens"] != null && (reasoning == null || reasoning > output)) return null
    val knownPrompt = sum(sum(input, read ?: 0) ?: return null, write ?: 0) ?: return null
    val total = if (value["totalTokens"] != null) {
        val exact = count(value["totalTokens"]) ?: return null
        if (exact < output || exact - output < knownPrompt) return null
        if (read != null && write != null && exact - output != knownPrompt) return null
        exact
    } else {
        if (read == null || write == null) return null
        sum(knownPrompt, output) ?: return null
    }
    return AttemptUsage(input, output, total, read, write, reasoning, route)
}

private enum class UsageEventKind { TurnStart, TurnEnd, StepStart, StepEnd, Message, Attempt, Retry, RetryStarted }

/** Retains only lifecycle coordinates and normalized counters, never text/stream payloads. */
private data class UsageEvent(val kind: UsageEventKind, val turn: Int?, val step: Int?, val usage: AttemptUsage?)

private fun usageEvent(event: JsonElement?): UsageEvent? {
    val kind = when (event["type"].str()) {
        "turn/start" -> UsageEventKind.TurnStart
        "turn/end" -> UsageEventKind.TurnEnd
        "step/start" -> UsageEventKind.StepStart
        "step/end" -> UsageEventKind.StepEnd
        "assistant/message" -> UsageEventKind.Message
        "assistant/attempt" -> UsageEventKind.Attempt
        "llm/retry" -> UsageEventKind.Retry
        "llm/retry-started" -> UsageEventKind.RetryStarted
        else -> return null
    }
    val data = event["data"]
    val turn = count(data["turn"])?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    val step = count(data["step"])?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    val usage = if (kind == UsageEventKind.Message || kind == UsageEventKind.Attempt) {
        val finalUsage = data["usage"]?.takeUnless { it == JsonNull }
        val streamUsage = data["stream"].arr()?.lastOrNull {
            it["type"].str() == "chunk" && it["chunk"]["type"].str() == "usage"
        }?.get("chunk")?.get("usage")
        val source = data["message"]["source"]
        val provider = source["provider"].str()?.takeIf { it.isNotEmpty() }
        val model = source["model"].str()?.takeIf { it.isNotEmpty() }
        val route = if (kind == UsageEventKind.Message && provider != null && model != null) {
            TurnTokenUsageRoute(provider, model)
        } else null
        // Official final message usage is authoritative; stream is its fallback.
        normalizeUsage(if (kind == UsageEventKind.Message) finalUsage ?: streamUsage else streamUsage, route)
    } else null
    return UsageEvent(kind, turn, step, usage)
}

private enum class AttemptPhase { Idle, Open, FinishClosed, MessageSettled, RetrySettled }

private class TurnUsageFold(val turn: Int?) {
    private var phase = AttemptPhase.Idle
    private var step: Int? = null
    private var invalid = turn == null
    private var ended = false
    private var attempts = 0
    private var input = 0L
    private var output = 0L
    private var total = 0L
    private var read: Long? = 0L
    private var write: Long? = 0L
    private var reasoning: Long? = 0L
    private var attributed = true
    private val routes = LinkedHashSet<TurnTokenUsageRoute>()

    fun accept(event: UsageEvent) {
        if (invalid) return
        if (event.turn != turn || ended || event.kind == UsageEventKind.TurnStart) {
            invalid = true
            return
        }
        when (event.kind) {
            UsageEventKind.TurnStart -> invalid = true
            UsageEventKind.TurnEnd -> if (phase == AttemptPhase.Idle) ended = true else invalid = true
            UsageEventKind.StepStart -> {
                if (phase != AttemptPhase.Idle || event.step == null) invalid = true
                else { phase = AttemptPhase.Open; step = event.step }
            }
            else -> {
                if (phase == AttemptPhase.Idle || event.step != step) { invalid = true; return }
                when (event.kind) {
                    UsageEventKind.RetryStarted -> {
                        if (phase != AttemptPhase.RetrySettled) invalid = true else phase = AttemptPhase.Open
                    }
                    UsageEventKind.Message, UsageEventKind.Attempt -> {
                        if (phase != AttemptPhase.Open || event.usage == null) { invalid = true; return }
                        add(event.usage)
                        phase = if (event.kind == UsageEventKind.Message) AttemptPhase.MessageSettled else AttemptPhase.FinishClosed
                    }
                    UsageEventKind.Retry -> {
                        if (phase != AttemptPhase.FinishClosed) invalid = true else phase = AttemptPhase.RetrySettled
                    }
                    UsageEventKind.StepEnd -> {
                        // An open attempt has no durable final usage yet; never infer one.
                        if (phase == AttemptPhase.Open) invalid = true else phase = AttemptPhase.Idle
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun add(usage: AttemptUsage) {
        input = sum(input, usage.input) ?: run { invalid = true; return }
        output = sum(output, usage.output) ?: run { invalid = true; return }
        total = sum(total, usage.total) ?: run { invalid = true; return }
        read = read?.let { a -> usage.read?.let { sum(a, it) } }
        write = write?.let { a -> usage.write?.let { sum(a, it) } }
        reasoning = reasoning?.let { a -> usage.reasoning?.let { sum(a, it) } }
        if (usage.route == null) attributed = false else routes.add(usage.route)
        attempts++
    }

    fun result(): TurnTokenUsage? = if (invalid || !ended || phase != AttemptPhase.Idle || attempts == 0) null else {
        TurnTokenUsage(input, output, total, read, write, reasoning,
            if (attributed) Collections.unmodifiableList(routes.toList()) else null)
    }
}

/** Fed only after TranscriptReducer's seq dedupe; older pages replay these compact facts. */
internal class TurnUsageLedger {
    private val events = ArrayList<UsageEvent>()
    private val completed = LinkedHashMap<Int, TurnTokenUsage>()
    private var current: TurnUsageFold? = null
    private var snapshot: Map<Int, TurnTokenUsage> = emptyMap()
    private var snapshotDirty = false

    fun snapshot(): Map<Int, TurnTokenUsage> {
        if (snapshotDirty) {
            snapshot = Collections.unmodifiableMap(LinkedHashMap(completed))
            snapshotDirty = false
        }
        return snapshot
    }

    fun clear() {
        events.clear()
        completed.clear()
        current = null
        snapshot = emptyMap()
        snapshotDirty = false
    }

    fun apply(event: JsonElement?) {
        val fact = usageEvent(event) ?: return
        events.add(fact)
        fold(fact)
    }

    fun prepend(earlier: TurnUsageLedger): Boolean {
        if (earlier.events.isEmpty()) return false
        events.addAll(0, earlier.events)
        completed.clear()
        current = null
        snapshot = emptyMap()
        snapshotDirty = true
        events.forEach(::fold)
        return true
    }

    private fun fold(event: UsageEvent) {
        val old = current
        if (event.kind == UsageEventKind.TurnStart && (old == null || old.turn != event.turn)) {
            current = TurnUsageFold(event.turn)
        } else {
            old?.accept(event)
        }
        val active = current ?: return
        val turn = active.turn ?: return
        val result = active.result()
        val changed = if (result == null) completed.remove(turn) != null else completed.put(turn, result) != result
        if (changed) snapshotDirty = true
    }
}
