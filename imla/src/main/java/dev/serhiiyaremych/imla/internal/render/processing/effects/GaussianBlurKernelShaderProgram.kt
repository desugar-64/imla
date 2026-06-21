/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.effects

import dev.serhiiyaremych.imla.internal.render.Float2
import dev.serhiiyaremych.imla.internal.render.SimpleRenderer
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary

internal class GaussianBlurKernelShaderProgram(
    shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder
) {
    val shader: Shader = shaderLibrary.loadShaderFromFile(
        vertFileName = "gaussian_blur",
        fragFileName = "gaussian_blur_kernel"
    ).apply {
        bind(shaderBinder)
        setInt("u_Texture", 0)
        setInt("u_Mask", 1)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    private val kernelBuffer = FloatArray(GaussianKernel.MAX_KERNEL_SAMPLES * 4)

    fun setKernelSamples(samples: GaussianKernel.KernelSamples, offsetScaleX: Float, offsetScaleY: Float) {
        val count = samples.sampleCount
        val offsets = samples.offsets
        val weights = samples.weights
        for (i in 0 until count) {
            val idx = i * 4
            val offset = offsets[i]
            kernelBuffer[idx] = offset * offsetScaleX
            kernelBuffer[idx + 1] = offset * offsetScaleY
            kernelBuffer[idx + 2] = weights[i]
            kernelBuffer[idx + 3] = 0f
        }
        shader.setInt("u_SampleCount", count)
        shader.setFloat4Array("u_KernelSamples", kernelBuffer, count)
    }

    fun setUvBounds(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        shader.setFloat2("u_UvMin", Float2(minX, minY))
        shader.setFloat2("u_UvMax", Float2(maxX, maxY))
    }

    fun setMaskUvBounds(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        shader.setFloat2("u_MaskUvMin", Float2(minX, minY))
        shader.setFloat2("u_MaskUvMax", Float2(maxX, maxY))
    }

    fun setMaskEnabled(enabled: Boolean) {
        shader.setFloat("u_MaskEnabled", if (enabled) 1.0f else 0.0f)
    }

    fun setMaskFlip(flip: Boolean) {
        shader.setFloat("u_MaskFlip", if (flip) 1.0f else 0.0f)
    }

    fun setFlipY(flip: Boolean) {
        shader.setFloat("u_FlipY", if (flip) 1.0f else 0.0f)
    }

    fun destroy() {
        shader.destroy()
    }
}
