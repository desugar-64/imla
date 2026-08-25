package dev.serhiiyaremych.imla.internal.render.gl

import androidx.compose.ui.unit.IntSize
import androidx.graphics.opengl.GLRenderer
import dev.serhiiyaremych.imla.internal.render.scheduler.SceneFrameRequest
import java.util.concurrent.atomic.AtomicBoolean

internal class SceneRenderTarget internal constructor(
    private val glOwner: SceneGlOwner,
    private val glRenderer: GLRenderer,
    private val renderTarget: GLRenderer.RenderTarget,
    initialSize: IntSize
) {
    private val detached = AtomicBoolean(false)

    @Volatile
    private var size: IntSize = initialSize

    fun submit(request: SceneFrameRequest) {
        glOwner.submit(this, request)
    }

    fun resize(size: IntSize) {
        if (size == IntSize.Zero || detached.get()) return
        this.size = size
        glRenderer.resize(renderTarget, size.width, size.height)
    }

    fun detach() {
        if (!detached.compareAndSet(false, true)) return
        glOwner.onTargetDetached()
        renderTarget.detach(cancelPending = true)
    }

    internal fun isDetached(): Boolean = detached.get() || !renderTarget.isAttached()

    internal fun requestRender(onRenderComplete: () -> Unit): Boolean {
        if (isDetached()) {
            return false
        }
        renderTarget.requestRender {
            onRenderComplete()
        }
        return true
    }

    internal fun draw() {
        if (!detached.get()) {
            glOwner.draw(size)
        }
    }
}
