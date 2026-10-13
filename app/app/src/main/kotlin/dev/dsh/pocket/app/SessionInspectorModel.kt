package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class InspectorRow(
    val key: String,
    val type: String,
    val value: JsonObject,
    val seq: Long? = null,
    val time: Long? = null,
    val children: List<InspectorRow> = emptyList(),
)

/** Literal case-insensitive fragments; '*' is the only wildcard (never a user regex). */
internal fun inspectorTypeMatches(type: String, filter: String): Boolean {
    var from = 0
    for (fragment in filter.trim().split('*')) {
        if (fragment.isEmpty()) continue
        val index = type.indexOf(fragment, startIndex = from, ignoreCase = true)
        if (index < 0) return false
        from = index + fragment.length
    }
    return true
}

internal data class InspectorVisibleRow(val row: InspectorRow, val depth: Int, val matches: Boolean)

/** Keep matching descendants' actual ancestors without counting those ancestors as matches. */
internal fun inspectorVisibleRows(
    rows: List<InspectorRow>, filter: String, collapsed: Set<String>,
): List<InspectorVisibleRow> = buildList {
    fun visit(row: InspectorRow, depth: Int): List<InspectorVisibleRow> {
        val children = row.children.flatMap { visit(it, depth + 1) }
        val matches = inspectorTypeMatches(row.type, filter)
        if (!matches && children.isEmpty()) return emptyList()
        return buildList {
            add(InspectorVisibleRow(row, depth, matches))
            if (row.key !in collapsed) addAll(children)
        }
    }
    rows.forEach { addAll(visit(it, 0)) }
}

private val inspectorUtc = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS 'UTC'", Locale.ROOT)
    .withZone(ZoneOffset.UTC)
internal fun inspectorTime(time: Long?): String = time?.let { inspectorUtc.format(Instant.ofEpochMilli(it)) } ?: "—"

internal fun inspectorRawRows(records: List<JsonObject>): List<InspectorRow> {
    var observedIndex = 0
    return records.map { value ->
        val stream = value["type"].str() == "assistant-stream"
        val baseline = value["activeAttempt"] is JsonObject
        val type = when {
            stream -> "assistant-stream/" + (value["frame"]["chunk"]["type"].str() ?: value["frame"]["type"].str().orEmpty())
            baseline -> "assistant-stream/baseline"
            else -> value["type"].str() ?: "unknown"
        }
        val seq = if (stream || baseline) null else value["seq"].long()
        InspectorRow(
            key = seq?.let { "event:$it" } ?: "observed:${value["frame"]["attemptId"].str().orEmpty()}:${observedIndex++}",
            type = type, value = value, seq = seq,
            time = if (stream) value["frame"]["time"].long() else value["time"].long(),
        )
    }
}

/** Inspect the same Android chat projection, including its real ProcessStep members.
 * Hidden reducer items remain roots at their input anchor; they are never assigned to a turn. */
internal fun inspectorGroupRows(st: ChatState, policy: PresentationPolicy): List<InspectorRow> {
    val positions = st.items.mapIndexed { index, item -> item.key to index }.toMap()
    val folded = foldChatItems(st.items, policy, st.running, st.turns)
    val controlAnchors = folded.filterIsInstance<ChatRow.Process>().filter { it.turnSummary }
        .associate { control ->
            control.key to st.items.indexOfFirst { it.inspectorTurn() == control.turn }.takeIf { it >= 0 }
        }
    val covered = HashSet<String>()
    var lastPosition = -1
    val anchored = folded.map { row ->
        val members = when (row) {
            is ChatRow.Process -> row.members
            is ChatRow.User -> listOf(row.item)
            is ChatRow.Answer -> listOf(row.item)
            is ChatRow.Tool -> listOf(row.item)
            is ChatRow.Reasoning -> listOf(row.item)
            is ChatRow.Notice -> listOf(row.item)
            is ChatRow.Deliverable -> listOf(row.item)
            is ChatRow.Changes -> listOf(row.item)
        }
        covered.addAll(members.map { it.key })
        val position = members.firstOrNull()?.let { positions[it.key] } ?: controlAnchors[row.key] ?: lastPosition
        lastPosition = position
        val result = if (row is ChatRow.Process) InspectorRow(
            key = "group:${row.key}", type = "ChatRow.Process",
            value = jsonOf("key" to row.key, "title" to row.title, "closed" to row.closed,
                "defaultOpen" to row.defaultOpen, "durationMs" to row.durationMs,
                "turnSummary" to row.turnSummary, "canCollapse" to row.canCollapse,
                "turn" to row.turn, "grouped" to row.grouped, "controlledKeys" to row.controlledKeys.toList(),
                "activityKind" to row.activityKind, "detail" to row.detail,
                "members" to row.members.map { it.key }, "steps" to row.steps.map { it.key }),
            children = row.steps.map { step -> InspectorRow(
                key = "step:${row.key}:${step.key}", type = "ProcessStep",
                value = jsonOf("key" to step.key, "step" to step.step, "members" to step.members.map { it.key }),
                children = step.members.map(::inspectorItemRow),
            ) },
        ) else inspectorItemRow(members.single())
        position to result
    }
    val unlisted = st.items.mapIndexedNotNull { index, item ->
        if (item.key in covered) null else index to inspectorItemRow(item)
    }
    return (anchored + unlisted).sortedBy { it.first }.map { it.second }
}

