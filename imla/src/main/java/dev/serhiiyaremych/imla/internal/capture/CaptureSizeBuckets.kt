/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.capture

import androidx.compose.ui.unit.IntSize

internal object CaptureSizeBuckets {
    private const val DEFAULT_BUCKET_SIZE_PX = 32

    fun bucket(size: IntSize, bucketSizePx: Int = DEFAULT_BUCKET_SIZE_PX): IntSize {
        if (size == IntSize.Zero) return size
        val widthStep = resolveBucketSize(size.width, bucketSizePx)
        val heightStep = resolveBucketSize(size.height, bucketSizePx)
        return IntSize(
            width = roundUpToMultiple(size.width, widthStep),
            height = roundUpToMultiple(size.height, heightStep)
        )
    }

    private fun resolveBucketSize(sizePx: Int, bucketSizePx: Int): Int {
        if (bucketSizePx != DEFAULT_BUCKET_SIZE_PX) return bucketSizePx
        // Coarser steps for larger content so a resize ramp reallocates a handful of times
        // instead of once per 32px (a 132->380dp card crossed ~20 content buckets, and the clip
        // cache that shares this policy churned even harder). Small content keeps fine 32px steps
        // so tiny slots do not waste buffer memory.
        return when {
            sizePx >= 2048 -> 256
            sizePx >= 512 -> 128
            sizePx >= 256 -> 64
            else -> DEFAULT_BUCKET_SIZE_PX
        }
    }

    private fun roundUpToMultiple(value: Int, multiple: Int): Int {
        if (multiple <= 0) return value
        return ((value + multiple - 1) / multiple) * multiple
    }
}
