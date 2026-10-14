package dev.dsh.pocket.app

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule as createDeviceComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android frames catch a frozen loader that static screenshot tests cannot. */
@RunWith(AndroidJUnit4::class)
class SidebarActivityInstrumentedTest {
    @get:Rule val rule = createDeviceComposeRule()

    @Test fun runningArcAnimatesAndStopsForApprovalAndIdle() {
        Pocket.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val previousScale = animatorDurationScale()
        setAnimatorDurationScale(1f)
        try {
            assumeTrue("Animation must be enabled to exercise real running frames", ValueAnimator.areAnimatorsEnabled())
            val activity = mutableStateOf(SidebarActivity.Running)
            rule.mainClock.autoAdvance = false
            rule.setContent {
                PocketTheme {
                    Box(Modifier.padding(24.dp)) {
                        SidebarActivityIndicator(activity.value, Modifier.size(14.dp).testTag("activity"))
                    }
                }
            }
            fun pixels(): IntArray {
                val bitmap = rule.onNodeWithTag("activity").captureToImage().asAndroidBitmap()
                return IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
            }
            rule.mainClock.advanceTimeBy(32)
            val first = pixels()
            rule.mainClock.advanceTimeBy(375)
            val second = pixels()
            assertFalse("Running arc must move between native frames", first.contentEquals(second))
            rule.runOnUiThread { activity.value = SidebarActivity.AwaitingApproval }
            rule.mainClock.advanceTimeBy(32)
            rule.onNodeWithTag("activity").assertContentDescriptionEquals(navText("等待审批", "Waiting for approval"))
            val waiting = pixels()
            rule.mainClock.advanceTimeBy(375)
            assertTrue("Approval marker must not spin", waiting.contentEquals(pixels()))
            rule.runOnUiThread { activity.value = SidebarActivity.Idle }
            rule.mainClock.advanceTimeBy(32)
            rule.onNodeWithTag("activity").assertDoesNotExist()
            rule.mainClock.autoAdvance = true
        } finally {
            setAnimatorDurationScale(previousScale)
        }
    }

    private fun animatorDurationScale(): Float = if (Build.VERSION.SDK_INT >= 33) {
        ValueAnimator.getDurationScale()
    } else if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f

    /** `ValueAnimator.setDurationScale` is still hidden; CI turns animators off globally. */
    private fun setAnimatorDurationScale(scale: Float) {
        runCatching {
            ValueAnimator::class.java
                .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(null, scale)
        }
    }
}
