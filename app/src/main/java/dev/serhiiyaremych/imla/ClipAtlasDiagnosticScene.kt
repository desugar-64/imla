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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.serhiiyaremych.imla.effectLayer

internal const val CLIP_ATLAS_DIAGNOSTIC_SCENE_TAG: String = "ImlaClipAtlasScene"

@Composable
internal fun ClipAtlasDiagnosticScene(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        ClipAtlasDiagnosticBackdrop()
        ClipAtlasSlotSpecs.forEach { spec ->
            ClipAtlasDiagnosticSlot(spec)
        }
    }
}

@Composable
private fun ClipAtlasDiagnosticBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val bandHeight = size.height / ClipAtlasBackdropBands.size
        ClipAtlasBackdropBands.forEachIndexed { index, color ->
            drawRect(
                color = color,
                topLeft = Offset(x = 0f, y = index * bandHeight),
                size = Size(width = size.width, height = bandHeight + 1f)
            )
        }

        val gridSpacing = 24.dp.toPx()
        val lightLine = 1.dp.toPx()
        val boldLine = 3.dp.toPx()
        var x = 0f
        var column = 0
        while (x <= size.width) {
            val strong = column % 3 == 0
            drawLine(
                color = if (strong) Color.White.copy(alpha = 0.42f) else Color.Black.copy(alpha = 0.24f),
                start = Offset(x = x, y = 0f),
                end = Offset(x = x, y = size.height),
                strokeWidth = if (strong) boldLine else lightLine
            )
            x += gridSpacing
            column++
        }

        var y = 0f
        var row = 0
        while (y <= size.height) {
            val strong = row % 3 == 0
            drawLine(
                color = if (strong) Color.White.copy(alpha = 0.36f) else Color.Black.copy(alpha = 0.2f),
                start = Offset(x = 0f, y = y),
                end = Offset(x = size.width, y = y),
                strokeWidth = if (strong) boldLine else lightLine
            )
            y += gridSpacing
            row++
        }

        ClipAtlasBackdropMarkers.forEach { marker ->
            drawCircle(
                color = marker.color,
                radius = marker.radius.toPx(),
                center = Offset(x = marker.centerX.toPx(), y = marker.centerY.toPx())
            )
        }
    }
}

@Composable
private fun ClipAtlasDiagnosticSlot(spec: ClipAtlasSlotSpec) {
    Box(
        modifier = Modifier
            .offset(x = spec.offsetX, y = spec.offsetY)
            .size(width = spec.width, height = spec.height)
            .graphicsLayer { rotationZ = spec.rotationZ }
            .zIndex(spec.zIndex)
            .effectLayer(
                style = spec.style,
                clipShape = spec.clipShape,
                debugName = spec.debugName,
                zIndex = spec.zIndex
            )
            .background(spec.fill, spec.clipShape)
            .border(width = 2.dp, color = spec.border, shape = spec.clipShape),
        contentAlignment = Alignment.Center
    ) {
        if (spec.showCrossingForeground) {
            Box(
                modifier = Modifier
                    .width(spec.width + 52.dp)
                    .height(18.dp)
                    .graphicsLayer { rotationZ = -18f }
                    .background(Color.White.copy(alpha = 0.86f))
            )
        }
        Text(
            text = spec.label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

internal data class ClipAtlasSlotSpec(
    val debugName: String,
    val label: String,
    val offsetX: Dp,
    val offsetY: Dp,
    val width: Dp,
    val height: Dp,
    val zIndex: Float,
    val tint: Color,
    val clipShape: Shape,
    val rotationZ: Float = 0f,
    val showCrossingForeground: Boolean = false
) {
    val style: DemoEffectStyle
        get() = DemoEffectStyle.default.copy(
            sigma = 8f,
            algorithm = DemoBlurAlgorithm.GAUSSIAN,
            noiseAlpha = 0f,
            blurOpacity = 1f,
            tint = tint.copy(alpha = 0.16f)
        )

    val fill: Color
        get() = tint.copy(alpha = 0.08f)

    val border: Color
        get() = tint.copy(alpha = 0.78f)
}

private data class ClipAtlasBackdropMarker(
    val centerX: Dp,
    val centerY: Dp,
    val radius: Dp,
    val color: Color
)

internal val ClipAtlasSlotSpecs: List<ClipAtlasSlotSpec> = listOf(
    ClipAtlasSlotSpec(
        debugName = "clip-atlas-rounded",
        label = "rounded",
        offsetX = 28.dp,
        offsetY = 112.dp,
        width = 176.dp,
        height = 116.dp,
        zIndex = 10f,
        tint = Color(0xFF4D96FF),
        clipShape = RoundedCornerShape(30.dp)
    ),
    ClipAtlasSlotSpec(
        debugName = "clip-atlas-rotated",
        label = "rotated",
        offsetX = 188.dp,
        offsetY = 292.dp,
        width = 176.dp,
        height = 118.dp,
        zIndex = 11f,
        tint = Color(0xFFFF6B6B),
        clipShape = RoundedCornerShape(34.dp),
        rotationZ = -14f
    ),
    ClipAtlasSlotSpec(
        debugName = "clip-atlas-foreground-edge",
        label = "foreground",
        offsetX = 38.dp,
        offsetY = 480.dp,
        width = 206.dp,
        height = 124.dp,
        zIndex = 12f,
        tint = Color(0xFF06D6A0),
        clipShape = RoundedCornerShape(38.dp),
        showCrossingForeground = true
    ),
    ClipAtlasSlotSpec(
        debugName = "clip-atlas-later-unclipped",
        label = "unclipped",
        offsetX = 112.dp,
        offsetY = 664.dp,
        width = 186.dp,
        height = 104.dp,
        zIndex = 13f,
        tint = Color(0xFFFFBE0B),
        clipShape = RectangleShape
    )
)

private val ClipAtlasBackdropBands: List<Color> = listOf(
    Color(0xFF16324F),
    Color(0xFF31572C),
    Color(0xFF7B2CBF),
    Color(0xFFB23A48),
    Color(0xFF0A9396),
    Color(0xFF5F0F40),
    Color(0xFF386641),
    Color(0xFF1D3557)
)

private val ClipAtlasBackdropMarkers: List<ClipAtlasBackdropMarker> = listOf(
    ClipAtlasBackdropMarker(88.dp, 154.dp, 34.dp, Color(0xFFFFD166).copy(alpha = 0.86f)),
    ClipAtlasBackdropMarker(238.dp, 194.dp, 26.dp, Color(0xFFEF476F).copy(alpha = 0.82f)),
    ClipAtlasBackdropMarker(284.dp, 322.dp, 38.dp, Color(0xFF06D6A0).copy(alpha = 0.8f)),
    ClipAtlasBackdropMarker(144.dp, 534.dp, 32.dp, Color(0xFF118AB2).copy(alpha = 0.84f)),
    ClipAtlasBackdropMarker(284.dp, 704.dp, 34.dp, Color(0xFFFFBE0B).copy(alpha = 0.84f))
)
