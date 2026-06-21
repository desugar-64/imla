/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import androidx.collection.FloatFloatPair
import androidx.compose.ui.geometry.Rect
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.scale
import dev.romainguy.kotlin.math.translation
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val DEG_TO_RAD = (Math.PI.toFloat() / 180f)
private const val OPACITY_LOW_THRESHOLD = 0.1f
private const val OPACITY_HIGH_THRESHOLD = 0.95f

internal data class UvBounds(
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float
)

internal data class RegionAabb(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    val right: Int = left + width
    val bottom: Int = top + height
}

internal data class GlYCoordinates(
    val glY0: Int,
    val glY1: Int
)

internal typealias LocalPoint = FloatFloatPair

internal fun computeAabbRegion(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    rotationDeg: Float,
    targetWidth: Int,
    targetHeight: Int
): RegionAabb? {
    if (width <= 1f || height <= 1f) return null

    val aabbWidth: Float
    val aabbHeight: Float
    if (rotationDeg == 0f || rotationDeg == 180f || rotationDeg == -180f || rotationDeg == 360f || rotationDeg == -360f) {
        aabbWidth = width
        aabbHeight = height
    } else if (
        rotationDeg == 90f || rotationDeg == -90f ||
        rotationDeg == 270f || rotationDeg == -270f
    ) {
        aabbWidth = height
        aabbHeight = width
    } else {
        val rad = rotationDeg * DEG_TO_RAD
        val c = abs(cos(rad))
        val s = abs(sin(rad))
        aabbWidth = (width * c) + (height * s)
        aabbHeight = (width * s) + (height * c)
    }

    val halfWidth = aabbWidth / 2f
    val halfHeight = aabbHeight / 2f
    val leftF = centerX - halfWidth
    val rightF = centerX + halfWidth
    val topF = centerY - halfHeight
    val bottomF = centerY + halfHeight

    val left = floor(leftF).toInt().coerceAtLeast(0)
    val top = floor(topF).toInt().coerceAtLeast(0)
    val right = ceil(rightF).toInt().coerceAtMost(targetWidth)
    val bottom = ceil(bottomF).toInt().coerceAtMost(targetHeight)

    val regionWidth = (right - left).coerceAtLeast(1)
    val regionHeight = (bottom - top).coerceAtLeast(1)
    if (regionWidth <= 1 || regionHeight <= 1) return null

    return RegionAabb(
        left = left,
        top = top,
        width = regionWidth,
        height = regionHeight
    )
}

internal fun computeUvBoundsFlippedYInt(
    left: Int,
    right: Int,
    top: Int,
    bottom: Int,
    targetWidth: Int,
    targetHeight: Int
): UvBounds {
    return computeUvBoundsFlippedYFloat(
        left = left.toFloat(),
        right = right.toFloat(),
        top = top.toFloat(),
        bottom = bottom.toFloat(),
        targetWidth = targetWidth,
        targetHeight = targetHeight
    )
}

internal fun computeUvBoundsFlippedYFloat(
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    targetWidth: Int,
    targetHeight: Int
): UvBounds {
    val targetWidthF = targetWidth.toFloat()
    val targetHeightF = targetHeight.toFloat()
    val invWidth = 1f / targetWidthF
    val invHeight = 1f / targetHeightF
    val u0 = left * invWidth
    val u1 = right * invWidth
    val v0 = (targetHeightF - bottom) * invHeight
    val v1 = (targetHeightF - top) * invHeight
    return UvBounds(u0 = u0, v0 = v0, u1 = u1, v1 = v1)
}

internal fun computeUvBoundsNoFlipFloat(
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    targetWidth: Int,
    targetHeight: Int
): UvBounds {
    val targetWidthF = targetWidth.toFloat()
    val targetHeightF = targetHeight.toFloat()
    val invWidth = 1f / targetWidthF
    val invHeight = 1f / targetHeightF
    val u0 = left * invWidth
    val u1 = right * invWidth
    val v0 = bottom * invHeight
    val v1 = top * invHeight
    return UvBounds(u0 = u0, v0 = v0, u1 = u1, v1 = v1)
}

internal fun convertToGlYCoordinates(
    totalHeight: Int,
    top: Int,
    bottom: Int
): GlYCoordinates {
    val glY0 = totalHeight - bottom
    val glY1 = totalHeight - top
    return GlYCoordinates(glY0 = glY0, glY1 = glY1)
}

internal fun convertPointToLocalSpace(
    pointX: Float,
    pointY: Float,
    regionLeft: Int,
    regionTop: Int
): LocalPoint {
    return FloatFloatPair(
        first = pointX - regionLeft.toFloat(),
        second = pointY - regionTop.toFloat()
    )
}

internal fun clampOpacity(value: Float): Float {
    return when {
        value < OPACITY_LOW_THRESHOLD -> 0.0f
        value > OPACITY_HIGH_THRESHOLD -> 1.0f
        else -> value
    }
}

/**
 * Convert a Compose row-major 4x4 to a Mat4 suitable for GL column-vector usage.
 *
 * Compose encodes a 2D homography in a 4x4 matrix; translation lives in row 3,
 * and perspective terms live in row 0/1, column 3. We remap those into the
 * Mat4 column-major layout expected by the renderer.
 */
internal fun composeMatrixToMat4(values: FloatArray): Mat4 {
    if (values.size < 16) return Mat4.identity()
    val a = values[0]
    val b = values[4]
    val c = values[12]
    val d = values[1]
    val e = values[5]
    val f = values[13]
    val g = values[3]
    val h = values[7]
    val i = values[15]
    return Mat4.of(
        a, b, 0f, c,
        d, e, 0f, f,
        0f, 0f, 1f, 0f,
        g, h, 0f, i
    )
}

/** Build a transform that places a unit quad at the local rect center with its size. */
internal fun localRectToTransform(localRect: Rect): Mat4 {
    val center = Float3(
        x = localRect.left + localRect.width / 2f,
        y = localRect.top + localRect.height / 2f,
        z = 0f
    )
    return translation(center) * scale(
        Float3(
            localRect.width.coerceAtLeast(0f),
            localRect.height.coerceAtLeast(0f),
            1f
        )
    )
}

/**
 * Compose world transform applied to a local rect quad.
 * The quad is defined in local coordinates and then moved into root space.
 */
internal fun buildRenderTransform(transformValues: FloatArray, localRect: Rect): Mat4 {
    return composeMatrixToMat4(transformValues) * localRectToTransform(localRect)
}

/** Shift an existing transform so it renders correctly inside a cropped region. */
internal fun offsetTransformForRegion(transform: Mat4, regionLeft: Float, regionTop: Float): Mat4 {
    return translation(Float3(-regionLeft, -regionTop, 0f)) * transform
}

/**
 * Estimate how front-facing a quad is based on perspective variation in the transform.
 *
 * Returns 1.0 for front-facing (no perspective distortion) and approaches 0.0 for grazing angles.
 */
internal fun computeFacingRatio(transform: Mat4): Float {
    fun wAt(x: Float, y: Float): Float {
        val w = transform[0, 3] * x + transform[1, 3] * y + transform[3, 3]
        return abs(w)
    }

    val w0 = wAt(-0.5f, -0.5f)
    val w1 = wAt(0.5f, -0.5f)
    val w2 = wAt(0.5f, 0.5f)
    val w3 = wAt(-0.5f, 0.5f)

    val minW = minOf(w0, w1, w2, w3)
    val maxW = maxOf(w0, w1, w2, w3)
    if (maxW <= 0f) return 1f
    return (minW / maxW).coerceIn(0f, 1f)
}
