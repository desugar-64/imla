/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.noise

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.Buffer

class BackdropCompositeSamplingOriginsTest {
    @Test
    fun frameNoiseFlipFollowsTextureOrigin() {
        assertTrue(
            origins(frameNoiseOrigin = CoordinateOrigin.TOP_LEFT).frameNoiseTextureFlip
        )
        assertFalse(
            origins(frameNoiseOrigin = CoordinateOrigin.BOTTOM_LEFT).frameNoiseTextureFlip
        )
        assertFalse(
            origins(frameNoiseOrigin = null).frameNoiseTextureFlip
        )
    }

    @Test
    fun compositeCoverageMaskFlipPreservesCurrentInvertedOriginRule() {
        assertFalse(
            origins(compositeCoverageMaskOrigin = CoordinateOrigin.TOP_LEFT).compositeCoverageMaskFlip
        )
        assertTrue(
            origins(compositeCoverageMaskOrigin = CoordinateOrigin.BOTTOM_LEFT).compositeCoverageMaskFlip
        )
        assertFalse(
            origins(compositeCoverageMaskOrigin = null).compositeCoverageMaskFlip
        )
    }

    @Test
    fun blurFlipPreservesTextureOriginRule() {
        assertTrue(
            origins(blurTextureOrigin = CoordinateOrigin.TOP_LEFT).blurTextureFlip
        )
        assertFalse(
            origins(blurTextureOrigin = CoordinateOrigin.BOTTOM_LEFT).blurTextureFlip
        )
    }

    private fun origins(
        blurTextureOrigin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT,
        frameNoiseOrigin: CoordinateOrigin? = null,
        compositeCoverageMaskOrigin: CoordinateOrigin? = null
    ): BackdropCompositeSamplingOrigins {
        return BackdropCompositeSamplingOrigins.fromTextures(
            blurTexture = FakeTexture(id = 1, coordinateOrigin = blurTextureOrigin),
            frameNoiseTexture = frameNoiseOrigin?.let { origin ->
                FakeTexture(id = 2, coordinateOrigin = origin)
            },
            compositeCoverageMask = compositeCoverageMaskOrigin?.let { origin ->
                FakeTexture(id = 3, coordinateOrigin = origin)
            }
        )
    }

    private class FakeTexture(
        override val id: Int,
        override val coordinateOrigin: CoordinateOrigin
    ) : Texture2D() {
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = 16
        override val height: Int = 16
        override val specification: Texture.Specification = Texture.Specification(
            size = IntSize(width = width, height = height),
            coordinateOrigin = coordinateOrigin
        )

        override fun bind(slot: Int) = Unit
        override fun setData(data: Buffer) = Unit
        override fun isLoaded(): Boolean = true
        override fun destroy() = Unit
        override fun generateMipMaps() = Unit
    }
}
