/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.effects

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

internal object GaussianKernel {
    const val MAX_KERNEL_SAMPLES = 50

    data class KernelSamples(
        val sampleCount: Int,
        val offsets: FloatArray,
        val weights: FloatArray
    )

    private const val MAX_INPUT_SAMPLES = MAX_KERNEL_SAMPLES * 2 - 1
    private const val SIGMA_QUANTIZATION = 100f
    private const val IDENTITY_SIGMA_THRESHOLD = 0.03f
    private const val SQRT_TWO_PI = 2.5066283f
    private const val CACHE_LIMIT = 64

    private val kernelCache = object : LinkedHashMap<Int, KernelSamples>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, KernelSamples>): Boolean {
            return size > CACHE_LIMIT
        }
    }

    fun clearCache() {
        kernelCache.clear()
    }

    fun getKernel(sigmaTexels: Float): KernelSamples {
        val clampedSigma = sigmaTexels.coerceAtLeast(0f)
        val key = (clampedSigma * SIGMA_QUANTIZATION).roundToInt()
        return kernelCache[key] ?: buildKernel(clampedSigma).also { kernelCache[key] = it }
    }

    private fun buildKernel(sigma: Float): KernelSamples {
        if (sigma <= IDENTITY_SIGMA_THRESHOLD) {
            return KernelSamples(
                sampleCount = 1,
                offsets = floatArrayOf(0f),
                weights = floatArrayOf(1f)
            )
        }

        val radius = calculateBlurRadius(sigma)
        var sampleCount = radius * 2 + 1
        var xOffset = 0

        if (radius >= 16) {
            sampleCount -= 2
            xOffset = 1
        }

        if (sampleCount > MAX_INPUT_SAMPLES) {
            val trim = sampleCount - MAX_INPUT_SAMPLES
            val trimLeft = trim / 2
            xOffset += trimLeft
            sampleCount = MAX_INPUT_SAMPLES
        }

        val offsets = FloatArray(sampleCount)
        val weights = FloatArray(sampleCount)
        var tally = 0f

        for (i in 0 until sampleCount) {
            val x = xOffset + i - radius
            val weight = gaussian(x.toFloat(), sigma)
            offsets[i] = x.toFloat()
            weights[i] = weight
            tally += weight
        }

        if (tally > 0f) {
            val inv = 1f / tally
            for (i in weights.indices) {
                weights[i] *= inv
            }
        }

        return lerpHack(offsets, weights)
    }

    private fun lerpHack(offsets: FloatArray, weights: FloatArray): KernelSamples {
        val inputCount = offsets.size
        val outputCount = ((inputCount - 1) / 2) + 1
        val outputOffsets = FloatArray(outputCount)
        val outputWeights = FloatArray(outputCount)
        val middle = outputCount / 2
        var j = 0

        for (i in 0 until outputCount) {
            if (i == middle) {
                outputOffsets[i] = offsets[j]
                outputWeights[i] = weights[j]
                j++
            } else {
                val leftOffset = offsets[j]
                val rightOffset = offsets[j + 1]
                val leftWeight = weights[j]
                val rightWeight = weights[j + 1]
                val combinedWeight = leftWeight + rightWeight
                val combinedOffset = if (combinedWeight > 0f) {
                    (leftOffset * leftWeight + rightOffset * rightWeight) / combinedWeight
                } else {
                    0f
                }

                outputOffsets[i] = combinedOffset
                outputWeights[i] = combinedWeight
                j += 2
            }
        }

        return KernelSamples(
            sampleCount = outputCount,
            offsets = outputOffsets,
            weights = outputWeights
        )
    }

    private fun calculateBlurRadius(sigma: Float): Int {
        if (sigma <= IDENTITY_SIGMA_THRESHOLD) return 0
        return ceil(3f * sigma).toInt()
    }

    private fun gaussian(x: Float, sigma: Float): Float {
        val variance = sigma * sigma
        return exp(-0.5f * x * x / variance) / (SQRT_TWO_PI * sigma)
    }
}
