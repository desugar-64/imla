/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasBenchmarkSceneTest {
    @Test
    fun defaultAppPathDoesNotEnableAtlasBenchmarkScene() {
        assertFalse(isAtlasBenchmarkSceneSwitchEnabled(diagnosticBuild = false) { true })
        assertFalse(isAtlasBenchmarkSceneSwitchEnabled(diagnosticBuild = true) { false })
    }

    @Test
    fun diagnosticBuildManualSwitchEnablesAtlasBenchmarkScene() {
        assertTrue(isAtlasBenchmarkSceneSwitchEnabled(diagnosticBuild = true) { true })
    }

    @Test
    fun atlasBenchmarkSlotsUsePlainEligibleStyles() {
        assertEquals(6, AtlasBenchmarkSlotSpecs.size)
        assertEquals(
            mapOf(6f to 2, 8f to 2, 10f to 2),
            AtlasBenchmarkSlotSpecs.groupingBy { spec -> spec.sigma }.eachCount()
        )

        AtlasBenchmarkSlotSpecs.forEach { spec ->
            val style = spec.style
            assertTrue("${spec.debugName} must have positive finite sigma", style.sigma.isFinite() && style.sigma > 0f)
            assertEquals(DemoBlurAlgorithm.GAUSSIAN, style.algorithm)
            assertEquals(0f, style.noiseAlpha, 0f)
            assertEquals(1f, style.blurOpacity, 0f)
            assertTrue("${spec.debugName} tint must leave blur visible", style.tint.alpha <= 0.2f)
            assertTrue("${spec.debugName} fill must not cover blur", spec.fill.alpha <= 0.1f)
        }
    }
}
