/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageMaskAtlasDiagnosticSceneTest {
    @Test
    fun coverageMaskAtlasSceneCoversSimplePaddedAndRotatedSlots() {
        val sceneSource = source("app/src/main/java/dev/serhiiyaremych/imla/CoverageMaskAtlasDiagnosticScene.kt")

        listOf(
            "coverage-mask-atlas-simple",
            "coverage-mask-atlas-padded",
            "coverage-mask-atlas-rotated"
        ).forEach { debugName ->
            assertTrue("Diagnostic scene must include $debugName", "debugName = \"$debugName\"" in sceneSource)
        }
        assertTrue("Padded slot must use expanded sample bounds", "visibleAreaProvider = paddedEffectLayerBoundsProvider" in sceneSource)
        assertTrue("Rotated slot must cover transformed quad-local masks", "rotationZ = -16f" in sceneSource)
        assertTrue("Coverage diagnostics must use supported atlas blur", "algorithm = DemoBlurAlgorithm.GAUSSIAN" in sceneSource)
    }

    @Test
    fun coverageMaskAtlasSceneStaysInternalAndManuallyGated() {
        val sceneSource = source("app/src/main/java/dev/serhiiyaremych/imla/CoverageMaskAtlasDiagnosticScene.kt")
        val mainSource = source("app/src/main/java/dev/serhiiyaremych/imla/MainActivity.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue("internal fun CoverageMaskAtlasDiagnosticScene(" in sceneSource)
        assertTrue("val coverageMaskAtlasSceneEnabled = isCoverageMaskAtlasSceneEnabled()" in mainSource)
        assertTrue("Log.isLoggable(COVERAGE_MASK_ATLAS_DIAGNOSTIC_SCENE_TAG, Log.DEBUG)" in mainSource)
        assertFalse("public fun CoverageMaskAtlasDiagnosticScene" in sceneSource)
        assertFalse(COVERAGE_MASK_ATLAS_DIAGNOSTIC_SCENE_TAG in manifest)
    }

    private fun source(relativePath: String): String {
        return projectRoot().resolve(relativePath).readText()
    }

    private fun projectRoot(): File {
        return generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
    }
}
