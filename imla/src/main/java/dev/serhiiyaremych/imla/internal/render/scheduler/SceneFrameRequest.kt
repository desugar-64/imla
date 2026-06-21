package dev.serhiiyaremych.imla.internal.render.scheduler

import dev.serhiiyaremych.imla.internal.layer.model.UiSceneCapture

/**
 * One UI-side scene capture plus timing anchors used by the main-thread frame scheduler.
 *
 * The request owns the UI capture until it is handed to GL or dropped. Closing the request releases
 * all captured hardware buffers that were not built into GL textures.
 */
internal class SceneFrameRequest(
    val uiCapture: UiSceneCapture,
    val readyAtNanos: Long
) : AutoCloseable {
    override fun close() {
        uiCapture.close()
    }
}

/**
 * Reason a captured request was closed before it became drawable GL state.
 */
internal enum class SceneFrameDropReason {
    Superseded,
    NoRenderTarget,
    SourceDetached,
    RenderRequestInFlight,
    Closed
}
