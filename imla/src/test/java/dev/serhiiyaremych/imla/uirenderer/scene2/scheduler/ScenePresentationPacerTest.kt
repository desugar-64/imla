package dev.serhiiyaremych.imla.internal.render.scheduler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePresentationPacerTest {
    @Test
    fun twoTickPacerAlternatesPresentedAndSkippedDirtyFrames() {
        val pacer = twoTickPacer()

        assertTrue(pacer.shouldPresentDirtyFrame(nowNanos = 10))
        assertFalse(pacer.shouldPresentDirtyFrame(nowNanos = 20))
        assertTrue(pacer.shouldPresentDirtyFrame(nowNanos = 30))
        assertFalse(pacer.shouldPresentDirtyFrame(nowNanos = 40))
    }

    @Test
    fun resetAllowsNextDirtyFrameToPresent() {
        val pacer = twoTickPacer()

        assertTrue(pacer.shouldPresentDirtyFrame(nowNanos = 10))
        assertFalse(pacer.shouldPresentDirtyFrame(nowNanos = 20))

        pacer.reset()

        assertTrue(pacer.shouldPresentDirtyFrame(nowNanos = 30))
    }

    @Test
    fun firstDirtyFrameAfterIdlePresentsImmediately() {
        val pacer = twoTickPacer()

        assertTrue(pacer.shouldPresentDirtyFrame(nowNanos = 10))
        assertFalse(pacer.shouldPresentDirtyFrame(nowNanos = 20))
        assertTrue(pacer.shouldPresentDirtyFrame(nowNanos = 70))
    }

    private fun twoTickPacer(): ScenePresentationPacer {
        return ScenePresentationPacer(
            dirtyTicksPerPresent = 2,
            idleResetNanos = 50
        )
    }
}
