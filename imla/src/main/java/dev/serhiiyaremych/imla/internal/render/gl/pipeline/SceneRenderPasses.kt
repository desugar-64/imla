package dev.serhiiyaremych.imla.internal.render.gl.pipeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.RenderQuad
import dev.serhiiyaremych.imla.internal.render.processing.draw
import dev.serhiiyaremych.imla.internal.render.shader.ShaderProgram
import dev.serhiiyaremych.imla.internal.render.gl.BackdropInput
import dev.serhiiyaremych.imla.internal.render.gl.ProcessedBackdrop
import dev.serhiiyaremych.imla.internal.render.gl.SCENE_BACKDROP_BLUR_MAX_RADIUS_PX
import dev.serhiiyaremych.imla.internal.render.gl.SceneBackdropBlurPass
import dev.serhiiyaremych.imla.internal.render.gl.SceneBackdropCompositePass
import dev.serhiiyaremych.imla.internal.render.gl.SceneBackdropPreparePass
import dev.serhiiyaremych.imla.internal.render.gl.SceneGlRenderFrame
import dev.serhiiyaremych.imla.internal.render.gl.SceneGlRenderSlot
import dev.serhiiyaremych.imla.internal.render.gl.SceneSampledTexture
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderMetricsLog
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderPassMetric
import dev.serhiiyaremych.imla.internal.layer.model.SceneBackdropOperation
import kotlin.math.ceil
import kotlin.math.min

internal class SceneStencilClipPass(
    private val commandsProvider: () -> RenderCommands,
    private val quadBatchRenderer: QuadBatchRenderer,
    private val shaderProgram: ShaderProgram
) {
    fun draw(
        frame: SceneGlRenderFrame,
        slot: SceneGlRenderSlot,
        target: SceneRenderBuffer,
        drawSlot: () -> Unit
    ) = trace("SceneStencilClipPass#draw") {
        val clipTexture: SceneSampledTexture = requireNotNull(slot.clipTexture) {
            "Scene slot ${slot.id.value} requires a clip texture"
        }
        check(target.hasStencil) {
            "Scene clip texture requires a stencil-capable scene render buffer"
        }
        try {
            val startedNanos = System.nanoTime()
            setupClip(
                clipTexture = clipTexture,
                frame = frame,
                slot = slot,
                target = target
            )
            val outputPixels = slot.geometry.localSize.pixelCount()
            frame.metricsFrame?.recordRenderPass(
                pass = SceneRenderPassMetric.StencilClip,
                startedNanos = startedNanos,
                finishedNanos = System.nanoTime(),
                outputPixels = outputPixels,
                textureSamples = outputPixels
            )
            drawSlot()
        } finally {
            commandsProvider().disableStencilTest()
        }
    }

    private fun setupClip(
        clipTexture: SceneSampledTexture,
        frame: SceneGlRenderFrame,
        slot: SceneGlRenderSlot,
        target: SceneRenderBuffer
    ) {
        val commands = commandsProvider()
        clearStencil(commands, target)
        writeClipToStencil(commands, frame, slot, clipTexture)
        beginStencilClippedDraws(commands)
    }

    private fun clearStencil(
        commands: RenderCommands,
        target: SceneRenderBuffer
    ) {
        target.bindForDraw(commands)
        commands.disableStencilTest()
        commands.clearStencil(0)
        commands.stencilMask(0xFF)
        commands.clear(commands.stencilBufferBit)
    }

    private fun writeClipToStencil(
        commands: RenderCommands,
        frame: SceneGlRenderFrame,
        slot: SceneGlRenderSlot,
        clipTexture: SceneSampledTexture
    ) {
        commands.colorMask(red = false, green = false, blue = false, alpha = false)
        try {
            commands.enableStencilTest()
            commands.stencilFunc(commands.stencilAlways, 1, 0xFF)
            commands.stencilOp(
                commands.stencilKeep,
                commands.stencilKeep,
                commands.stencilReplace
            )
            commands.stencilMask(0xFF)
            drawClipQuad(frame, slot, clipTexture)
        } finally {
            commands.colorMask(red = true, green = true, blue = true, alpha = true)
        }
    }

    private fun drawClipQuad(
        frame: SceneGlRenderFrame,
        slot: SceneGlRenderSlot,
        clipTexture: SceneSampledTexture
    ) {
        val bounds = slot.geometry.rootBounds
        val localSize = slot.geometry.localSize
        val size = Size(
            width = localSize.width.toFloat(),
            height = localSize.height.toFloat()
        )
        quadBatchRenderer.draw(
            targetSize = frame.targetSize,
            shaderProgram = shaderProgram,
            enableBlending = false,
            traceLabel = "SceneStencilClipPass#writeStencil"
        ) {
            submit(
                RenderQuad(
                    id = "",
                    center = Offset(
                        x = bounds.center.x.toFloat(),
                        y = bounds.center.y.toFloat()
                    ),
                    size = size,
                    uv = clipTexture.contentUv,
                    texture = clipTexture.texture,
                    flipTexture = false,
                    transform = slot.geometry.renderTransform
                )
            )
        }
    }

    private fun beginStencilClippedDraws(commands: RenderCommands) {
        commands.stencilFunc(commands.stencilEqual, 1, 0xFF)
        commands.stencilOp(
            commands.stencilKeep,
            commands.stencilKeep,
            commands.stencilKeep
        )
        commands.stencilMask(0x00)
    }
}

