package dev.dsh.pocket.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal fun teamText(zh: String, en: String) = if (Pocket.prefs.language == AppLanguage.En) en else zh

@Composable
internal fun TeamAction(sessionId: String, enabled: Boolean = true) {
    val team by Pocket.team.collectAsState()
    val chat by Pocket.chat.collectAsState()
    val active by Pocket.active.collectAsState()
    val status by Pocket.status.collectAsState()
    var open by remember(active, sessionId) { mutableStateOf(false) }
    Box {
        HeaderAction(D.users, teamText("智能体团队", "Agent Team"), { open = true }, enabled = enabled)
        if (open) HeaderPopup(if (team?.members?.size == 1 && team?.tasks.isNullOrEmpty()) 320 else 500, 680, { open = false }) {
            TeamPanel(team, loading = chat?.loaded == false, online = status[active] == Dot.Online) {
                Pocket.openTeammate(it)
                open = false
            }
        }
    }
}

@Composable
internal fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.heightIn(min = 32.dp).clip(T.R8).border(.5.dp, T.Border, T.R8)
        .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = text }
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, tint = T.Text3, modifier = Modifier.size(14.dp))
        Text(text, color = T.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Icon(D.chevronDown, null, tint = T.Text3, modifier = Modifier.size(12.dp))
    }
}

/** Header-anchored, outside-dismissable menu; no modal scrim or bottom-sheet chrome. */
@Composable
internal fun HeaderPopup(widthDp: Int, maxHeightDp: Int, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val margin = with(density) { 16.dp.roundToPx() }
    val gap = with(density) { 5.dp.roundToPx() }
    val position = remember(margin, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = anchorBounds.left.coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin))
                val below = anchorBounds.bottom + gap
                val y = if (below + popupContentSize.height <= windowSize.height - margin) below else anchorBounds.top - gap - popupContentSize.height
                return IntOffset(x, y.coerceIn(margin, maxOf(margin, windowSize.height - popupContentSize.height - margin)))
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            Modifier.width(minOf(widthDp, (config.screenWidthDp - 32).coerceAtLeast(1)).dp)
                .heightIn(max = minOf(maxHeightDp, (config.screenHeightDp - 140).coerceAtLeast(96)).dp),
            shape = T.R12, color = T.Surface, shadowElevation = 12.dp, border = BorderStroke(.5.dp, T.Border),
        ) { content() }
    }
}

@Composable
internal fun TeamPanel(team: AgentTeam?, loading: Boolean, online: Boolean, onOpen: (AgentTeamMember) -> Unit) {
    Column(Modifier.testTag("teamPanel").verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (team == null) {
            Text(if (loading) teamText("正在加载团队…", "Loading Team…") else teamText("Team 暂不可用", "Team is unavailable"), color = T.Text3, fontSize = 12.sp)
        } else {
            team.failure?.let { Text(teamText("团队持久记录无效：", "Invalid persisted Team record: ") + it, color = T.Err, fontSize = 12.sp) }
            TeamHeading(teamText("成员", "Members"), team.members.size.takeIf { it > 1 })
            team.members.chunked(if (team.members.size > 1) 2 else 1).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { member -> TeamMemberCard(member, team.members.size, online, Modifier.weight(1f)) { onOpen(member) } }
                    if (row.size == 1 && team.members.size > 1) Spacer(Modifier.weight(1f))
                }
            }
            if (team.tasks.isEmpty()) Text(teamText("暂无共享任务，可以通过对话创建", "No shared tasks yet. Create them through the conversation."),
                color = T.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            else {
                Spacer(Modifier.height(8.dp))
                TeamHeading(teamText("共享任务", "Shared tasks"), team.tasks.size)
                team.tasks.forEach { task -> TeamTaskCard(task) }
            }
        }
    }
}

@Composable
private fun TeamHeading(text: String, count: Int?) {
    Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = T.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        count?.let { Text("$it", color = T.Caption, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp)) }
    }
}

@Composable
private fun TeamMemberCard(member: AgentTeamMember, count: Int, online: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val border = if (member.current && count > 1) T.Accent.copy(alpha = .4f) else T.Border
    Surface(modifier.clip(T.R8).clickable(enabled = online && member.canOpen, onClick = onOpen).testTag("teamMember:${member.id}"),
        color = T.Surface2, shape = T.R8, border = BorderStroke(.5.dp, border)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (member.status == "inactive") Icon(D.user, null, tint = T.Text3, modifier = Modifier.padding(top = 2.dp).size(14.dp))
            else Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(if (member.status == "failed") T.Err else T.Accent))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(member.name, color = T.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (member.current) Text(teamText("当前会话", "Current chat"), fontSize = 10.sp, lineHeight = 15.sp, color = T.Accent,
                        maxLines = 1, modifier = Modifier.clip(CircleShape).background(T.Tint).padding(horizontal = 4.dp))
                }
                Text(when (member.status) {
                    "running" -> teamText("运行中", "Running")
                    "provisioning" -> teamText("准备中", "Provisioning")
                    "failed" -> teamText("失败", "Failed")
                    else -> teamText("未运行", "Inactive")
                }, color = T.Text3, fontSize = 11.sp, maxLines = 1)
                member.error?.let { Text(it, color = T.Err, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun TeamTaskCard(task: AgentTeamTask) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    var clamped by remember(task.id, task.description) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(bottom = 7.dp), shape = T.R8, color = T.Surface2, border = BorderStroke(.5.dp, T.Border)) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.subject, Modifier.weight(1f), color = T.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(when (task.status) {
                    "in_progress" -> teamText("进行中", "In progress")
                    "completed" -> teamText("已完成", "Completed")
                    else -> teamText("待处理", "Pending")
                }, color = T.Text3, fontSize = 11.sp)
            }
            Text(task.description, color = T.Text2, fontSize = 12.sp, lineHeight = 18.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) clamped = it.hasVisualOverflow }, modifier = Modifier.padding(vertical = 5.dp))
            if (clamped || expanded) Text(teamText(if (expanded) "收起" else "展开", if (expanded) "Show less" else "Show more"),
                color = T.Text3, fontSize = 11.sp, modifier = Modifier.align(Alignment.End).clickable { expanded = !expanded }.padding(4.dp))
            val meta = buildList {
                add(task.id)
                add("Owner: ${task.ownerName ?: teamText("未分配", "Unowned")}")
                if (task.status == "pending") add(if (task.ready) teamText("可开始", "Ready") else teamText("被依赖阻塞", "Blocked by dependencies"))
                if (task.blockedBy.isNotEmpty()) add(teamText("依赖: ", "Blocked by: ") + task.blockedBy.joinToString(", "))
                if (task.writeScopes.isNotEmpty()) add(teamText("写入范围: ", "Write scopes: ") + task.writeScopes.joinToString(", "))
            }
            Text(meta.joinToString("  ·  "), color = T.Text3, fontSize = 11.sp, lineHeight = 20.sp)
            task.writeScopeWarnings.forEach { Text(it, color = T.Err, fontSize = 11.sp) }
        }
    }
}
