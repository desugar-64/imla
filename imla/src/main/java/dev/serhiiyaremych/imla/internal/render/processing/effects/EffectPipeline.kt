/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.effects

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SinglePassQuadExecutor

/**
 * Coordinates active preprocess and blur effects with explicit output size and UV metadata.
 *
 * The pipeline owns effect instances and their transient shader programs. Callers own effect output
 * lifetime according to each returned type; effects release only the temporary FBOs documented by
 * their public methods. All GL work must run on the renderer GL thread.
 */
internal class EffectPipeline(
    pool: FramebufferLendingPool,
    commands: RenderCommands,
    shaderLibrary: ShaderLibrary,
    shaderBinder: ShaderBinder,
    singlePassQuadExecutor: SinglePassQuadExecutor
) {
    private val context = EffectContext(
        pool,
        commands,
        shaderLibrary,
        shaderBinder,
        singlePassQuadExecutor
    )
    private val preProcessEffect = PreProcessEffect(context)
    private val gaussianBlurEffect = GaussianBlurEffect(context)

    /**
     * Apply pre-process effect for [GaussianBlurEffect] blur.
     *
     * @param rootFbo Source framebuffer to sample from
     * @param sampleArea Area in root FBO to extract (in pixels)
     * @param contentSize Actual content size (smaller than sampleArea if there's rotation padding)
     * @return PreProcessEffect.PreProcessOutput containing processed FBO and metadata
     */
    internal fun preProcess(
        rootFbo: Framebuffer,
        sampleArea: Rect,
        contentSize: IntSize,
        sigma: Float
    ): PreProcessEffect.PreProcessOutput =
        preProcessEffect.apply(rootFbo, sampleArea, contentSize, sigma)

    internal fun gaussianBlur(
        input: PreProcessEffect.PreProcessOutput,
        sigma: Float,
        maskTexture: Texture2D? = null
    ): GaussianBlurEffect.Output = gaussianBlurEffect.applyEffect(input, sigma, maskTexture)

    internal fun gaussianBlurAtlas(
        input: SizedFramebuffer,
        sampleCrop: Rect,
        sigmaTexels: Float,
        maskTexture: Texture2D? = null,
        releaseInput: Boolean = true
    ): SizedFramebuffer = gaussianBlurEffect.applyAtlas(
        input = input,
        sampleCrop = sampleCrop,
        sigmaTexels = sigmaTexels,
        maskTexture = maskTexture,
        releaseInput = releaseInput
    )

    /**
     * Reset transient FBO pool. Call at frame end after all effects are consumed.
     * Note: FramebufferLendingPool is a lending pool - FBOs are returned when released.
     */
    internal fun resetTransientPool() {
        // No-op for FramebufferLendingPool - it's a lending pool
    }

    internal fun resetEffects() {
        gaussianBlurEffect.reset()
    }

    /**
     * Clean up resources.
     * Note: This is handled at the GraphicsContext level.
     */
    internal fun destroy() {
        // No-op - pool is managed by GraphicsContext
    }
}
