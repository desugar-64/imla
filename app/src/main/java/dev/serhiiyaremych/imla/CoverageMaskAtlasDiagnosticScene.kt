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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.serhiiyaremych.imla.EffectLayerBoundsProvider
import dev.serhiiyaremych.imla.effectLayer

internal const val COVERAGE_MASK_ATLAS_DIAGNOSTIC_SCENE_TAG: String = "ImlaCovMaskAtlasScene"

@Composable
internal fun CoverageMaskAtlasDiagnosticScene(modifier: Modifier = Modifier) {
    val hardMask = remember {
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0.0f to Color.White,
                0.46f to Color.White,
                0.54f to Color.Transparent,
                1.0f to Color.Transparent
            )
        )
    }
    val softMask = remember {
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.20f to Color.White.copy(alpha = 0.28f),
                0.50f to Color.White,
                0.80f to Color.White.copy(alpha = 0.42f),
                1.0f to Color.Transparent
            )
        )
    }
    val paddedEffectLayerBoundsProvider = remember {
        EffectLayerBoundsProvider { _, layoutSize ->
            Rect(
                left = -44f,
                top = -34f,
                right = layoutSize.width + 44f,
                bottom = layoutSize.height + 34f
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        CoverageMaskAtlasBackdrop()
        CoverageMaskAtlasSlot(
            label = "simple",
            debugName = "coverage-mask-atlas-simple",
            modifier = Modifier.offset(x = 32.dp, y = 108.dp),
            mask = hardMask,
            style = CoverageMaskAtlasStyles.simple
        )
        CoverageMaskAtlasSlot(
            label = "padded",
            debugName = "coverage-mask-atlas-padded",
            modifier = Modifier.offset(x = 156.dp, y = 314.dp),
            mask = softMask,
            style = CoverageMaskAtlasStyles.padded,
            visibleAreaProvider = paddedEffectLayerBoundsProvider
        )
        CoverageMaskAtlasSlot(
            label = "rotated",
            debugName = "coverage-mask-atlas-rotated",
            modifier = Modifier
                .offset(x = 48.dp, y = 552.dp)
                .graphicsLayer {
                    rotationZ = -16f
                    transformOrigin = TransformOrigin.Center
                },
            mask = hardMask,
            style = CoverageMaskAtlasStyles.rotated
        )
    }
}

@Composable
private fun CoverageMaskAtlasBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val bandHeight = size.height / CoverageMaskAtlasBackgroundBands.size
        CoverageMaskAtlasBackgroundBands.forEachIndexed { index, color ->
            drawRect(
                color = color,
                topLeft = Offset(x = 0f, y = index * bandHeight),
                size = Size(width = size.width, height = bandHeight + 1f)
            )
        }

        val spacing = 24.dp.toPx()
        var x = 0f
        var column = 0
        while (x <= size.width) {
            val strong = column % 4 == 0
            drawLine(
                color = if (strong) Color.White.copy(alpha = 0.44f) else Color.Black.copy(alpha = 0.25f),
                start = Offset(x = x, y = 0f),
                end = Offset(x = x, y = size.height),
                strokeWidth = if (strong) 3.dp.toPx() else 1.dp.toPx()
            )
            x += spacing
            column++
        }

        var y = 0f
        var row = 0
        while (y <= size.height) {
            val strong = row % 4 == 0
            drawLine(
                color = if (strong) Color.White.copy(alpha = 0.38f) else Color.Black.copy(alpha = 0.22f),
                start = Offset(x = 0f, y = y),
                end = Offset(x = size.width, y = y),
                strokeWidth = if (strong) 3.dp.toPx() else 1.dp.toPx()
            )
            y += spacing
            row++
        }

        CoverageMaskAtlasBackdropMarkers.forEach { marker ->
            drawCircle(
                color = marker.color,
                radius = marker.radius.toPx(),
                center = Offset(x = marker.centerX.toPx(), y = marker.centerY.toPx())
            )
        }
    }
}

@Composable
private fun CoverageMaskAtlasSlot(
    label: String,
    debugName: String,
    modifier: Modifier,
    mask: Brush,
    style: DemoEffectStyle,
    visibleAreaProvider: EffectLayerBoundsProvider? = null
) {
    Box(
        modifier = modifier
            .size(width = 188.dp, height = 132.dp)
            .zIndex(20f)
            .effectLayer(
                style = style,
                blurMask = mask,
                visibleAreaProvider = visibleAreaProvider,
                debugName = debugName,
                zIndex = 20f
            )
            .background(Color.White.copy(alpha = 0.05f))
            .border(width = 2.dp, color = Color.White.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private object CoverageMaskAtlasStyles {
    val simple: DemoEffectStyle = DemoEffectStyle.default.copy(
        sigma = 12f,
        algorithm = DemoBlurAlgorithm.GAUSSIAN,
        noiseAlpha = 0f,
        blurOpacity = 1f,
        tint = Color(0xFF90CAF9).copy(alpha = 0.08f)
    )
    val padded: DemoEffectStyle = DemoEffectStyle.default.copy(
        sigma = 18f,
        algorithm = DemoBlurAlgorithm.GAUSSIAN,
        noiseAlpha = 0f,
        blurOpacity = 1f,
        tint = Color(0xFFA5D6A7).copy(alpha = 0.08f)
    )
    val rotated: DemoEffectStyle = DemoEffectStyle.default.copy(
        sigma = 14f,
        algorithm = DemoBlurAlgorithm.GAUSSIAN,
        noiseAlpha = 0f,
        blurOpacity = 1f,
        tint = Color(0xFFFFCC80).copy(alpha = 0.08f)
    )
}

private data class CoverageMaskAtlasBackdropMarker(
    val centerX: Dp,
    val centerY: Dp,
    val radius: Dp,
    val color: Color
)

private val CoverageMaskAtlasBackgroundBands: List<Color> = listOf(
    Color(0xFF12355B),
    Color(0xFF5C677D),
    Color(0xFF4D908E),
    Color(0xFFF9844A),
    Color(0xFF577590),
    Color(0xFF43AA8B),
    Color(0xFF9B5DE5),
    Color(0xFFF15BB5)
)

private val CoverageMaskAtlasBackdropMarkers: List<CoverageMaskAtlasBackdropMarker> = listOf(
    CoverageMaskAtlasBackdropMarker(96.dp, 194.dp, 42.dp, Color(0xFFFFD166).copy(alpha = 0.9f)),
    CoverageMaskAtlasBackdropMarker(264.dp, 390.dp, 38.dp, Color(0xFF06D6A0).copy(alpha = 0.8f)),
    CoverageMaskAtlasBackdropMarker(138.dp, 640.dp, 44.dp, Color(0xFFEF476F).copy(alpha = 0.82f))
)
