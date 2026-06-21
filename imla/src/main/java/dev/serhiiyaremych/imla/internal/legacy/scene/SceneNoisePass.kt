/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.SimpleRenderer
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SinglePassQuadExecutor

/**
 * Prepares the frame-wide noise texture used by scene backdrop compositing.
 *
 * SceneGlRenderer invokes this after root seeding and before slot passes. The pass owns only its
 * R8 framebuffer, cached noise draw state, context-local command dependency, and shader used to
 * fill that framebuffer through a single-pass quad draw; callers must run it on the GL thread. It
 * does not own frame planning, slot resources, child pass execution, or shared texture lifetime.
 */
internal class SceneNoisePass(
    private val commandsProvider: () -> RenderCommands,
    private val singlePassQuadExecutor: SinglePassQuadExecutor,
    shaderLibrary: ShaderLibrary
) {
    private val noiseGenShader by lazy(LazyThreadSafetyMode.NONE) {
        shaderLibrary.loadShaderFromFile(
            vertFileName = "simple_quad",
            fragFileName = "noise_flat"
        ).apply {
            bindUniformBlock(SimpleRenderer.TEXTURE_DATA_UBO_BLOCK, SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT)
        }
    }

    private var noiseFbo: Framebuffer? = null
    private var noiseSize: IntSize = IntSize.Zero
    private var noiseFlip: Boolean = false
    private var noiseReady: Boolean = false

    fun prepare(frame: SceneRenderFrame, targetSize: IntSize): Texture2D? {
        if (!frame.requiresNoiseTexture) return null

        ensureNoiseFbo(targetSize, frame.rootTexture.flipTexture)
        drawNoiseIfNeeded()
        return noiseFbo?.colorAttachmentTexture
    }

    fun destroy() {
        noiseFbo?.destroy()
        noiseFbo = null
        noiseSize = IntSize.Zero
        noiseFlip = false
        noiseReady = false
    }

    private fun ensureNoiseFbo(size: IntSize, desiredFlip: Boolean) = trace("SceneNoisePass#ensureNoiseFbo") {
        if (size == IntSize.Zero) return@trace
        if (noiseSize == size && noiseFlip == desiredFlip && noiseFbo != null) return@trace

        noiseFbo?.destroy()
        val spec = FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                format = FramebufferTextureFormat.R8,
                coordinateOrigin = if (desiredFlip) CoordinateOrigin.TOP_LEFT else CoordinateOrigin.BOTTOM_LEFT
            )
        )
        noiseFbo = Framebuffer.create(spec, commandsProvider())
        noiseSize = size
        noiseFlip = desiredFlip
        noiseReady = false
    }

    private fun drawNoiseIfNeeded() = trace("SceneNoisePass#drawNoiseIfNeeded") {
        val target = noiseFbo ?: return@trace
        if (noiseReady) return@trace

        target.bindForOverwrite(commandsProvider(), Bind.DRAW)
        singlePassQuadExecutor.draw(shader = noiseGenShader)
        noiseReady = true
    }
}
