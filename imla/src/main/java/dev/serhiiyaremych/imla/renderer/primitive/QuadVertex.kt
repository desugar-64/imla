/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.primitive

import androidx.compose.ui.geometry.Offset
import dev.romainguy.kotlin.math.Float4

internal data class QuadVertex(
    val position: Float4,
    val texCoord: Offset,
    val texIndex: Float,
    val flipTexture: Float,
    val isExternalTexture: Float,
    val alpha: Float,
    val mask: Float,
    val maskCoord: Offset
) {
    companion object {
        // @formatter:off
        const val NUMBER_OF_COMPONENTS =
                       /*position*/ 4 +
                       /*texCoord*/ 2 +
                     /* texIndex */ 1 +
                  /* flipTexture */ 1 +
            /* isExternalTexture */ 1 +
                        /* alpha */ 1 +
                         /* mask */ 1 +
                    /* maskCoord */ 2
    }
}
