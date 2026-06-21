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

class ClipAtlasDiagnosticSceneTest {
    @Test
    fun clipAtlasDiagnosticSceneCoversRequiredVisualCases() {
        assertEquals(4, ClipAtlasSlotSpecs.size)
        assertTrue(ClipAtlasSlotSpecs.any { spec -> spec.debugName == "clip-atlas-rounded" })
        assertTrue(ClipAtlasSlotSpecs.any { spec -> spec.debugName == "clip-atlas-rotated" && spec.rotationZ != 0f })
        assertTrue(ClipAtlasSlotSpecs.any { spec -> spec.debugName == "clip-atlas-foreground-edge" && spec.showCrossingForeground })
        assertEquals("clip-atlas-later-unclipped", ClipAtlasSlotSpecs.last().debugName)

        ClipAtlasSlotSpecs.forEach { spec ->
            val style = spec.style
            assertTrue("${spec.debugName} must have positive finite sigma", style.sigma.isFinite() && style.sigma > 0f)
            assertEquals(DemoBlurAlgorithm.GAUSSIAN, style.algorithm)
            assertEquals(0f, style.noiseAlpha, 0f)
            assertEquals(1f, style.blurOpacity, 0f)
        }
    }

    @Test
    fun clipAtlasDiagnosticSceneStaysInternalAndManuallyGated() {
        val sceneSource = source("app/src/main/java/dev/serhiiyaremych/imla/ClipAtlasDiagnosticScene.kt")
        val mainSource = source("app/src/main/java/dev/serhiiyaremych/imla/MainActivity.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue("internal fun ClipAtlasDiagnosticScene(" in sceneSource)
        assertTrue("internal val ClipAtlasSlotSpecs" in sceneSource)
        assertTrue("val clipAtlasDiagnosticSceneEnabled = isClipAtlasDiagnosticSceneEnabled()" in mainSource)
        assertTrue("Log.isLoggable(CLIP_ATLAS_DIAGNOSTIC_SCENE_TAG, Log.DEBUG)" in mainSource)
        assertFalse("public fun ClipAtlasDiagnosticScene" in sceneSource)
        assertFalse(CLIP_ATLAS_DIAGNOSTIC_SCENE_TAG in manifest)
    }

    private fun source(relativePath: String): String {
        return projectRoot().resolve(relativePath).readText()
    }

    private fun projectRoot(): File {
        return generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
    }
}
