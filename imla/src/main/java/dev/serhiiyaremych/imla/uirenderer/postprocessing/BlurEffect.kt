/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer.postprocessing

import android.content.res.AssetManager
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.uirenderer.RenderObject
import kotlin.properties.Delegates

internal class BlurEffect(
    assetManager: AssetManager
) : PostProcessingEffect {

    private val blurShaderProgram: BlurShaderProgram = BlurShaderProgram(assetManager)

    private var horizontalPassFramebuffer: Framebuffer by Delegates.notNull()
    private var verticalPassFramebuffer: Framebuffer by Delegates.notNull()

    private var isInitialized: Boolean = false

    var bluerRadius: Float = 4f
        set(value) {
            field = value
            blurShaderProgram.setBlurRadius(value)
        }

    override fun applyEffect(renderObject: RenderObject): Texture2D =
        with(renderObject.renderableScope) {
            trace("BlurEffect#applyEffect") {
                if (!isInitialized) {
                    init(renderObject.layerArea.size) // downsampled layer
                    isInitialized = true
                }
                blurShaderProgram.setBlurringTextureSize(renderObject.layerArea.size) // downsampled layer
                blurShaderProgram.setHorizontalPass()
                bindFrameBuffer(horizontalPassFramebuffer) {
                    drawScene(shaderProgram = blurShaderProgram) {
                        drawQuad(
                            position = scaledCenter,
                            size = scaledSize,
                            subTexture = renderObject.layerArea
                        )
                    }
                }
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

    private fun init(size: IntSize) {
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
}