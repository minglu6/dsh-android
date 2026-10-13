package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.*
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
    val identity: String = seq?.toString().orEmpty(),
    val location: String = "",
    val summary: String? = null,
    val defaultCollapsed: Boolean = false,
)

/** Desktop containment semantics: only surrounding stars are optional, not internal wildcards. */
private fun inspectorTypeQuery(filter: String): String = filter.trim().trim('*').trim()

internal fun inspectorTypeMatches(type: String, filter: String): Boolean =
    type.contains(inspectorTypeQuery(filter), ignoreCase = true)

internal fun inspectorTypeSuggestions(rows: List<InspectorRow>, filter: String): List<String> = buildSet {
    fun visit(row: InspectorRow) {
        if (inspectorTypeMatches(row.type, filter)) add(row.type)
        row.children.forEach(::visit)
    }
    rows.forEach(::visit)
}.sorted()

internal data class InspectorVisibleRow(val row: InspectorRow, val depth: Int, val matches: Boolean)

/** Keep matching descendants' actual ancestors without counting those ancestors as matches. */
internal fun inspectorVisibleRows(
    rows: List<InspectorRow>, filter: String, collapsed: Set<String>,
    expanded: Set<String> = emptySet(),
): List<InspectorVisibleRow> = buildList {
    fun visit(row: InspectorRow, depth: Int): List<InspectorVisibleRow> {
        val children = row.children.flatMap { visit(it, depth + 1) }
        val matches = inspectorTypeMatches(row.type, filter)
        if (!matches && children.isEmpty()) return emptyList()
        return buildList {
            add(InspectorVisibleRow(row, depth, matches))
            if (row.key !in collapsed && (inspectorTypeQuery(filter).isNotEmpty() ||
                    !row.defaultCollapsed || row.key in expanded)) addAll(children)
        }
    }
    rows.forEach { addAll(visit(it, 0)) }
}

private val inspectorUtc = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
    .withZone(ZoneOffset.UTC)
internal fun inspectorTime(time: Long?): String = time?.let { inspectorUtc.format(Instant.ofEpochMilli(it)) }.orEmpty()

/** Bounded horizontal display only; the original JSON remains the detail value. */
internal fun inspectorPreview(value: JsonElement, hiddenRootFields: Set<String> = emptySet()): String {
    fun preview(value: JsonElement, depth: Int): String {
        if (value is JsonPrimitive) return if (value.isString) value.content.take(180) else value.toString()
        if (depth > 1) return if (value is JsonArray) "[${value.size}]" else "{…}"
        val fields = when (value["type"].str()) {
            "tool-call-delta" -> setOf("argumentsDelta")
            "reasoning-delta" -> setOf("text")
            else -> null
        }
        val entries = when (value) {
            is JsonObject -> value.entries.map { it.key to it.value }
            is JsonArray -> value.mapIndexed { i, item -> i.toString() to item }
            else -> emptyList()
        }.filter { (key, _) ->
            !(depth == 0 && (key.equals("time", true) || key in hiddenRootFields)) &&
                if (fields == null) key.lowercase(Locale.ROOT) !in setOf("type", "seq", "sequence") else key in fields
        }
        if (entries.size == 1) return preview(entries.single().second, depth + 1)
        return entries.take(5).joinToString(" · ") { (key, item) -> "$key: ${preview(item, depth + 1)}" }.take(240)
    }
    return preview(value, 0)
}

private fun inspectorLocation(value: JsonElement?): String {
    val turn = value["turn"].long() ?: return ""
    return "$turn" + (value["step"].long()?.let { "/$it" } ?: "")
}

private class InspectorNode(var row: InspectorRow) {
    val children = ArrayList<InspectorNode>()
    fun freeze(): InspectorRow = row.copy(children = children.map { it.freeze() })
}

