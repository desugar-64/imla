package dev.serhiiyaremych.imla.internal.render.scheduler

internal class ScenePresentationPacer(
    private val dirtyTicksPerPresent: Int,
    private val idleResetNanos: Long
) {
    private var dirtyTicksUntilNextPresent = 0
    private var lastDirtyNanos = 0L

    init {
        require(dirtyTicksPerPresent >= 1) {
            "Scene presentation dirty tick count must be positive"
        }
        require(idleResetNanos > 0L) {
            "Scene presentation idle reset must be positive"
        }
    }

    fun shouldPresentDirtyFrame(nowNanos: Long): Boolean {
        if (lastDirtyNanos == 0L || nowNanos - lastDirtyNanos >= idleResetNanos) {
            dirtyTicksUntilNextPresent = 0
        }
        lastDirtyNanos = nowNanos
        if (dirtyTicksUntilNextPresent > 0) {
            dirtyTicksUntilNextPresent--
            return false
        }
        dirtyTicksUntilNextPresent = dirtyTicksPerPresent - 1
        return true
    }

    fun reset() {
        dirtyTicksUntilNextPresent = 0
        lastDirtyNanos = 0L
    }
}
