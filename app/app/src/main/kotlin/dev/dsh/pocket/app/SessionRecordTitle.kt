package dev.dsh.pocket.app

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

/** Selected-row touch equivalent of the desktop hover marquee: reveal once, then hold the tail. */
@Composable
internal fun SessionRecordTitle(text: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (!enabled) {
        Text(text, fontSize = 15.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    key(text) {
        val scroll = rememberScrollState()
        val density = LocalDensity.current.density
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val lifecycleState by lifecycle.currentStateFlow.collectAsState()
        var visible by remember { mutableStateOf(false) }
        val reveal = visible && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
        LaunchedEffect(scroll.maxValue, reveal, density) {
            scroll.scrollTo(0)
            val range = scroll.maxValue
            if (!reveal || range == Int.MAX_VALUE || range <= 8 * density) return@LaunchedEffect
            if (!ValueAnimator.areAnimatorsEnabled()) scroll.scrollTo(range)
            else scroll.animateScrollTo(range, tween(
                durationMillis = (range / (0.03f * density)).roundToInt().coerceAtLeast(1), easing = LinearEasing,
            ))
        }
        Box(modifier.onGloballyPositioned { visible = it.boundsInWindow().height > 0f }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val fade = minOf(12.dp.toPx(), size.width / 2)
                val left = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = fade)
                val right = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
                    startX = size.width - fade, endX = size.width)
                onDrawWithContent {
                    drawContent()
                    if (scroll.value > 0) drawRect(left, size = Size(fade, size.height), blendMode = BlendMode.DstIn)
                    if (scroll.value < scroll.maxValue) drawRect(right, topLeft = Offset(size.width - fade, 0f),
                        size = Size(fade, size.height), blendMode = BlendMode.DstIn)
                }
            }) {
            Text(text, fontSize = 15.sp, color = color, maxLines = 1, softWrap = false,
                modifier = Modifier.horizontalScroll(scroll, enabled = false))
        }
    }
}
