/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing.mask

import android.content.res.AssetManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.MAX_TEXTURE_SLOTS
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.uirenderer.RenderableScope

internal class MaskEffect(assetManager: AssetManager) {

    private val shaderProgram = MaskShaderProgram(assetManager)

    private lateinit var backgroundFramebuffer: Framebuffer
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

            backgroundFramebuffer = Framebuffer.create(spec)

            val samplers = IntArray(MAX_TEXTURE_SLOTS) { index -> index }
            shaderProgram.shader.bind()
            shaderProgram.shader.setIntArray("u_Textures", *samplers)
        }
    }

    context(RenderableScope)
    fun applyEffect(background: Texture, blur: Texture, mask: Texture2D?) =
        trace("MaskEffect#applyEffect") {
            maskTexture = mask
            if (isEnabled()) {
                requireNotNull(mask)
                setup(IntSize(mask.width, mask.height))
                trace("drawBackground") { // todo: copy texture region instead of drawing
                    bindFrameBuffer(backgroundFramebuffer) {
                        RenderCommand.clear(Color.Transparent)
                        drawScene(cameraController.camera) {
                            drawQuad(
                                position = center,
                                size = size,
                                texture = background
                            )
                        }
                    }
                }

                trace("setMaskProp") {
                    shaderProgram.setMask(mask)
                    shaderProgram.setBackground(backgroundFramebuffer.colorAttachmentTexture)
                }

                trace("drawMask") {
                    bindFrameBuffer(finalMaskFrameBuffer) {
                        RenderCommand.clear(Color.Transparent)
                        drawScene(cameraController.camera, shaderProgram) {
                            drawQuad(
                                position = center,
                                size = size,
                                texture = blur,
                                withMask = true
                            )
                        }
                    }
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