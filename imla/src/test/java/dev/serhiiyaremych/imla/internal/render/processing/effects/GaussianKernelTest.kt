/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GaussianKernelTest {

    @Before
    fun clearKernelCache() {
        GaussianKernel.clearCache()
    }

    @Test
    fun representativeSigmas_keepPackedWeightsNormalized() {
        val sigmas = listOf(0f, 0.031f, 0.5f, 1f, 1.1f, 5f, 5.34f, 16.5f, 40f)

        for (sigma in sigmas) {
            val kernel = GaussianKernel.getKernel(sigma)

            assertFloatEquals("sigma=$sigma", 1f, kernel.totalWeight())
        }
    }

    @Test
    fun oddAndEvenRadiusSigmas_keepEffectiveSampleCenterAtZero() {
        val sigmas = listOf(0.5f, 1f, 1.1f, 5f, 5.34f, 16.5f, 40f)

        for (sigma in sigmas) {
            val kernel = GaussianKernel.getKernel(sigma)

            assertFloatEquals("sigma=$sigma", 0f, kernel.effectiveOffset())
        }
    }

    @Test
    fun packedKernel_doesNotRequireLiteralZeroOffsetToRemainCentered() {
        val kernel = GaussianKernel.getKernel(1f)

        assertTrue(kernel.offsets.none { it == 0f })
        assertFloatEquals(0f, kernel.effectiveOffset())
        assertFloatEquals(1f, kernel.totalWeight())
    }

    @Test
    fun sigmaQuantization_reusesCachedKernelWithinSameRoundedBucket() {
        val first = GaussianKernel.getKernel(1.001f)
        val sameBucket = GaussianKernel.getKernel(1.004f)
        val nextBucket = GaussianKernel.getKernel(1.006f)

        assertSame(first, sameBucket)
        assertNotSame(first, nextBucket)
    }

    @Test
    fun cacheClear_discardsQuantizedKernelInstances() {
        val first = GaussianKernel.getKernel(1.001f)

        GaussianKernel.clearCache()
        val afterClear = GaussianKernel.getKernel(1.001f)

        assertNotSame(first, afterClear)
    }

    @Test
    fun largeSigma_clampsPackedSamplesToShaderLimitAndKeepsCenteredWeight() {
        val kernels = listOf(
            GaussianKernel.getKernel(16.5f),
            GaussianKernel.getKernel(40f)
        )

        for (kernel in kernels) {
            assertEquals(GaussianKernel.MAX_KERNEL_SAMPLES, kernel.sampleCount)
            assertFloatEquals(1f, kernel.totalWeight())
            assertFloatEquals(0f, kernel.effectiveOffset())
        }
    }

    private fun GaussianKernel.KernelSamples.totalWeight(): Float {
        var total = 0f
        for (weight in weights) {
            total += weight
        }
        return total
    }

    private fun GaussianKernel.KernelSamples.effectiveOffset(): Float {
        var offset = 0f
        for (i in 0 until sampleCount) {
            offset += offsets[i] * weights[i]
        }
        return offset
    }

    private fun assertFloatEquals(expected: Float, actual: Float) {
        assertEquals(expected, actual, EPS)
    }

    private fun assertFloatEquals(message: String, expected: Float, actual: Float) {
        assertEquals(message, expected, actual, EPS)
    }

    private companion object {
        private const val EPS = 1e-5f
    }
}
