/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EffectLayerScopeTest {
    @Test
    fun duplicateBackdropBlurFails() {
        try {
            EffectLayerScope().apply {
                backdropBlur(sigmaPx = 12f)
                backdropBlur(sigmaPx = 24f)
            }
            fail("Expected duplicate backdrop blur to fail")
        } catch (caught: IllegalStateException) {
            assertTrue(caught.message.orEmpty().contains("backdropBlur"))
        }
    }

    @Test
    fun dpBackdropBlurStoresRadius() {
        val config = EffectLayerScope().apply {
            backdropBlur(radius = 8.dp)
        }.toConfig()

        assertEquals(8.dp, config.backdropBlurRadius)
        assertEquals(null, config.backdropBlurSigmaPx)
    }

    @Test
    fun mixingPxAndDpBackdropBlurFails() {
        try {
            EffectLayerScope().apply {
                backdropBlur(sigmaPx = 12f)
                backdropBlur(radius = 8.dp)
            }
            fail("Expected mixing backdrop blur units to fail")
        } catch (caught: IllegalStateException) {
            assertTrue(caught.message.orEmpty().contains("backdropBlur"))
        }
    }

    @Test
    fun repeatedClipUsesLastShape() {
        val firstShape = RoundedCornerShape(8.dp)
        val secondShape = RoundedCornerShape(16.dp)
        val inset = PaddingValues(1.dp)

        val config = EffectLayerScope().apply {
            clip(firstShape)
            clip(secondShape, inset = inset)
        }.toConfig()

        assertSame(secondShape, config.clipShape)
        assertSame(inset, config.clipInset)
        assertFalse(config.clipContent)
    }

    @Test
    fun clipContentIsExplicit() {
        val shape = RoundedCornerShape(16.dp)

        val config = EffectLayerScope().apply {
            clip(shape, clipContent = true)
        }.toConfig()

        assertTrue(config.clipContent)
    }

    @Test
    fun repeatedTintUsesLastColor() {
        val color = Color(0xFFFF5252).copy(alpha = 0.24f)

        val config = EffectLayerScope().apply {
            tint(Color.White.copy(alpha = 0.12f))
            tint(color)
        }.toConfig()

        assertEquals(color, config.backdropTint)
    }

    @Test
    fun repeatedNoiseUsesLastClampedAlpha() {
        val config = EffectLayerScope().apply {
            noise(alpha = 0.12f)
            noise(alpha = 2f)
        }.toConfig()

        assertEquals(1f, config.noiseAlpha, 0f)
    }

    @Test
    fun visualBoundsProviderIsStored() {
        val provider = EffectLayerBoundsProvider { _, layoutSize ->
            Rect(
                left = 0f,
                top = layoutSize.height / 2f,
                right = layoutSize.width.toFloat(),
                bottom = layoutSize.height.toFloat()
            )
        }

        val config = EffectLayerScope().apply {
            visualBounds(provider)
        }.toConfig()

        assertSame(provider, config.visualBoundsProvider)
    }
}
