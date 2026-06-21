/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.primitive

import androidx.compose.ui.geometry.Offset
import dev.romainguy.kotlin.math.Float4

internal data class QuadVertex(
    val position: Float4,
    val texCoord: Offset,
    val texIndex: Float,
    val flags: Float,
    val alpha: Float,
    val maskCoord: Offset,
    val tintPacked: Float
) {
    companion object {
        // @formatter:off
        const val NUMBER_OF_COMPONENTS =
                       /*position*/ 4 +
                       /*texCoord*/ 2 +
                     /* texIndex */ 1 +
                       /* flags */ 1 +
                        /* alpha */ 1 +
                    /* maskCoord */ 2 +
                   /* tintPacked */ 1
    }
}
