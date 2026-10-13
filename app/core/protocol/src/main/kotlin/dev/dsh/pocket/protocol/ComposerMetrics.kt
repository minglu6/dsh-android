package dev.dsh.pocket.protocol

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Whole-log Host projections; null is unavailable, not a zero reading. */
data class ComposerMetrics(
    val activity: ComposerActivity? = null,
    val tokens: ComposerTokenUsage? = null,
    val context: ComposerContext? = null,
    val contextBreakdown: ComposerContextBreakdown? = null,
    /** False only for the official window-scoped fallback on Hosts without session-stats. */
    val activityIsWholeSession: Boolean = false,
)

data class ComposerActivity(
    val turns: Long,
    val steps: Long,
    val llmMs: Double,
    val toolMs: Double,
    val ttftMs: Double,
    val ttftSteps: Long,
    val decodeMs: Double,
    val decodeTokens: Long,
) {
    val tokensPerSecond: Double? get() = if (decodeMs > 0) decodeTokens / (decodeMs / 1000) else null
    val meanTtftMs: Double? get() = if (ttftSteps > 0) ttftMs / ttftSteps else null
}

data class ComposerTokenUsage(
    val uncachedInputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
) {
    val billedInputTokens: Long get() = uncachedInputTokens + cacheReadTokens + cacheWriteTokens
    val totalTokens: Long get() = billedInputTokens + outputTokens
    val cacheHitPercent: String? get() = formatComposerCacheHit(cacheReadTokens, billedInputTokens)
}

data class ComposerContext(val usedTokens: Double, val contextWindow: Double) {
    val percent: Int get() = kotlin.math.floor(usedTokens / contextWindow * 100 + 0.5).coerceIn(0.0, 100.0).toInt()
}

data class ComposerContextBreakdown(val systemTokens: Double, val toolsTokens: Double, val messageTokens: Double)

/** Durable call/result facts, separate from tool-card creation and PTC dispatch timing. */
internal class ToolCallTiming(var callStartedAt: Long? = null, var resultAt: Long? = null)

/** Match token-format.ts: a partial cache hit must never round to 100%. */
fun formatComposerCacheHit(cacheReadTokens: Long, promptTokens: Long): String? {
    if (promptTokens <= 0 || cacheReadTokens < 0 || cacheReadTokens > promptTokens) return null
    if (cacheReadTokens == promptTokens) return "100"
    val numerator = BigDecimal.valueOf(cacheReadTokens).multiply(BigDecimal.valueOf(100))
    val denominator = BigDecimal.valueOf(promptTokens)
    var places = 0
    var rounded = numerator.divide(denominator, places, RoundingMode.HALF_UP)
    while (rounded.compareTo(BigDecimal.valueOf(100)) == 0) {
        rounded = numerator.divide(denominator, ++places, RoundingMode.HALF_UP)
    }
    return rounded.stripTrailingZeros().toPlainString()
}

/** Same higher-seq-wins semantics as the official ProjectionValueStore, independent of event cursor. */
internal class ComposerProjectionStore {
    private val sequences = HashMap<String, Long>()
    private val keys = setOf("sessionStats", "tokenUsage", "contextPressure", "contextBreakdown")
    var activity: ComposerActivity? = null; private set
    var tokens: ComposerTokenUsage? = null; private set
    var context: ComposerContext? = null; private set
    var contextBreakdown: ComposerContextBreakdown? = null; private set

    fun apply(key: String, value: JsonElement?, seq: Long): Boolean {
        if (key !in keys || sequences[key]?.let { seq <= it } == true) return false
        sequences[key] = seq
        readValue(key, value)
        return true
    }

    private fun readValue(key: String, value: JsonElement?) {
        when (key) {
            "sessionStats" -> activity = parseActivity(value)
            "tokenUsage" -> tokens = parseTokens(value)
            "contextPressure" -> context = parseContext(value)
            "contextBreakdown" -> contextBreakdown = parseBreakdown(value)
        }
    }

    fun baseline(projections: JsonElement?, fallbackSeq: Long) {
        val values = projections["values"].obj() ?: return
        val seq = projections["asOfSeq"].long() ?: fallbackSeq
        // An omitted key means capability absent at this cut, never retain an old budget.
        for (key in keys) {
            if (values.containsKey(key)) {
                apply(key, values[key], seq)
            } else if ((sequences[key] ?: Long.MIN_VALUE) <= seq) {
                sequences.remove(key)
                readValue(key, null)
            }
        }
    }

