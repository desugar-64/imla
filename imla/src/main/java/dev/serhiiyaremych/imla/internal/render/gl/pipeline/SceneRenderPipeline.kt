package dev.serhiiyaremych.imla.internal.render.gl.pipeline

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.gl.BackdropInput
import dev.serhiiyaremych.imla.internal.render.gl.ProcessedBackdrop
import dev.serhiiyaremych.imla.internal.render.gl.SceneGlRenderFrame
import dev.serhiiyaremych.imla.internal.render.gl.SceneGlRenderSlot
import dev.serhiiyaremych.imla.internal.render.gl.SceneNoisePass
import dev.serhiiyaremych.imla.internal.ext.logw
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import dev.serhiiyaremych.imla.internal.metrics.SceneFeatureStats
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderMetricsLog

private const val TAG = "SceneRenderPipeline"

internal class SceneRenderPipeline(
    private val presenter: HardwareBitmapPresenter,
    private val noisePass: SceneNoisePass,
    private val rootPass: SceneRootDrawPass,
    private val backdropPass: SceneBackdropEffectPass,
    private val contentPass: SceneSlotContentPass,
    private val stencilClipPass: SceneStencilClipPass
) {
    fun render(frame: SceneGlRenderFrame) = SceneRenderMetricsLog.time(
        phase = "renderAll.renderBatches",
        details = "slot=${frame.slot.id.value}"
    ) {
        trace("SceneRenderPipeline#render") {
            frame.metricsFrame?.recordFeatureStats(frame.featureStats())
            withRenderBuffer(
                targetSize = frame.targetSize,
                slotId = frame.slot.id
            ) { renderBuffer ->
                drawFrame(
                    preparedFrame = prepareFrame(
                        frame = frame,
                        target = renderBuffer
                    )
                )
            }
        }
    }

    fun release() {
        noisePass.release()
        backdropPass.releaseCachedFramebuffers()
        presenter.close()
    }

    private inline fun withRenderBuffer(
        targetSize: IntSize,
        slotId: SceneSlotId,
        draw: (SceneRenderBuffer) -> Unit
    ) {
        val renderBuffer = SceneRenderMetricsLog.time(
            phase = "render.buffer.acquire",
            details = "${targetSize.width}x${targetSize.height}"
        ) {
            presenter.acquire(
                size = targetSize,
                slotId = slotId
            )
        }

        try {
            draw(renderBuffer)
        } finally {
            SceneRenderMetricsLog.time("render.buffer.release") {
                presenter.release(renderBuffer)
            }
        }
    }

    private fun prepareFrame(
        frame: SceneGlRenderFrame,
        target: SceneRenderBuffer
    ): ScenePreparedFrame {
        val noiseTexture = SceneRenderMetricsLog.time(
            phase = "render.noise",
            details = "required=${frame.requiresNoise}"
        ) {
            noisePass.textureFor(
                targetSize = frame.targetSize,
                required = frame.requiresNoise,
                metricsFrame = frame.metricsFrame
            )
        }
        return ScenePreparedFrame(
            frame = frame,
            renderBuffer = target,
            noiseTexture = noiseTexture
        )
    }

    private fun drawFrame(preparedFrame: ScenePreparedFrame) {
        // Overlay present: the source is already in HWUI. Clear to transparent and
        // composite only this layer's glass + content so the bitmap has real alpha.
        SceneRenderMetricsLog.time("render.root") {
            rootPass.beginOverlay(preparedFrame.frame, preparedFrame.renderBuffer)
        }
        renderFrameSlot(preparedFrame)
        present(preparedFrame)
    }

    private fun renderFrameSlot(preparedFrame: ScenePreparedFrame) {
        val slot = preparedFrame.frame.slot
        SceneRenderMetricsLog.time(
            phase = "render.slot.total",
            details = "slot=${slot.id.value}"
        ) {
            if (slot.requiresClip && slot.clipTexture == null) {
                // Clip mask not imported yet this frame (capture lag under heavy
                // re-capture). Skip the slot rather than crash; it renders once
                // the clip lands next frame.
                logw(TAG, "Slot ${slot.id.value} requires a clip texture but none is imported yet; skipping frame")
                return@time
            }
            val preparedSlot = prepareSlot(preparedFrame, slot)
            drawSlot(preparedSlot)
        }
    }

    private fun present(preparedFrame: ScenePreparedFrame) {
        SceneRenderMetricsLog.time("render.present") {
            presenter.present(
                frame = preparedFrame.frame,
                buffer = preparedFrame.renderBuffer
            )
        }
    }

    private fun prepareSlot(
        preparedFrame: ScenePreparedFrame,
        slot: SceneGlRenderSlot
    ): ScenePreparedSlot {
        val backdrop = backdropPass.prepare(
            input = BackdropInput.root(preparedFrame.frame),
            frame = preparedFrame.frame,
            slot = slot
        )
        return ScenePreparedSlot(
            frame = preparedFrame.frame,
            slot = slot,
            renderBuffer = preparedFrame.renderBuffer,
            backdrop = backdrop,
            noiseTexture = preparedFrame.noiseTexture
        )
    }

    private fun drawSlot(
        preparedSlot: ScenePreparedSlot
    ) = SceneRenderMetricsLog.time(
        phase = "render.slot.draw",
        details = "slot=${preparedSlot.slot.id.value} clip=${preparedSlot.slot.requiresClip}"
    ) {
        try {
            if (preparedSlot.slot.requiresBackdropClip && preparedSlot.slot.requiresContentClip) {
                drawClipped(preparedSlot) {
                    drawBackdrop(preparedSlot)
                    drawContent(preparedSlot)
                }
            } else {
                if (preparedSlot.slot.requiresBackdropClip) {
                    drawClipped(preparedSlot) {
                        drawBackdrop(preparedSlot)
                    }
                } else {
                    drawBackdrop(preparedSlot)
                }

                if (preparedSlot.slot.requiresContentClip) {
                    drawClipped(preparedSlot) {
                        drawContent(preparedSlot)
                    }
                } else {
                    drawContent(preparedSlot)
                }
            }
        } finally {
            preparedSlot.backdrop?.let(backdropPass::release)
        }
    }

    private fun drawClipped(
        preparedSlot: ScenePreparedSlot,
        drawSlot: () -> Unit
    ) {
        SceneRenderMetricsLog.time(
            phase = "render.stencilClip.total",
            details = "slot=${preparedSlot.slot.id.value}"
        ) {
            stencilClipPass.draw(
                frame = preparedSlot.frame,
                slot = preparedSlot.slot,
                target = preparedSlot.renderBuffer,
                drawSlot = drawSlot
            )
        }
    }

    private fun drawBackdrop(preparedSlot: ScenePreparedSlot) {
        if (preparedSlot.backdrop == null) return
        backdropPass.drawComposite(
            frame = preparedSlot.frame,
            slot = preparedSlot.slot,
            target = preparedSlot.renderBuffer,
            backdrop = preparedSlot.backdrop,
            noiseTexture = preparedSlot.noiseTexture
        )
    }

    private fun drawContent(preparedSlot: ScenePreparedSlot) {
        contentPass.draw(preparedSlot.frame, preparedSlot.slot, preparedSlot.renderBuffer)
    }
}

