package dev.dsh.pocket.app

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule as platformCreateComposeRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Material3 InfiniteTransition (spinners, ripples, text cursors) never reaches
 * Compose/Espresso idle. LocalInspectionMode stops those transitions from being
 * created. MotionDurationScale 0 is intentionally not applied: a zero-duration
 * infinite transition pumps frames until the idling policy expires.
 */
private object NoRipple : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun hashCode(): Int = 0
    override fun equals(other: Any?): Boolean = other === this
}

@OptIn(ExperimentalMaterial3Api::class)
fun createComposeRule(): ComposeContentTestRule {
    val inner = platformCreateComposeRule()
    return object : ComposeContentTestRule by inner {
        override fun apply(base: Statement, description: Description): Statement {
            val composed = inner.apply(base, description)
            return object : Statement() {
                override fun evaluate() {
                    installTestIdlePolicy()
                    clearTestIdleLeftovers()
                    inner.mainClock.autoAdvance = true
                    try {
                        composed.evaluate()
                    } finally {
                        inner.mainClock.autoAdvance = false
                        clearTestIdleLeftovers()
                    }
                }
            }
        }

        override fun setContent(composable: @Composable () -> Unit) {
            inner.setContent {
                CompositionLocalProvider(
                    LocalIndication provides NoRipple,
                    LocalRippleConfiguration provides null,
                    LocalInspectionMode provides true,
                ) { composable() }
            }
        }
    }
}
