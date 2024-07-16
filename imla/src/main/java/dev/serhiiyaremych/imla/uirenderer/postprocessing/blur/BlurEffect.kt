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
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.MAX_TEXTURE_SLOTS
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.SubTexture2D
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.renderer.camera.OrthographicCameraController
import dev.serhiiyaremych.imla.uirenderer.RenderableScope
import kotlin.properties.Delegates

internal class BlurEffect(
    assetManager: AssetManager
) {

    private val blurShaderProgram: BlurShaderProgram = BlurShaderProgram(assetManager)

    private var extraHPassFramebuffer: Framebuffer by Delegates.notNull()
    private var extraVPassFramebuffer: Framebuffer by Delegates.notNull()
    private var extraPassCameraController: OrthographicCameraController by Delegates.notNull()

    private var horizontalPassFramebuffer: Framebuffer by Delegates.notNull()
    private var verticalPassFramebuffer: Framebuffer by Delegates.notNull()

    private var resultFramebuffer: Framebuffer by Delegates.notNull()

    private var blurRadius: Float = 0f

    private var isInitialized: Boolean = false

    internal val outputFramebuffer: Framebuffer
        get() = resultFramebuffer

    fun setup(size: IntSize) {
        if (isInitialized.not() || shouldResize(size)) {
            trace("setup") {
                init(size)
                isInitialized = true


                val samplers = IntArray(MAX_TEXTURE_SLOTS) { index -> index }
                blurShaderProgram.shader.bind()
                blurShaderProgram.shader.setIntArray("u_Textures", *samplers)
            }
        }
    }

    private fun shouldResize(size: IntSize): Boolean {
        return !isInitialized ||
                (horizontalPassFramebuffer.specification.size != size || verticalPassFramebuffer.specification.size != size)
    }

    context(RenderableScope)
    fun applyEffect(texture: Texture, blurRadius: Float, tint: Color): Texture {
        trace("BlurEffect#applyEffect") {
            val effectSize = getSize(texture)
            setup(effectSize)
            this.blurRadius = blurRadius

            if (isEnabled().not()) {
                return@trace texture
            }

            trace("setStyle") {
                blurShaderProgram.setBlurRadius(blurRadius)
                blurShaderProgram.setTintColor(tint)
                blurShaderProgram.setBlurringTextureSize(effectSize)
            }

            // horizontal pass
            trace("horizontalPass") {
                blurShaderProgram.setHorizontalPass()
                horizontalPassFramebuffer.bind(Bind.DRAW)
                drawScene(shaderProgram = blurShaderProgram) {
                    drawQuad(
                        position = scaledCenter,
                        size = scaledSize,
                        texture = texture
                    )
                }
            }
            // vertical pass
            trace("verticalPass") {
                blurShaderProgram.setVerticalPass()
                verticalPassFramebuffer.bind(Bind.DRAW)
                drawScene(shaderProgram = blurShaderProgram) {
                    drawQuad(
                        position = scaledCenter,
                        size = scaledSize,
                        texture = horizontalPassFramebuffer.colorAttachmentTexture
                    )
                }
            }

            if (DO_EXTRA_BLURRING_PASS) {
                val additionalEffectSize = IntSize(
                    width = extraHPassFramebuffer.colorAttachmentTexture.width,
                    height = extraHPassFramebuffer.colorAttachmentTexture.height
                )
                blurShaderProgram.setBlurringTextureSize(
                    additionalEffectSize
                )
                val passCenter = Float3(
                    x = additionalEffectSize.width / 2f,
                    y = additionalEffectSize.height / 2f
                )
                val passSize = Float2(
                    x = additionalEffectSize.width.toFloat(),
                    y = additionalEffectSize.height.toFloat()
                )
                trace("additionalHPass") {
                    extraHPassFramebuffer.bind(Bind.DRAW)
                    blurShaderProgram.setHorizontalPass()
                    drawScene(
                        camera = extraPassCameraController.camera,
                        shaderProgram = blurShaderProgram
                    ) {
                        drawQuad(
                            position = passCenter,
                            size = passSize,
                            texture = verticalPassFramebuffer.colorAttachmentTexture
                        )
                    }
                }
                trace("additionalVPass") {
                    extraVPassFramebuffer.bind(Bind.DRAW)
                    blurShaderProgram.setVerticalPass()
                    drawScene(
                        camera = extraPassCameraController.camera,
                        shaderProgram = blurShaderProgram
                    ) {
                        drawQuad(
                            position = passCenter,
                            size = passSize,
                            texture = extraHPassFramebuffer.colorAttachmentTexture
                        )
                    }
                }
            }

            trace("blitResult") {
                val srcFramebuffer = if (DO_EXTRA_BLURRING_PASS) {
                    extraVPassFramebuffer
                } else {
                    verticalPassFramebuffer
                }

                blitFramebuffers(
                    srcFramebuffer = srcFramebuffer,
                    dstFramebuffer = resultFramebuffer,
                    srcWidth = srcFramebuffer.colorAttachmentTexture.width,
                    srcHeight = srcFramebuffer.colorAttachmentTexture.height,
                    dstWidth = resultFramebuffer.colorAttachmentTexture.width,
                    dstHeight = resultFramebuffer.colorAttachmentTexture.height
                )
            }
        }

        return resultFramebuffer.colorAttachmentTexture
    }

    private fun blitFramebuffers(
        srcFramebuffer: Framebuffer,
        dstFramebuffer: Framebuffer,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ) {
        srcFramebuffer.bind(Bind.READ)
        dstFramebuffer.bind(Bind.DRAW)
        srcFramebuffer.readBuffer(0)
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
        resultFramebuffer = Framebuffer.create(spec)

        if (DO_EXTRA_BLURRING_PASS) {
            val additionalPassSpec = spec.copy(downSampleFactor = spec.downSampleFactor * 2)
            extraHPassFramebuffer = Framebuffer.create(additionalPassSpec)
            extraVPassFramebuffer = Framebuffer.create(additionalPassSpec)
            extraPassCameraController =
                OrthographicCameraController.createPixelUnitsController(
                    viewportWidth = additionalPassSpec.size.width / additionalPassSpec.downSampleFactor,
                    viewportHeight = additionalPassSpec.size.height / additionalPassSpec.downSampleFactor
                )
        }
    }

    fun isEnabled(): Boolean = blurRadius > MIN_BLUR_RADIUS_PX

    fun dispose() {
        horizontalPassFramebuffer.destroy()
        verticalPassFramebuffer.destroy()
        blurShaderProgram.shader.destroy()
        resultFramebuffer.destroy()
        if (DO_EXTRA_BLURRING_PASS) {
            extraHPassFramebuffer.destroy()
            extraVPassFramebuffer.destroy()
        }
        isInitialized = false
    }

    companion object {
        const val MIN_BLUR_RADIUS_PX = 2
        const val DO_EXTRA_BLURRING_PASS = false
    }
}