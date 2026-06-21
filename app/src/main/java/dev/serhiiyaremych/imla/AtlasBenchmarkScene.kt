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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.serhiiyaremych.imla.effectLayer

internal const val ATLAS_BENCHMARK_SCENE_TAG: String = "ImlaAtlasBenchmarkScene"

internal fun isAtlasBenchmarkSceneSwitchEnabled(
    diagnosticBuild: Boolean,
    manualSwitchEnabled: () -> Boolean
): Boolean {
    return diagnosticBuild && manualSwitchEnabled()
}

@Composable
internal fun AtlasBenchmarkScene(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        AtlasBenchmarkBackdrop()
        AtlasBenchmarkSlotSpecs.forEach { spec ->
            AtlasBenchmarkSlot(spec)
        }
    }
}

@Composable
private fun AtlasBenchmarkBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val bandHeight = size.height / AtlasBenchmarkBackgroundBands.size
        AtlasBenchmarkBackgroundBands.forEachIndexed { index, color ->
            drawRect(
                color = color,
                topLeft = Offset(x = 0f, y = index * bandHeight),
                size = Size(width = size.width, height = bandHeight + 1f)
            )
        }

        val gridSpacing = 28.dp.toPx()
        val fineLine = 1.dp.toPx()
        val boldLine = 3.dp.toPx()
        var x = 0f
        var column = 0
        while (x <= size.width) {
            val strong = column % 4 == 0
            drawLine(
                color = if (strong) Color.White.copy(alpha = 0.32f) else Color.Black.copy(alpha = 0.22f),
                start = Offset(x = x, y = 0f),
                end = Offset(x = x, y = size.height),
                strokeWidth = if (strong) boldLine else fineLine
            )
            x += gridSpacing
            column++
        }

        var y = 0f
        var row = 0
        while (y <= size.height) {
            val strong = row % 4 == 0
            drawLine(
                color = if (strong) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.2f),
                start = Offset(x = 0f, y = y),
                end = Offset(x = size.width, y = y),
                strokeWidth = if (strong) boldLine else fineLine
            )
            y += gridSpacing
            row++
        }

        AtlasBenchmarkBackdropMarkers.forEach { marker ->
            drawCircle(
                color = marker.color,
                radius = marker.radius.toPx(),
                center = Offset(
                    x = marker.centerX.toPx(),
                    y = marker.centerY.toPx()
                )
            )
        }
    }
}

