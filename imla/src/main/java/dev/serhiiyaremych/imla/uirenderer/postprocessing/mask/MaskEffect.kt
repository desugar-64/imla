/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing.mask

import android.content.res.AssetManager
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.MAX_TEXTURE_SLOTS
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.uirenderer.RenderableScope
import dev.serhiiyaremych.imla.uirenderer.postprocessing.PostProcessingEffect

internal class MaskEffect(assetManager: AssetManager) : PostProcessingEffect {

    private val shaderProgram = MaskShaderProgram(assetManager)

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

            val samplers = IntArray(MAX_TEXTURE_SLOTS) { index -> index }
            shaderProgram.shader.bind()
            shaderProgram.shader.setIntArray("u_Textures", *samplers)
        }
    }

    context(RenderableScope)
    override fun applyEffect(texture: Texture): Texture {
        val mask = maskTexture
        if (mask != null) {
            shaderProgram.setMask(mask)
            setup(IntSize(mask.width, mask.height))
            bindFrameBuffer(framebuffer) {
                drawScene(cameraController.camera, shaderProgram) {
                    drawQuad(
                        position = center,
                        size = size,
                        texture = texture
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