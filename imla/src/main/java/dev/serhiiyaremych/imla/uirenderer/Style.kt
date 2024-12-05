/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer

import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.imla.uirenderer.processing.blur.BlurContext

@Immutable
public data class Style(
    @FloatRange(from = 0.1, to = 2.0)
    val offset: Float,
    @IntRange(from = 0, to = BlurContext.MAX_PASSES.toLong())
    val passes: Int,
    val tint: Color = Color.Cyan.copy(alpha = 0.3f),
    @FloatRange(from = 0.0, to = 1.0) val noiseAlpha: Float = 0.07f,
    @FloatRange(from = 0.0, to = 1.0) val blurOpacity: Float = 1.0f,
) {

    public companion object {
        public val default: Style = Style(
            offset = 1.5f,
            passes = 4,
            tint = Color.Transparent,
            noiseAlpha = 0.2f
        )
    }
}
