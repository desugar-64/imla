/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.util

import androidx.compose.ui.unit.IntSize

internal object SizeUtil {
    private val powersOfTwo = intArrayOf(
        2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096
    )

    fun closestPOTDown(arbitrarySize: Int): Int {
        if (arbitrarySize <= 2) return 2
        return powersOfTwo.last { it <= arbitrarySize }
    }

    fun closestPOTUp(arbitrarySize: Int): Int {
        if (arbitrarySize <= 2) return 2
        return powersOfTwo.first { it >= arbitrarySize }
    }

    fun closestPOTDown(arbitrarySize: IntSize): IntSize {
        return IntSize(
            width = closestPOTDown(arbitrarySize.width),
            height = closestPOTDown(arbitrarySize.height)
        )
    }

    fun closestPOTUp(arbitrarySize: IntSize): IntSize {
        return IntSize(
            width = closestPOTUp(arbitrarySize.width),
            height = closestPOTUp(arbitrarySize.height)
        )
    }

}