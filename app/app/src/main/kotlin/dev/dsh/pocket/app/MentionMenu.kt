package dev.dsh.pocket.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.FileReference
import dev.dsh.pocket.protocol.SessionReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class MentionCandidates(
    val files: List<FileReference> = emptyList(),
    val sessions: List<SessionReference> = emptyList(),
)

/** Each discovery domain may fail independently; cancellation still owns the entire query. */
internal suspend fun loadMentionCandidates(
    quoted: Boolean,
    files: suspend () -> List<FileReference>,
    sessions: suspend () -> List<SessionReference>,
): MentionCandidates = coroutineScope {
    val fileRequest = async { mentionDomain(files) }
    val sessionRequest = if (quoted) null else async { mentionDomain(sessions) }
    MentionCandidates(fileRequest.await(), sessionRequest?.await().orEmpty())
}

private suspend fun <T> mentionDomain(request: suspend () -> List<T>): List<T> = try {
    request()
} catch (_: TimeoutCancellationException) {
    coroutineContext.ensureActive()
    emptyList()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    emptyList()
}

internal enum class MentionSection { Files, Subagents, Sessions }

internal data class MentionRow(
    val section: MentionSection,
    val title: String,
    val description: String?,
    val mention: String,
    val filePath: String? = null,
    val drill: String? = null,
)

internal fun mentionFileRow(file: FileReference, quoted: Boolean, withLocation: Boolean): MentionRow? {
    val directory = file.kind == "directory" || file.kind == "folder"
    val mention = fileReferenceToken(file.path, directory, quoted) ?: return null
    val path = file.path.trimEnd('/')
    return MentionRow(
        MentionSection.Files,
        path.substringAfterLast('/') + if (directory) "/" else "",
        path.substringBeforeLast('/', "").takeIf { withLocation && it.isNotEmpty() },
        mention,
        file.path,
        if (directory) mention.removeSuffix("\"") else null,
    )
}

internal data class MentionCrumb(val label: String, val drill: String)

internal fun mentionCrumbs(query: String, quoted: Boolean, drilled: Boolean): List<MentionCrumb> {
    if (!drilled || '/' !in query) return emptyList()
    val segments = query.substringBeforeLast('/').split('/').filter(String::isNotEmpty)
    val result = mutableListOf(MentionCrumb("", if (quoted) "@\"" else "@"))
    segments.forEachIndexed { index, label ->
        val token = fileReferenceToken(segments.take(index + 1).joinToString("/"), true, quoted)
            ?: return emptyList()
        result += MentionCrumb(label, token.removeSuffix("\""))
    }
    return result
}

