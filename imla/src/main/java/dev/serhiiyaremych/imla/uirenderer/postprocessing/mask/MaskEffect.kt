/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing.mask

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.uirenderer.RenderableScope
import dev.serhiiyaremych.imla.uirenderer.postprocessing.PostProcessingEffect

internal class MaskEffect : PostProcessingEffect {

    private lateinit var framebuffer: Framebuffer
    private var isInitialized: Boolean = false

    var maskTexture: Texture2D? = null

    override fun shouldResize(size: IntSize): Boolean {
        return !isInitialized || (framebuffer.specification.size != size)
    }

    override fun setup(size: IntSize) {
        if (shouldResize(size)) {
            if (isInitialized) {
                framebuffer.destroy()
            }

            val spec = FramebufferSpecification(
                size = size,
                attachmentsSpec = FramebufferAttachmentSpecification()
            )
            framebuffer = Framebuffer.create(spec)
            isInitialized = true
        }
    }

    context(RenderableScope)
    override fun applyEffect(texture: Texture): Texture {
        val mask = maskTexture
        if (mask != null) {
            setup(IntSize(mask.width, mask.height))
            bindFrameBuffer(framebuffer) {
                drawScene(cameraController.camera) {
                    drawQuad(
                        position = center,
                        size = size,
                        texture = texture
                    )
                }
                drawScene(cameraController.camera) {
                    drawQuad(
                        position = center,
                        size = size,
                        texture = mask,
                        alpha = 0.1f
                    )
                }
            }
            return framebuffer.colorAttachmentTexture
        } else {
            return texture
        }
    }

    override fun dispose() {
        if (isInitialized) {
            framebuffer.destroy()
            isInitialized = false
        }
    }
}