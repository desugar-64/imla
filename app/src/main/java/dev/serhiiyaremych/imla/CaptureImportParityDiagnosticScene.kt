/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.serhiiyaremych.imla.EffectLayerBoundsProvider
import dev.serhiiyaremych.imla.effectLayer

internal const val CAPTURE_IMPORT_PARITY_SCENE_TAG: String = "ImlaImportParityScene"

@Composable
internal fun CaptureImportParityDiagnosticScene(modifier: Modifier = Modifier) {
    val movingCropTransition = rememberInfiniteTransition(label = "capture-import-crop")
    val movingCropOffset = movingCropTransition.animateFloat(
        initialValue = 0f,
        targetValue = 42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "same-size-visible-area-offset"
    )
    val movingCropProvider = remember {
        EffectLayerBoundsProvider { _, layoutSize ->
            Rect(
                left = movingCropOffset.value,
                top = 0f,
                right = layoutSize.width + movingCropOffset.value,
                bottom = layoutSize.height.toFloat()
            )
        }
    }
    val blurMask = remember {
        Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to Color.White,
                0.55f to Color.White.copy(alpha = 0.9f),
                0.78f to Color.White.copy(alpha = 0.22f),
                1.0f to Color.Transparent
            )
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        CaptureImportParityRootPlate()
        CaptureImportParitySlots.forEach { spec ->
            CaptureImportParitySlot(
                spec = spec,
                blurMask = if (CaptureImportParityCase.BLUR_MASK in spec.cases) blurMask else null,
                visibleAreaProvider = if (CaptureImportParityCase.MOVING_SAME_SIZE_CONTENT_CROP in spec.cases) {
                    movingCropProvider
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun CaptureImportParityRootPlate() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val bandHeight = size.height / CaptureImportParityBands.size
        CaptureImportParityBands.forEachIndexed { index, color ->
            drawRect(
                color = color,
                topLeft = Offset(x = 0f, y = index * bandHeight),
                size = Size(width = size.width, height = bandHeight + 1f)
            )
        }

        val spacing = 20.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(x = x, y = 0f),
                end = Offset(x = x + size.height, y = size.height),
                strokeWidth = 2.dp.toPx()
            )
            x += spacing
        }

        CaptureImportParityMarkers.forEach { marker ->
            drawCircle(
                color = marker.color,
                radius = marker.radius.toPx(),
                center = Offset(x = marker.centerX.toPx(), y = marker.centerY.toPx())
            )
        }
    }
}

@Composable
private fun CaptureImportParitySlot(
    spec: CaptureImportParitySlotSpec,
    blurMask: Brush?,
    visibleAreaProvider: EffectLayerBoundsProvider?
) {
    val clipShape = spec.foregroundClipShape
    Box(
        modifier = Modifier
            .offset(x = spec.offsetX, y = spec.offsetY)
            .size(width = spec.width, height = spec.height)
            .graphicsLayer {
                rotationZ = spec.rotationZ
                transformOrigin = TransformOrigin.Center
            }
            .zIndex(spec.zIndex)
            .effectLayer(
                style = spec.style,
                blurMask = blurMask,
                clipShape = spec.clipShape,
                visibleAreaProvider = visibleAreaProvider,
                debugName = spec.debugName,
                zIndex = spec.zIndex
            )
            .then(if (clipShape == null) Modifier else Modifier.clip(clipShape))
            .background(spec.fill, spec.clipShape)
            .border(width = 2.dp, color = spec.border, shape = spec.clipShape),
        contentAlignment = Alignment.Center
    ) {
        CaptureImportParityForeground(spec)
        Text(
            text = spec.label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BoxScope.CaptureImportParityForeground(spec: CaptureImportParitySlotSpec) {
    if (CaptureImportParityCase.TRANSPARENT_EDGES in spec.cases) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(spec.width)
                .height(18.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.82f),
                            Color.Transparent
                        )
                    )
                )
        )
    }

    Box(
        modifier = Modifier
            .width(spec.width + 64.dp)
            .height(22.dp)
            .graphicsLayer { rotationZ = spec.foregroundRotationZ }
            .background(Color.White.copy(alpha = spec.foregroundAlpha))
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 20.dp, y = 10.dp)
            .size(44.dp)
            .background(spec.border.copy(alpha = 0.9f), CircleShape)
    )
}

internal enum class CaptureImportParityCase {
    ROOT_CAPTURE,
    SLOT_FOREGROUND_CONTENT_CAPTURE,
    BLUR_MASK,
    CLIP_MASK,
    MOVING_SAME_SIZE_CONTENT_CROP,
    ROTATED_FOREGROUND_CONTENT,
    CLIPPED_FOREGROUND_CONTENT,
    TRANSPARENT_EDGES
}