internal class SceneRootDrawPass(
    private val commandsProvider: () -> RenderCommands,
    private val quadBatchRenderer: QuadBatchRenderer
) {
    fun draw(
        frame: SceneGlRenderFrame,
        target: SceneRenderBuffer
    ) = trace("SceneRootDrawPass#draw") {
        val startedNanos = System.nanoTime()
        val targetSize = frame.targetSize
        val size = Size(
            width = targetSize.width.toFloat(),
            height = targetSize.height.toFloat()
        )
        val center = Offset(
            x = size.width / 2f,
            y = size.height / 2f
        )
        target.bindForFrameStart(commandsProvider())
        quadBatchRenderer.draw(
            targetSize = targetSize,
            debug = false,
            enableBlending = false,
            traceLabel = "SceneRootDrawPass#quad"
        ) {
            submit(
                RenderQuad(
                    id = "scene-root",
                    center = center,
                    size = size,
                    texture = frame.rootTexture,
                    maskValue = 0f,
                    alpha = 1f,
                    flipTexture = frame.rootTextureFlipYForScreen,
                    tint = Color.Transparent,
                    transform = translateScale(center, size)
                )
            )
        }
        val outputPixels = targetSize.pixelCount()
        frame.metricsFrame?.recordRenderPass(
            pass = SceneRenderPassMetric.RootPresent,
            startedNanos = startedNanos,
            finishedNanos = System.nanoTime(),
            outputPixels = outputPixels,
            textureSamples = outputPixels
        )
    }
}

internal class SceneFinalPresentPass(
    private val commandsProvider: () -> RenderCommands,
    private val quadBatchRenderer: QuadBatchRenderer
) {
    fun draw(
        frame: SceneGlRenderFrame,
        buffer: SceneRenderBuffer,
        viewportDivisor: Int = 1
    ) = trace("SceneFinalPresentPass#draw") {
        val startedNanos = System.nanoTime()
        val targetSize = frame.targetSize
        val presentSize = targetSize.presentViewportSize(viewportDivisor)
        val viewportX = (targetSize.width - presentSize.width) / 2
        val viewportY = (targetSize.height - presentSize.height) / 2
        val size = Size(
            width = presentSize.width.toFloat(),
            height = presentSize.height.toFloat()
        )
        val center = Offset(
            x = size.width / 2f,
            y = size.height / 2f
        )
        commandsProvider().run {
            bindDefaultFramebuffer()
            setViewPort(
                x = viewportX,
                y = viewportY,
                width = presentSize.width,
                height = presentSize.height
            )
        }
        trace("SceneFinalPresentPass#viewport[${presentSize.width}x${presentSize.height}]") {}
        quadBatchRenderer.draw(
            targetSize = presentSize,
            debug = false,
            enableBlending = false,
            traceLabel = "SceneFinalPresentPass#quad"
        ) {
            submit(
                RenderQuad(
                    id = "scene-final",
                    center = center,
                    size = size,
                    texture = buffer.texture,
                    maskValue = 0f,
                    alpha = 1f,
                    flipTexture = true,
                    tint = Color.Transparent,
                    transform = translateScale(center, size)
                )
            )
        }
        val outputPixels = presentSize.pixelCount()
        frame.metricsFrame?.recordRenderPass(
            pass = SceneRenderPassMetric.FinalPresent,
            startedNanos = startedNanos,
            finishedNanos = System.nanoTime(),
            outputPixels = outputPixels,
            textureSamples = outputPixels
        )
    }
}

