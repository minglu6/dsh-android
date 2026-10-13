package dev.dsh.pocket.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.pocket.protocol.ComposerMetrics
import java.util.Locale
import kotlin.math.floor

private enum class StatisticsDialog { Activity, Usage, Context }

private val statisticsContextColors = listOf(DswStatic.NeutralBluish400, Color(0xFFA78BFA), DswStatic.Blue450)

/** Native equivalents of ActivityPill, UsagePill and ContextMeter; absent projections never become zero. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposerStats(metrics: ComposerMetrics, modifier: Modifier = Modifier) {
    val detailed = Pocket.prefs.performanceUsage == PerformanceUsage.Detailed
    val en = Pocket.prefs.language == AppLanguage.En
    fun label(zh: String, english: String) = if (en) english else zh
    var dialog by remember { mutableStateOf<StatisticsDialog?>(null) }
    val activity = metrics.activity
    val usage = metrics.tokens?.takeIf { it.totalTokens > 0 }
    val context = metrics.context
    val speed = activity?.tokensPerSecond?.let { "${statisticsSpeed(it)} tok/s" }
    val cache = usage?.cacheHitPercent?.let { label("缓存命中 $it%", "Cache hit $it%") }
    val activityText = if (detailed) {
        activity?.takeIf { it.steps > 0 }?.let {
            listOfNotNull(label("${it.turns} 轮 ${it.steps} 步", "${it.turns} turns ${it.steps} steps"), speed).joinToString(" · ")
        }
    } else speed
    val usageText = if (detailed) usage?.let {
        listOfNotNull("${statisticsTokens(it.totalTokens.toDouble())} tokens", cache).joinToString(" · ")
    } else cache
    val activityHasDetails = activity != null &&
        (activity.llmMs > 0 || activity.toolMs > 0 || activity.ttftSteps > 0 || speed != null)
    LaunchedEffect(context, activityText, usageText, detailed) {
        if ((dialog == StatisticsDialog.Context && context == null) ||
            (dialog == StatisticsDialog.Activity && (!detailed || activityText == null || !activityHasDetails)) ||
            (dialog == StatisticsDialog.Usage && (!detailed || usageText == null))) dialog = null
    }
    if (activityText == null && usageText == null && context == null) return
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp).testTag("composerStatistics"),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (activityText != null) StatisticsPill(
            text = activityText,
            tag = "composerStatisticsActivity",
            description = if (metrics.activityIsWholeSession) activityText else label("当前已加载历史：$activityText", "Loaded history: $activityText"),
            onClick = if (detailed && activityHasDetails) ({ dialog = StatisticsDialog.Activity }) else null,
            icon = { Icon(ImageVector.vectorResource(R.drawable.dsh_statistics_gauge), null, Modifier.size(14.dp), tint = T.Text3) },
        )
        if (usageText != null) StatisticsPill(
            text = usageText,
            tag = "composerStatisticsUsage",
            onClick = if (detailed) ({ dialog = StatisticsDialog.Usage }) else null,
            icon = { Icon(ImageVector.vectorResource(R.drawable.dsh_statistics_database), null, Modifier.size(14.dp), tint = T.Text3) },
        )
        if (context != null) StatisticsPill(
            text = "${context.percent}%",
            tag = "composerStatisticsContext",
            description = label("上下文已用 ${context.percent}%", "${context.percent}% of context used"),
            onClick = { dialog = StatisticsDialog.Context },
            icon = { StatisticsRing(context.percent) },
        )
    }
    val currentDialog = dialog ?: return
    val title = when (currentDialog) {
        StatisticsDialog.Activity -> if (metrics.activityIsWholeSession) label("会话统计", "Session statistics") else label("已加载历史统计", "Loaded history statistics")
        StatisticsDialog.Usage -> label("Token 用量", "Token usage")
        StatisticsDialog.Context -> label("上下文用量", "Context usage")
    }
    AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (currentDialog) {
                    StatisticsDialog.Activity -> if (activity != null) {
                        if (activity.llmMs > 0) StatisticsDetail(label("模型用时", "LLM time"), statisticsDuration(activity.llmMs))
                        if (activity.toolMs > 0) StatisticsDetail(label("工具调用用时", "Tool time"), statisticsDuration(activity.toolMs))
                        activity.meanTtftMs?.let { StatisticsDetail(label("首 token 平均（TTFT）", "Avg time to first token (TTFT)"), statisticsDuration(it)) }
                        if (speed != null) StatisticsDetail(label("输出速度（TPS）", "Tokens per second (TPS)"), speed)
                    }
                    StatisticsDialog.Usage -> if (usage != null) {
                        StatisticsDetail(label("合计", "Total"), statisticsExact(usage.totalTokens))
                        usage.cacheHitPercent?.let { StatisticsDetail(label("缓存命中率", "Cache hit"), "$it%") }
                        StatisticsDetail(label("未缓存输入", "Uncached input"), statisticsExact(usage.uncachedInputTokens))
                        StatisticsDetail(label("缓存读取", "Cache read"), statisticsExact(usage.cacheReadTokens))
                        if (usage.cacheWriteTokens != 0L) StatisticsDetail(label("缓存写入", "Cache write"), statisticsExact(usage.cacheWriteTokens))
                        StatisticsDetail(label("输出", "Output"), statisticsExact(usage.outputTokens))
                    }
                    StatisticsDialog.Context -> if (context != null) {
                        StatisticsDetail(label("上下文已用 ${context.percent}%", "${context.percent}% of context used"), "~${statisticsTokens(context.usedTokens)} / ${statisticsTokens(context.contextWindow)}")
                        val breakdown = metrics.contextBreakdown
                        StatisticsContextBar(context.percent, metrics)
                        if (breakdown != null) {
                            StatisticsDetail(label("系统提示词", "System prompt"), "~${statisticsTokens(breakdown.systemTokens)}", statisticsContextColors[0])
                            StatisticsDetail(label("工具", "Tools"), "~${statisticsTokens(breakdown.toolsTokens)}", statisticsContextColors[1])
                            StatisticsDetail(label("对话", "Conversation"), "~${statisticsTokens(breakdown.messageTokens)}", statisticsContextColors[2])
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { dialog = null }) { Text(label("关闭", "Close")) } },
        containerColor = T.Surface,
        titleContentColor = T.Ink,
        textContentColor = T.Text2,
    )
}

@Composable
private fun StatisticsPill(
    text: String,
    tag: String,
    description: String = text,
    onClick: (() -> Unit)?,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.testTag(tag).semantics { contentDescription = description }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(text, fontSize = 12.sp, color = T.Text3)
    }
}

/** Official ContextMeter: 14px viewport, radius 5.5, 2px stroke, starting at twelve o'clock. */
@Composable
private fun StatisticsRing(percent: Int) {
    val color = T.Text3
    val track = ThemeState.colors.BorderL3
    Canvas(Modifier.size(14.dp)) {
        val unit = size.width / 14
        val stroke = Stroke(2 * unit)
        drawCircle(track, radius = 5.5f * unit, style = stroke)
        if (percent > 0) drawArc(color, -90f, 360f * percent / 100, false,
            topLeft = Offset(1.5f * unit, 1.5f * unit), size = Size(11 * unit, 11 * unit), style = Stroke(2 * unit, cap = StrokeCap.Round))
    }
}

