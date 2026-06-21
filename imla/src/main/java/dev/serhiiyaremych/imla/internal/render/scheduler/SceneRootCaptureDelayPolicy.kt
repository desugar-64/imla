package dev.serhiiyaremych.imla.internal.render.scheduler

internal class SceneRootCaptureDelayPolicy(
    private val delayAfterPresentBudgetFraction: Float
) {
    init {
        require(delayAfterPresentBudgetFraction > 0f) {
            "Scene root capture delay fraction must be positive"
        }
    }

    fun shouldDelayRootCapture(
        dirtyNanos: Long,
        lastRenderCompleteNanos: Long,
        frameBudgetNanos: Long
    ): Boolean {
        if (frameBudgetNanos <= 0L) return false
        val delayAfterPresentNanos = frameBudgetNanos * delayAfterPresentBudgetFraction
        val elapsedAfterPresentNanos = dirtyNanos - lastRenderCompleteNanos
        return lastRenderCompleteNanos > 0L &&
                elapsedAfterPresentNanos >= 0L &&
                elapsedAfterPresentNanos < delayAfterPresentNanos
    }
}
