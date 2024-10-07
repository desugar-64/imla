/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.mask

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.MAX_TEXTURE_SLOTS
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.uirenderer.RenderableScope
import dev.serhiiyaremych.imla.uirenderer.processing.SimpleQuadRenderer

internal class MaskEffect(
    assetManager: AssetManager,
    private val simpleQuadRenderer: SimpleQuadRenderer
) {

    private val shaderProgram = MaskShaderProgram(assetManager)

    private lateinit var cropBackgroundFramebuffer: Framebuffer
    private lateinit var finalMaskFrameBuffer: Framebuffer
    private var isInitialized: Boolean = false

    private var maskTexture: Texture? = null

    internal val outputFramebuffer: Framebuffer
        get() = finalMaskFrameBuffer

    private fun shouldResize(size: IntSize): Boolean {
        return !isInitialized || (finalMaskFrameBuffer.specification.size != size)
    }

    private fun setup(size: IntSize) {
        if (shouldResize(size)) {
            if (isInitialized) {
                finalMaskFrameBuffer.destroy()
            }

            val spec = FramebufferSpecification(
                size = size,
                attachmentsSpec = FramebufferAttachmentSpecification()
            )
            finalMaskFrameBuffer = Framebuffer.create(spec)
            isInitialized = true

            cropBackgroundFramebuffer = Framebuffer.create(spec)

            val samplers = IntArray(MAX_TEXTURE_SLOTS) { index -> index }
            shaderProgram.shader.bind()
            shaderProgram.shader.setIntArray("u_Textures", *samplers)
        }
    }

    context(RenderableScope)
    fun applyEffect(
        backgroundFramebuffer: Framebuffer,
        backgroundRect: Rect,
        blur: Texture,
        mask: Texture2D?
    ) =
        trace("MaskEffect#applyEffect") {
            maskTexture = mask
            if (isEnabled()) {
                requireNotNull(mask)
                setup(IntSize(mask.width, mask.height))
                trace("cutBackgroundRegion") {
                    backgroundFramebuffer.bind(Bind.READ)
                    cropBackgroundFramebuffer.bind(Bind.DRAW)
                    RenderCommand.clear()
                    RenderCommand.blitFramebuffer(
                        srcX0 = backgroundRect.left.toInt(),
                        srcY0 = backgroundRect.top.toInt(),
                        srcX1 = backgroundRect.width.toInt(),
                        srcY1 = backgroundRect.bottom.toInt(),
                        dstX0 = 0,
                        dstY0 = 0,
                        dstX1 = cropBackgroundFramebuffer.specification.size.width,
                        dstY1 = cropBackgroundFramebuffer.specification.size.height,
                        mask = RenderCommand.colorBufferBit,
                        filter = RenderCommand.linearTextureFilter,
                    )
                }

                trace("setMaskProp") {
                    shaderProgram.setMask(mask)
                    shaderProgram.setBackground(cropBackgroundFramebuffer.colorAttachmentTexture)
                }

                trace("drawMask") {
                    finalMaskFrameBuffer.bind(Bind.DRAW)
                    RenderCommand.clear()
                    simpleQuadRenderer.draw(shader = shaderProgram.shader, texture = blur)
                }
            }
        }

    fun isEnabled(): Boolean = maskTexture != null

    fun dispose() {
        if (isInitialized) {
            finalMaskFrameBuffer.destroy()
            isInitialized = false
        }
    }
}