/** Non-modal content: the composer owns the popup, focus and literal query text. */
@Composable
internal fun MentionMenu(
    sessionId: String?,
    query: String,
    quoted: Boolean,
    drilled: Boolean,
    onPick: (String) -> Unit,
    onDrill: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onKeyHandler: ((KeyEvent) -> Boolean) -> Unit = {},
) {
    var candidates by remember(sessionId) { mutableStateOf(MentionCandidates()) }
    var loading by remember(sessionId, query, quoted) { mutableStateOf(true) }
    var selected by remember(sessionId) { mutableIntStateOf(0) }
    val listed by Pocket.sessions.collectAsState()
    LaunchedEffect(sessionId, query, quoted) {
        if (sessionId == null) return@LaunchedEffect
        val result = loadMentionCandidates(
            quoted,
            files = { Pocket.referenceFiles(sessionId, query) },
            sessions = { Pocket.referenceSessions(sessionId, query) },
        )
        coroutineContext.ensureActive()
        candidates = result
        loading = false
    }
    val crumbs = remember(query, quoted, drilled) { mentionCrumbs(query, quoted, drilled) }
    val language = Pocket.prefs.language
    val rows = remember(candidates, listed, crumbs, quoted, language) {
        val now = System.currentTimeMillis()
        val summaries = listed.associateBy { it.sessionId }
        val sessionRows = (if (quoted) emptyList() else candidates.sessions).map { candidate ->
            val at = summaries[candidate.sessionId]?.updatedAt ?: candidate.updatedAt ?: candidate.createdAt
            val age = at?.let { formatSessionTime(it, now, language) }
            val location = if (candidate.sameWorkspace == true) null
                else candidate.cwd ?: if (language == AppLanguage.En) "(no cwd)" else "（无工作目录）"
            MentionRow(
                if (candidate.kind == "subagent") MentionSection.Subagents else MentionSection.Sessions,
                candidate.title ?: candidate.mention,
                listOfNotNull(location, age).joinToString(" · ").takeIf(String::isNotEmpty),
                candidate.mention,
            )
        }
        candidates.files.mapNotNull { mentionFileRow(it, quoted, crumbs.isEmpty()) } +
            sessionRows.filter { it.section == MentionSection.Subagents } +
            sessionRows.filter { it.section == MentionSection.Sessions }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(selected, rows) {
        if (rows.isNotEmpty()) {
            val index = selected.coerceIn(rows.indices)
            if (index != selected) selected = index
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
            when {
                visible == null -> listState.animateScrollToItem(index)
                visible.offset < info.viewportStartOffset -> listState.animateScrollBy((visible.offset - info.viewportStartOffset).toFloat())
                visible.offset + visible.size > info.viewportEndOffset ->
                    listState.animateScrollBy((visible.offset + visible.size - info.viewportEndOffset).toFloat())
            }
        }
    }
    val currentHandler by rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) false
        else if (event.key == Key.Escape || (event.key == Key.Tab && event.isShiftPressed)) {
            onDismiss()
            true
        } else when (event.key) {
            Key.DirectionDown -> { if (rows.isNotEmpty()) selected = (selected + 1) % rows.size; true }
            Key.DirectionUp -> { if (rows.isNotEmpty()) selected = (selected - 1 + rows.size) % rows.size; true }
            Key.Enter, Key.NumPadEnter, Key.Tab -> rows.getOrNull(selected)?.let { row ->
                if (!loading) {
                    if (event.key == Key.Tab && row.drill != null) onDrill(row.drill) else onPick(row.mention)
                }
                true
            } ?: false
            else -> false
        }
    }
    val registerHandler by rememberUpdatedState(onKeyHandler)
    DisposableEffect(Unit) {
        registerHandler { currentHandler(it) }
        onDispose { registerHandler { false } }
    }
    val menuColor = ThemeState.colors.Specificmenu.compositeOver(T.Bg)
    val scrollColor = ThemeState.colors.ScrollbarBgL2
    Surface(
        modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("mentionMenu"),
        shape = T.R12,
        color = menuColor,
        border = BorderStroke(0.5.dp, T.Border),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(4.dp)) {
            if (crumbs.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    crumbs.forEachIndexed { index, crumb ->
                        if (index != 0) Icon(D.chevronRight, null, Modifier.size(12.dp), tint = T.Text3)
                        Text(
                            if (index == 0) navText("工作区", "Workspace") else crumb.label,
                            modifier = Modifier.clip(T.R8).clickable(enabled = index != crumbs.lastIndex, role = Role.Button) { onDrill(crumb.drill) }
                                .padding(horizontal = 5.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == crumbs.lastIndex) T.Ink else T.Text3,
                            maxLines = 1,
                        )
                    }
                }
                HorizontalDivider(color = T.Border, thickness = 0.5.dp)
            }
            if (loading && rows.isEmpty()) {
                for (fraction in listOf(0.32f, 0.48f)) {
                    Box(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.fillMaxWidth(fraction).height(18.dp).clip(T.R4).background(ThemeState.colors.BgSkeleton))
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).drawWithCache {
                    val fadeHeight = 16.dp.toPx()
                    val fade = Brush.verticalGradient(
                        listOf(Color.Transparent, menuColor),
                        startY = size.height - fadeHeight, endY = size.height,
                    )
                    onDrawWithContent {
                        drawContent()
                        if (listState.canScrollForward) {
                            drawRect(fade, Offset(0f, size.height - fadeHeight), Size((size.width - 8.dp.toPx()).coerceAtLeast(0f), fadeHeight))
                        }
                        val info = listState.layoutInfo
                        val first = info.visibleItemsInfo.firstOrNull()
                        val last = info.visibleItemsInfo.lastOrNull()
                        if ((listState.canScrollForward || listState.canScrollBackward) && first != null && last != null) {
                            val from = first.index + ((info.viewportStartOffset - first.offset).toFloat() / first.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val to = last.index + ((info.viewportEndOffset - last.offset).toFloat() / last.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val visible = to - from
                            val trackHeight = (size.height - 16.dp.toPx()).coerceAtLeast(0f)
                            val thumbHeight = (trackHeight * visible / info.totalItemsCount.coerceAtLeast(1)).coerceIn(minOf(18.dp.toPx(), trackHeight), trackHeight)
                            val fraction = (from / (info.totalItemsCount - visible).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            drawRoundRect(
                                scrollColor,
                                Offset(size.width - 3.dp.toPx(), 8.dp.toPx() + fraction * (trackHeight - thumbHeight)),
                                Size(2.dp.toPx(), thumbHeight), CornerRadius(1.dp.toPx()),
                            )
                        }
                    }
                },
            ) {
                itemsIndexed(rows) { index, row ->
                    if (index == 0 || rows[index - 1].section != row.section) {
                        Text(
                            when (row.section) {
                                MentionSection.Files -> L.filesAndFolders()
                                MentionSection.Subagents -> L.subagents()
                                MentionSection.Sessions -> navText("对话", "Sessions")
                            },
                            Modifier.padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 2.dp),
                            color = T.Text3,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                        )
                    }
                    MentionCandidateRow(row, index == selected, !loading, { selected = index; onPick(row.mention) }, {
                        selected = index
                        row.drill?.let(onDrill)
                    })
                }
            }
        }
    }
}

