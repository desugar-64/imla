/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.layer.model

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneBackdropEffectTest {
    @Test
    fun blurCarriesCompositeSeparatelyFromOperations() {
        val tint = Color(0xFFE0F7FA).copy(alpha = 0.26f)

        val effect = SceneBackdropEffect.blur(
            sigmaPx = 24f,
            composite = SceneBackdropComposite(tint = tint, noiseAlpha = 0.18f)
        )

        assertEquals(SceneBackdropComposite(tint = tint, noiseAlpha = 0.18f), effect.composite)
        assertEquals(1, effect.operations.size)
        assertTrue(effect.operations.single() is SceneBackdropOperation.Blur)
    }
}
