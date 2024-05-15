/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer

import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.Color

public data class Style(
    val tint: Color,
    @FloatRange(from = 0.0, to = 1.0) val noiseFactor: Float
)
