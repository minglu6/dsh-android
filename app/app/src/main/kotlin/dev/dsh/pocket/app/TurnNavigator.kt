package dev.dsh.pocket.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.TurnOutlineEntry
import dev.dsh.pocket.protocol.TurnRecord
import kotlinx.coroutines.flow.first

private val turnPreviewWhitespace = Regex("\\s+")

internal data class TranscriptTurnMark(
    val turn: Int,
    val prompt: String,
    val response: String,
    val anchorKey: String? = null,
    val seq: Long? = null,
)

/** Merge Host identities with the actual visible rows, never local row numbers. */
internal fun transcriptTurnMarks(
    rows: List<ChatRow>,
    turns: List<TurnRecord>,
    outline: List<TurnOutlineEntry>,
): List<TranscriptTurnMark> {
    val marks = linkedMapOf<Int, TranscriptTurnMark>()
    val starts = mutableMapOf<Int, Long>()
    val ends = mutableMapOf<Int, Long>()
    for (entry in outline) {
        starts[entry.turn] = entry.seq
        marks[entry.turn] = TranscriptTurnMark(entry.turn, entry.prompt, entry.response, seq = entry.seq)
    }
    for (record in turns) {
        record.startSeq?.let { seq ->
            starts[record.turn] = seq
            marks.putIfAbsent(record.turn, TranscriptTurnMark(record.turn, "", "", seq = seq))
        }
        record.endSeq?.let { ends[record.turn] = it }
    }
    val boundaries = starts.entries.sortedBy { it.value }
    fun userTurn(user: Item.User): Int? {
        if (user.pending) return null
        val seq = user.seq ?: return null
        // A steering message stays in its existing Turn; an idle echo starts none.
        val match = boundaries.binarySearch { it.value.compareTo(seq) }
        val index = if (match >= 0) match else -match - 2
        val boundary = boundaries.getOrNull(index) ?: return null
        if (ends[boundary.key]?.let { seq > it } == true) return null
        return boundary.key
    }
    val anchoredUsers = HashSet<Int>()
    val prompts = mutableMapOf<Int, String>()
    val responses = mutableMapOf<Int, String>()
    fun preview(text: String, limit: Int): String {
        val normalized = text.take(limit * 2).replace(turnPreviewWhitespace, " ").trim()
        return if (normalized.length >= limit) normalized.take(limit - 1).trimEnd() + "…"
            else if (text.length > limit * 2) "$normalized…" else normalized
    }
    fun response(turn: Int, text: String) {
        if (text.isNotBlank()) responses[turn] = preview(text, 120)
    }
    for (row in rows) {
        val turn = when (row) {
            is ChatRow.User -> userTurn(row.item)
            is ChatRow.Answer -> row.item.turn
            is ChatRow.Notice -> row.item.turn
            is ChatRow.Retry -> row.item.turn
            is ChatRow.TurnFailure -> row.item.turn
            is ChatRow.Tool -> row.item.turn
            is ChatRow.Reasoning -> row.item.turn
            is ChatRow.Process -> row.turn
            is ChatRow.Deliverable -> row.item.turn
            is ChatRow.Changes -> row.item.turn
        } ?: continue
        val previous = marks[turn] ?: TranscriptTurnMark(turn, "", "", seq = starts[turn])
        val firstUser = row is ChatRow.User && anchoredUsers.add(turn)
        // Desktop prefers the first human prompt even when a context row precedes it.
        if (previous.anchorKey == null || firstUser) marks[turn] = previous.copy(anchorKey = row.key)
        when (row) {
            is ChatRow.User -> if (firstUser && row.item.text.isNotBlank()) prompts[turn] = preview(row.item.text, 50)
            is ChatRow.Answer -> response(turn, row.item.text)
            is ChatRow.Process -> for (member in row.members) {
                if (member is Item.Text) response(turn, member.text)
            }
            else -> Unit
        }
    }
    return marks.values.sortedBy { it.turn }.map { mark ->
        mark.copy(prompt = prompts[mark.turn] ?: mark.prompt,
            response = responses[mark.turn] ?: mark.response)
    }
}