/** Loaded starts alone create ranges; a partial history never gains invented Turn/Step headers. */
internal fun inspectorRawRows(records: List<JsonObject>): List<InspectorRow> {
    val roots = ArrayList<InspectorNode>()
    var turn: InspectorNode? = null
    var step: InspectorNode? = null
    val attempts = HashMap<String, InspectorStream>()
    fun attach(node: InspectorNode, parent: InspectorNode? = step ?: turn) {
        if (parent == null) roots.add(node) else parent.children.add(node)
    }
    records.forEachIndexed { ordinal, value ->
        val frame = value["frame"].obj()
        val baseline = value["activeAttempt"].obj()
        if (value["type"].str() == "assistant-stream" || baseline != null) {
            val source = baseline ?: frame
            val attemptId = source?.get("attemptId").str()
            if (attemptId != null) {
                val stream = attempts.getOrPut(attemptId) {
                    val node = InspectorNode(InspectorRow("attempt:$attemptId", "assistant/live", value,
                        identity = attemptId, location = inspectorLocation(source)))
                    attach(node)
                    InspectorStream(node)
                }
                stream.sources.add(value)
                val location = inspectorLocation(source).ifEmpty { stream.root.row.location }
                stream.root.row = stream.root.row.copy(
                    value = if (stream.sources.size == 1) value else jsonOf("records" to stream.sources),
                    location = location,
                )
                baseline?.get("stream")?.arr()?.forEach { stream.record(it) }
                if (frame?.get("type").str() == "chunk") {
                    frame?.get("chunk")?.obj()?.let { stream.chunk(it, frame["time"].long(), frame["index"].long()) }
                }
                return@forEachIndexed
            }
        }
        val type = value["type"].str() ?: "unknown"
        val seq = value["seq"].long()
        val node = InspectorNode(InspectorRow(seq?.let { "event:$it" } ?: "observed:$ordinal",
            type, value, seq, value["time"].long(), location = inspectorLocation(value["data"])))
        when (type) {
            "turn/start" -> { step = null; attach(node, null); turn = node }
            "step/start" -> { attach(node, turn); step = node }
            "step/end" -> { attach(node); step = null }
            "turn/end" -> { attach(node, turn); step = null; turn = null }
            else -> attach(node)
        }
        if (type == "assistant/message" || type == "assistant/attempt") {
            val stream = InspectorStream(node)
            value["data"]["stream"].arr()?.forEach { stream.record(it) }
        }
    }
    return roots.map { it.freeze() }
}

/** A block owns its indexed chunks even when different blocks are interleaved. */
private class InspectorStream(val root: InspectorNode) {
    val sources = ArrayList<JsonObject>()
    private val blocks = HashMap<Long, InspectorNode>()
    private val reasoning = HashMap<Long, String>()
    private var count = 0L

    fun chunk(value: JsonObject, time: Long?, index: Long? = null) {
        val ordinal = index ?: count
        count = maxOf(count, ordinal + 1)
        val type = value["type"].str() ?: "unknown"
        val row = InspectorNode(InspectorRow("${root.row.key}/chunk:$ordinal", type, value,
            time = time, identity = ordinal.toString(), location = root.row.location))
        val blockIndex = value["index"].long()
        val parent = if (type == "block-start") null else blocks[blockIndex]
        (parent ?: root).children.add(row)
        if (root.row.type == "assistant/live") root.row = root.row.copy(time = time)
        if (type == "block-start" && blockIndex != null) {
            blocks[blockIndex] = row
            if (value["blockType"].str() == "reasoning") {
                reasoning[blockIndex] = ""
                row.row = row.row.copy(summary = "blockType: reasoning")
            }
        } else if (parent != null && blockIndex != null) {
            if (type == "reasoning-delta" && blockIndex in reasoning) {
                val text = (reasoning.getValue(blockIndex) + value["text"].str().orEmpty()).take(240)
                reasoning[blockIndex] = text
                parent.row = parent.row.copy(summary = ("blockType: reasoning" +
                    if (text.isEmpty()) "" else " · $text").take(240))
            }
            if (type == "block-end") parent.row = parent.row.copy(defaultCollapsed = true)
        }
    }

    fun record(value: JsonElement) {
        val record = value.obj() ?: return // Original malformed values remain in the owning event.
        if (record["type"].str() == "chunk" && record["chunk"] is JsonObject) {
            chunk(record.getValue("chunk") as JsonObject, record["time"].long())
            return
        }
        val type = record["type"].str()
        val tool = type == "tool-call-chunks"
        val members = record[if (tool) "args" else "texts"].arr()
        val gaps = record["dt"].arr()
        val index = record["index"].inspectorInteger()
        var time = record["time0"].inspectorInteger()
        // Do not manufacture deltas or timestamps from unknown / incomplete compact records.
        if (type !in setOf("text-chunks", "reasoning-chunks", "tool-call-chunks") ||
            members.isNullOrEmpty() || gaps == null || gaps.size != members.size - 1 ||
            index == null || index < 0 || time == null ||
            members.any { it !is JsonPrimitive || !it.isString } ||
            gaps.any { it.inspectorInteger() == null } ||
            (tool && (record["id"].str().isNullOrEmpty() ||
                ("name" in record && record["name"].str().isNullOrEmpty())))) {
            chunk(record, record["time"].long())
            return
        }
        var lastTime = time!!
        for (gap in gaps) {
            lastTime += gap.inspectorInteger()!!
            if (lastTime !in -INSPECTOR_SAFE_INTEGER..INSPECTOR_SAFE_INTEGER) {
                chunk(record, record["time"].long())
                return
            }
        }
        members.forEachIndexed { memberIndex, text ->
            if (memberIndex > 0) time = time!! + gaps[memberIndex - 1].long()!!
            val delta = buildJsonObject {
                put("type", when (type) {
                    "text-chunks" -> "text-delta"
                    "reasoning-chunks" -> "reasoning-delta"
                    else -> "tool-call-delta"
                })
                put("index", index)
                if (tool) {
                    put("id", record.getValue("id"))
                    record["name"]?.let { put("name", it) }
                    put("argumentsDelta", text)
                } else put("text", text)
            }
            chunk(delta, time)
        }
    }
}

