/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer.processing.blur

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.SubTexture2D
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.uirenderer.RenderableScope
import dev.serhiiyaremych.imla.uirenderer.processing.SimpleQuadRenderer
import kotlin.properties.Delegates

// Credits:
// GM Shaders: Blur Philosophy, https://mini.gmshaders.com/p/blur-philosophy
// Bandwidth-efficient graphics, https://community.arm.com/cfs-file/__key/communityserver-blogs-components-weblogfiles/00-00-00-20-66/siggraph2015_2D00_mmg_2D00_marius_2D00_notes.pdf
internal class DualBlurEffect(
    private val assetManager: AssetManager,
    private val simpleRenderer: SimpleQuadRenderer
) {
    private var resultFramebuffer: Framebuffer by Delegates.notNull()
    private var blurContext: BlurContext by Delegates.notNull()

    private var blurRadius: Float = 0f

    private var isInitialized: Boolean = false

    internal val outputFramebuffer: Framebuffer
        get() = resultFramebuffer

    private fun setup(size: IntSize) {
        if (isInitialized.not() || shouldResize(size)) {
            trace("setup") {
                init(size)
                isInitialized = true
            }
        }
    }

    private fun shouldResize(size: IntSize): Boolean {
        return !isInitialized ||
                (resultFramebuffer.specification.size != size)
    }

    context(RenderableScope)
    fun applyEffect(
        inputFbo: Framebuffer,
        renderTargetSize: IntSize,
        blurRadius: Float,
        tint: Color // TODO: Implement tinting
    ): Texture = trace("DualKawaseBlurEffect") {
        trace("BlurEffect#applyEffect") {
            setup(renderTargetSize)
            this.blurRadius = blurRadius

            val enabled = isEnabled()

            // TODO: provide as configuration
            val offset = 1f//BlurContext.convertGaussianRadius(blurRadius).second
            val passes = 3
            var readFBO: Framebuffer = inputFbo
            var drawFBO = if (enabled) blurContext.framebuffers[0] else resultFramebuffer

            trace("blitFirstFBO") {
                readFBO.bind(Bind.READ, false)
                drawFBO.bind(Bind.DRAW)
                RenderCommand.clear()

                RenderCommand.blitFramebuffer(
                    srcX0 = 0,
                    srcY0 = 0,
                    srcX1 = readFBO.specification.size.width,
                    srcY1 = readFBO.specification.size.height,
                    dstX0 = 0,
                    dstY0 = 0,
                    dstX1 = drawFBO.specification.size.width,
                    dstY1 = drawFBO.specification.size.height,
                    mask = RenderCommand.colorBufferBit,
                    filter = RenderCommand.linearTextureFilter,
                )
            }
            if (enabled.not()) {
                return resultFramebuffer.colorAttachmentTexture
            }

//            readFBO.bind(updateViewport = false)
//            readFBO.invalidateAttachments()
//            RenderCommand.bindDefaultFramebuffer()


            val shaderProgram = blurContext.shaderProgram
            shaderProgram.downShader.bind()
            // Downsample
            trace("downsample") {
                for (i in 0 until passes) {
                    readFBO = blurContext.framebuffers[i]
                    drawFBO = blurContext.framebuffers[i + 1]
                    val drawSize = drawFBO.specification.size
                    val texelX = (1f / drawSize.width) * offset
                    val texelY = (1f / drawSize.height) * offset
                    shaderProgram.setOffset(offset = offset, down = true)
                    shaderProgram.setTexelSize(
                        texel = Size(texelX, texelY),
                        down = true
                    )
                    drawFBO.bind(bind = Bind.DRAW)
                    RenderCommand.clear()

                    simpleRenderer.draw(
                        shader = shaderProgram.downShader,
                        texture = readFBO.colorAttachmentTexture
                    )
                }

            }
            shaderProgram.upShader.bind()
            // Upsample
            trace("upsample") {
                for (i in 0 until passes) {
                    // Upsampling uses buffers in the reverse direction
                    readFBO = blurContext.framebuffers[passes - i]
                    drawFBO = blurContext.framebuffers[passes - i - 1]
                    drawFBO.bind(Bind.DRAW)
                    RenderCommand.clear()

                    val drawSize = drawFBO.specification.size
                    val texelX = (1f / drawSize.width) * offset
                    val texelY = (1f / drawSize.height) * offset
                    shaderProgram.setOffset(offset, false)
                    shaderProgram.setTexelSize(Size(texelX, texelY), false)
                    simpleRenderer.draw(shaderProgram.upShader, readFBO.colorAttachmentTexture)
                }
            }
            trace("blitResult") {
                blitFramebuffers(
                    srcFramebuffer = drawFBO,
                    dstFramebuffer = resultFramebuffer,
                    srcWidth = drawFBO.colorAttachmentTexture.width,
                    srcHeight = drawFBO.colorAttachmentTexture.height,
                    dstWidth = resultFramebuffer.colorAttachmentTexture.width,
                    dstHeight = resultFramebuffer.colorAttachmentTexture.height
                )
            }

//            trace("clean-up") {
//                blurContext.framebuffers.forEach {
//                    it.bind(updateViewport = false)
//                    it.invalidateAttachments()
//                }
//            }
        }
        RenderCommand.useDefaultProgram()
        RenderCommand.bindDefaultFramebuffer()
        return resultFramebuffer.colorAttachmentTexture
    }

    private fun blitFramebuffers(
        srcFramebuffer: Framebuffer,
        dstFramebuffer: Framebuffer,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ) = trace("blitFramebuffers") {
        srcFramebuffer.bind(Bind.READ)
        dstFramebuffer.bind(Bind.DRAW)
        dstFramebuffer.invalidateAttachments()
        RenderCommand.blitFramebuffer(
            srcX0 = 0,
            srcY0 = 0,
            srcX1 = srcWidth,
            srcY1 = srcHeight,
            dstX0 = 0,
            dstY0 = 0,
            dstX1 = dstWidth,
            dstY1 = dstHeight,
        )
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
            blurContext.shaderProgram.destroy()
            blurContext.framebuffers.forEach { it.destroy() }
        }
        val spec = FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification(
                attachments = listOf(FramebufferTextureSpecification(format = FramebufferTextureFormat.RGBA8))
            )
        )
        blurContext = BlurContext.create(assetManager = assetManager, textureSize = size)
        resultFramebuffer = Framebuffer.create(spec)
    }

    fun isEnabled(): Boolean = blurRadius > MIN_BLUR_RADIUS_PX

    fun dispose() {
        if (isInitialized) {
            blurContext.shaderProgram.destroy()
            blurContext.framebuffers.forEach { it.destroy() }
            resultFramebuffer.destroy()
            isInitialized = false
        }
    }

    companion object {
        const val MIN_BLUR_RADIUS_PX = 2
    }
}