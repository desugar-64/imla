/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Data class for texture region bounds
 */
internal data class TextureRegion(
    val uvBounds: Rect,        // Texture coordinates [0,1] range
    val screenSize: Size       // Actual screen dimensions of this region
)