package dev.dsh.pocket.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Session-scoped bridge between the inspector sheet and the actual conversation. */
internal object InspectorNavigation {
    var sessionId by mutableStateOf<String?>(null)
    var picking by mutableStateOf(false)
    var pickedItemKey by mutableStateOf<String?>(null)
    var revealItemKey by mutableStateOf<String?>(null)

    fun begin(sessionId: String) {
        this.sessionId = sessionId
        pickedItemKey = null
        revealItemKey = null
        picking = true
    }

    fun pick(itemKey: String) {
        if (!picking || itemKey.isBlank()) return
        pickedItemKey = itemKey
        picking = false
    }

    fun cancel() { picking = false }

    fun reveal(sessionId: String, itemKey: String) {
        this.sessionId = sessionId
        picking = false
        pickedItemKey = null
        revealItemKey = itemKey
    }

    fun reset() {
        sessionId = null
        picking = false
        pickedItemKey = null
        revealItemKey = null
    }
}

internal fun inspectorRowIndex(rows: List<ChatRow>, itemKey: String): Int = rows.indexOfFirst { row ->
    row.key == itemKey || row is ChatRow.Process && row.members.any { it.key == itemKey }
}

internal fun inspectorPickKey(row: ChatRow): String =
    if (row is ChatRow.Process) row.members.firstOrNull()?.key ?: row.key else row.key

private class InspectorChatTargets {
    val rows = mutableMapOf<Any, Pair<String, LayoutCoordinates>>()
    var surface: LayoutCoordinates? = null
    var scrollBy: ((Float) -> Unit)? = null

    fun hit(position: Offset): String? {
        val rootPosition = surface?.takeIf { it.isAttached }?.localToRoot(position) ?: return null
        return rows.values.asSequence()
            .filter { (_, coordinates) -> coordinates.isAttached && coordinates.boundsInRoot().contains(rootPosition) }
            .minByOrNull { (_, coordinates) -> coordinates.boundsInRoot().let { it.width * it.height } }?.first
    }
}

private val LocalInspectorChatTargets = staticCompositionLocalOf<InspectorChatTargets?> { null }

/** Like the desktop click-catching mask: no child button, link or composer receives a pick gesture. */
@Composable
internal fun InspectorChatSurface(sessionId: String?, content: @Composable () -> Unit) {
    val targets = remember(sessionId) { InspectorChatTargets() }
    val picking = InspectorNavigation.sessionId == sessionId && InspectorNavigation.picking
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(sessionId) {
        if (InspectorNavigation.sessionId != sessionId) InspectorNavigation.reset()
        onDispose { InspectorNavigation.reset() }
    }
    LaunchedEffect(picking) {
        if (picking) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    BackHandler(enabled = picking) { InspectorNavigation.cancel() }
    CompositionLocalProvider(LocalInspectorChatTargets provides targets) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { targets.surface = it }
            .then(if (picking) Modifier.pointerInput(targets, sessionId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    val startedOnRow = targets.hit(down.position) != null
                    var dragging = false
                    var previous = down.position
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        event.changes.forEach { it.consume() }
                        if (pointer == null) break
                        if ((pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                        if (dragging && startedOnRow) targets.scrollBy?.invoke(previous.y - pointer.position.y)
                        previous = pointer.position
                        if (!pointer.pressed && !dragging && InspectorNavigation.sessionId == sessionId) {
                            targets.hit(pointer.position)?.let(InspectorNavigation::pick)
                        }
                    } while (event.changes.any { it.pressed })
                }
            } else Modifier)) { content() }
    }
}

@Composable
internal fun InspectorTranscriptScroll(scrollBy: (Float) -> Unit) {
    val targets = LocalInspectorChatTargets.current
    val currentScroll by rememberUpdatedState(scrollBy)
    DisposableEffect(targets) {
        targets?.scrollBy = { currentScroll(it) }
        onDispose { targets?.scrollBy = null }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InspectorChatTarget(
    itemKey: String,
    highlighted: Boolean = false,
    reveal: Boolean = false,
    content: @Composable () -> Unit,
) {
    val targets = LocalInspectorChatTargets.current
    val token = remember(itemKey, targets) { Any() }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal) {
        if (reveal) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    DisposableEffect(token, targets) {
        onDispose { targets?.rows?.remove(token) }
    }
    Box(Modifier.fillMaxWidth().testTag("inspectorChat:$itemKey").bringIntoViewRequester(requester)
        .onGloballyPositioned { targets?.rows?.set(token, itemKey to it) }
        .then(if (highlighted || reveal) Modifier.background(T.Accent.copy(alpha = 0.08f))
            .border(1.dp, T.Accent.copy(alpha = 0.5f), T.R8) else Modifier)) { content() }
}
