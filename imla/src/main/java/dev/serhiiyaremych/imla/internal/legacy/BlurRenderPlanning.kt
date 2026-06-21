/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import androidx.compose.ui.geometry.Rect
import dev.serhiiyaremych.imla.internal.render.processing.effects.GaussianKernel
import kotlin.math.ceil
import kotlin.math.floor

internal data class BlurPlan(
    val sigmaTexels: Float,
    val kernelSampleCount: Int
)

internal object BlurRenderPlanning {
    internal fun rectsOverlap(first: Rect, second: Rect): Boolean {
        return first.left < second.right &&
            first.right > second.left &&
            first.top < second.bottom &&
            first.bottom > second.top
    }

    internal fun snapAreaToPixels(area: Rect): Rect {
        return Rect(
            left = floor(area.left),
            top = floor(area.top),
            right = ceil(area.right),
            bottom = ceil(area.bottom)
        )
    }

    internal fun computeBlurPlan(sigma: Float, downsampleScale: Float): BlurPlan {
        val sigmaTexels = sigma * downsampleScale
        val kernelSamples = GaussianKernel.getKernel(sigmaTexels)
        return BlurPlan(
            sigmaTexels = sigmaTexels,
            kernelSampleCount = kernelSamples.sampleCount
        )
    }
}
