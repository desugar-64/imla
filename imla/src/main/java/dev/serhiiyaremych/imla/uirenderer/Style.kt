/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
public data class Style(
    val blurRadius: Dp,
    val tint: Color,
    @FloatRange(from = 0.0, to = 1.0) val noiseAlpha: Float
) {
    context(Density)
    internal fun blurRadiusPx(): Float {
        return blurRadius.toPx()
    }

    internal companion object {
        val default = Style(
            blurRadius = 8.dp,
            tint = Color.Transparent,
            noiseAlpha = 0.0f
        )
    }
}
