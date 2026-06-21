/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import androidx.collection.FloatFloatPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderingPipelineMathTest {

    private companion object {
        private const val EPS = 1e-5f
    }

    @Test
    fun computeAabbRegion_cases() {
        data class Case(
            val name: String,
            val centerX: Float,
            val centerY: Float,
            val width: Float,
            val height: Float,
            val rotationDeg: Float,
            val targetWidth: Int,
            val targetHeight: Int,
            val expected: RegionAabb?
        )

        val cases = listOf(
            Case(
                name = "no rotation centered",
                centerX = 100f,
                centerY = 100f,
                width = 50f,
                height = 30f,
                rotationDeg = 0f,
                targetWidth = 200,
                targetHeight = 200,
                expected = RegionAabb(left = 75, top = 85, width = 50, height = 30)
            ),
            Case(
                name = "rotation 90 swaps width/height",
                centerX = 100f,
                centerY = 100f,
                width = 50f,
                height = 30f,
                rotationDeg = 90f,
                targetWidth = 200,
                targetHeight = 200,
                expected = RegionAabb(left = 85, top = 75, width = 30, height = 50)
            ),
            Case(
                name = "rotation 45 expands aabb",
                centerX = 100f,
                centerY = 100f,
                width = 50f,
                height = 50f,
                rotationDeg = 45f,
                targetWidth = 200,
                targetHeight = 200,
                expected = RegionAabb(left = 64, top = 64, width = 72, height = 72)
            ),
            Case(
                name = "rotation 180 matches no rotation",
                centerX = 100f,
                centerY = 100f,
                width = 50f,
                height = 30f,
                rotationDeg = 180f,
                targetWidth = 200,
                targetHeight = 200,
                expected = RegionAabb(left = 75, top = 85, width = 50, height = 30)
            ),
            Case(
                name = "rotation 270 swaps width/height",
                centerX = 100f,
                centerY = 100f,
                width = 50f,
                height = 30f,
                rotationDeg = 270f,
                targetWidth = 200,
                targetHeight = 200,
                expected = RegionAabb(left = 85, top = 75, width = 30, height = 50)
            ),
            Case(
                name = "clamped to target bounds",
                centerX = 100f,
                centerY = 100f,
                width = 300f,
                height = 200f,
                rotationDeg = 0f,
                targetWidth = 150,
                targetHeight = 150,
                expected = RegionAabb(left = 0, top = 0, width = 150, height = 150)
            ),
            Case(
                name = "too small returns null",
                centerX = 100f,
                centerY = 100f,
                width = 1f,
                height = 30f,
                rotationDeg = 0f,
                targetWidth = 200,
                targetHeight = 200,
                expected = null
            )
        )

        for (case in cases) {
            val result = computeAabbRegion(
                centerX = case.centerX,
                centerY = case.centerY,
                width = case.width,
                height = case.height,
                rotationDeg = case.rotationDeg,
                targetWidth = case.targetWidth,
                targetHeight = case.targetHeight
            )
            if (case.expected == null) {
                assertNull(case.name, result)
            } else {
                val expected = case.expected
                assertNotNull(case.name, result)
                assertEquals(case.name, expected.left, result?.left)
                assertEquals(case.name, expected.top, result?.top)
                assertEquals(case.name, expected.width, result?.width)
                assertEquals(case.name, expected.height, result?.height)
            }
        }
    }

    @Test
    fun computeUvBoundsFlippedYInt_cases() {
        data class Case(
            val name: String,
            val left: Int,
            val right: Int,
            val top: Int,
            val bottom: Int,
            val targetWidth: Int,
            val targetHeight: Int,
            val expected: UvBounds
        )

        val cases = listOf(
            Case(
                name = "simple region",
                left = 10,
                right = 30,
                top = 20,
                bottom = 50,
                targetWidth = 100,
                targetHeight = 200,
                expected = UvBounds(u0 = 0.1f, v0 = 0.75f, u1 = 0.3f, v1 = 0.9f)
            ),
            Case(
                name = "full screen",
                left = 0,
                right = 1920,
                top = 0,
                bottom = 1080,
                targetWidth = 1920,
                targetHeight = 1080,
                expected = UvBounds(u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f)
            )
        )

        for (case in cases) {
            val result = computeUvBoundsFlippedYInt(
                left = case.left,
                right = case.right,
                top = case.top,
                bottom = case.bottom,
                targetWidth = case.targetWidth,
                targetHeight = case.targetHeight
            )
            assertFloatEquals("${case.name} u0", case.expected.u0, result.u0, EPS)
            assertFloatEquals("${case.name} v0", case.expected.v0, result.v0, EPS)
            assertFloatEquals("${case.name} u1", case.expected.u1, result.u1, EPS)
            assertFloatEquals("${case.name} v1", case.expected.v1, result.v1, EPS)
        }
    }

    @Test
    fun computeUvBoundsFlippedYFloat_cases() {
        data class Case(
            val name: String,
            val left: Float,
            val right: Float,
            val top: Float,
            val bottom: Float,
            val targetWidth: Int,
            val targetHeight: Int,
            val expected: UvBounds
        )

        val cases = listOf(
            Case(
                name = "float bounds",
                left = 5.5f,
                right = 25.5f,
                top = 10.25f,
                bottom = 30.75f,
                targetWidth = 100,
                targetHeight = 100,
                expected = UvBounds(u0 = 0.055f, v0 = 0.6925f, u1 = 0.255f, v1 = 0.8975f)
            )
        )

        for (case in cases) {
            val result = computeUvBoundsFlippedYFloat(
                left = case.left,
                right = case.right,
                top = case.top,
                bottom = case.bottom,
                targetWidth = case.targetWidth,
                targetHeight = case.targetHeight
            )
            assertFloatEquals("${case.name} u0", case.expected.u0, result.u0, EPS)
            assertFloatEquals("${case.name} v0", case.expected.v0, result.v0, EPS)
            assertFloatEquals("${case.name} u1", case.expected.u1, result.u1, EPS)
            assertFloatEquals("${case.name} v1", case.expected.v1, result.v1, EPS)
        }
    }

    @Test
    fun computeUvBoundsNoFlipFloat_cases() {
        data class Case(
            val name: String,
            val left: Float,
            val right: Float,
            val top: Float,
            val bottom: Float,
            val targetWidth: Int,
            val targetHeight: Int,
            val expected: UvBounds
        )

        val cases = listOf(
            Case(
                name = "float bounds no flip",
                left = 5.5f,
                right = 25.5f,
                top = 10.25f,
                bottom = 30.75f,
                targetWidth = 100,
                targetHeight = 100,
                expected = UvBounds(u0 = 0.055f, v0 = 0.3075f, u1 = 0.255f, v1 = 0.1025f)
            )
        )

        for (case in cases) {
            val result = computeUvBoundsNoFlipFloat(
                left = case.left,
                right = case.right,
                top = case.top,
                bottom = case.bottom,
                targetWidth = case.targetWidth,
                targetHeight = case.targetHeight
            )
            assertFloatEquals("${case.name} u0", case.expected.u0, result.u0, EPS)
            assertFloatEquals("${case.name} v0", case.expected.v0, result.v0, EPS)
            assertFloatEquals("${case.name} u1", case.expected.u1, result.u1, EPS)
            assertFloatEquals("${case.name} v1", case.expected.v1, result.v1, EPS)
        }
    }

    @Test
    fun convertToGlYCoordinates_cases() {
        data class Case(
            val name: String,
            val totalHeight: Int,
            val top: Int,
            val bottom: Int,
            val expected: GlYCoordinates
        )

        val cases = listOf(
            Case(
                name = "full height",
                totalHeight = 1080,
                top = 0,
                bottom = 1080,
                expected = GlYCoordinates(glY0 = 0, glY1 = 1080)
            ),
            Case(
                name = "partial region",
                totalHeight = 1080,
                top = 100,
                bottom = 500,
                expected = GlYCoordinates(glY0 = 580, glY1 = 980)
            )
        )

        for (case in cases) {
            val result = convertToGlYCoordinates(
                totalHeight = case.totalHeight,
                top = case.top,
                bottom = case.bottom
            )
            assertEquals(case.name, case.expected.glY0, result.glY0)
            assertEquals(case.name, case.expected.glY1, result.glY1)
        }
    }

    @Test
    fun convertPointToLocalSpace_cases() {
        data class Case(
            val name: String,
            val pointX: Float,
            val pointY: Float,
            val regionLeft: Int,
            val regionTop: Int,
            val expected: LocalPoint
        )

        val cases = listOf(
            Case(
                name = "origin",
                pointX = 100f,
                pointY = 50f,
                regionLeft = 100,
                regionTop = 50,
                expected = FloatFloatPair(first = 0f, second = 0f)
            ),
            Case(
                name = "offset",
                pointX = 200f,
                pointY = 150f,
                regionLeft = 100,
                regionTop = 50,
                expected = FloatFloatPair(first = 100f, second = 100f)
            )
        )

        for (case in cases) {
            val result = convertPointToLocalSpace(
                pointX = case.pointX,
                pointY = case.pointY,
                regionLeft = case.regionLeft,
                regionTop = case.regionTop
            )
            assertFloatEquals("${case.name} x", case.expected.first, result.first, EPS)
            assertFloatEquals("${case.name} y", case.expected.second, result.second, EPS)
        }
    }

    @Test
    fun clampOpacity_cases() {
        data class Case(
            val name: String,
            val input: Float,
            val expected: Float
        )

        val cases = listOf(
            Case(name = "below low threshold", input = 0.05f, expected = 0.0f),
            Case(name = "at low threshold", input = 0.1f, expected = 0.1f),
            Case(name = "middle", input = 0.5f, expected = 0.5f),
            Case(name = "at high threshold", input = 0.95f, expected = 0.95f),
            Case(name = "above high threshold", input = 0.96f, expected = 1.0f)
        )

        for (case in cases) {
            val result = clampOpacity(case.input)
            assertFloatEquals(case.name, case.expected, result, EPS)
        }
    }

    private fun assertFloatEquals(label: String, expected: Float, actual: Float, tolerance: Float) {
        val delta = kotlin.math.abs(expected - actual)
        assertTrue("$label expected $expected +/- $tolerance but was $actual", delta <= tolerance)
    }
}