internal data class CaptureImportParitySlotSpec(
    val debugName: String,
    val label: String,
    val offsetX: Dp,
    val offsetY: Dp,
    val width: Dp,
    val height: Dp,
    val zIndex: Float,
    val tint: Color,
    val cases: Set<CaptureImportParityCase>,
    val clipShape: Shape = RectangleShape,
    val foregroundClipShape: Shape? = null,
    val rotationZ: Float = 0f,
    val foregroundRotationZ: Float = -12f,
    val foregroundAlpha: Float = 0.82f
) {
    val style: DemoEffectStyle
        get() = DemoEffectStyle.default.copy(
            sigma = 12f,
            algorithm = DemoBlurAlgorithm.GAUSSIAN,
            noiseAlpha = 0f,
            blurOpacity = 1f,
            tint = tint.copy(alpha = 0.12f)
        )

    val fill: Color
        get() = tint.copy(alpha = 0.08f)

    val border: Color
        get() = tint.copy(alpha = 0.82f)
}

private data class CaptureImportParityMarker(
    val centerX: Dp,
    val centerY: Dp,
    val radius: Dp,
    val color: Color
)

internal val CaptureImportParitySlots: List<CaptureImportParitySlotSpec> = listOf(
    CaptureImportParitySlotSpec(
        debugName = "capture-parity-root-plate",
        label = "root",
        offsetX = 30.dp,
        offsetY = 96.dp,
        width = 184.dp,
        height = 116.dp,
        zIndex = 20f,
        tint = Color(0xFF4D96FF),
        cases = setOf(
            CaptureImportParityCase.ROOT_CAPTURE,
            CaptureImportParityCase.SLOT_FOREGROUND_CONTENT_CAPTURE
        )
    ),
    CaptureImportParitySlotSpec(
        debugName = "capture-parity-blur-mask",
        label = "mask",
        offsetX = 164.dp,
        offsetY = 250.dp,
        width = 188.dp,
        height = 126.dp,
        zIndex = 21f,
        tint = Color(0xFFFFB703),
        cases = setOf(
            CaptureImportParityCase.BLUR_MASK,
            CaptureImportParityCase.SLOT_FOREGROUND_CONTENT_CAPTURE
        )
    ),
    CaptureImportParitySlotSpec(
        debugName = "capture-parity-clip-mask",
        label = "clip",
        offsetX = 34.dp,
        offsetY = 406.dp,
        width = 194.dp,
        height = 126.dp,
        zIndex = 22f,
        tint = Color(0xFF06D6A0),
        cases = setOf(
            CaptureImportParityCase.CLIP_MASK,
            CaptureImportParityCase.CLIPPED_FOREGROUND_CONTENT
        ),
        clipShape = RoundedCornerShape(32.dp),
        foregroundClipShape = RoundedCornerShape(32.dp)
    ),
    CaptureImportParitySlotSpec(
        debugName = "capture-parity-moving-crop",
        label = "crop",
        offsetX = 154.dp,
        offsetY = 562.dp,
        width = 184.dp,
        height = 116.dp,
        zIndex = 23f,
        tint = Color(0xFFEF476F),
        cases = setOf(
            CaptureImportParityCase.MOVING_SAME_SIZE_CONTENT_CROP,
            CaptureImportParityCase.SLOT_FOREGROUND_CONTENT_CAPTURE
        ),
        foregroundRotationZ = 0f
    ),
    CaptureImportParitySlotSpec(
        debugName = "capture-parity-rotated-foreground",
        label = "rot",
        offsetX = 46.dp,
        offsetY = 712.dp,
        width = 188.dp,
        height = 122.dp,
        zIndex = 24f,
        tint = Color(0xFF9B5DE5),
        cases = setOf(
            CaptureImportParityCase.ROTATED_FOREGROUND_CONTENT,
            CaptureImportParityCase.SLOT_FOREGROUND_CONTENT_CAPTURE
        ),
        rotationZ = -14f,
        foregroundRotationZ = 24f
    ),
    CaptureImportParitySlotSpec(
        debugName = "capture-parity-transparent-edge",
        label = "edge",
        offsetX = 150.dp,
        offsetY = 850.dp,
        width = 190.dp,
        height = 116.dp,
        zIndex = 25f,
        tint = Color(0xFFF15BB5),
        cases = setOf(
            CaptureImportParityCase.TRANSPARENT_EDGES,
            CaptureImportParityCase.SLOT_FOREGROUND_CONTENT_CAPTURE
        ),
        foregroundAlpha = 0.34f
    )
)

private val CaptureImportParityBands: List<Color> = listOf(
    Color(0xFF16324F),
    Color(0xFF386641),
    Color(0xFF7B2CBF),
    Color(0xFFB23A48),
    Color(0xFF0A9396),
    Color(0xFF5F0F40),
    Color(0xFF31572C),
    Color(0xFF1D3557)
)

private val CaptureImportParityMarkers: List<CaptureImportParityMarker> = listOf(
    CaptureImportParityMarker(72.dp, 162.dp, 34.dp, Color(0xFFFFD166).copy(alpha = 0.9f)),
    CaptureImportParityMarker(292.dp, 316.dp, 40.dp, Color(0xFF06D6A0).copy(alpha = 0.78f)),
    CaptureImportParityMarker(116.dp, 488.dp, 38.dp, Color(0xFFEF476F).copy(alpha = 0.84f)),
    CaptureImportParityMarker(286.dp, 640.dp, 34.dp, Color(0xFF4D96FF).copy(alpha = 0.82f)),
    CaptureImportParityMarker(128.dp, 818.dp, 42.dp, Color(0xFFFFB703).copy(alpha = 0.82f))
)
