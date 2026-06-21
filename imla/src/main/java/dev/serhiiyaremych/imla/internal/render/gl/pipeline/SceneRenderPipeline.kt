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
import dev.serhiiyaremych.imla.internal.metrics.SceneFeatureStats
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderMetricsLog

private const val TAG = "SceneRenderPipeline"

internal class SceneRenderPipeline(
    private val presenter: ScenePresenter,
    private val noisePass: SceneNoisePass,
    private val rootPass: SceneRootDrawPass,
    private val backdropPass: SceneBackdropEffectPass,
    private val contentPass: SceneSlotContentPass,
    private val stencilClipPass: SceneStencilClipPass
) {
    fun render(frame: SceneGlRenderFrame) = SceneRenderMetricsLog.time(
        phase = "renderAll.renderBatches",
        details = "slots=${frame.slots.size}"
    ) {
        trace("SceneRenderPipeline#render") {
            frame.metricsFrame?.recordFeatureStats(frame.featureStats())
            withRenderBuffer(
                targetSize = frame.targetSize,
                slots = frame.slots
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
        slots: List<SceneGlRenderSlot>,
        draw: (SceneRenderBuffer) -> Unit
    ) {
        val renderBuffer = SceneRenderMetricsLog.time(
            phase = "render.buffer.acquire",
            details = "${targetSize.width}x${targetSize.height}"
        ) {
            presenter.acquire(
                size = targetSize,
                requiresStencil = slots.any { it.requiresClip }
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
        drawRoot(preparedFrame)
        drawSlots(preparedFrame)
        present(preparedFrame)
    }

    private fun drawRoot(preparedFrame: ScenePreparedFrame) {
        SceneRenderMetricsLog.time("render.root") {
            rootPass.draw(preparedFrame.frame, preparedFrame.renderBuffer)
        }
    }

    private fun drawSlots(preparedFrame: ScenePreparedFrame) {
        SceneRenderMetricsLog.time(
            phase = "render.slots.total",
            details = "slots=${preparedFrame.frame.slots.size}"
        ) {
            preparedFrame.frame.slots.sortedBy { it.drawOrder }.forEach { slot ->
                if (slot.requiresClip && slot.clipTexture == null) {
                    // Clip mask not imported yet this frame (capture lag under heavy
                    // re-capture). Skip the slot rather than crash; it renders once
                    // the clip lands next frame.
                    logw(TAG, "Slot ${slot.id.value} requires a clip texture but none is imported yet; skipping frame")
                    return@forEach
                }
                val preparedSlot = prepareSlot(preparedFrame, slot)
                drawSlot(preparedSlot)
            }
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
            input = BackdropInput.accumulatedScene(preparedFrame.renderBuffer),
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
        phase = "render.slot.total",
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
    get() = slots.any { slot -> slot.backdrop?.composite?.hasNoise == true }

private fun SceneGlRenderFrame.featureStats(): SceneFeatureStats {
    val backdropSlots = slots.count { slot -> slot.backdrop != null }.toLong()
    return SceneFeatureStats(
        slots = slots.size.toLong(),
        backdropSlots = backdropSlots,
        cumulativeBackdropSlots = backdropSlots,
        backdropClipSlots = slots.count { slot -> slot.requiresBackdropClip }.toLong(),
        contentClipSlots = slots.count { slot -> slot.requiresContentClip }.toLong(),
        tintSlots = slots.count { slot -> slot.backdrop?.composite?.tint != Color.Transparent }
            .toLong(),
        noiseSlots = slots.count { slot -> slot.backdrop?.composite?.hasNoise == true }.toLong()
    )
}
