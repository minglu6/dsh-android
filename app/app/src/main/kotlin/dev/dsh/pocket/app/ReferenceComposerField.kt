package dev.dsh.pocket.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

internal enum class ComposerReferenceKind { File, Directory, Session }

/** Offsets and canonical text always address the original UTF-16 draft, never its short label. */
internal data class ComposerReference(
    val start: Int,
    val end: Int,
    val canonical: String,
    val label: String,
    val kind: ComposerReferenceKind,
    val path: String? = null,
)

private val referencePattern = Regex("""(?<!\S)@(?:\[((?:\\.|[^\\\]])*)\]\((dsh-session:[A-Za-z0-9_-]+)\)|"([^"\r\n]+)"|([^\s"\u0000-\u001f\u007f-\u009f]+))""")
private val escapedReferenceLabel = Regex("""\\(.)""")

internal data class DisplayReference(val reference: ComposerReference, val start: Int, val end: Int)

internal class ReferenceProjection(
    val original: String,
    val display: String,
    val references: List<DisplayReference>,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val at = offset.coerceIn(0, original.length)
        var delta = 0
        for (span in references) {
            val ref = span.reference
            if (at <= ref.start) break
            if (at < ref.end) return if (at - ref.start < ref.end - at) span.start else span.end
            delta += (span.end - span.start) - (ref.end - ref.start)
        }
        return at + delta
    }

    override fun transformedToOriginal(offset: Int): Int {
        val at = offset.coerceIn(0, display.length)
        var delta = 0
        for (span in references) {
            if (at <= span.start) break
            if (at < span.end) return if (at - span.start < span.end - at) span.reference.start else span.reference.end
            delta += (span.reference.end - span.reference.start) - (span.end - span.start)
        }
        return at + delta
    }
}

private data class ReferenceEdit(val start: Int, val oldEnd: Int, val newEnd: Int)

private fun referenceEdit(before: String, after: String): ReferenceEdit {
    var start = 0
    while (start < before.length && start < after.length && before[start] == after[start]) start++
    // A differing low surrogate must not split its shared high surrogate.
    if (start > 0 && start < before.length && before[start].isLowSurrogate() && before[start - 1].isHighSurrogate()) start--
    var oldEnd = before.length
    var newEnd = after.length
    while (oldEnd > start && newEnd > start && before[oldEnd - 1] == after[newEnd - 1]) { oldEnd--; newEnd-- }
    if (oldEnd < before.length && oldEnd > start && before[oldEnd].isLowSurrogate() && before[oldEnd - 1].isHighSurrogate()) { oldEnd++; newEnd++ }
    return ReferenceEdit(start, oldEnd, newEnd)
}

internal fun referenceProjection(value: TextFieldValue, previous: ReferenceProjection? = null): ReferenceProjection {
    val text = value.text
    val edit = previous?.let { referenceEdit(it.original, text) }
    val retained = previous?.references.orEmpty().mapNotNull { span ->
        val ref = span.reference
        when {
            ref.end <= edit!!.start -> ref.start
            ref.start >= edit.oldEnd -> ref.start + edit.newEnd - edit.oldEnd
            else -> null
        }?.let { it to ref.canonical }
    }.toMap()
    val active = if (value.selection.collapsed) composerTrigger(text, value.selection.end)?.takeIf { it.kind == '@' } else null
    val spans = ArrayList<DisplayReference>()
    val display = StringBuilder(text.length)
    var copied = 0
    for (match in referencePattern.findAll(text)) {
        val start = match.range.first
        val end = match.range.last + 1
        val session = match.groups[2] != null
        val quoted = match.groups[3] != null
        // Incomplete Markdown mentions are not filenames. A live query remains editable text.
        if (!session && !quoted && match.groupValues[4].startsWith("[")) continue
        if (!session && end < text.length && !text[end].isWhitespace()) continue
        if (active?.start == start && !session && (!quoted || active.quoted) && retained[start] != match.value) continue
        if (value.composition?.let { it.min < end && it.max > start } == true) continue
        val path = if (session) null else if (quoted) match.groupValues[3] else match.groupValues[4]
        if (path != null && (path.isEmpty() || path.any { it.code in 0..31 || it.code in 127..159 })) continue
        val kind = when { session -> ComposerReferenceKind.Session; path!!.endsWith('/') -> ComposerReferenceKind.Directory; else -> ComposerReferenceKind.File }
        val label = if (session) escapedReferenceLabel.replace(match.groupValues[1]) { it.groupValues[1] }
            else path!!.trimEnd('/').substringAfterLast('/').ifEmpty { "/" } + if (kind == ComposerReferenceKind.Directory && path != "/") "/" else ""
        val ref = ComposerReference(start, end, match.value, label, kind, path)
        display.append(text, copied, start)
        val displayStart = display.length
        // The em space is painted with the domain icon. NBSP keeps the short label on one line.
        display.append('\u2003').append('\u00a0')
        label.forEach { display.append(if (it.isWhitespace()) '\u00a0' else it) }
        spans += DisplayReference(ref, displayStart, display.length)
        copied = end
    }
    display.append(text, copied, text.length)
    return ReferenceProjection(text, display.toString(), spans)
}

