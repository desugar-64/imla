/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.noise

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.renderer.framebuffer.Bind
import dev.serhiiyaremych.imla.renderer.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.shader.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferPool
import dev.serhiiyaremych.imla.renderer.shader.ShaderBinder
import dev.serhiiyaremych.imla.renderer.shader.ShaderLibrary
import dev.serhiiyaremych.imla.uirenderer.processing.SimpleQuadRenderer
import kotlin.properties.Delegates

internal class NoiseEffect(
    shaderLibrary: ShaderLibrary,
    shaderBinder: ShaderBinder,
    private val framebufferPool: FramebufferPool,
    private val simpleQuadRenderer: SimpleQuadRenderer
) {

    private val shader: Shader = shaderLibrary.loadShaderFromFile(
        vertFileName = "simple_quad",
        fragFileName = "noise",
    ).apply {
        bind(shaderBinder)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    private var noiseTextureFrameBuffer: Framebuffer by Delegates.notNull()
    private var effectFrameBuffer: Framebuffer by Delegates.notNull()
    private var effectFrameSpec: FramebufferSpecification by Delegates.notNull()
    private var isNoiseTextureInitialized: Boolean = false
    private var isNoiseTextureDrawn: Boolean = false
    private var noiseAlpha: Float = 0.0f

    internal val outputFramebuffer: Framebuffer
        get() = effectFrameBuffer

    private fun setup(size: IntSize) {
        if (shouldResize(size)) {
            init(size)
        }
    }

    private fun init(size: IntSize) = trace("init") {
        if (isNoiseTextureInitialized) {
            noiseTextureFrameBuffer.destroy()
            effectFrameBuffer.destroy()
        }
        effectFrameSpec = FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification()
        )
        noiseTextureFrameBuffer = Framebuffer.create(effectFrameSpec)
        isNoiseTextureInitialized = true
    }

    private fun shouldResize(size: IntSize): Boolean {
        return !isNoiseTextureInitialized || noiseTextureFrameBuffer.specification.size != size
    }

    private fun drawNoiseTextureOnce() {
        if (!isNoiseTextureDrawn) {
            trace("drawNoiseTextureOnce") {
                noiseTextureFrameBuffer.bind(Bind.DRAW)
                simpleQuadRenderer.draw(shader = shader)
            }
            isNoiseTextureDrawn = true
        }
    }

    fun applyEffect(texture: Texture2D, noiseAlpha: Float): Texture2D {
        this.noiseAlpha = noiseAlpha
        if (isEnabled()) {
            trace("NoiseEffect#applyEffect") {
                setup(IntSize(width = texture.width, height = texture.height))
                drawNoiseTextureOnce()
                effectFrameBuffer = framebufferPool.acquire(effectFrameSpec)
                effectFrameBuffer.bind(Bind.DRAW)
                RenderCommand.clear()
                RenderCommand.enableBlending()
                simpleQuadRenderer.draw(texture = texture)
                simpleQuadRenderer.draw(
                    texture = noiseTextureFrameBuffer.colorAttachmentTexture,
                    alpha = noiseAlpha
                )
            }
            RenderCommand.disableBlending()
            return effectFrameBuffer.colorAttachmentTexture
        } else {
            return texture
        }
    }

    fun isEnabled(): Boolean = noiseAlpha >= MIN_NOISE_ALPHA

    fun dispose() {
        if (isNoiseTextureInitialized) {
            noiseTextureFrameBuffer.destroy()
        }
        isNoiseTextureInitialized = false
    }

    private companion object {
        const val MIN_NOISE_ALPHA = 0.05f
    }
}