private data class ScenePreparedFrame(
    val frame: SceneGlRenderFrame,
    val renderBuffer: SceneRenderBuffer,
    val noiseTexture: Texture2D?
)

private data class ScenePreparedSlot(
    val frame: SceneGlRenderFrame,
    val slot: SceneGlRenderSlot,
    val renderBuffer: SceneRenderBuffer,
    val backdrop: ProcessedBackdrop?,
    val noiseTexture: Texture2D?
)

private val SceneGlRenderFrame.requiresNoise: Boolean
    get() = slot.backdrop?.composite?.hasNoise == true

private fun SceneGlRenderFrame.featureStats(): SceneFeatureStats {
    val hasBackdrop = slot.backdrop != null
    return SceneFeatureStats(
        slots = 1L,
        backdropSlots = if (hasBackdrop) 1L else 0L,
        cumulativeBackdropSlots = if (hasBackdrop) 1L else 0L,
        backdropClipSlots = if (slot.requiresBackdropClip) 1L else 0L,
        contentClipSlots = if (slot.requiresContentClip) 1L else 0L,
        tintSlots = if (slot.backdrop?.composite?.tint != Color.Transparent) 1L else 0L,
        noiseSlots = if (slot.backdrop?.composite?.hasNoise == true) 1L else 0L
    )
}