private fun inspectorItemRow(item: Item): InspectorRow {
    val fields = when (item) {
        is Item.User -> jsonOf("text" to item.text, "images" to item.images, "pending" to item.pending,
            "at" to item.at, "seq" to item.seq, "messageSourceJson" to item.messageSourceJson,
            "imageRefs" to item.imageRefs.map { jsonOf("attachmentId" to it.attachmentId, "mediaType" to it.mediaType) })
        is Item.Text -> jsonOf("text" to item.text, "live" to item.live, "at" to item.at,
            "seq" to item.seq, "messageId" to item.messageId, "turn" to item.turn, "step" to item.step)
        is Item.Reasoning -> jsonOf("text" to item.text, "durationMs" to item.durationMs,
            "live" to item.live, "turn" to item.turn, "step" to item.step, "seq" to item.seq)
        is Item.Tool -> jsonOf("callId" to item.callId, "name" to item.name, "arguments" to item.arguments,
            "status" to item.status.name, "result" to item.result, "error" to item.error,
            "startedAt" to item.startedAt, "endedAt" to item.endedAt, "schemaJson" to item.schemaJson,
            "turn" to item.turn, "step" to item.step, "parentCallId" to item.parentCallId)
        is Item.Context -> jsonOf("text" to item.text, "sourceKind" to item.sourceKind,
            "at" to item.at, "messageSourceJson" to item.messageSourceJson)
        is Item.System -> jsonOf("label" to item.label, "text" to item.text, "at" to item.at,
            "previousText" to item.previousText, "toolsJson" to item.toolsJson, "previousToolsJson" to item.previousToolsJson)
        is Item.Notice -> jsonOf("text" to item.text, "turn" to item.turn)
        is Item.Compacted -> jsonOf("compactionId" to item.compactionId, "status" to item.status.name,
            "summary" to item.summary, "error" to item.error, "interrupted" to item.interrupted,
            "startedAt" to item.startedAt, "endedAt" to item.endedAt, "turn" to item.turn)
        is Item.Deliverable -> jsonOf("turn" to item.turn,
            "files" to item.files.map { jsonOf("path" to it.path, "description" to it.description) })
        is Item.Changes -> jsonOf("turn" to item.turn, "count" to item.count, "name" to item.name, "seq" to item.seq)
    }
    val type = when (item) {
        is Item.User -> "Item.User"
        is Item.Text -> "Item.Text"
        is Item.Reasoning -> "Item.Reasoning"
        is Item.Tool -> "Item.Tool"
        is Item.Context -> "Item.Context"
        is Item.System -> "Item.System"
        is Item.Notice -> "Item.Notice"
        is Item.Compacted -> "Item.Compacted"
        is Item.Deliverable -> "Item.Deliverable"
        is Item.Changes -> "Item.Changes"
    }
    return InspectorRow("item:${item.key}", type, JsonObject(jsonOf("key" to item.key) + fields))
}

private fun Item.inspectorTurn(): Int? = when (this) {
    is Item.Text -> turn
    is Item.Reasoning -> turn
    is Item.Tool -> turn
    is Item.Notice -> turn
    is Item.Compacted -> turn
    is Item.Deliverable -> turn
    is Item.Changes -> turn
    else -> null
}
