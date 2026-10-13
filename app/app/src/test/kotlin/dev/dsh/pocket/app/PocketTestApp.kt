package dev.dsh.pocket.app

import android.animation.ValueAnimator
import androidx.test.espresso.IdlingPolicies
import androidx.test.espresso.IdlingRegistry
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import org.robolectric.TestLifecycleApplication
import org.robolectric.shadows.ShadowChoreographer

/**
 * Robolectric application for unit tests.
 *
 * Compose/Espresso treat infinite clocks (progress indicators, ripples, cursors,
 * running-status ticks, connection/schedule poll delays) as "not idle". The default
 * 60s master policy then makes each assertion look like a CI hang. Animator scale 0
 * matches production's `areAnimatorsEnabled()` gates; the shorter idling timeout
 * fails a stuck test in seconds, not a minute. Re-applied before every method because
 * a Robolectric sandbox can reset static animator state between tests.
 */
class PocketTestApp : PocketApp(), TestLifecycleApplication {
    override fun onCreate() {
        installTestIdlePolicy()
        super.onCreate()
    }

    override fun beforeTest(method: Method?) {
        installTestIdlePolicy()
        clearTestIdleLeftovers()
    }

    override fun prepareTest(test: Any?) {}

    override fun afterTest(method: Method?) {
        clearTestIdleLeftovers()
    }
}

internal fun installTestIdlePolicy() {
    disableTestAnimators()
    IdlingPolicies.setMasterPolicyTimeout(8, TimeUnit.SECONDS)
    IdlingPolicies.setIdlingResourceTimeout(8, TimeUnit.SECONDS)
    // NATIVE graphics tests (drawer ripples, Typeface/bitmap) leave Choreographer
    // frame callbacks that make later Compose waitForIdle spin forever.
    ShadowChoreographer.reset()
}

internal fun clearTestIdleLeftovers() {
    IdlingRegistry.getInstance().resources.toList().forEach { resource ->
        IdlingRegistry.getInstance().unregister(resource)
    }
    InspectorNavigation.reset()
    ShadowChoreographer.reset()
}

internal fun disableTestAnimators() {
    val setter = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", checkNotNull(Float::class.javaPrimitiveType))
    setter.isAccessible = true
    setter.invoke(null, 0f)
    check(!ValueAnimator.areAnimatorsEnabled()) { "Animator duration scale must be 0 so Compose tests can become idle" }
}
