package dev.dsh.pocket.app

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.RetryState
import kotlinx.coroutines.delay

/** The Host owns retry timing; the local countdown never claims the next request has started. */
internal fun retrySeconds(milliseconds: Long): Long =
    if (milliseconds <= 1000L) 1L else milliseconds / 1000L + if (milliseconds % 1000L == 0L) 0L else 1L

internal fun retryRemainingSeconds(delayMs: Long, elapsedMs: Long): Long =
    retrySeconds(delayMs.coerceAtLeast(0L) - elapsedMs.coerceAtLeast(0L))

internal fun chatFailureMessage(message: String, code: String?, en: Boolean): String = when (code) {
    "ACCOUNT_SIGNED_OUT" -> if (en) "Stopped because you signed out of DeepSeek." else "任务已因退出 DeepSeek 登录而停止。"
    "ACCOUNT_SIGN_IN_REQUIRED" -> if (en) "Sign in to DeepSeek and ensure the request destination supports account authentication." else "请先登录 DeepSeek，并确认请求地址支持账号认证。"
    "QUOTA", "ACCOUNT_QUOTA", "QUOTA_EXCEEDED" -> if (en) "Request quota exhausted." else "当前请求的额度已用尽"
    "AUTH", "AUTH_ERROR", "INVALID_API_KEY" -> if (en) "API key is invalid" else "API 密钥无效"
    else -> message
}

@Composable
internal fun RetryRow(item: Item.Retry) {
    val attempt = item.attempts.lastOrNull() ?: return
    val en = Pocket.prefs.language == AppLanguage.En
    val fontSize = Pocket.prefs.fontSize - 1
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    // Host timestamps can use another clock. Start from first local rendering of this attempt.
    val renderedAt = remember(item.key, attempt.seq, attempt.delayMs) { SystemClock.elapsedRealtime() }
    var remaining by remember(item.key, attempt.seq, attempt.delayMs) {
        mutableLongStateOf(retrySeconds(attempt.delayMs))
    }
    val active = attempt.state == RetryState.Scheduled
    LaunchedEffect(item.key, attempt.seq, attempt.delayMs, active) {
        if (active) {
            do {
                remaining = retryRemainingSeconds(attempt.delayMs, SystemClock.elapsedRealtime() - renderedAt)
                if (remaining > 1L) delay(250)
            } while (remaining > 1L)
        }
    }
    val label = when (attempt.state) {
        RetryState.Scheduled -> if (en) "Retrying model request" else "正在重试模型请求"
        RetryState.Started -> if (en) "Retried model request" else "已重试模型请求"
        RetryState.Cancelled -> if (en) "Model request retry cancelled" else "模型请求重试已取消"
    }
    val maximum = if (attempt.mode == "always") "∞" else attempt.maxRetries?.toString() ?: "?"
    val seconds = if (active) remaining else retrySeconds(attempt.delayMs)
    val summary = if (en) "$label (${attempt.retry}/$maximum) · ${seconds}s"
        else "$label（${attempt.retry}/$maximum） · ${seconds}s"
    val disclosure = if (expanded) { if (en) "Expanded" else "已展开" } else { if (en) "Collapsed" else "已收起" }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) {
                    if (en) "Collapse retry details" else "收起重试详情"
                } else {
                    if (en) "Expand retry details" else "展开重试详情"
                }) { expanded = !expanded }
                .semantics { stateDescription = disclosure }
                .testTag("retry-summary-${item.key}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProcessShimmer(active, Modifier.weight(1f, fill = false)) {
                Text(summary, color = T.Text3, fontSize = fontSize.sp, lineHeight = (fontSize + 7).sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Spacer(Modifier.width(7.dp))
            Icon(D.chevronDown, null, tint = T.Text3,
                modifier = Modifier.size(12.dp).rotate(if (expanded) 0f else -90f))
        }
        if (expanded) {
            SelectableOutput {
                Column(
                    Modifier.fillMaxWidth().padding(start = 14.dp, top = 3.dp)
                        .testTag("retry-details-${item.key}"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val delayValue = if (en) "${attempt.delayMs} ms" else "${attempt.delayMs} 毫秒"
                    RetryDetail(if (en) "Retry delay: " else "重试延迟：", delayValue, fontSize)
                    RetryDetail(if (en) "Failure reason: " else "失败原因：",
                        chatFailureMessage(attempt.message, attempt.code, en), fontSize)
                }
            }
        }
    }
}

@Composable
private fun RetryDetail(label: String, value: String, fontSize: Int) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = T.Text2)) { append(label) }
        append(value.breakAnywhere())
    }, color = T.Text3, fontSize = fontSize.sp, lineHeight = (fontSize + 5).sp,
        modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun TurnFailureRow(item: Item.TurnFailure) {
    val en = Pocket.prefs.language == AppLanguage.En
    val fontSize = Pocket.prefs.fontSize - 1
    val title = if (item.code == "ACCOUNT_SIGNED_OUT") {
        if (en) "Task stopped" else "任务已停止"
    } else {
        if (en) "This turn failed" else "本轮运行失败"
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("turn-failure-${item.key}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(T.Err))
        SelectableOutput(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = T.Err, fontWeight = FontWeight.SemiBold)) { append(title) }
                    append("  ")
                    append(chatFailureMessage(item.message, item.code, en).breakAnywhere())
                }, color = T.Text2, fontSize = fontSize.sp, lineHeight = (fontSize + 7).sp,
                    modifier = Modifier.fillMaxWidth())
                item.code?.let { code ->
                    Text(code.breakAnywhere(), color = T.Text3, fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp, lineHeight = (fontSize + 7).sp,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