/** Enforce atomic keyboard/IME deletion and selection while keeping Android's canonical clipboard. */
internal fun normalizeReferenceEdit(before: TextFieldValue, next: TextFieldValue, projection: ReferenceProjection): TextFieldValue {
    val refs = projection.references.map { it.reference }
    if (before.text == next.text) {
        fun boundary(at: Int, upper: Boolean): Int = refs.firstOrNull { at > it.start && at < it.end }
            ?.let { if (upper) it.end else it.start } ?: at
        val selection = if (next.selection.collapsed) {
            val at = next.selection.start
            val forward = at > before.selection.start
            TextRange(boundary(at, forward))
        } else {
            val low = boundary(next.selection.min, false)
            val high = boundary(next.selection.max, true)
            if (next.selection.reversed) TextRange(high, low) else TextRange(low, high)
        }
        return next.copy(selection = selection, composition = if (selection == next.selection) next.composition else null)
    }
    val selection = before.selection
    val suffixLength = before.text.length - selection.max
    val replacesSelection = !selection.collapsed &&
        next.text.length >= selection.min + suffixLength &&
        next.text.startsWith(before.text.substring(0, selection.min)) &&
        next.text.endsWith(before.text.substring(selection.max))
    val edit = if (replacesSelection) ReferenceEdit(selection.min, selection.max, next.text.length - suffixLength)
        else referenceEdit(before.text, next.text)
    var start = edit.start
    var end = edit.oldEnd
    for (ref in refs) {
        val overlaps = if (edit.start == edit.oldEnd) edit.start > ref.start && edit.start < ref.end
            else ref.start < edit.oldEnd && ref.end > edit.start
        if (overlaps) { start = minOf(start, ref.start); end = maxOf(end, ref.end) }
    }
    val inserted = next.text.substring(edit.start, edit.newEnd)
    if (start == edit.start && end == edit.oldEnd) {
        // Adjacent typing must not silently change a committed path's canonical identity.
        if (edit.start == edit.oldEnd && inserted.isNotEmpty()) {
            val leading = if (!inserted.first().isWhitespace() && refs.any { it.end == start }) " " else ""
            val trailing = if (!inserted.last().isWhitespace() && refs.any { it.start == start }) " " else ""
            if (leading.isNotEmpty() || trailing.isNotEmpty()) {
                fun moved(at: Int): Int = at + when {
                    at < edit.start -> 0
                    at <= edit.newEnd -> leading.length
                    else -> leading.length + trailing.length
                }
                fun movedRange(range: TextRange) = TextRange(moved(range.start), moved(range.end))
                return next.copy(
                    text = before.text.replaceRange(start, end, leading + inserted + trailing),
                    selection = movedRange(next.selection),
                    composition = next.composition?.let { movedRange(it) },
                )
            }
        }
        return next
    }
    val text = before.text.replaceRange(start, end, inserted)
    fun mapped(at: Int): Int = when {
        at <= edit.start -> minOf(at, start)
        at < edit.newEnd -> start + at - edit.start
        else -> start + inserted.length + (at - edit.newEnd - (end - edit.oldEnd)).coerceAtLeast(0)
    }.coerceIn(0, text.length)
    return TextFieldValue(text, TextRange(mapped(next.selection.start), mapped(next.selection.end)))
}

