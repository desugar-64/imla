package dev.serhiiyaremych.imla.internal.render.gl

import androidx.graphics.opengl.GLRenderer
import dev.serhiiyaremych.imla.internal.render.scheduler.SceneFrameRequest
import java.util.concurrent.atomic.AtomicBoolean

internal class SceneRenderTarget internal constructor(
    private val glOwner: SceneGlOwner,
    private val glRenderer: GLRenderer
) {
    private val detached = AtomicBoolean(false)

    fun submit(request: SceneFrameRequest) {
        glOwner.submit(this, request)
    }

    fun detach() {
        if (!detached.compareAndSet(false, true)) return
        glOwner.onTargetDetached()
    }

    internal fun isDetached(): Boolean = detached.get()

    internal fun scheduleDraw(onRenderComplete: () -> Unit): Boolean {
        if (isDetached()) {
            return false
        }
        glRenderer.execute {
            if (isDetached()) return@execute
            glOwner.draw()
            onRenderComplete()
        }
        return true
    }
}
