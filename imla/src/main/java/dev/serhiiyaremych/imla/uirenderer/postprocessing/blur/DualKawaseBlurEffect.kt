/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer.postprocessing.blur

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
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
import dev.serhiiyaremych.imla.uirenderer.postprocessing.SimpleQuadRenderer
import kotlin.math.pow
import kotlin.properties.Delegates

internal data class BlurContext(
    val framebuffers: List<Framebuffer>,
    val shaderProgram: KawaseShaderProgram
) {
    companion object {
        const val MAX_PASSES = 3
        private const val PASS_SCALE = 0.5f

        // Minimum and maximum sampling offsets for each pass count, determined empirically.
        // Too low: bilinear downsampling artifacts
        // Too high: diagonal sampling artifacts
        private val offsetRanges = listOf(
            1.00f..2.50f, // pass 1
            1.25f..4.25f, // pass 2
            1.50f..11.25f, // pass 3
            1.75f..18.00f, // pass 4
            2.00f..20.00f  // pass 5
            /* limited by MAX_PASSES */
        )

        fun create(assetManager: AssetManager, textureSize: IntSize): BlurContext {
            val fboSpec = FramebufferSpecification(
                size = textureSize,
                attachmentsSpec = FramebufferAttachmentSpecification(
                    attachments = listOf(FramebufferTextureSpecification(format = FramebufferTextureFormat.RGB10_A2))
                )
            )
            val baseLayerSize = (textureSize.toSize() * PASS_SCALE).toIntSize()
            val fbos = buildList {
                for (i in 0..MAX_PASSES) {
                    add(
                        Framebuffer.create(
                            spec = fboSpec.copy(
                                size = baseLayerSize shr i
                            )
                        )
                    )
                }
            }

            return BlurContext(
                framebuffers = fbos,
                shaderProgram = KawaseShaderProgram(assetManager)
            )
        }

        fun convertGaussianRadius(radius: Float): Pair<Int, Float> {
//            for (i in 0 until MAX_PASSES) {
//                val offsetRange = offsetRanges[i]
//                val offset = (radius * PASS_SCALE / (2.0).pow(i + 1)).toFloat()
//                if (offset in offsetRange) {
//                    return (i + 1) to offset
//                }
//            }

            return 1 to (radius * PASS_SCALE / (2.0).pow(1)).toFloat()
        }

    }
}

/**
 * Performs a bitwise right shift operation on both the width and height of an IntSize.
 * This is equivalent to dividing both dimensions by 2^i.
 *
 * @param i The number of positions to shift right. Must be non-negative.
 * @return A new IntSize with both width and height shifted right by i positions.
 *
 * Example:
 *   val originalSize = IntSize(1024, 768)
 *   val halfSize = originalSize shr 1  // Results in IntSize(512, 384)
 *   val quarterSize = originalSize shr 2  // Results in IntSize(256, 192)
 *
 */
private infix fun IntSize.shr(i: Int): IntSize {
    return IntSize(
        width = width shr i,
        height = height shr i
    )
}

internal class DualKawaseBlurEffect(
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
        highResFBO: Framebuffer,
        fboRect: Rect,
        blurRadius: Float,
        tint: Color
    ): Texture = trace("DualKawaseBlurEffect") {
        trace("BlurEffect#applyEffect") {
            val effectSize = fboRect.size.toIntSize()
            setup(effectSize)
            this.blurRadius = blurRadius

            val enabled = isEnabled()

            val offset = BlurContext.convertGaussianRadius(blurRadius).second
            var readFBO: Framebuffer = highResFBO
            var drawFBO = if (enabled) blurContext.framebuffers[0] else resultFramebuffer

            trace("blitFirstFBO") {
                readFBO.bind(Bind.READ, false)
                drawFBO.bind(Bind.DRAW)
                RenderCommand.clear()

                RenderCommand.blitFramebuffer(
                    srcX0 = fboRect.left.toInt(),
                    srcY0 = fboRect.top.toInt(),
                    srcX1 = fboRect.width.toInt(),
                    srcY1 = fboRect.bottom.toInt(),
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

            readFBO.bind(updateViewport = false)
            readFBO.invalidateAttachments()
            RenderCommand.bindDefaultFramebuffer()

            val passes = BlurContext.MAX_PASSES

            val shaderProgram = blurContext.shaderProgram
            shaderProgram.downShader.bind()
            // Downsample
            trace("downsample") {
                for (i in 0 until passes) {
                    readFBO = blurContext.framebuffers[i]
                    drawFBO = blurContext.framebuffers[i + 1]
                    val drawSize = drawFBO.specification.size
                    val halfPixelX = (0.5f / drawSize.width)
                    val halfPixelY = (0.5f / drawSize.height)
                    shaderProgram.setOffset(offset = offset, down = true)
                    shaderProgram.setHalfPixel(
                        halfPixel = Size(halfPixelX, halfPixelY),
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
                    val halfPixelX = (0.5f / drawSize.width)
                    val halfPixelY = (0.5f / drawSize.height)
                    shaderProgram.setOffset(offset, false)
                    shaderProgram.setHalfPixel(Size(halfPixelX, halfPixelY), false)
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