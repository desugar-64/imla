/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render

import androidx.compose.ui.geometry.Rect
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.scale
import dev.romainguy.kotlin.math.translation

internal data class UvBounds(
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float
)

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
private fun localRectToTransform(localRect: Rect): Mat4 {
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