/** Arrows cross an entire chip; do not let a many-to-one visual offset map trap the caret. */
internal fun referenceKeyEdit(
    value: TextFieldValue,
    projection: ReferenceProjection,
    key: Key,
    shift: Boolean = false,
): TextFieldValue? {
    val caret = value.selection.end
    val backwards = key == Key.DirectionLeft || key == Key.Backspace
    if (key != Key.DirectionLeft && key != Key.DirectionRight && key != Key.Backspace && key != Key.Delete) return null
    if (!value.selection.collapsed && !shift) return null
    val ref = projection.references.firstOrNull {
        if (backwards) it.reference.end == caret else it.reference.start == caret
    }?.reference ?: return null
    if (key == Key.Backspace || key == Key.Delete) {
        if (!value.selection.collapsed || shift) return null
        return TextFieldValue(value.text.removeRange(ref.start, ref.end), TextRange(ref.start))
    }
    val target = if (backwards) ref.start else ref.end
    return value.copy(selection = TextRange(if (shift) value.selection.start else target, target), composition = null)
}

/** Native field: caller owns focus/key arbitration; presentation never replaces the draft's wire text. */
@Composable
internal fun ReferenceComposerField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    onOpenFile: (String) -> Unit = {},
) {
    val history = remember { arrayOfNulls<ReferenceProjection>(1) }
    val projection = remember(value) { referenceProjection(value, history[0]) }
    val currentProjection by rememberUpdatedState(projection)
    val openFile by rememberUpdatedState(onOpenFile)
    SideEffect { history[0] = projection }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val accent = T.Accent
    val chat = rememberVectorPainter(D.referenceSession)
    val file = rememberVectorPainter(D.referenceFile)
    val folder = rememberVectorPainter(D.referenceFolder)
    val side = with(LocalDensity.current) { 16.dp.toPx() }
    val transformation = remember(projection, accent) {
        VisualTransformation {
            val annotated = buildAnnotatedString {
                append(projection.display)
                for (span in projection.references) addStyle(SpanStyle(color = accent), span.start, span.end)
            }
            TransformedText(annotated, projection)
        }
    }
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(normalizeReferenceEdit(value, it, projection)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = T.Ink),
        cursorBrush = SolidColor(accent),
        visualTransformation = transformation,
        onTextLayout = { layout = it },
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) false
                else referenceKeyEdit(value, projection, event.key, event.isShiftPressed)?.let {
                    onValueChange(it)
                    true
                } ?: false
            }
            .verticalScroll(rememberScrollState())
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    val result = layout
                    if (result != null && result.layoutInput.text.text == projection.display) {
                        for (span in projection.references) {
                            val box = result.getBoundingBox(span.start)
                            val y = box.top + (box.height - side) / 2
                            val painter = when (span.reference.kind) {
                                ComposerReferenceKind.Session -> chat
                                ComposerReferenceKind.Directory -> folder
                                ComposerReferenceKind.File -> file
                            }
                            translate(box.left, y) { with(painter) { draw(Size(side, side), colorFilter = ColorFilter.tint(accent)) } }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                // Observe taps without taking focus, consuming editor keys, or stealing selection drags.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    if (up != null && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        val result = layout
                        val projection = currentProjection
                        if (result != null && result.layoutInput.text.text == projection.display) {
                            val offset = result.getOffsetForPosition(up.position)
                            val span = projection.references.firstOrNull { offset >= it.start && offset < it.end }
                            if (span?.reference?.kind == ComposerReferenceKind.File &&
                                result.getBoundingBox(offset.coerceAtMost(projection.display.lastIndex)).contains(up.position)) {
                                openFile(span.reference.path!!)
                            }
                        }
                    }
                }
            },
    )
}
