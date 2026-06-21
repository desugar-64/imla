/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.noise

import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.processing.RenderQuad

internal data class BackdropCompositeSamplingOrigins(
    val blurTextureOrigin: CoordinateOrigin?,
    val blurTextureFlip: Boolean,
    val frameNoiseTextureOrigin: CoordinateOrigin?,
    val compositeCoverageMaskOrigin: CoordinateOrigin?
) {
    val frameNoiseTextureFlip: Boolean
        get() = frameNoiseTextureOrigin?.needsFlipForOpenGL() == true

    val compositeCoverageMaskFlip: Boolean
        get() = compositeCoverageMaskOrigin?.needsFlipForCompositeCoverageMask() == true

    fun withFrameInputs(
        frameNoiseTexture: Texture2D?,
        compositeCoverageMask: Texture2D?
    ): BackdropCompositeSamplingOrigins {
        return copy(
            frameNoiseTextureOrigin = frameNoiseTexture?.coordinateOrigin,
            compositeCoverageMaskOrigin = compositeCoverageMask?.coordinateOrigin
        )
    }

    companion object {
        fun fromTextures(
            blurTexture: Texture2D,
            frameNoiseTexture: Texture2D?,
            compositeCoverageMask: Texture2D?
        ): BackdropCompositeSamplingOrigins {
            return BackdropCompositeSamplingOrigins(
                blurTextureOrigin = blurTexture.coordinateOrigin,
                blurTextureFlip = blurTexture.flipTexture,
                frameNoiseTextureOrigin = frameNoiseTexture?.coordinateOrigin,
                compositeCoverageMaskOrigin = compositeCoverageMask?.coordinateOrigin
            )
        }

        fun fromRenderQuad(
            quad: RenderQuad,
            frameNoiseTexture: Texture2D?,
            compositeCoverageMask: Texture2D?
        ): BackdropCompositeSamplingOrigins {
            return BackdropCompositeSamplingOrigins(
                blurTextureOrigin = quad.texture?.coordinateOrigin,
                blurTextureFlip = quad.flipTexture ?: quad.texture?.flipTexture ?: false,
                frameNoiseTextureOrigin = frameNoiseTexture?.coordinateOrigin,
                compositeCoverageMaskOrigin = compositeCoverageMask?.coordinateOrigin
            )
        }
    }
}

private fun CoordinateOrigin.needsFlipForCompositeCoverageMask(): Boolean {
    return !needsFlipForOpenGL()
}
