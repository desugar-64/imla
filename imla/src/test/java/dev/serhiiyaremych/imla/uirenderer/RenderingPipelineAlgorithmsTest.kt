/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import androidx.compose.ui.geometry.Rect
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

class RenderingPipelineAlgorithmsTest {

    private companion object {
        private const val EPS = 1e-5f
    }

    // =========================================================================
    // rectsOverlap() tests
    // =========================================================================

    @Test
    fun rectsOverlap_noOverlap_horizontal() {
        val first = Rect(0f, 0f, 10f, 10f)
        val second = Rect(11f, 0f, 20f, 10f)
        assertFalse(BlurRenderPlanning.rectsOverlap(first, second))
    }

    @Test
    fun rectsOverlap_noOverlap_vertical() {
        val first = Rect(0f, 0f, 10f, 10f)
        val second = Rect(0f, 11f, 10f, 20f)
        assertFalse(BlurRenderPlanning.rectsOverlap(first, second))
    }

    @Test
    fun rectsOverlap_touching_notOverlapping() {
        val first = Rect(0f, 0f, 10f, 10f)
        val second = Rect(10f, 0f, 20f, 10f)
        assertFalse(BlurRenderPlanning.rectsOverlap(first, second))
    }

    @Test
    fun rectsOverlap_partial() {
        val first = Rect(0f, 0f, 10f, 10f)
        val second = Rect(5f, 5f, 15f, 15f)
        assertTrue(BlurRenderPlanning.rectsOverlap(first, second))
    }

    @Test
    fun rectsOverlap_contained() {
        val first = Rect(0f, 0f, 20f, 20f)
        val second = Rect(5f, 5f, 15f, 15f)
        assertTrue(BlurRenderPlanning.rectsOverlap(first, second))
    }

    @Test
    fun rectsOverlap_identical() {
        val first = Rect(0f, 0f, 10f, 10f)
        val second = Rect(0f, 0f, 10f, 10f)
        assertTrue(BlurRenderPlanning.rectsOverlap(first, second))
    }

    @Test
    fun rectsOverlap_cornerOverlap() {
        val first = Rect(0f, 0f, 10f, 10f)
        val second = Rect(9f, 9f, 20f, 20f)
        assertTrue(BlurRenderPlanning.rectsOverlap(first, second))
    }

    // =========================================================================
    // snapAreaToPixels() tests
    // =========================================================================

    @Test
    fun snapAreaToPixels_alreadyAligned() {
        val area = Rect(10f, 20f, 30f, 40f)
        val snapped = BlurRenderPlanning.snapAreaToPixels(area)
        assertEquals(10f, snapped.left, EPS)
        assertEquals(20f, snapped.top, EPS)
        assertEquals(30f, snapped.right, EPS)
        assertEquals(40f, snapped.bottom, EPS)
    }

    @Test
    fun snapAreaToPixels_needsFlooring() {
        val area = Rect(10.7f, 20.3f, 30.9f, 40.1f)
        val snapped = BlurRenderPlanning.snapAreaToPixels(area)
        assertEquals(floor(10.7f), snapped.left, EPS)
        assertEquals(floor(20.3f), snapped.top, EPS)
        assertEquals(ceil(30.9f), snapped.right, EPS)
        assertEquals(ceil(40.1f), snapped.bottom, EPS)
    }

    @Test
    fun snapAreaToPixels_subPixel() {
        val area = Rect(10.1f, 10.1f, 10.9f, 10.9f)
        val snapped = BlurRenderPlanning.snapAreaToPixels(area)
        assertEquals(10f, snapped.left, EPS)
        assertEquals(10f, snapped.top, EPS)
        assertEquals(11f, snapped.right, EPS)
        assertEquals(11f, snapped.bottom, EPS)
    }

    @Test
    fun snapAreaToPixels_negative() {
        val area = Rect(-10.5f, -5.5f, 10.5f, 5.5f)
        val snapped = BlurRenderPlanning.snapAreaToPixels(area)
        assertEquals(floor(-10.5f), snapped.left, EPS)
        assertEquals(floor(-5.5f), snapped.top, EPS)
        assertEquals(ceil(10.5f), snapped.right, EPS)
        assertEquals(ceil(5.5f), snapped.bottom, EPS)
    }

    // =========================================================================
    // computeBlurPlan() tests
    // =========================================================================

    @Test
    fun computeBlurPlan_smallSigma() {
        val plan = BlurRenderPlanning.computeBlurPlan(sigma = 1.0f, downsampleScale = 1.0f)
        assertEquals(1.0f, plan.sigmaTexels, EPS)
        assertTrue("Gaussian kernel blur must provide samples", plan.kernelSampleCount > 0)
    }

    @Test
    fun computeBlurPlan_largeSigma() {
        val plan = BlurRenderPlanning.computeBlurPlan(sigma = 10.0f, downsampleScale = 1.0f)
        assertEquals(10.0f, plan.sigmaTexels, EPS)
        assertTrue("Gaussian kernel blur must provide samples", plan.kernelSampleCount > 0)
    }

    @Test
    fun computeBlurPlan_withDownsample() {
        val plan = BlurRenderPlanning.computeBlurPlan(sigma = 5.0f, downsampleScale = 0.5f)
        assertEquals(2.5f, plan.sigmaTexels, EPS)
    }

    @Test
    fun computeBlurPlan_zeroSigma() {
        val plan = BlurRenderPlanning.computeBlurPlan(sigma = 0f, downsampleScale = 1.0f)
        assertEquals(0f, plan.sigmaTexels, EPS)
        assertTrue("Gaussian kernel blur must provide samples", plan.kernelSampleCount > 0)
    }

    @Test
    fun algorithmTestsNoLongerReferenceLegacyPipelineOwner() {
        val source = sourceFile(
            "imla/src/test/java/dev/serhiiyaremych/imla/uirenderer/RenderingPipelineAlgorithmsTest.kt"
        )
        val body = source.substringAfter("fun algorithmTestsNoLongerReferenceLegacyPipelineOwner()")
        assertFalse(
            "Algorithm tests must stay attached to the scene-neutral owner, not CopyLessRenderingPipeline.",
            source.substringBefore(body).contains("CopyLessRenderingPipeline")
        )
    }

    private fun sourceFile(relativePath: String): String {
        val path = Paths.get(relativePath)
        val cwd = Paths.get("").toAbsolutePath()
        val sourcePath = listOfNotNull(
            cwd.resolve(path),
            cwd.parent?.resolve(path)
        ).firstOrNull { candidate -> Files.exists(candidate) } ?: path

        return String(Files.readAllBytes(sourcePath), Charsets.UTF_8)
    }
}