@Composable
private fun StatisticsDetail(label: String, value: String, swatch: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (swatch != null) Canvas(Modifier.size(8.dp)) { drawRect(swatch) }
        Text(label, Modifier.weight(1f), color = T.Text2, fontSize = 13.sp)
        Text(value, color = T.Ink, fontSize = 13.sp)
    }
}

@Composable
private fun StatisticsContextBar(percent: Int, metrics: ComposerMetrics) {
    val colors = statisticsContextColors
    val track = T.HoverBg
    val totalTint = T.Text3
    val breakdown = metrics.contextBreakdown
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        drawRect(track)
        if (percent > 0) {
            val total = breakdown?.let { it.systemTokens + it.toolsTokens + it.messageTokens } ?: 0.0
            if (breakdown == null || total <= 0) {
                drawRect(totalTint, size = Size(size.width * percent / 100, size.height))
            } else {
                var x = 0f
                listOf(breakdown.systemTokens, breakdown.toolsTokens, breakdown.messageTokens).forEachIndexed { index, tokens ->
                    val width = (size.width * percent / 100 * tokens / total).toFloat()
                    if (width > 0) drawRect(colors[index], Offset(x, 0f), Size(width, size.height))
                    x += width
                }
            }
        }
    }
}

private fun statisticsRounded(value: Double, decimals: Int): String {
    val scale = if (decimals == 0) 1 else 10
    val rounded = floor(value * scale + 0.5) / scale
    return if (rounded % 1 == 0.0) rounded.toLong().toString() else String.format(Locale.ROOT, "%.1f", rounded)
}
private fun statisticsSpeed(value: Double): String = statisticsRounded(value, if (value >= 10) 0 else 1)
private fun statisticsTokens(value: Double): String = when {
    value < 1000 -> statisticsRounded(value, 0)
    value < 1_000_000 -> (value / 1000).let { statisticsRounded(it, if (it >= 100) 0 else 1) } + "K"
    else -> (value / 1_000_000).let { statisticsRounded(it, if (it >= 100) 0 else 1) } + "M"
}
private fun statisticsExact(value: Long): String = String.format(Locale.US, "%,d", value)
private fun statisticsDuration(milliseconds: Double): String {
    val seconds = milliseconds / 1000
    if (seconds < 60) return "${statisticsRounded(seconds, 1)}s"
    val whole = floor(seconds + 0.5).toLong()
    return "${whole / 60}m${whole % 60}s"
}
