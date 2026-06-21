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

class AtlasBenchmarkSceneTest {
    @Test
    fun defaultAppPathDoesNotEnableAtlasBenchmarkScene() {
        assertFalse(isAtlasBenchmarkSceneSwitchEnabled(diagnosticBuild = false) { true })
        assertFalse(isAtlasBenchmarkSceneSwitchEnabled(diagnosticBuild = true) { false })

        val mainSource = source("app/src/main/java/dev/serhiiyaremych/imla/MainActivity.kt")
        assertTrue("val atlasBenchmarkSceneEnabled = isAtlasBenchmarkSceneEnabled()" in mainSource)
        assertTrue("if (!atlasBenchmarkSceneEnabled && selectedNavIndex.intValue == 2)" in mainSource)
        val switchIndex = mainSource.indexOf("if (atlasBenchmarkSceneEnabled)")
        val sceneIndex = if (switchIndex >= 0) {
            mainSource.indexOf("AtlasBenchmarkScene(", startIndex = switchIndex)
        } else {
            -1
        }
        assertTrue(switchIndex >= 0 && sceneIndex > switchIndex)
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

        val sceneSource = source("app/src/main/java/dev/serhiiyaremych/imla/AtlasBenchmarkScene.kt")
        val slotBody = sceneSource.functionBody("AtlasBenchmarkSlot")
        assertFalse("Benchmark slots must not set blur masks.", "blurMask =" in slotBody)
        assertFalse("Benchmark slots must not set clip shapes.", "clipShape =" in slotBody)
    }

    @Test
    fun atlasBenchmarkBackdropHasStaticDetailToMakeBlurVisible() {
        val sceneSource = source("app/src/main/java/dev/serhiiyaremych/imla/AtlasBenchmarkScene.kt")
        val backdropBody = sceneSource.functionBody("AtlasBenchmarkBackdrop")

        assertTrue("Backdrop must use a deterministic canvas scene.", "Canvas(" in backdropBody)
        assertTrue("Backdrop must include grid edges for visible blur.", "drawLine(" in backdropBody)
        assertTrue("Backdrop must include local detail under slots.", "drawCircle(" in backdropBody)
    }

    @Test
    fun atlasBenchmarkSceneStaysInternalAndHasNoManifestRoute() {
        val sceneSource = source("app/src/main/java/dev/serhiiyaremych/imla/AtlasBenchmarkScene.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue("internal fun AtlasBenchmarkScene(" in sceneSource)
        assertTrue("internal val AtlasBenchmarkSlotSpecs" in sceneSource)
        assertFalse("public fun AtlasBenchmarkScene" in sceneSource)
        assertFalse("public val AtlasBenchmarkSlotSpecs" in sceneSource)
        assertFalse(ATLAS_BENCHMARK_SCENE_TAG in manifest)
    }

    private fun source(relativePath: String): String {
        return projectRoot().resolve(relativePath).readText()
    }

    private fun String.functionBody(name: String): String {
        val functionStart = indexOf("fun $name")
        require(functionStart >= 0) { "Could not find $name" }

        val bodyStart = indexOf('{', functionStart)
        require(bodyStart >= 0) { "Could not find $name body" }

        var depth = 0
        for (index in bodyStart until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return substring(bodyStart, index + 1)
                }
            }
        }
        error("Could not find $name body end")
    }

    private fun projectRoot(): File {
        return generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
    }
}