/** Compact fixed pitch with a wide transparent touch lane; ticks keep desktop geometry. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TranscriptTurnNavigator(
    items: List<TranscriptTurnMark>,
    activeTurn: Int?,
    busyTurn: Int?,
    onNavigate: (TranscriptTurnMark) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.size < 2) return
    val en = Pocket.prefs.language == AppLanguage.En
    val activeIndex = items.indexOfFirst { it.turn == activeTurn }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = activeIndex.coerceAtLeast(0))
    var touching by remember { mutableStateOf(false) }
    var previewTurn by remember { mutableStateOf<Int?>(null) }
    val viewportHeight by remember(list) { derivedStateOf { list.layoutInfo.viewportSize.height } }
    LaunchedEffect(activeTurn, activeIndex, items.size, viewportHeight) {
        if (activeIndex < 0 || viewportHeight == 0) return@LaunchedEffect
        // Active-turn changes may wait for a gesture/fling, but manual rail scrolling
        // alone never restarts follow and pulls the ladder back under the finger.
        snapshotFlow { touching || list.isScrollInProgress }.first { !it }
        val layout = list.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.index == activeIndex }
        if (visible == null || visible.offset < layout.viewportStartOffset ||
            visible.offset + visible.size > layout.viewportEndOffset) {
            val pitch = visible?.size ?: layout.visibleItemsInfo.firstOrNull()?.size ?: return@LaunchedEffect
            list.scrollToItem(activeIndex, -((viewportHeight - pitch) / 2).coerceAtLeast(0))
        }
    }
    val previewWidth = (LocalConfiguration.current.screenWidthDp - 72).coerceIn(1, 280).dp
    val previewGap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val previewPosition = remember(previewGap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
                (anchorBounds.left - popupContentSize.width - previewGap).coerceAtLeast(0),
                (anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2)
                    .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
            )
        }
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(vertical = 6.dp),
        horizontalAlignment = Alignment.End,
        modifier = modifier.width(48.dp).heightIn(max = minOf(420, items.size * 16 + 12).dp)
            .testTag("transcriptTurnNavigator")
            .semantics { contentDescription = if (en) "Turn navigation" else "轮次导航" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    touching = true
                    try { waitForUpOrCancellation() } finally { touching = false }
                }
            },
    ) {
        items(items, key = { it.turn }) { item ->
            val active = item.turn == activeTurn
            val busy = item.turn == busyTurn
            val turnLabel = if (en) "Turn ${item.turn}" else "第${item.turn}轮"
            val jump = if (item.anchorKey != null) {
                if (en) "Jump to $turnLabel" else "跳转到$turnLabel"
            } else {
                if (en) "Load and jump to $turnLabel" else "加载并跳转到$turnLabel"
            }
            Box(
                Modifier.width(48.dp).height(16.dp)
                    .testTag("transcript-turn-${item.turn}")
                    .semantics {
                        contentDescription = jump
                        selected = active
                        stateDescription = when {
                            busy -> if (en) "Loading" else "正在加载"
                            active -> if (en) "Current turn" else "当前轮次"
                            else -> ""
                        }
                    }
                    .combinedClickable(role = Role.Button, onClickLabel = jump,
                        onLongClickLabel = if (en) "Preview $turnLabel" else "预览$turnLabel",
                        onLongClick = { previewTurn = item.turn },
                        onClick = { previewTurn = null; onNavigate(item) }),
                contentAlignment = Alignment.CenterEnd,
            ) {
                val width = when {
                    active -> 20.dp
                    previewTurn == item.turn -> 18.dp
                    item.anchorKey == null -> 8.dp
                    else -> 12.dp
                }
                val color = when {
                    active -> T.Ink
                    previewTurn == item.turn -> T.Text3
                    item.anchorKey == null -> T.BorderStrong.copy(alpha = 0.6f)
                    else -> T.BorderStrong
                }
                Box(Modifier.padding(end = 8.dp).width(width).height(2.dp).background(color, CircleShape))
                // Static busy dot remains visible with reduced motion and has no idle loop.
                if (busy) Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp)
                    .size(4.dp).background(T.Ink, CircleShape))
                if (previewTurn == item.turn) {
                    Popup(popupPositionProvider = previewPosition,
                        onDismissRequest = { previewTurn = null }, properties = PopupProperties(focusable = true)) {
                        Surface(shape = T.R12, color = T.Surface, shadowElevation = 6.dp,
                            modifier = Modifier.width(previewWidth).testTag("transcriptTurnPreview")) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(item.prompt.ifBlank { turnLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = T.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                if (item.response.isNotBlank()) Text(item.response, maxLines = 3,
                                    overflow = TextOverflow.Ellipsis, color = T.Caption, fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
