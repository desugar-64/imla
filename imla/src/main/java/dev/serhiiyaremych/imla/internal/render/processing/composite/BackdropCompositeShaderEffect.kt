/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.composite

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Float4
import dev.serhiiyaremych.imla.internal.render.Float2
import dev.serhiiyaremych.imla.internal.render.Renderer2D
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.objects.QuadShaderProgram
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.RenderQuad
import dev.serhiiyaremych.imla.internal.render.processing.draw
import dev.serhiiyaremych.imla.internal.render.processing.noise.BackdropCompositeSamplingOrigins

internal class BackdropCompositeShaderEffect(
    private val shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder,
    private val renderer2D: Renderer2D
) {
    private val quadShaderProgram: QuadShaderProgram by lazy(LazyThreadSafetyMode.NONE) {
        val maxSlots = renderer2D.data.maxTextureSlots
        val shaderPreprocessor = mapOf(
            "MAX_TEXTURE_SLOTS" to maxSlots.toString(),
            "TEXTURE_SWITCH_CASES" to generateTextureSwitchCases(maxSlots, "baseColor"),
            "NOISE_SWITCH_CASES" to generateTextureSwitchCases(maxSlots, "noiseColor"),
            "MASK_SWITCH_CASES" to generateTextureSwitchCases(maxSlots, "maskColor"),
            "BASE_SWITCH_CASES" to generateTextureSwitchCases(maxSlots, "baseSource")
        )
        QuadShaderProgram(
            shaderBinder = shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "noise_blend_quad",
                preprocessorDefines = shaderPreprocessor
            )
        )
    }

    /**
     * Blend blur in screen space while keeping noise/mask in quad space for 3D transforms.
     */
    fun drawCompositeQuad(
        quadBatchRenderer: QuadBatchRenderer,
        targetSize: IntSize,
        quad: RenderQuad,
        sampleRect: Rect,
        sampleUv: Rect,
        noiseTexture: Texture2D?,
        noiseAlpha: Float,
        blurSigma: Float,
        maskTexture: Texture2D? = null,
        samplingOrigins: BackdropCompositeSamplingOrigins = BackdropCompositeSamplingOrigins.fromRenderQuad(
            quad = quad,
            frameNoiseTexture = noiseTexture,
            compositeCoverageMask = maskTexture
        ),
        enableBlending: Boolean = false
    ) {
        val wantsNoise = noiseTexture != null && noiseAlpha > 0f
        val wantsMask = maskTexture != null
        val reservedTextures = listOfNotNull(
            noiseTexture?.takeIf { wantsNoise },
            maskTexture?.takeIf { wantsMask }
        )

        quadBatchRenderer.draw(
            targetSize,
            enableBlending = enableBlending,
            shaderProgram = quadShaderProgram,
            reservedTextures = reservedTextures,
            configureShader = { shaderProgram, reservedTextureSlots ->
                val shader = shaderProgram.shader
                val noiseSlot = reservedTextureSlots.slotFor(noiseTexture, wantsNoise)
                val maskSlot = reservedTextureSlots.slotFor(maskTexture, wantsMask)
                shader.setFloat2(
                    "u_TargetSize",
                    Float2(targetSize.width.toFloat(), targetSize.height.toFloat())
                )
                shader.setFloat4(
                    "u_SampleRect",
                    Float4(sampleRect.left, sampleRect.top, sampleRect.width, sampleRect.height)
                )
                shader.setFloat2("u_SampleUvMin", Float2(sampleUv.left, sampleUv.top))
                shader.setFloat2("u_SampleUvMax", Float2(sampleUv.right, sampleUv.bottom))
                shader.setFloat("u_NoiseAlpha", noiseAlpha)
                shader.setFloat(
                    "u_NoiseFlip",
                    if (wantsNoise && samplingOrigins.frameNoiseTextureFlip) 1f else 0f
                )
                shader.setFloat(
                    "u_MaskFlip",
                    if (wantsMask && samplingOrigins.compositeCoverageMaskFlip) 1f else 0f
                )
                shader.setFloat("u_BlurMaskPower", BLUR_MASK_POWER)
                shader.setFloat("u_BlurSigma", blurSigma)
                shader.setFloat("u_BlurSigmaRange", BLUR_SIGMA_RANGE)
                shader.setFloat("u_BaseFlip", 0f)
                shader.setFloat2("u_BaseUvMin", Float2(0f, 0f))
                shader.setFloat2("u_BaseUvMax", Float2(1f, 1f))
                shader.setFloat("u_NoiseEnabled", if (wantsNoise) 1f else 0f)
                shader.setFloat("u_MaskEnabled", if (wantsMask) 1f else 0f)
                shader.setFloat("u_BaseEnabled", 0f)
                shader.setInt("u_NoiseTexIndex", noiseSlot)
                shader.setInt("u_MaskTexIndex", maskSlot)
                shader.setInt("u_BaseTexIndex", 0)
            },
            traceLabel = "QuadBatchRenderer#backdropCompositeShader"
        ) {
            submit(quad)
        }
    }

    private fun Map<Texture2D, Int>.slotFor(texture: Texture2D?, enabled: Boolean): Int {
        return if (enabled) getValue(requireNotNull(texture)) else 0
    }

    private companion object {
        const val BLUR_MASK_POWER = 1.0f
        const val BLUR_SIGMA_RANGE = 1.2f
    }

    private fun generateTextureSwitchCases(maxTextureSlots: Int, resultVar: String): String {
        return buildString {
            for (i in 0 until maxTextureSlots) {
                appendLine("        case $i:")
                appendLine("            $resultVar = textureLod(u_Textures[$i], uv, 0.0);break;")
            }
        }
    }
}
