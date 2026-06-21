/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.Renderer2D
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.RenderQuad
import dev.serhiiyaremych.imla.internal.render.processing.composite.BackdropCompositeShaderEffect
import dev.serhiiyaremych.imla.internal.render.processing.noise.BackdropCompositeSamplingOrigins

/**
 * Renderer 2 backdrop composite boundary for the shared noise/mask shader path.
 *
 * This adapter owns only the scene-facing input contract and draw submission. It preserves the
 * current shader implementation, including noise, coverage-mask, tint, opacity, and screen-space
 * blur sampling math. Inputs are borrowed for the duration of one GL-thread draw; the caller owns
 * texture lifetimes, scene framebuffer binding, clipping state, and effect-frame release.
 */
internal class BackdropCompositeEffect(
    shaderLibrary: ShaderLibrary,
    shaderBinder: ShaderBinder,
    renderer2D: Renderer2D
) {
    private val shaderEffect = BackdropCompositeShaderEffect(shaderLibrary, shaderBinder, renderer2D)

    fun draw(
        quadBatchRenderer: QuadBatchRenderer,
        input: BackdropCompositeInput
    ) {
        shaderEffect.drawCompositeQuad(
            quadBatchRenderer = quadBatchRenderer,
            targetSize = input.targetSize,
            quad = input.toRenderQuad(),
            sampleRect = input.sampleRect,
            sampleUv = input.sampleUv,
            noiseTexture = input.noiseTexture,
            noiseAlpha = input.noiseAlpha,
            blurSigma = input.blurSigma,
            maskTexture = input.compositeCoverageMask,
            samplingOrigins = input.samplingOrigins,
            enableBlending = true
        )
    }
}

/**
 * Scene-facing inputs for one backdrop composite draw.
 *
 * [blurTexture], [sampleRect], [sampleUv], [noiseTexture], [compositeCoverageMask], [tint],
 * [opacity], and [targetSize] are the explicit Renderer 2 composite contract. The remaining fields
 * identify the quad and preserve the current transform, noise-alpha, and sigma uniforms without
 * changing shader behavior.
 */
internal data class BackdropCompositeInput(
    val id: String,
    val blurTexture: Texture2D,
    val sampleRect: Rect,
    val sampleUv: Rect,
    val transform: Mat4,
    val tint: Color,
    val opacity: Float,
    val targetSize: IntSize,
    val noiseTexture: Texture2D?,
    val noiseAlpha: Float,
    val compositeCoverageMask: Texture2D?,
    val blurSigma: Float,
    val samplingOrigins: BackdropCompositeSamplingOrigins = BackdropCompositeSamplingOrigins.fromTextures(
        blurTexture = blurTexture,
        frameNoiseTexture = noiseTexture,
        compositeCoverageMask = compositeCoverageMask
    )
) {
    fun toRenderQuad(): RenderQuad {
        return RenderQuad(
            id = id,
            center = Offset(
                x = sampleRect.left + sampleRect.width / 2f,
                y = sampleRect.top + sampleRect.height / 2f
            ),
            size = sampleRect.size,
            uv = sampleUv,
            texture = blurTexture,
            alpha = opacity,
            maskValue = 1f,
            flipTexture = samplingOrigins.blurTextureFlip,
            transform = transform,
            tint = tint
        )
    }
}
