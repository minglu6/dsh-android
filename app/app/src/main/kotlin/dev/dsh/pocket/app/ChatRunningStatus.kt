package dev.dsh.pocket.app

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** One shared highlight across a process row's text, never its leading icon. */
@Composable
internal fun ProcessShimmer(
    active: Boolean,
    modifier: Modifier = Modifier,
    highlight: Color = ThemeState.colors.LabelShimmer,
    content: @Composable RowScope.() -> Unit,
) {
    val animated = active && ValueAnimator.areAnimatorsEnabled()
    val sweep = if (animated) rememberInfiniteTransition(label = "processShimmer").animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 1500; -1f at 0; 1f at 1000; 1f at 1500 },
            repeatMode = RepeatMode.Restart, initialStartOffset = StartOffset(300),
        ), label = "highlightPosition",
    ) else null
    Row(
        modifier.then(if (sweep == null) Modifier else Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val brush = Brush.linearGradient(
                    0f to Color.Transparent, 0.4f to highlight, 0.6f to highlight, 1f to Color.Transparent,
                    start = Offset.Zero, end = Offset(size.width, size.width * 0.268f),
                )
                onDrawWithContent {
                    drawContent()
                    translate(left = sweep.value * size.width) {
                        drawRect(brush, blendMode = BlendMode.SrcAtop)
                    }
                }
            }),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Official 60-frame APNG, packed losslessly into one atlas; 50 ms per frame. */
@Composable
private fun RunningWhale(color: Color, modifier: Modifier) {
    if (!ValueAnimator.areAnimatorsEnabled()) {
        Icon(painterResource(R.drawable.ic_running_whale), null, modifier, tint = color)
        return
    }
    val frames = ImageBitmap.imageResource(R.drawable.running_whale_frames)
    val frame by rememberInfiniteTransition(label = "runningWhale").animateFloat(
        0f, 60f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "whaleFrame",
    )
    val tint = remember(color) { ColorFilter.tint(color) }
    Canvas(modifier) {
        drawImage(
            frames, srcOffset = IntOffset(frame.toInt().coerceAtMost(59) * 28, 0),
            srcSize = IntSize(28, 28), dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            colorFilter = tint, filterQuality = FilterQuality.Medium,
        )
    }
}

@Composable
internal fun RunningStatus(since: Long?, showDivider: Boolean) {
    var now by remember(since) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        if (since != null) while (true) { delay(1000); now = System.currentTimeMillis() }
    }
    val en = Pocket.prefs.language == AppLanguage.En
    val status = if (en) "Deep diving" else "深度求索中"
    val label = if (since == null) status else {
        val duration = formatRunDuration((now - since).coerceAtLeast(1000), en)
        if (en) "Deep diving for $duration ···" else "深度求索中，用时 $duration ···"
    }
    val colors = ThemeState.colors
    val fontSize = Pocket.prefs.fontSize
    Column(Modifier.fillMaxWidth().testTag("runningStatus").clearAndSetSemantics {
        // Announce a run once, not on each timer tick (desktop role=status).
        contentDescription = status
        liveRegion = LiveRegionMode.Polite
    }) {
        if (showDivider) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.5.dp, color = lerp(colors.BorderL1, colors.BorderL2, 0.25f))
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RunningWhale(colors.LabelDeepDiving, Modifier.size(fontSize.dp))
            Spacer(Modifier.width(6.dp))
            ProcessShimmer(true, highlight = colors.LabelDeepDivingShimmer) {
                Text(label, fontSize = (fontSize - 2).sp, lineHeight = (fontSize + 8).sp, color = colors.LabelDeepDiving)
            }
        }
    }
}
