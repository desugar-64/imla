package dev.serhiiyaremych.imla.internal.render.gl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.shader.ShaderProgram
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.RenderQuad
import dev.serhiiyaremych.imla.internal.render.processing.draw
import dev.serhiiyaremych.imla.internal.metrics.SceneMetricsFrame
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderPassMetric

internal class SceneNoisePass(
    private val commandsProvider: () -> RenderCommands,
    private val quadBatchRenderer: QuadBatchRenderer,
    private val shaderProgram: ShaderProgram
) {
    private var noiseFbo: Framebuffer? = null
    private var noiseSize: IntSize = IntSize.Zero
    private var noiseReady: Boolean = false

    fun textureFor(
        targetSize: IntSize,
        required: Boolean,
        metricsFrame: SceneMetricsFrame?
    ): Texture2D? = trace("SceneNoisePass#textureFor") {
        if (!required) return@trace null
        val startedNanos = System.nanoTime()
        ensureNoiseFbo(targetSize)
        if (drawNoiseIfNeeded()) {
            val outputPixels = targetSize.pixelCount()
            metricsFrame?.recordRenderPass(
                pass = SceneRenderPassMetric.NoiseGenerate,
                startedNanos = startedNanos,
                finishedNanos = System.nanoTime(),
                outputPixels = outputPixels
            )
        }
        return@trace noiseFbo?.colorAttachmentTexture
    }

    fun release() {
        noiseFbo?.destroy()
        noiseFbo = null
        noiseSize = IntSize.Zero
        noiseReady = false
    }

    private fun ensureNoiseFbo(size: IntSize) = trace("SceneNoisePass#ensureNoiseFbo") {
        if (size == IntSize.Zero) return@trace
        if (noiseSize == size && noiseFbo != null) return@trace

        noiseFbo?.destroy()
        noiseFbo = Framebuffer.create(
            spec = FramebufferSpecification(
                size = size,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                    format = FramebufferTextureFormat.R8,
                    coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
                )
            ),
            commands = commandsProvider()
        )
        noiseSize = size
        noiseReady = false
    }

    private fun drawNoiseIfNeeded(): Boolean = trace("SceneNoisePass#drawNoiseIfNeeded") {
        val target = noiseFbo ?: return@trace false
        if (noiseReady) return@trace false

        val size = Size(noiseSize.width.toFloat(), noiseSize.height.toFloat())
        val center = Offset(size.width / 2f, size.height / 2f)
        target.bindForOverwrite(commandsProvider(), Bind.DRAW)
        quadBatchRenderer.draw(
            targetSize = noiseSize,
            debug = false,
            enableBlending = false,
            shaderProgram = shaderProgram,
            traceLabel = "SceneNoisePass#quad"
        ) {
            submit(
                RenderQuad(
                    id = "scene2-noise",
                    center = center,
                    size = size,
                    texture = null,
                    maskValue = 0f,
                    alpha = 1f,
                    flipTexture = false,
                    tint = Color.Transparent,
                    transform = translateScale(center, size)
                )
            )
        }
        noiseReady = true
        true
    }
}

private fun IntSize.pixelCount(): Long {
    return width.toLong().coerceAtLeast(0L) * height.toLong().coerceAtLeast(0L)
}
