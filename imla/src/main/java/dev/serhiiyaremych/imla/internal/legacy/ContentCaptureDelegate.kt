/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize

internal interface ContentCaptureDelegate {
    fun renderGraphicsLayer(
        graphicsLayer: GraphicsLayer?,
        size: IntSize,
        contentOffset: Offset
    )

    fun releaseCurrentLayer()
    fun destroy()
}