    fun clear() {
        sequences.clear()
        for (key in keys) readValue(key, null)
    }

    fun metrics(steps: List<StepRecord>, toolTimings: Collection<ToolCallTiming>): ComposerMetrics = ComposerMetrics(
        activity = activity ?: windowActivity(steps, toolTimings),
        tokens = tokens,
        context = context,
        contextBreakdown = contextBreakdown,
        activityIsWholeSession = activity != null,
    )
}

private fun JsonElement?.nonnegativeNumber(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    ?.takeIf { it.isFinite() && it >= 0 }
private fun JsonElement?.nonnegativeCount(): Long? = long()?.takeIf { it >= 0 }

private fun parseActivity(v: JsonElement?): ComposerActivity? {
    if (v !is JsonObject) return null
    return ComposerActivity(
        turns = v["turns"].nonnegativeCount() ?: return null,
        steps = v["steps"].nonnegativeCount() ?: return null,
        llmMs = v["llmMs"].nonnegativeNumber() ?: return null,
        toolMs = v["toolMs"].nonnegativeNumber() ?: return null,
        ttftMs = v["ttftMs"].nonnegativeNumber() ?: return null,
        ttftSteps = v["ttftSteps"].nonnegativeCount() ?: return null,
        decodeMs = v["decodeMs"].nonnegativeNumber() ?: return null,
        decodeTokens = v["decodeTokens"].nonnegativeCount() ?: return null,
    )
}

private fun parseTokens(v: JsonElement?): ComposerTokenUsage? {
    if (v !is JsonObject) return null
    return ComposerTokenUsage(
        uncachedInputTokens = v["uncachedInputTokens"].nonnegativeCount() ?: return null,
        cacheReadTokens = v["cacheReadTokens"].nonnegativeCount() ?: return null,
        cacheWriteTokens = v["cacheWriteTokens"].nonnegativeCount() ?: return null,
        outputTokens = v["outputTokens"].nonnegativeCount() ?: return null,
    )
}

private fun parseContext(v: JsonElement?): ComposerContext? {
    val used = (v["projectedTokens"] ?: v["pressureTokens"]).nonnegativeNumber() ?: return null
    val capacity = v["contextWindow"].nonnegativeNumber()?.takeIf { it > 0 } ?: return null
    return ComposerContext(used, capacity)
}

private fun parseBreakdown(v: JsonElement?): ComposerContextBreakdown? {
    return ComposerContextBreakdown(
        systemTokens = v["systemTokens"].nonnegativeNumber() ?: return null,
        toolsTokens = v["toolsTokens"].nonnegativeNumber() ?: return null,
        messageTokens = v["messageTokens"].nonnegativeNumber() ?: return null,
    )
}

/** Official deriveStats fallback: only the loaded window, never advertised as a durable session total. */
private fun windowActivity(steps: List<StepRecord>, toolTimings: Collection<ToolCallTiming>): ComposerActivity? {
    if (steps.isEmpty() && toolTimings.isEmpty()) return null
    val turns = HashSet<Int>()
    var llmMs = 0.0
    var ttftMs = 0.0
    var ttftSteps = 0L
    var decodeMs = 0.0
    var decodeTokens = 0L
    for (step in steps) {
        turns += step.turn
        val start = step.stepStartTime
        val first = step.firstTokenTime
        val end = step.messageTime
        if (start != null && end != null) llmMs += (end - start).coerceAtLeast(0)
        if (start != null && first != null) {
            ttftMs += (first - start).coerceAtLeast(0)
            ttftSteps++
        }
        val output = step.usage.outputTokens
        if (first != null && end != null && output != null && output >= 0) {
            decodeMs += (end - first).coerceAtLeast(0)
            decodeTokens += output
        }
    }
    var toolMs = 0.0
    for (timing in toolTimings) {
        val start = timing.callStartedAt
        val end = timing.resultAt
        if (start != null && end != null) toolMs += (end - start).coerceAtLeast(0)
    }
    return ComposerActivity(turns.size.toLong(), steps.size.toLong(), llmMs, toolMs, ttftMs, ttftSteps, decodeMs, decodeTokens)
}