private fun IntSize.presentViewportSize(divisor: Int): IntSize {
    if (divisor <= 1) return this
    return IntSize(
        width = (width / divisor).coerceAtLeast(1),
        height = (height / divisor).coerceAtLeast(1)
    )
}

internal class SceneBackdropEffectPass(
    private val preparePass: SceneBackdropPreparePass,
    private val blurPass: SceneBackdropBlurPass,
    private val compositePass: SceneBackdropCompositePass
) {
    fun prepare(
        input: BackdropInput,
        frame: SceneGlRenderFrame,
        slot: SceneGlRenderSlot
    ): ProcessedBackdrop? = SceneRenderMetricsLog.time(
        phase = "effect.preProcess",
        details = "slot=${slot.id.value}"
    ) {
        trace("SceneBackdropEffectPass#prepare") {
            val blur = slot.backdropBlur ?: return@trace null
            val prepareStartedNanos = System.nanoTime()
            val prepared = SceneRenderMetricsLog.time(
                phase = "effect.backdropPrepare",
                details = "slot=${slot.id.value}"
            ) {
                preparePass.prepare(
                    input = input,
                    slot = slot,
                    operation = blur,
                    targetSize = frame.targetSize
                )
            }
            val prepareOutputPixels = prepared.outputPixels
            frame.metricsFrame?.recordRenderPass(
                pass = SceneRenderPassMetric.BackdropPrepare,
                startedNanos = prepareStartedNanos,
                finishedNanos = System.nanoTime(),
                outputPixels = prepareOutputPixels,
                textureSamples = prepareOutputPixels * PREPARE_PREFILTER_TAPS
            )
            var processed: ProcessedBackdrop? = null
            try {
                val blurStartedNanos = System.nanoTime()
                val hasProgressiveMask =
                    blur.hasProgressiveMask && slot.progressiveMaskTexture != null
                processed = SceneRenderMetricsLog.time(
                    phase = "effect.blurDirect.total",
                    details = "slot=${slot.id.value}"
                ) {
                    blurPass.process(
                        prepared = prepared,
                        operation = blur,
                        progressiveMaskTexture = slot.progressiveMaskTexture.takeIf { hasProgressiveMask }
                    )
                }
                val blurOutputPixels = processed.outputPixels
                frame.metricsFrame?.recordRenderPass(
                    pass = SceneRenderPassMetric.BackdropBlur,
                    startedNanos = blurStartedNanos,
                    finishedNanos = System.nanoTime(),
                    outputPixels = blurOutputPixels,
                    textureSamples = blurOutputPixels *
                            blur.textureSamplesPerOutputPixel(
                                prepared.downsampleScale,
                                hasProgressiveMask
                            )
                )
                processed
            } catch (throwable: Throwable) {
                processed?.let(blurPass::release)
                preparePass.release(prepared)
                throw throwable
            }
        }
    }

    fun drawComposite(
        frame: SceneGlRenderFrame,
        slot: SceneGlRenderSlot,
        target: SceneRenderBuffer,
        backdrop: ProcessedBackdrop,
        noiseTexture: Texture2D?
    ) = SceneRenderMetricsLog.time(
        phase = "effect.backgroundComposite",
        details = "slot=${slot.id.value}"
    ) {
        trace("SceneBackdropEffectPass#drawComposite") {
            val compositeStartedNanos = System.nanoTime()
            compositePass.draw(
                frame = frame,
                slot = slot,
                processed = backdrop,
                target = target,
                noiseTexture = noiseTexture
            )
            val compositeOutputPixels = slot.geometry.localSize.pixelCount()
            val compositeTextureSamples =
                compositeOutputPixels * slot.compositeTextureSamplesPerPixel()
            frame.metricsFrame?.recordRenderPass(
                pass = SceneRenderPassMetric.BackdropComposite,
                startedNanos = compositeStartedNanos,
                finishedNanos = System.nanoTime(),
                outputPixels = compositeOutputPixels,
                textureSamples = compositeTextureSamples
            )
        }
    }

    fun release(backdrop: ProcessedBackdrop) {
        blurPass.release(backdrop)
        preparePass.release(backdrop.source)
    }

    fun releaseCachedFramebuffers() {
        blurPass.releaseCachedFramebuffers()
        preparePass.releaseCachedFramebuffers()
    }

    private val SceneGlRenderSlot.backdropBlur: SceneBackdropOperation.Blur?
        get() = backdrop?.operations?.firstNotNullOfOrNull { operation ->
            operation as? SceneBackdropOperation.Blur
        }
}

