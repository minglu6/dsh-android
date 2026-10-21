package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Whole-log Host turn boundary, including turns outside the loaded transcript window. */
data class TurnOutlineEntry(
    val turn: Int,
    val seq: Long,
    val prompt: String = "",
    val response: String = "",
)

private const val MaxSafeOutlineSeq = 9_007_199_254_740_991L

/** Desktop turn-rail-items: damaged coordinates drop; decorative previews may be absent. */
private fun outlineEntry(value: JsonElement): TurnOutlineEntry? {
    val turn = outlineNumber(value["turn"])?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
    val seq = outlineNumber(value["seq"], rejectNegativeZero = true) ?: return null
    return TurnOutlineEntry(turn, seq, value["prompt"].str().orEmpty(), value["response"].str().orEmpty())
}

private fun outlineNumber(value: JsonElement?, rejectNegativeZero: Boolean = false): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.doubleOrNull ?: return null
    if (!number.isFinite() || number < 0 || number > MaxSafeOutlineSeq.toDouble()) return null
    if (rejectNegativeZero && number == 0.0 && number.toRawBits() < 0) return null
    return number.toLong().takeIf { it.toDouble() == number }
}

/** Same per-key, higher-seq-wins lifecycle as the official ProjectionValueStore. */
internal class TurnOutlineProjectionStore {
    private var sequence: Long? = null
    var entries: List<TurnOutlineEntry> = emptyList(); private set

    fun apply(value: JsonElement?, seq: Long): Boolean {
        if (seq < -1 || seq > MaxSafeOutlineSeq || sequence?.let { seq <= it } == true) return false
        sequence = seq
        // The wire view is an array, not the persisted { turns, draft } fold state.
        // Match the desktop rail merge: last duplicate wins and marks ascend by turn.
        val byTurn = HashMap<Int, TurnOutlineEntry>()
        if (value is JsonArray) for (row in value) {
            val entry = outlineEntry(row) ?: continue
            byTurn[entry.turn] = entry
        }
        entries = byTurn.values.sortedBy { it.turn }
        return true
    }

    fun baseline(projections: JsonElement?, fallbackSeq: Long) {
        val values = projections["values"].obj() ?: return
        val seq = projections["asOfSeq"].long() ?: fallbackSeq
        if (values.containsKey("turnOutline")) {
            apply(values["turnOutline"], seq)
        } else if ((sequence ?: Long.MIN_VALUE) <= seq) {
            clear()
        }
    }

    fun clear() {
        sequence = null
        entries = emptyList()
    }
}
