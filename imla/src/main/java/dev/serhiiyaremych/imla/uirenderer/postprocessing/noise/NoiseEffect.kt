/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing.noise

import android.content.res.AssetManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.uirenderer.RenderableScope
import kotlin.properties.Delegates

internal class NoiseEffect(assetManager: AssetManager) {

    private val shader = NoiseShaderProgram(assetManager)

    private var noiseTextureFrameBuffer: Framebuffer by Delegates.notNull()
    private var outputFrameBuffer: Framebuffer by Delegates.notNull()
    private var isNoiseTextureInitialized: Boolean = false
    private var isNoiseTextureDrawn: Boolean = false

    private fun setup(size: IntSize) {
        if (shouldResize(size)) {
            init(size)
        }
    }

    private fun init(size: IntSize) = trace("init") {
        if (isNoiseTextureInitialized) {
            noiseTextureFrameBuffer.destroy()
            outputFrameBuffer.destroy()
        }
        val spec = FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification()
        )
        noiseTextureFrameBuffer = Framebuffer.create(spec)
        outputFrameBuffer = Framebuffer.create(spec)
        isNoiseTextureInitialized = true
    }

    private fun shouldResize(size: IntSize): Boolean {
        return !isNoiseTextureInitialized || noiseTextureFrameBuffer.specification.size != size
    }

    context(RenderableScope)
    private fun drawNoiseTextureOnce() {
        if (!isNoiseTextureDrawn) {
            trace("drawNoiseTextureOnce") {
                bindFrameBuffer(noiseTextureFrameBuffer) {
                    drawScene(camera = cameraController.camera, shaderProgram = shader) {
                        drawQuad(
                            position = center,
                            size = size
                        )
                    }
                }
            }
            isNoiseTextureDrawn = true
        }
    }

    context(RenderableScope)
    fun applyEffect(texture: Texture, noiseAlpha: Float): Texture {
        if (noiseAlpha >= MIN_NOISE_ALPHA) {
            trace("NoiseEffect#applyEffect") {
                setup(IntSize(width = size.x.toInt(), height = size.y.toInt()))
                drawNoiseTextureOnce()
                bindFrameBuffer(outputFrameBuffer) {
                    RenderCommand.clear(Color.Transparent)
                    drawScene(camera = cameraController.camera) {
                        drawQuad(
                            position = center,
                            size = size,
                            texture = texture
                        )
                        drawQuad(
                            position = center,
                            size = size,
                            texture = noiseTextureFrameBuffer.colorAttachmentTexture,
                            alpha = noiseAlpha
                        )
                    }
                }
            }
            return outputFrameBuffer.colorAttachmentTexture
        } else {
            return texture
        }
    }

    fun dispose() {
        if (isNoiseTextureInitialized) {
            noiseTextureFrameBuffer.destroy()
            outputFrameBuffer.destroy()
        }
        isNoiseTextureInitialized = false
    }

    private companion object {
        const val MIN_NOISE_ALPHA = 0.05f
    }
}