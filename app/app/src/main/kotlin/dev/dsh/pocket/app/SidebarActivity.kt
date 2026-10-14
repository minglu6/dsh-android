package dev.dsh.pocket.app

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI

internal enum class SidebarActivity(val priority: Int) {
    Idle(0), Running(1), AwaitingAnswer(2), AwaitingApproval(3);
}

internal fun mergeSidebarActivity(a: SidebarActivity, b: SidebarActivity): SidebarActivity =
    if (a.priority >= b.priority) a else b

/** Keep activity independent of selection, pinning, filtering and expansion. */
@Composable
internal fun rememberSidebarActivities(): Map<String, SidebarActivity> {
    val sessions by Pocket.sessions.collectAsState()
    val approvals by Pocket.approvals.collectAsState()
    val questions by Pocket.userQuestions.collectAsState()
    return remember(sessions, approvals, questions) {
        buildMap {
            sessions.forEach { put(it.sessionId, if (it.running) SidebarActivity.Running else SidebarActivity.Idle) }
            questions.forEach { it.sessionId?.let { id -> put(id, SidebarActivity.AwaitingAnswer) } }
            approvals.forEach { it.sessionId?.let { id -> put(id, SidebarActivity.AwaitingApproval) } }
        }
    }
}

private val activityBreathing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Desktop StateDot: 14dp neutral track + breathing arc, one 1.5s rotation. */
@Composable
internal fun SidebarActivityIndicator(activity: SidebarActivity, modifier: Modifier = Modifier, workspace: Boolean = false) {
    if (activity == SidebarActivity.Idle) return
    val label = when (activity) {
        SidebarActivity.Running -> navText("运行中", "Running")
        SidebarActivity.AwaitingApproval -> navText("等待审批", "Waiting for approval")
        SidebarActivity.AwaitingAnswer -> navText("等待回答", "Waiting for answer")
        SidebarActivity.Idle -> return
    }
    val color = if (activity == SidebarActivity.Running) T.Text3 else T.WarnBorder
    val phase = if (activity == SidebarActivity.Running && ValueAnimator.areAnimatorsEnabled()) {
        rememberInfiniteTransition(label = "sidebarActivity").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "spinnerPhase",
        )
    } else null
    Canvas(modifier.semantics {
        contentDescription = if (workspace) navText("工作区内有会话：", "Workspace session: ") + label else label
    }) {
        val unit = size.minDimension / 24f
        if (activity == SidebarActivity.Running) {
            val progress = phase?.value
            val breathing = if (progress == null) 0.5f else activityBreathing.transform(
                if (progress < 0.5f) progress * 2f else (1f - progress) * 2f,
            )
            val stroke = Stroke(2f * unit, cap = StrokeCap.Round)
            drawCircle(color.copy(alpha = color.alpha * 0.25f), radius = 9.5f * unit, style = stroke)
            val degreesPerUnit = (180.0 / (PI * 9.5)).toFloat()
            drawArc(
                color, startAngle = (progress ?: 0f) * 360f + 6f * breathing * degreesPerUnit,
                sweepAngle = (12f + 12f * breathing) * degreesPerUnit, useCenter = false,
                topLeft = center - Offset(9.5f * unit, 9.5f * unit),
                size = Size(19f * unit, 19f * unit), style = stroke,
            )
        } else {
            drawCircle(color, radius = size.minDimension * 3f / 14f)
        }
    }
}