private const val INSPECTOR_SAFE_INTEGER = 9_007_199_254_740_991L
private fun JsonElement?.inspectorInteger(): Long? =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?.takeIf { it in -INSPECTOR_SAFE_INTEGER..INSPECTOR_SAFE_INTEGER }

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
            identity = row.key,
            location = row.turn?.toString().orEmpty(),
            children = row.steps.map { step -> InspectorRow(
                key = "step:${row.key}:${step.key}", type = "ProcessStep",
                value = jsonOf("key" to step.key, "step" to step.step, "members" to step.members.map { it.key }),
                children = step.members.map(::inspectorItemRow),
                identity = step.key,
                location = step.members.firstOrNull()?.let { inspectorItemLocation(it) }.orEmpty(),
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
    val value = JsonObject(jsonOf("key" to item.key) + fields)
    return InspectorRow("item:${item.key}", type, value, seq = item.inspectorSeq(),
        time = fields["at"].long() ?: fields["startedAt"].long(), identity = item.key,
        location = inspectorItemLocation(item),
        summary = inspectorPreview(value, setOf("key", "turn", "step", "at", "startedAt")))
}

private fun Item.inspectorSeq(): Long? = when (this) {
    is Item.User -> seq
    is Item.Text -> seq
    is Item.Reasoning -> seq
    is Item.Changes -> seq
    else -> null
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

private fun Item.inspectorStep(): Int? = when (this) {
    is Item.Text -> step
    is Item.Reasoning -> step
    is Item.Tool -> step
    else -> null
}

private fun inspectorItemLocation(item: Item): String =
    item.inspectorTurn()?.let { turn -> "$turn" + (item.inspectorStep()?.let { "/$it" } ?: "") }.orEmpty()

private fun inspectorFind(rows: List<InspectorRow>, predicate: (InspectorRow) -> Boolean): InspectorRow? {
    for (row in rows) {
        if (predicate(row)) return row
        inspectorFind(row.children, predicate)?.let { return it }
    }
    return null
}

/** Resolve only identities and locations retained by the actual Android projection. */
internal fun inspectorRowForItem(rows: List<InspectorRow>, item: Item): InspectorRow? {
    inspectorFind(rows) { it.key == "item:${item.key}" }?.let { return it }
    if (item is Item.Tool) inspectorFind(rows) {
        it.value["data"]["callId"].str() == item.callId ||
            (it.type == "tool-call-delta" && it.value["id"].str() == item.callId)
    }?.let { return it }
    val seq = item.inspectorSeq()
    val exact = if (seq == null) null else inspectorFind(rows) { it.seq == seq }
    val location = inspectorItemLocation(item)
    val assistant = exact ?: if (location.isEmpty()) null else inspectorFind(rows) {
        it.location == location && it.type in setOf("assistant/message", "assistant/attempt", "assistant/live")
    }
    if (item is Item.Reasoning && assistant != null) inspectorFind(assistant.children) {
        it.type == "block-start" && it.value["blockType"].str() == "reasoning"
    }?.let { return it }
    if (exact != null) return exact
    if (item is Item.Text || item is Item.Reasoning) assistant?.let { return it }
    if (location.isEmpty()) return null
    return inspectorFind(rows) { it.location == location && it.type == "step/start" }
        ?: inspectorFind(rows) { it.location == item.inspectorTurn()?.toString() && it.type == "turn/start" }
        ?: inspectorFind(rows) { it.location == location }
}

/** Reverse navigation never guesses a chat item when neither identity nor Turn/Step is known. */
internal fun inspectorItemForRow(st: ChatState, row: InspectorRow): String? {
    if (row.key.startsWith("item:")) return st.items.firstOrNull { "item:${it.key}" == row.key }?.key
    if (row.type == "ChatRow.Process" || row.type == "ProcessStep") {
        for (child in row.children) inspectorItemForRow(st, child)?.let { return it }
        return null
    }
    val callId = row.value["data"]["callId"].str()
        ?: if (row.type == "tool-call-delta") row.value["id"].str() else null
    if (callId != null) st.items.firstOrNull { it is Item.Tool && it.callId == callId }?.let { return it.key }
    if (row.seq != null) {
        if (row.type.startsWith("assistant/")) st.items.firstOrNull {
            it is Item.Text && it.seq == row.seq
        }?.let { return it.key }
        st.items.firstOrNull { it.inspectorSeq() == row.seq }?.let { return it.key }
    }
    if (row.location.isEmpty()) return null
    val matching = st.items.filter {
        if ('/' in row.location) inspectorItemLocation(it) == row.location
        else it.inspectorTurn()?.toString() == row.location
    }
    if (row.type == "reasoning-delta" || (row.type == "block-start" && row.value["blockType"].str() == "reasoning")) {
        matching.firstOrNull { it is Item.Reasoning }?.let { return it.key }
    }
    if (row.type == "text-delta" || row.type.startsWith("assistant/")) {
        matching.firstOrNull { it is Item.Text }?.let { return it.key }
    }
    return matching.firstOrNull()?.key
}
