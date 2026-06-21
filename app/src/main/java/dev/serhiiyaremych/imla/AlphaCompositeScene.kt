/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.serhiiyaremych.imla.effectLayer

internal const val ALPHA_COMPOSITE_SCENE_TAG: String = "ImlaAlphaCompositeScene"

@Composable
internal fun AlphaCompositeScene(modifier: Modifier = Modifier) {
    val coverageGradient = remember {
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.22f to Color.White.copy(alpha = 0.22f),
                0.55f to Color.White,
                0.82f to Color.White.copy(alpha = 0.38f),
                1.0f to Color.Transparent
            )
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        AlphaCompositeBackdrop()
        AlphaCompositeSlot(
            label = "low opacity",
            debugName = "alpha-low-opacity",
            modifier = Modifier.offset(x = 30.dp, y = 86.dp),
            style = AlphaCompositeStyles.lowOpacity
        )
        AlphaCompositeSlot(
            label = "tint",
            debugName = "alpha-tint",
            modifier = Modifier.offset(x = 202.dp, y = 210.dp),
            style = AlphaCompositeStyles.tint
        )
        AlphaCompositeSlot(
            label = "mask gradient",
            debugName = "alpha-mask-gradient",
            modifier = Modifier.offset(x = 36.dp, y = 350.dp),
            style = AlphaCompositeStyles.maskGradient,
            mask = coverageGradient
        )
        AlphaCompositeSlot(
            label = "noise",
            debugName = "alpha-noise",
            modifier = Modifier.offset(x = 204.dp, y = 490.dp),
            style = AlphaCompositeStyles.noise
        )
        AlphaCompositeSlot(
            label = "edge source",
            debugName = "alpha-edge-source",
            modifier = Modifier.offset(x = 42.dp, y = 636.dp),
            style = AlphaCompositeStyles.edgeSource,
            edgeContent = true
        )
    }
}

@Composable
private fun AlphaCompositeBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stripeHeight = 28.dp.toPx()
        var y = 0f
        var index = 0
        while (y < size.height) {
            val color = AlphaCompositeBackgroundBands[index % AlphaCompositeBackgroundBands.size]
            drawRect(
                color = color,
                topLeft = Offset(x = 0f, y = y),
                size = Size(width = size.width, height = stripeHeight + 1f)
            )
            y += stripeHeight
            index++
        }

        val columnWidth = 42.dp.toPx()
        var x = 0f
        var column = 0
        while (x < size.width) {
            drawRect(
                color = if (column % 2 == 0) {
                    Color.White.copy(alpha = 0.30f)
                } else {
                    Color.Black.copy(alpha = 0.28f)
                },
                topLeft = Offset(x = x, y = 0f),
                size = Size(width = 6.dp.toPx(), height = size.height)
            )
            x += columnWidth
            column++
        }

        AlphaCompositeMarkers.forEach { marker ->
            drawCircle(
                color = marker.color,
                radius = marker.radius.toPx(),
                center = Offset(x = marker.centerX.toPx(), y = marker.centerY.toPx())
            )
        }

        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.04f),
                    Color.White.copy(alpha = 0.44f),
                    Color.Transparent
                ),
                start = Offset(x = 0f, y = 560.dp.toPx()),
                end = Offset(x = size.width, y = 760.dp.toPx())
            ),
            topLeft = Offset(x = 24.dp.toPx(), y = 586.dp.toPx()),
            size = Size(width = 330.dp.toPx(), height = 210.dp.toPx()),
            cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
        )
    }
}

@Composable
private fun AlphaCompositeSlot(
    label: String,
    debugName: String,
    modifier: Modifier,
    style: DemoEffectStyle,
    mask: Brush? = null,
    edgeContent: Boolean = false
) {
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .size(width = 156.dp, height = 118.dp)
            .zIndex(30f)
            .effectLayer(
                style = style,
                blurMask = mask,
                clipShape = shape,
                debugName = debugName,
                zIndex = 30f
            )
            .background(Color.White.copy(alpha = 0.045f), shape)
            .border(width = 2.dp, color = Color.White.copy(alpha = 0.76f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (edgeContent) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.30f),
                    radius = 34.dp.toPx(),
                    center = Offset(x = 26.dp.toPx(), y = 28.dp.toPx())
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.24f),
                    radius = 30.dp.toPx(),
                    center = Offset(x = 122.dp.toPx(), y = 92.dp.toPx())
                )
                drawLine(
                    color = Color(0xFFFFD166).copy(alpha = 0.65f),
                    start = Offset(x = 0f, y = size.height),
                    end = Offset(x = size.width, y = 0f),
                    strokeWidth = 8.dp.toPx()
                )
            }
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private object AlphaCompositeStyles {
    val lowOpacity: DemoEffectStyle = DemoEffectStyle.default.copy(
        sigma = 18f,
        noiseAlpha = 0f,
        blurOpacity = 0.28f,
        tint = Color.Transparent
    )

    val tint: DemoEffectStyle = DemoEffectStyle.default.copy(
        sigma = 16f,
        noiseAlpha = 0f,
        blurOpacity = 0.62f,
        tint = Color(0xFFFF5252).copy(alpha = 0.48f)
    )

    val maskGradient: DemoEffectStyle = DemoEffectStyle.default.copy(
        sigma = 18f,
        noiseAlpha = 0f,
        blurOpacity = 0.84f,
        tint = Color(0xFF80CBC4).copy(alpha = 0.12f)
    )

    val noise: DemoEffectStyle = DemoEffectStyle.default.copy(
        sigma = 14f,
        noiseAlpha = 0.24f,
        blurOpacity = 0.54f,
        tint = Color.White.copy(alpha = 0.10f)
    )

    val edgeSource: DemoEffectStyle = DemoEffectStyle.default.copy(
        sigma = 20f,
        noiseAlpha = 0f,
        blurOpacity = 0.38f,
        tint = Color(0xFF64B5F6).copy(alpha = 0.20f)
    )
}

private data class AlphaCompositeMarker(
    val centerX: Dp,
    val centerY: Dp,
    val radius: Dp,
    val color: Color
)

private val AlphaCompositeBackgroundBands: List<Color> = listOf(
    Color(0xFF101820),
    Color(0xFFFEE715),
    Color(0xFF2E86AB),
    Color(0xFFF24236),
    Color(0xFF2B9348),
    Color(0xFF5F0F40),
    Color(0xFF0F7173),
    Color(0xFFE9C46A)
)

private val AlphaCompositeMarkers: List<AlphaCompositeMarker> = listOf(
    AlphaCompositeMarker(84.dp, 142.dp, 36.dp, Color.White.copy(alpha = 0.72f)),
    AlphaCompositeMarker(270.dp, 258.dp, 42.dp, Color(0xFF000000).copy(alpha = 0.42f)),
    AlphaCompositeMarker(112.dp, 412.dp, 48.dp, Color(0xFFFFE66D).copy(alpha = 0.78f)),
    AlphaCompositeMarker(282.dp, 544.dp, 40.dp, Color(0xFF4ECDC4).copy(alpha = 0.72f)),
    AlphaCompositeMarker(126.dp, 696.dp, 52.dp, Color(0xFFFF6B6B).copy(alpha = 0.58f))
)
