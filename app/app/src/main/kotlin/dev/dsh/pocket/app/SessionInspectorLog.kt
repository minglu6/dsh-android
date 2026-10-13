package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The loaded follow window, not a second subscription or a durable logging service. */
internal class SessionInspectorLog {
    private var events = ArrayList<JsonObject>()
    private var observed = ArrayList<JsonObject>()
    private var sequences = HashSet<Long>()
    private var published: List<JsonObject> = emptyList()

    fun clear() {
        events = ArrayList()
        observed = ArrayList()
        sequences = HashSet()
        publish()
    }

    fun apply(frame: JsonElement?) {
        when (frame["type"].str()) {
            "snapshot" -> {
                // A snapshot replaces the loaded window, including on reconnect. Pocket rejects
                // obsolete subscription generations before reaching this store.
                clear()
                merge(frame["records"])
                baseline(frame["assistantStream"])
            }
            "resume" -> {
                merge(frame["records"], settle = true)
                if (frame["assistantStream"] is JsonObject) baseline(frame["assistantStream"])
            }
            "event" -> {
                val event = frame["event"] as? JsonObject ?: return
                val seq = event["seq"].long() ?: return
                if (!sequences.add(seq)) return
                if (events.lastOrNull()?.get("seq").long()?.let { seq < it } == true) {
                    events = ArrayList((events + event).sortedBy { it["seq"].long() })
                } else events.add(event)
                if (event["type"].str() == "assistant/message") observed = ArrayList()
            }
            "assistant-stream" -> {
                val value = frame as? JsonObject ?: return
                if (value["frame"]["type"].str() == "start") observed = ArrayList()
                observed.add(value)
            }
            else -> return // Projection/control frames are not session events.
        }
        publish()
    }

    fun prepend(records: JsonElement?) {
        merge(records)
        publish()
    }

    fun snapshot(): List<JsonObject> = published

    private fun baseline(value: JsonElement?) {
        observed = ArrayList()
        // Preserve the actual baseline object, never synthesize stream timestamps or seqs.
        val baseline = value as? JsonObject ?: return
        if (baseline["activeAttempt"] is JsonObject) observed.add(baseline)
    }

    private fun merge(records: JsonElement?, settle: Boolean = false) {
        val incoming = records.arr().orEmpty().mapNotNull { record ->
            if (record["type"].str() != "event") return@mapNotNull null
            val event = record["event"] as? JsonObject ?: return@mapNotNull null
            val seq = event["seq"].long() ?: return@mapNotNull null
            if (sequences.add(seq)) event else null
        }
        if (incoming.isEmpty()) return
        events = ArrayList((events + incoming).sortedBy { it["seq"].long() })
        if (settle && incoming.any { it["type"].str() == "assistant/message" }) observed = ArrayList()
    }

    private fun publish() {
        published = InspectorWindow(events, events.size, observed, observed.size)
    }
}

/** Backings are append-only; replacing/reordering always allocates a new backing in the store.
 * Published windows therefore remain immutable without copying the history on every delta. */
private class InspectorWindow(
    private val events: List<JsonObject>, private val eventCount: Int,
    private val observed: List<JsonObject>, private val observedCount: Int,
) : AbstractList<JsonObject>(), RandomAccess {
    override val size: Int get() = eventCount + observedCount
    override fun get(index: Int): JsonObject {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index size=$size")
        return if (index < eventCount) events[index] else observed[index - eventCount]
    }
}
