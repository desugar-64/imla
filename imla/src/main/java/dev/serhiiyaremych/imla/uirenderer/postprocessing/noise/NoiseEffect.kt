/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing.noise

import android.content.res.AssetManager
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.uirenderer.RenderObject
import dev.serhiiyaremych.imla.uirenderer.postprocessing.PostProcessingEffect
import kotlin.properties.Delegates

internal class NoiseEffect(assetManager: AssetManager) : PostProcessingEffect {

    private val shader = NoiseShaderProgram(assetManager)

    private var noiseTextureFrameBuffer: Framebuffer by Delegates.notNull()
    private var textureFrameBuffer: Framebuffer by Delegates.notNull()
    private var isNoiseTextureInitialized: Boolean = false


    override fun applyEffect(renderObject: RenderObject): Texture2D {
        with(renderObject.renderableScope) {
            if (!isNoiseTextureInitialized) {
                isNoiseTextureInitialized = true
                initializeNoiseTexture(
                    renderObject.layerArea.size.width,
                    renderObject.layerArea.size.height
                )
                bindFrameBuffer(noiseTextureFrameBuffer) {
                    scaledCameraController.onVisibleBoundsResize(
                        renderObject.layerArea.size.width,
                        renderObject.layerArea.size.height,
                    )
                    drawScene(shaderProgram = shader) {
                        drawQuad(
                            position = Float3(
                                Float2(
                                    renderObject.layerArea.size.width / 2f,
                                    renderObject.layerArea.size.height / 2f
                                )
                            ),
                            size = Float2(
                                renderObject.layerArea.size.width.toFloat(),
                                renderObject.layerArea.size.height.toFloat()
                            )

                        )
                    }
                }
            }
//            bindFrameBuffer(textureFrameBuffer) {
//                drawScene() {
//                    drawQuad(
//                        position = scaledCenter,
//                        size = scaledSize,
//                        texture = noiseTextureFrameBuffer.colorAttachmentTexture
//                    )
//                }
//            }
        }
        return noiseTextureFrameBuffer.colorAttachmentTexture
    }

    private fun initializeNoiseTexture(width: Int, height: Int) {
        val spec = FramebufferSpecification(
            size = IntSize(width, height),
            attachmentsSpec = FramebufferAttachmentSpecification(
                attachments = listOf(
                    FramebufferTextureSpecification(format = FramebufferTextureFormat.RGBA8)
                )
            )
        )
        noiseTextureFrameBuffer = Framebuffer.create(spec)

        textureFrameBuffer = Framebuffer.create(
            FramebufferSpecification(
                size = IntSize(width, height),
                attachmentsSpec = FramebufferAttachmentSpecification()
            )
        )
    }

    override fun dispose() {
        TODO("Not yet implemented")
    }
}