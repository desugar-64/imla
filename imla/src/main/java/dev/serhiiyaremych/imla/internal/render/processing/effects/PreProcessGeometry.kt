/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.effects

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import dev.serhiiyaremych.imla.internal.render.Float2
import dev.serhiiyaremych.imla.internal.render.util.roundToMultipleOf
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

internal data class PreProcessGeometryRequest(
    val sampleArea: Rect,
    val contentSize: IntSize,
    val extendedArea: Rect,
    val sourceContentSize: IntSize,
    val referenceSize: IntSize,
    val sigmaDownsampleScale: Float,
    val targetContentSize: IntSize,
    val blurScale: Float,
    val noiseScale: Float2
) {
    fun withContentOffsets(
        sourceContentOffset: IntOffset,
        targetContentOffset: IntOffset
    ): PreProcessGeometryPlan {
        val fitScale = calculateFitScale(
            inner = sourceContentSize,
            outer = targetContentSize
        )
        val scaledExtendedWidth = sourceContentSize.width * fitScale
        val scaledExtendedHeight = sourceContentSize.height * fitScale
        val centeredLeft = targetContentOffset.x + (targetContentSize.width - scaledExtendedWidth) / 2f
        val centeredTop = targetContentOffset.y + (targetContentSize.height - scaledExtendedHeight) / 2f
        val rotationPadX = ((sampleArea.width - contentSize.width.toFloat()).coerceAtLeast(0f)) / 2f
        val rotationPadY = ((sampleArea.height - contentSize.height.toFloat()).coerceAtLeast(0f)) / 2f
        val contentLeft = centeredLeft + ((PRE_PROCESS_PADDING + rotationPadX) * fitScale)
        val contentTop = centeredTop + ((PRE_PROCESS_PADDING + rotationPadY) * fitScale)
        val scaledContentWidth = contentSize.width.toFloat() * fitScale
        val scaledContentHeight = contentSize.height.toFloat() * fitScale
        val sampleLeft = centeredLeft + (PRE_PROCESS_PADDING * fitScale)
        val sampleTop = centeredTop + (PRE_PROCESS_PADDING * fitScale)
        val scaledSampleWidth = sampleArea.width * fitScale
        val scaledSampleHeight = sampleArea.height * fitScale
        val sourceSampleLeft = sourceContentOffset.x.toFloat() + PRE_PROCESS_PADDING
        val sourceSampleTop = sourceContentOffset.y.toFloat() + PRE_PROCESS_PADDING

        return PreProcessGeometryPlan(
            sourceSampleCrop = Rect(
                left = sourceSampleLeft,
                top = sourceSampleTop,
                right = sourceSampleLeft + sampleArea.width,
                bottom = sourceSampleTop + sampleArea.height
            ),
            sampleCrop = Rect(
                left = sampleLeft,
                top = sampleTop,
                right = sampleLeft + scaledSampleWidth,
                bottom = sampleTop + scaledSampleHeight
            ),
            contentCrop = Rect(
                left = contentLeft,
                top = contentTop,
                right = contentLeft + scaledContentWidth,
                bottom = contentTop + scaledContentHeight
            ),
            fitScale = fitScale
        )
    }
}

internal data class PreProcessGeometryPlan(
    val sourceSampleCrop: Rect,
    val sampleCrop: Rect,
    val contentCrop: Rect,
    val fitScale: Float
)

internal fun planPreProcessGeometry(
    rootSize: IntSize,
    sampleArea: Rect,
    contentSize: IntSize,
    sigma: Float
): PreProcessGeometryRequest {
    val srcWidth = rootSize.width.toFloat()
    val srcHeight = rootSize.height.toFloat()
    val extendedArea = Rect(
        left = sampleArea.left - PRE_PROCESS_PADDING,
        top = sampleArea.top - PRE_PROCESS_PADDING,
        right = (sampleArea.right + PRE_PROCESS_PADDING).coerceAtMost(srcWidth),
        bottom = (sampleArea.bottom + PRE_PROCESS_PADDING).coerceAtMost(srcHeight)
    )
    val sourceContentSize = extendedArea.size.toIntSize()
    val sigmaDownsampleScale = calculatePreProcessDownsampleScale(sigma)
    val referenceSize = IntSize(
        width = contentSize.width + (PRE_PROCESS_PADDING.toInt() * 2),
        height = contentSize.height + (PRE_PROCESS_PADDING.toInt() * 2)
    )
    val targetContentSize = (referenceSize.toSize() * sigmaDownsampleScale).toIntSize()
        .roundToMultipleOf(4)
    val blurScaleX = referenceSize.width.toFloat() / sourceContentSize.width.toFloat()
    val blurScaleY = referenceSize.height.toFloat() / sourceContentSize.height.toFloat()
    val safeContentWidth = contentSize.width.coerceAtLeast(1).toFloat()
    val safeContentHeight = contentSize.height.coerceAtLeast(1).toFloat()

    return PreProcessGeometryRequest(
        sampleArea = sampleArea,
        contentSize = contentSize,
        extendedArea = extendedArea,
        sourceContentSize = sourceContentSize,
        referenceSize = referenceSize,
        sigmaDownsampleScale = sigmaDownsampleScale,
        targetContentSize = targetContentSize,
        blurScale = kotlin.math.sqrt(blurScaleX * blurScaleY),
        noiseScale = Float2(
            sourceContentSize.width.toFloat() / safeContentWidth,
            sourceContentSize.height.toFloat() / safeContentHeight
        )
    )
}

internal const val PRE_PROCESS_PADDING: Float = 20f

private const val IDENTITY_SIGMA_THRESHOLD = 0.03f
private const val EIGHTH_DOWNSAMPLE_KERNEL_WIDTH_MAX = 41

private fun calculateFitScale(
    inner: IntSize,
    outer: IntSize
): Float {
    val safeInnerWidth = inner.width.coerceAtLeast(1)
    val safeInnerHeight = inner.height.coerceAtLeast(1)
    val widthScale = outer.width / safeInnerWidth.toFloat()
    val heightScale = outer.height / safeInnerHeight.toFloat()
    return minOf(widthScale, heightScale).coerceAtMost(1f)
}

private fun calculatePreProcessDownsampleScale(sigma: Float): Float {
    if (sigma <= 4f) return 1.0f

    val rawResult = 4.0f / sigma
    val exponent = round(log2(rawResult)).coerceAtLeast(-4f)
    val rounded = 2f.pow(exponent)
    var result = rounded

    if (rounded < 0.125f) {
        val roundedPlus = 2f.pow(exponent + 1f)
        val blurRadius = calculateBlurRadius(sigma)
        val kernelSizePlus = (scaleBlurRadius(blurRadius, roundedPlus) * 2) + 1
        if (kernelSizePlus <= EIGHTH_DOWNSAMPLE_KERNEL_WIDTH_MAX) {
            result = roundedPlus
        }
    }

    return result
}

private fun calculateBlurRadius(sigma: Float): Int {
    if (sigma <= IDENTITY_SIGMA_THRESHOLD) return 0
    return ceil(3f * sigma).toInt()
}

private fun scaleBlurRadius(radius: Int, scale: Float): Int {
    return round(radius * scale).toInt()
}
