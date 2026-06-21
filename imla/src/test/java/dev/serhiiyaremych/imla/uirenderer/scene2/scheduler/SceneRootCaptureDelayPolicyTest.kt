package dev.serhiiyaremych.imla.internal.render.scheduler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneRootCaptureDelayPolicyTest {
    @Test
    fun captureIsNotDelayedBeforeAnyRenderCompletes() {
        val policy = delayPolicy()

        assertFalse(
            policy.shouldDelayRootCapture(
                dirtyNanos = 10,
                lastRenderCompleteNanos = 0,
                frameBudgetNanos = 30
            )
        )
    }

    @Test
    fun captureIsDelayedInsideWindowAfterRenderCompletes() {
        val policy = delayPolicy()

        assertTrue(
            policy.shouldDelayRootCapture(
                dirtyNanos = 29,
                lastRenderCompleteNanos = 10,
                frameBudgetNanos = 30
            )
        )
    }

    @Test
    fun captureIsNotDelayedAtWindowBoundary() {
        val policy = delayPolicy()

        assertFalse(
            policy.shouldDelayRootCapture(
                dirtyNanos = 30,
                lastRenderCompleteNanos = 10,
                frameBudgetNanos = 30
            )
        )
    }

    @Test
    fun captureIsNotDelayedWhenDirtyPredatesRenderComplete() {
        val policy = delayPolicy()

        assertFalse(
            policy.shouldDelayRootCapture(
                dirtyNanos = 9,
                lastRenderCompleteNanos = 10,
                frameBudgetNanos = 30
            )
        )
    }

    @Test
    fun captureIsNotDelayedWhenFrameBudgetIsInvalid() {
        val policy = delayPolicy()

        assertFalse(
            policy.shouldDelayRootCapture(
                dirtyNanos = 29,
                lastRenderCompleteNanos = 10,
                frameBudgetNanos = 0
            )
        )
    }

    private fun delayPolicy(): SceneRootCaptureDelayPolicy {
        return SceneRootCaptureDelayPolicy(delayAfterPresentBudgetFraction = 0.66f)
    }
}
