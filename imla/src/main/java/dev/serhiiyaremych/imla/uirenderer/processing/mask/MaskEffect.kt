/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.mask

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.framebuffer.Bind
import dev.serhiiyaremych.imla.renderer.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.MAX_TEXTURE_SLOTS
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.shader.ShaderBinder
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferPool
import dev.serhiiyaremych.imla.renderer.shader.ShaderLibrary
import dev.serhiiyaremych.imla.uirenderer.processing.SimpleQuadRenderer

internal class MaskEffect(
    shaderLibrary: ShaderLibrary,
    private val framebufferPool: FramebufferPool,
    private val shaderBinder: ShaderBinder,
    private val simpleQuadRenderer: SimpleQuadRenderer
) {

    private val shaderProgram = MaskShaderProgram(shaderLibrary, shaderBinder)

    private lateinit var cropBackgroundFramebuffer: Framebuffer
    private lateinit var cropBackgroundFramebufferSpec: FramebufferSpecification
    private lateinit var finalMaskFrameBuffer: Framebuffer
    private lateinit var finalMaskFrameBufferSpec: FramebufferSpecification
    private var isInitialized: Boolean = false

    private var maskTexture: Texture? = null
    private val cropCoordinates: Array<Offset> = Array<Offset>(4) { Offset.Zero }

    internal val outputFramebuffer: Framebuffer
        get() = finalMaskFrameBuffer

    private fun shouldResize(size: IntSize): Boolean {
        return !isInitialized || (finalMaskFrameBuffer.specification.size != size)
    }

    private fun setup(size: IntSize) {
        if (shouldResize(size)) {
            if (isInitialized) {
                finalMaskFrameBuffer.destroy()
                cropBackgroundFramebuffer.destroy()
            }

            finalMaskFrameBufferSpec = FramebufferSpecification(
                size = size,
                attachmentsSpec = FramebufferAttachmentSpecification()
            )
//            finalMaskFrameBuffer = Framebuffer.create(spec)
            isInitialized = true

//            cropBackgroundFramebuffer = Framebuffer.create(spec)

            val samplers = IntArray(MAX_TEXTURE_SLOTS) { index -> index }
            shaderProgram.shader.bind(shaderBinder)
            shaderProgram.shader.setIntArray("u_Textures", samplers)
        }
    }

    fun applyEffect(
        backgroundFramebuffer: Framebuffer,
        backgroundCrop: Rect,
        foreground: Texture2D,
        foregroundCrop: Rect,
        mask: Texture2D?
    ) =
        trace("MaskEffect#applyEffect") {
            maskTexture = mask
            if (isEnabled()) {
                requireNotNull(mask)
                setup(IntSize(mask.width, mask.height))
                trace("cutBackgroundRegion") {
                    cropBackgroundFramebuffer = framebufferPool.acquire(cropBackgroundFramebufferSpec)
                    finalMaskFrameBuffer = framebufferPool.acquire(finalMaskFrameBufferSpec)

                    backgroundFramebuffer.bind(Bind.READ)
                    cropBackgroundFramebuffer.bind(Bind.DRAW)
                    RenderCommand.clear()
                    val crop = backgroundCrop.translate(
                        translateX = 0f,
                        translateY = backgroundFramebuffer.specification.size.height - backgroundCrop.height
                    )
                    RenderCommand.blitFramebuffer(
                        srcX0 = crop.left.toInt(),
                        srcY0 = crop.top.toInt(),
                        srcX1 = crop.width.toInt(),
                        srcY1 = crop.bottom.toInt(),
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
                    val foregroundSize = IntSize(foreground.width, foreground.height)
                    cropCoordinates[0] = Offset(
                        x = foregroundCrop.left / foregroundSize.width,
                        y = 1.0f - (foregroundCrop.bottom / foregroundSize.height)
                    ) // BL
                    cropCoordinates[1] = Offset(
                        x = foregroundCrop.right / foregroundSize.width,
                        y = 1.0f - (foregroundCrop.bottom / foregroundSize.height)
                    ) // BR
                    cropCoordinates[2] = Offset(
                        x = foregroundCrop.right / foregroundSize.width,
                        y = 1.0f - (foregroundCrop.top / foregroundSize.height)
                    ) // TR
                    cropCoordinates[3] = Offset(
                        x = foregroundCrop.left / foregroundSize.width,
                        y = 1.0f - (foregroundCrop.top / foregroundSize.height)
                    ) // TL

                    simpleQuadRenderer.draw(
                        shader = shaderProgram.shader,
                        texture = foreground,
                        textureCoordinates = cropCoordinates
                    )
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