@Composable
private fun AtlasBenchmarkSlot(spec: AtlasBenchmarkSlotSpec) {
    Box(
        modifier = Modifier
            .offset(x = spec.offsetX, y = spec.offsetY)
            .size(width = spec.width, height = spec.height)
            .zIndex(spec.zIndex)
            .effectLayer(
                style = spec.style,
                debugName = spec.debugName,
                zIndex = spec.zIndex
            )
            .background(spec.fill)
            .border(width = 1.dp, color = spec.border),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = spec.label,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

internal data class AtlasBenchmarkSlotSpec(
    val debugName: String,
    val label: String,
    val sigma: Float,
    val offsetX: Dp,
    val offsetY: Dp,
    val width: Dp,
    val height: Dp,
    val zIndex: Float,
    val tint: Color
) {
    val style: DemoEffectStyle
        get() = DemoEffectStyle.default.copy(
            sigma = sigma,
            algorithm = DemoBlurAlgorithm.GAUSSIAN,
            noiseAlpha = 0f,
            blurOpacity = 1f,
            tint = tint.copy(alpha = 0.12f)
        )

    val fill: Color
        get() = tint.copy(alpha = 0.06f)

    val border: Color
        get() = tint.copy(alpha = 0.72f)
}

private data class AtlasBenchmarkBackdropMarker(
    val centerX: Dp,
    val centerY: Dp,
    val radius: Dp,
    val color: Color
)

internal val AtlasBenchmarkSlotSpecs: List<AtlasBenchmarkSlotSpec> = listOf(
    AtlasBenchmarkSlotSpec(
        debugName = "atlas-benchmark-6-a",
        label = "6A",
        sigma = 6f,
        offsetX = 24.dp,
        offsetY = 104.dp,
        width = 132.dp,
        height = 92.dp,
        zIndex = 10f,
        tint = Color(0xFF5B8DEF)
    ),
    AtlasBenchmarkSlotSpec(
        debugName = "atlas-benchmark-6-b",
        label = "6B",
        sigma = 6f,
        offsetX = 196.dp,
        offsetY = 132.dp,
        width = 132.dp,
        height = 92.dp,
        zIndex = 11f,
        tint = Color(0xFF46B29D)
    ),
    AtlasBenchmarkSlotSpec(
        debugName = "atlas-benchmark-8-a",
        label = "8A",
        sigma = 8f,
        offsetX = 48.dp,
        offsetY = 272.dp,
        width = 132.dp,
        height = 92.dp,
        zIndex = 12f,
        tint = Color(0xFFE76F51)
    ),
    AtlasBenchmarkSlotSpec(
        debugName = "atlas-benchmark-8-b",
        label = "8B",
        sigma = 8f,
        offsetX = 220.dp,
        offsetY = 308.dp,
        width = 132.dp,
        height = 92.dp,
        zIndex = 13f,
        tint = Color(0xFF8A63D2)
    ),
    AtlasBenchmarkSlotSpec(
        debugName = "atlas-benchmark-10-a",
        label = "10A",
        sigma = 10f,
        offsetX = 32.dp,
        offsetY = 472.dp,
        width = 132.dp,
        height = 92.dp,
        zIndex = 14f,
        tint = Color(0xFFF2A541)
    ),
    AtlasBenchmarkSlotSpec(
        debugName = "atlas-benchmark-10-b",
        label = "10B",
        sigma = 10f,
        offsetX = 204.dp,
        offsetY = 504.dp,
        width = 132.dp,
        height = 92.dp,
        zIndex = 15f,
        tint = Color(0xFF4AA3A2)
    )
)

private val AtlasBenchmarkBackgroundBands: List<Color> = listOf(
    Color(0xFF16324F),
    Color(0xFF2A628F),
    Color(0xFF3E885B),
    Color(0xFFB35C44),
    Color(0xFF865D9C),
    Color(0xFF7A8450),
    Color(0xFF4F5D75),
    Color(0xFF1F7A8C)
)

private val AtlasBenchmarkBackdropMarkers: List<AtlasBenchmarkBackdropMarker> = listOf(
    AtlasBenchmarkBackdropMarker(96.dp, 148.dp, 34.dp, Color(0xFFF4D35E).copy(alpha = 0.82f)),
    AtlasBenchmarkBackdropMarker(284.dp, 188.dp, 28.dp, Color(0xFFE94F37).copy(alpha = 0.78f)),
    AtlasBenchmarkBackdropMarker(132.dp, 328.dp, 30.dp, Color(0xFF5BC0EB).copy(alpha = 0.78f)),
    AtlasBenchmarkBackdropMarker(312.dp, 372.dp, 36.dp, Color(0xFF9BC53D).copy(alpha = 0.76f)),
    AtlasBenchmarkBackdropMarker(104.dp, 528.dp, 32.dp, Color(0xFFFA7921).copy(alpha = 0.78f)),
    AtlasBenchmarkBackdropMarker(292.dp, 552.dp, 30.dp, Color(0xFF3A86FF).copy(alpha = 0.78f)),
    AtlasBenchmarkBackdropMarker(184.dp, 628.dp, 38.dp, Color(0xFFF72585).copy(alpha = 0.78f)),
    AtlasBenchmarkBackdropMarker(352.dp, 644.dp, 28.dp, Color(0xFF4CC9F0).copy(alpha = 0.78f))
)
