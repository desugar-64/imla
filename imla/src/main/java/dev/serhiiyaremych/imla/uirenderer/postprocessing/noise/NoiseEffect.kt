/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing.noise

import android.content.res.AssetManager
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.uirenderer.RenderableScope
import dev.serhiiyaremych.imla.uirenderer.postprocessing.SimpleQuadRenderer
import kotlin.properties.Delegates

internal class NoiseEffect(
    assetManager: AssetManager,
    private val simpleQuadRenderer: SimpleQuadRenderer
) {

    private val shader: Shader = Shader.create(
        assetManager = assetManager,
        vertexAsset = "shader/simple_quad.vert",
        fragmentAsset = "shader/noise.frag",
    ).apply {
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    private var noiseTextureFrameBuffer: Framebuffer by Delegates.notNull()
    private var effectFrameBuffer: Framebuffer by Delegates.notNull()
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
        val spec = FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification()
        )
        noiseTextureFrameBuffer = Framebuffer.create(spec)
        effectFrameBuffer = Framebuffer.create(spec)
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

    context(RenderableScope)
    fun applyEffect(texture: Texture, noiseAlpha: Float): Texture {
        this.noiseAlpha = noiseAlpha
        if (isEnabled()) {
            trace("NoiseEffect#applyEffect") {
                setup(IntSize(width = size.x.toInt(), height = size.y.toInt()))
                drawNoiseTextureOnce()

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
            effectFrameBuffer.destroy()
        }
        isNoiseTextureInitialized = false
    }

    private companion object {
        const val MIN_NOISE_ALPHA = 0.05f
    }
}