/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureImportParityDiagnosticSceneTest {
    @Test
    fun captureImportParitySceneCoversRequiredVisualCases() {
        val coveredCases = CaptureImportParitySlots.flatMap { spec -> spec.cases }.toSet()

        assertEquals(CaptureImportParityCase.entries.toSet(), coveredCases)
        assertTrue(CaptureImportParitySlots.any { spec -> spec.debugName == "capture-parity-root-plate" })
        assertTrue(CaptureImportParitySlots.any { spec -> spec.debugName == "capture-parity-blur-mask" })
        assertTrue(CaptureImportParitySlots.any { spec -> spec.debugName == "capture-parity-clip-mask" })
        assertTrue(CaptureImportParitySlots.any { spec -> spec.debugName == "capture-parity-moving-crop" })
        assertTrue(CaptureImportParitySlots.any { spec -> spec.debugName == "capture-parity-rotated-foreground" })
        assertTrue(CaptureImportParitySlots.any { spec -> spec.debugName == "capture-parity-transparent-edge" })

        CaptureImportParitySlots.forEach { spec ->
            val style = spec.style
            assertTrue("${spec.debugName} must have positive finite sigma", style.sigma.isFinite() && style.sigma > 0f)
            assertEquals(DemoBlurAlgorithm.GAUSSIAN, style.algorithm)
            assertEquals(0f, style.noiseAlpha, 0f)
            assertEquals(1f, style.blurOpacity, 0f)
        }
    }

    @Test
    fun captureImportParitySceneStaysInternalAndManuallyGated() {
        val sceneSource = source("app/src/main/java/dev/serhiiyaremych/imla/CaptureImportParityDiagnosticScene.kt")
        val mainSource = source("app/src/main/java/dev/serhiiyaremych/imla/MainActivity.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue("internal fun CaptureImportParityDiagnosticScene(" in sceneSource)
        assertTrue("internal val CaptureImportParitySlots" in sceneSource)
        assertTrue("val captureImportParitySceneEnabled = isCaptureImportParitySceneEnabled()" in mainSource)
        assertTrue("Log.isLoggable(CAPTURE_IMPORT_PARITY_SCENE_TAG, Log.DEBUG)" in mainSource)
        assertFalse("public fun CaptureImportParityDiagnosticScene" in sceneSource)
        assertFalse(CAPTURE_IMPORT_PARITY_SCENE_TAG in manifest)
    }

    @Test
    fun captureImportParitySceneIncludesMotionAndOriginGuards() {
        val sceneSource = source("app/src/main/java/dev/serhiiyaremych/imla/CaptureImportParityDiagnosticScene.kt")

        assertTrue("same-size-visible-area-offset" in sceneSource)
        assertTrue("EffectLayerBoundsProvider" in sceneSource)
        assertTrue("Brush.radialGradient" in sceneSource)
        assertTrue("TransformOrigin.Center" in sceneSource)
        assertTrue("Modifier.clip(clipShape)" in sceneSource)
        assertTrue("Color.Transparent" in sceneSource)
    }

    private fun source(relativePath: String): String {
        return projectRoot().resolve(relativePath).readText()
    }

    private fun projectRoot(): File {
        return generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
    }
}
