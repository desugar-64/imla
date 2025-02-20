/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.blur

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.shader.ShaderBinder
import dev.serhiiyaremych.imla.renderer.shader.ShaderLibrary
import dev.serhiiyaremych.imla.uirenderer.processing.preprocess.times

internal data class BlurContext(
    val layerSpecs: List<FramebufferSpecification>,
    val shaderProgram: DualBlurFilterShaderProgram
) {
    companion object {
        const val PASS_SCALE = 0.67f
        const val MAX_PASSES = 4

        fun create(
            shaderLibrary: ShaderLibrary,
            shaderBinder: ShaderBinder,
            textureSize: IntSize
        ): BlurContext {
            val fboSpec = FramebufferSpecification(
                size = textureSize,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(format = FramebufferTextureFormat.RGB10_A2)
            )

            var baseLayerSize = (textureSize * PASS_SCALE).roundToMultipleOfFour()

            val fbos = buildList {
                for (i in 0..MAX_PASSES) {
                    add(fboSpec.copy(size = baseLayerSize))
                    baseLayerSize = (baseLayerSize * PASS_SCALE).roundToMultipleOfFour()
                }
            }

            return BlurContext(
                layerSpecs = fbos,
                shaderProgram = DualBlurFilterShaderProgram(shaderLibrary, shaderBinder)
            )
        }
    }
}

private fun roundUpToMultipleOfFour(value: Int): Int = (value + 3) / 4 * 4

private fun IntSize.roundToMultipleOfFour(): IntSize =
    IntSize(
        width = roundUpToMultipleOfFour(this.width),
        height = roundUpToMultipleOfFour(this.height)
    )