package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.delay

/** Desktop SessionHoverContent: fixed dark surface in both application themes. */
@Composable
internal fun SessionTitleAction(
    sessionId: String,
    title: String,
    updatedAt: Long?,
    blank: Boolean,
    activity: SidebarActivity,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(sessionId) { mutableStateOf(false) }
    val en = Pocket.prefs.language == AppLanguage.En
    val screen = LocalConfiguration.current
    val margin = with(LocalDensity.current) { 16.dp.roundToPx() }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(margin, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val left = anchorBounds.left.coerceIn(margin,
                    (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                val below = anchorBounds.bottom + gap
                val top = if (below + popupContentSize.height <= windowSize.height - margin) below
                    else (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(margin)
                return IntOffset(left, top)
            }
        }
    }
    Box(modifier) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sessionTitle")
            .semantics { contentDescription = if (en) "Show full conversation title" else "查看会话完整标题" }
            .clickable(role = Role.Button) { expanded = !expanded }, contentAlignment = Alignment.CenterStart) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = T.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            val now by produceState(System.currentTimeMillis(), updatedAt) {
                while (true) { delay(60_000); value = System.currentTimeMillis() }
            }
            val status = when (activity) {
                SidebarActivity.Idle -> if (en) "Idle" else "空闲"
                SidebarActivity.Running -> if (en) "Running" else "运行中"
                SidebarActivity.AwaitingAnswer -> if (en) "Waiting for answer" else "等待回答"
                SidebarActivity.AwaitingApproval -> if (en) "Waiting for approval" else "等待审批"
            }
            Popup(popupPositionProvider = position, onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)) {
                Surface(shape = T.R16, color = Color(0xFF2C2C2E), shadowElevation = 8.dp,
                    modifier = Modifier.width(minOf(244, (screen.screenWidthDp - 32).coerceAtLeast(1)).dp)
                        .heightIn(max = (screen.screenHeightDp - 48).coerceAtLeast(1).dp)
                        .testTag("sessionTitlePopover")) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectableOutput {
                            Text(title, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp,
                                modifier = Modifier.testTag("sessionTitleFull"))
                        }
                        if (!blank && updatedAt != null && updatedAt > 0) {
                            val time = formatSessionTime(updatedAt, now, Pocket.prefs.language)
                            val label = if (time == "now" || time == "刚刚") time else if (en) "$time ago" else "${time}前"
                            Text(label, color = Color(0xFFCFD3D6), fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                if (activity == SidebarActivity.Idle) {
                                    Box(Modifier.size(6.dp).background(Color(0xFFADB2B8), CircleShape))
                                } else SidebarActivityIndicator(activity, Modifier.size(14.dp))
                            }
                            Text(status, color = Color(0xFFADB2B8), fontSize = 12.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }
        }
    }
}