internal class SceneSlotContentPass(
    private val commandsProvider: () -> RenderCommands,
    private val quadBatchRenderer: QuadBatchRenderer
) {
    fun draw(
        frame: SceneGlRenderFrame,
        slot: SceneGlRenderSlot,
        target: SceneRenderBuffer
    ) = SceneRenderMetricsLog.time(
        phase = "effect.contentComposite",
        details = "slot=${slot.id.value}"
    ) {
        trace("SceneSlotContentPass#draw") {
            val content = slot.contentTexture ?: return@trace
            val startedNanos = System.nanoTime()
            val bounds = slot.geometry.rootBounds
            val localSize = slot.geometry.localSize
            val size = Size(
                width = localSize.width.toFloat(),
                height = localSize.height.toFloat()
            )
            // Slot content is captured async, so on a fast collapse it is 1-2 frames taller than
            // the current geometry. Mapping the full stale frame onto the smaller quad squashes it
            // and reads as the content lagging the shrinking card. Sample only the top
            // localSize-worth of the captured content at 1:1 and let the current-frame stencil clip
            // own the card edge. When geometry >= captured (expand) this reduces to the full
            // contentUv, preserving the existing fill behavior.
            val visibleContentUv = content.visibleUv(localSize)
            target.bindForDraw(commandsProvider())
            quadBatchRenderer.draw(
                targetSize = frame.targetSize,
                debug = false,
                enableBlending = true,
                traceLabel = "SceneSlotContentPass#content"
            ) {
                submit(
                    RenderQuad(
                        id = slot.id.value.toString(),
                        center = Offset(
                            x = bounds.center.x.toFloat(),
                            y = bounds.center.y.toFloat()
                        ),
                        size = size,
                        uv = visibleContentUv,
                        texture = content.texture,
                        maskValue = 0f,
                        alpha = 1f,
                        flipTexture = false,
                        tint = Color.Transparent,
                        transform = slot.geometry.renderTransform
                    ),
                )
            }
            val outputPixels = localSize.pixelCount()
            frame.metricsFrame?.recordRenderPass(
                pass = SceneRenderPassMetric.SlotContent,
                startedNanos = startedNanos,
                finishedNanos = System.nanoTime(),
                outputPixels = outputPixels,
                textureSamples = outputPixels
            )
        }
    }
}

private fun SceneSampledTexture.visibleUv(geometrySize: IntSize): Rect {
    val texWidth = texture.width.toFloat().coerceAtLeast(1f)
    val texHeight = texture.height.toFloat().coerceAtLeast(1f)
    return Rect(
        left = 0f,
        top = 0f,
        right = (min(geometrySize.width, contentSize.width).toFloat() / texWidth).coerceIn(0f, 1f),
        bottom = (min(geometrySize.height, contentSize.height).toFloat() / texHeight).coerceIn(0f, 1f)
    )
}

private fun SceneGlRenderSlot.compositeTextureSamplesPerPixel(): Long {
    return if (backdrop?.composite?.hasNoise == true) 2L else 1L
}

private fun IntSize.pixelCount(): Long {
    return width.toLong().coerceAtLeast(0L) * height.toLong().coerceAtLeast(0L)
}

private fun SceneBackdropOperation.Blur.textureSamplesPerOutputPixel(
    downsampleScale: Float,
    hasProgressiveMask: Boolean
): Long {
    val radiusPx = min(
        ceil(sigmaPx * downsampleScale.coerceAtLeast(1e-6f)),
        SCENE_BACKDROP_BLUR_MAX_RADIUS_PX.toFloat()
    ).toInt()
    val samplesPerPass = if (hasProgressiveMask) {
        radiusPx * 2 + 1
    } else {
        1 + ((radiusPx + 1) / 2) * 2
    }
    return samplesPerPass * 2L
}

private const val PREPARE_PREFILTER_TAPS = 8L