@Composable
private fun MentionCandidateRow(row: MentionRow, active: Boolean, enabled: Boolean, onPick: () -> Unit, onDrill: () -> Unit) {
    BoxWithConstraints {
        val titleMaxWidth = maxWidth * 0.4f
        Row(
            Modifier.fillMaxWidth().clip(T.R8).background(if (active) T.HoverBg else Color.Transparent)
                .semantics { selected = active },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = 34.dp).clickable(enabled = enabled, role = Role.Button, onClick = onPick)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    when {
                        row.drill != null -> D.folderClose
                        row.filePath != null -> D.browse
                        else -> D.chat
                    },
                    null, Modifier.size(14.dp), tint = ThemeState.colors.MenuIcon,
                )
                Text(
                    row.title, Modifier.widthIn(max = titleMaxWidth), color = T.Ink, style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (row.description != null) {
                    Text(
                        row.description, Modifier.weight(1f), color = T.Text3, style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                    )
                } else Spacer(Modifier.weight(1f))
            }
            if (row.drill != null) {
                if (active) {
                    Row(
                        Modifier.clickable(enabled = enabled, onClick = onPick),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(navText("进入目录", "Browse folder"), color = T.Caption, fontSize = 10.sp, lineHeight = 16.sp)
                        Text("Tab", Modifier.clip(T.R4).background(T.HoverBg).padding(horizontal = 4.dp), color = T.Caption, fontSize = 10.sp, lineHeight = 16.sp)
                    }
                }
                Box(
                    Modifier.width(28.dp).height(34.dp).clip(T.R8).clickable(
                        enabled = enabled, role = Role.Button,
                        onClickLabel = navText("进入目录", "Browse folder"), onClick = onDrill,
                    ).testTag("mentionDrill:${row.filePath}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(D.chevronRight, navText("进入目录", "Browse folder"), Modifier.size(12.dp), tint = ThemeState.colors.MenuIcon)
                }
            }
        }
    }
}
