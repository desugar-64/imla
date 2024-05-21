/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer.postprocessing.blur

import android.content.res.AssetManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.MAX_TEXTURE_SLOTS
import dev.serhiiyaremych.imla.renderer.SubTexture2D
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.uirenderer.RenderableScope
import dev.serhiiyaremych.imla.uirenderer.postprocessing.PostProcessingEffect
import kotlin.properties.Delegates

internal class BlurEffect(
    assetManager: AssetManager
) : PostProcessingEffect {

    private val blurShaderProgram: BlurShaderProgram = BlurShaderProgram(assetManager)

    private var horizontalPassFramebuffer: Framebuffer by Delegates.notNull()
    private var verticalPassFramebuffer: Framebuffer by Delegates.notNull()

    private var isInitialized: Boolean = false

    var bluerRadius: Float = 0f
    var tint: Color = Color.Transparent

    private fun isEnabled(): Boolean {
        return bluerRadius > MIN_BLUR_RADIUS_PX
    }

    override fun setup(size: IntSize) {
        if (isEnabled() && shouldResize(size)) {
            init(size)
            isInitialized = true


            val samplers = IntArray(MAX_TEXTURE_SLOTS) { index -> index }
            blurShaderProgram.shader.bind()
            blurShaderProgram.shader.setIntArray("u_Textures", *samplers)
        }
    }

    override fun shouldResize(size: IntSize): Boolean {
        return !isInitialized ||
                (horizontalPassFramebuffer.specification.size != size || verticalPassFramebuffer.specification.size != size)
    }

    context(RenderableScope)
    override fun applyEffect(texture: Texture): Texture {
        trace("BlurEffect#applyEffect") {
            val effectSize = getSize(texture)
            setup(effectSize)

            if (isEnabled().not()) {
                return@trace texture
            }

            blurShaderProgram.setBlurRadius(bluerRadius)
            blurShaderProgram.setTintColor(tint)
            blurShaderProgram.setBlurringTextureSize(effectSize) // down-sampled layer

            // first pass
            blurShaderProgram.setHorizontalPass()
            bindFrameBuffer(horizontalPassFramebuffer) {
                drawScene(shaderProgram = blurShaderProgram) {
                    drawQuad(
                        position = scaledCenter,
                        size = scaledSize,
                        texture = texture
                    )
                }
            }
            // second pass
            blurShaderProgram.setVerticalPass()
            bindFrameBuffer(verticalPassFramebuffer) {
                drawScene(shaderProgram = blurShaderProgram) {
                    drawQuad(
                        position = scaledCenter,
                        size = scaledSize,
                        texture = horizontalPassFramebuffer.colorAttachmentTexture
                    )
                }
            }
        }
        return verticalPassFramebuffer.colorAttachmentTexture
    }

    private fun getSize(texture: Texture): IntSize {
        return when (texture) {
            is Texture2D -> IntSize(width = texture.width, height = texture.height)
            is SubTexture2D -> texture.subTextureSize
            else -> error("Unsupported texture: $texture")
        }
    }

    private fun init(size: IntSize) = trace("BlurEffect#init") {
        if (isInitialized) {
            horizontalPassFramebuffer.destroy()
            verticalPassFramebuffer.destroy()
        }
        val spec = FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification(
                attachments = listOf(FramebufferTextureSpecification(format = FramebufferTextureFormat.RGBA8))
            )
        )
        horizontalPassFramebuffer = Framebuffer.create(spec)
        verticalPassFramebuffer = Framebuffer.create(spec)
    }

    override fun dispose() {
        horizontalPassFramebuffer.destroy()
        verticalPassFramebuffer.destroy()
        isInitialized = false
    }

    companion object {
        const val MIN_BLUR_RADIUS_PX = 1
    }
}