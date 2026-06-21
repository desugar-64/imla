package dev.serhiiyaremych.imla.internal.layer.model

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

internal data class SceneProgressiveMaskKey(
    val brush: Brush,
    val size: IntSize,
    val density: Float,
    val fontScale: Float,
    val layoutDirection: LayoutDirection
)

internal data class SceneClipShapeKey(
    val shape: Shape,
    val inset: SceneClipInsetPx,
    val size: IntSize,
    val density: Float,
    val fontScale: Float,
    val layoutDirection: LayoutDirection
)

internal data class SceneClipInsetPx(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
