/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.imla.EffectLayerBoundsProvider
import dev.serhiiyaremych.imla.effectLayer

internal enum class Scene2ProfilingCase(
    val label: String,
    val tag: String,
) {
    RootOnly("root-only", "ImlaScene2ProfileRoot"),
    SlotContent("slot-content", "ImlaScene2ProfileSlot"),
    BackdropBlur("backdrop-blur", "ImlaScene2ProfileBlur"),
    StaticRootBackdropBlur("static-root-backdrop-blur", "ImlaScene2ProfileStaticRootBlur"),
    StaticRootFrozenSlotBlur("static-root-frozen-slot-blur", "ImlaScene2ProfileFrozenSlotBlur"),
    StaticRootGeometrySlotBlur("static-root-geometry-slot-blur", "ImlaScene2ProfileGeometrySlotBlur"),
    StaticRootSmallSlotBlur("static-root-small-slot-blur", "ImlaScene2ProfileSmallSlotBlur"),
    StaticRootLargeSlotBlur("static-root-large-slot-blur", "ImlaScene2ProfileLargeSlotBlur"),
    AnimatedSizeSlotContent("animated-size-slot-content", "ImlaScene2ProfileAnimatedSize"),
    BlurTint("blur-tint", "ImlaScene2ProfileTint"),
    BlurTintNoise("blur-tint-noise", "ImlaScene2ProfileNoise"),
    BlurTintNoiseClip("blur-tint-noise-clip", "ImlaScene2ProfileClip"),
    ProgressiveMask("progressive-mask", "ImlaScene2ProfileMask"),
    Rotation("rotation", "ImlaScene2ProfileRotation"),
    Translation("translation", "ImlaScene2ProfileTranslation"),
    Cumulative("cumulative", "ImlaScene2ProfileCumulative"),
    MaterialBottomSheetVisualBounds(
        "material-bottom-sheet-visual-bounds",
        "ImlaScene2ProfileMaterialBottomSheetVisualBounds"
    );

    companion object {
        private const val PROFILING_TAG = "ImlaScene2Profiling"

        fun fromLogTags(): Scene2ProfilingCase? {
            return entries.firstOrNull { Log.isLoggable(it.tag, Log.DEBUG) }
                ?: RootOnly.takeIf { Log.isLoggable(PROFILING_TAG, Log.DEBUG) }
        }
    }
}

@Composable
internal fun Scene2ProfilingScene(
    case: Scene2ProfilingCase,
    modifier: Modifier = Modifier,
) {
    var screenSize by remember { mutableStateOf(IntSize.Zero) }
    val updateTransition = rememberInfiniteTransition(label = "scene2ProfilingUpdates")
    val updateProgress by updateTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scene2ProfilingUpdateProgress"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { screenSize = it }
            .drawProfilingCheckerboard()
    ) {
        if (screenSize == IntSize.Zero) return@Box
        ProfilingCaseLabel(case = case)
        if (case.animatesRoot) {
            RootUpdateMarker(screenSize = screenSize, progress = updateProgress)
        } else if (case.needsInvisibleRootTick) {
            InvisibleRootTick(progress = updateProgress)
        }
        when (case) {
            Scene2ProfilingCase.Cumulative -> {
                CumulativeProfilingSlots(contentProgress = updateProgress)
            }
            Scene2ProfilingCase.MaterialBottomSheetVisualBounds -> {
                MaterialBottomSheetVisualBoundsProfiling()
            }
            else -> {
                CenterProfilingSlot(
                    case = case,
                    screenSize = screenSize,
                    contentProgress = case.slotContentProgress(updateProgress)
                )
            }
        }
    }
}

private fun Modifier.drawProfilingCheckerboard(): Modifier {
    return drawBehind {
        val cellSize = size.width / 10f
        if (cellSize <= 0f) return@drawBehind
        val cols = (size.width / cellSize).toInt() + 1
        val rows = (size.height / cellSize).toInt() + 1
        drawRect(Color(0xFF101010))
        for (x in 0 until cols) {
            for (y in 0 until rows) {
                if ((x + y) % 2 == 0) {
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(x * cellSize, y * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.CenterProfilingSlot(
    case: Scene2ProfilingCase,
    screenSize: IntSize,
    contentProgress: Float,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(16.dp)
    val transition = rememberInfiniteTransition(label = "scene2ProfilingMotion")
    val motion by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scene2ProfilingMotionProgress"
    )
    val slotWidth = case.slotWidth(motion)
    val slotHeight = case.slotHeight(motion)
    val slotWidthPx = with(density) { slotWidth.toPx() }
    val slotHeightPx = with(density) { slotHeight.toPx() }
    val maxTranslation = with(density) { 72.dp.toPx() }
    val baseX = (screenSize.width - slotWidthPx) / 2f
    val baseY = (screenSize.height - slotHeightPx) / 2f
    val rotation = if (case == Scene2ProfilingCase.Rotation) -24f + 48f * motion else 0f
    val translation = if (case.movesSlotGeometry) {
        -maxTranslation + 2f * maxTranslation * motion
    } else {
        0f
    }

    ProfilingCard(
        case = case,
        shape = shape,
        contentProgress = contentProgress,
        modifier = Modifier
            .align(Alignment.TopStart)
            .graphicsLayer {
                translationX = baseX + translation
                translationY = baseY
                rotationZ = rotation
            }
            .slotModifier(case, shape)
            .size(width = slotWidth, height = slotHeight)
    )
}

@Composable
private fun BoxScope.CumulativeProfilingSlots(contentProgress: Float) {
    val shape = RoundedCornerShape(16.dp)
    ProfilingCard(
        case = Scene2ProfilingCase.SlotContent,
        shape = shape,
        contentProgress = contentProgress,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = (-54).dp, y = (-22).dp)
            .effectLayer {}
            .size(width = 220.dp, height = 136.dp)
    )
    ProfilingCard(
        case = Scene2ProfilingCase.Cumulative,
        shape = shape,
        contentProgress = 1f - contentProgress,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = 54.dp, y = 22.dp)
            .effectLayer {
                backdropBlur(sigmaPx = 24f)
                tint(Color(0xFFE0F7FA).copy(alpha = 0.22f))
                clip(shape, inset = ProfilingClipInset)
            }
            .size(width = 220.dp, height = 136.dp)
    )
}

private fun Modifier.slotModifier(
    case: Scene2ProfilingCase,
    shape: RoundedCornerShape
): Modifier = then(
    when (case) {
        Scene2ProfilingCase.RootOnly -> Modifier
        Scene2ProfilingCase.SlotContent -> Modifier.effectLayer {}
        Scene2ProfilingCase.BackdropBlur,
        Scene2ProfilingCase.StaticRootBackdropBlur,
        Scene2ProfilingCase.StaticRootFrozenSlotBlur,
        Scene2ProfilingCase.StaticRootGeometrySlotBlur,
        Scene2ProfilingCase.StaticRootSmallSlotBlur,
        Scene2ProfilingCase.StaticRootLargeSlotBlur -> Modifier.effectLayer {
            backdropBlur(sigmaPx = 24f)
        }
        Scene2ProfilingCase.AnimatedSizeSlotContent -> Modifier.effectLayer {}
        Scene2ProfilingCase.BlurTint -> Modifier.effectLayer {
            backdropBlur(sigmaPx = 24f)
            tint(Color(0xFFE0F7FA).copy(alpha = 0.22f))
        }
        Scene2ProfilingCase.BlurTintNoise -> Modifier.effectLayer {
            backdropBlur(sigmaPx = 24f)
            tint(Color(0xFFE0F7FA).copy(alpha = 0.22f))
            noise(alpha = ProfilingNoiseAlpha)
        }
        Scene2ProfilingCase.BlurTintNoiseClip -> Modifier.effectLayer {
            backdropBlur(sigmaPx = 24f)
            tint(Color(0xFFE0F7FA).copy(alpha = 0.22f))
            noise(alpha = ProfilingNoiseAlpha)
            clip(shape, inset = ProfilingClipInset)
        }
        Scene2ProfilingCase.ProgressiveMask -> Modifier.effectLayer {
            backdropBlur(sigmaPx = 24f, progressiveMask = ProfilingProgressiveMask)
            tint(Color(0xFFE0F7FA).copy(alpha = 0.22f))
            noise(alpha = ProfilingNoiseAlpha)
            clip(shape, inset = ProfilingClipInset)
        }
        Scene2ProfilingCase.Rotation -> Modifier.effectLayer {
            backdropBlur(sigmaPx = 24f)
            tint(Color(0xFFE0F7FA).copy(alpha = 0.22f))
            noise(alpha = ProfilingNoiseAlpha)
            clip(shape, inset = ProfilingClipInset)
        }
        Scene2ProfilingCase.Translation -> Modifier.effectLayer {
            backdropBlur(sigmaPx = 24f)
            tint(Color(0xFFE0F7FA).copy(alpha = 0.22f))
            noise(alpha = ProfilingNoiseAlpha)
            clip(shape, inset = ProfilingClipInset)
        }
        Scene2ProfilingCase.Cumulative,
        Scene2ProfilingCase.MaterialBottomSheetVisualBounds -> Modifier
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.MaterialBottomSheetVisualBoundsProfiling() {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val materialHeight = 360.dp
    val materialHeightPx = with(density) { materialHeight.toPx() }
    val expandedTopPx = with(density) { 72.dp.toPx() }
    val collapsedTopPx = with(density) { 420.dp.toPx() }
    var collapsed by remember { mutableStateOf(true) }
    val positionProgress by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = tween(350, easing = LinearEasing),
        label = "materialBottomSheetVisualBoundsPosition"
    )
    val visualTopPx = expandedTopPx + (collapsedTopPx - expandedTopPx) * positionProgress
    val visualBoundsProvider = remember(visualTopPx, materialHeightPx) {
        EffectLayerBoundsProvider { _, layoutSize ->
            val top = visualTopPx.coerceIn(0f, layoutSize.height.toFloat())
            Rect(
                left = 0f,
                top = top,
                right = layoutSize.width.toFloat(),
                bottom = (top + materialHeightPx).coerceAtMost(layoutSize.height.toFloat())
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = {},
        scrimColor = Color.Transparent,
        containerColor = Color.Transparent,
        dragHandle = null,
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clickable { collapsed = !collapsed }
                .effectLayer {
                    visualBounds(visualBoundsProvider)
                    backdropBlur(sigmaPx = 28f)
                    tint(Color(0xFFF6F3EF).copy(alpha = 0.38f))
                    noise(alpha = ProfilingNoiseAlpha)
                    clip(shape, inset = ProfilingClipInset, clipContent = true)
                }
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        translationY = visualTopPx
                    }
                    .fillMaxWidth()
                    .height(materialHeight),
                shape = shape,
                color = Color.White.copy(alpha = 0.46f),
                border = BorderStroke(1.dp, Color(0xFF202124).copy(alpha = 0.72f)),
                tonalElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "visual bounds",
                        color = Color(0xFF202124),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                    Text(
                        text = "standard Material bottom sheet",
                        color = Color(0xFF202124),
                        fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(width = 96.dp, height = 18.dp)
                            .background(Color(0xFF00C853), RoundedCornerShape(9.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfilingCard(
    case: Scene2ProfilingCase,
    shape: RoundedCornerShape,
    contentProgress: Float,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.White.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, Color(0xFF202124).copy(alpha = 0.72f)),
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(width = 18.dp, height = 76.dp)
                    .background(Color(0xFF1E88E5), RoundedCornerShape(4.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(Color(0xFFD32F2F), CircleShape)
            )
            Text(
                text = case.label,
                color = Color(0xFF202124),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(x = (-34).dp + 68.dp * contentProgress)
                    .size(width = 28.dp, height = 8.dp)
                    .background(Color(0xFF00C853), RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun BoxScope.RootUpdateMarker(
    screenSize: IntSize,
    progress: Float,
) {
    val density = LocalDensity.current
    val markerSize = with(density) { 18.dp.toPx() }
    val travel = (screenSize.width - markerSize).coerceAtLeast(0f)
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .graphicsLayer {
                translationX = travel * progress
                translationY = 18f
            }
            .size(18.dp)
            .background(Color(0xFFFFC107), CircleShape)
    )
}

@Composable
private fun BoxScope.ProfilingCaseLabel(case: Scene2ProfilingCase) {
    Text(
        text = "scene2 profile: ${case.label}  slot ${case.slotSizeLabel}",
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.64f), RoundedCornerShape(4.dp))
            .border(1.dp, Color.White.copy(alpha = 0.48f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun BoxScope.InvisibleRootTick(progress: Float) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .graphicsLayer {
                alpha = 0f
                translationX = progress
            }
            .size(1.dp)
    )
}

private val ProfilingClipInset = PaddingValues(1.dp)
private val ProfilingProgressiveMask = Brush.verticalGradient(
    0f to Color.Transparent,
    0.42f to Color.White,
    1f to Color.White
)
private const val ProfilingNoiseAlpha = 0.16f

private val Scene2ProfilingCase.animatesRoot: Boolean
    get() = when (this) {
        Scene2ProfilingCase.StaticRootBackdropBlur,
        Scene2ProfilingCase.StaticRootFrozenSlotBlur,
        Scene2ProfilingCase.StaticRootGeometrySlotBlur,
        Scene2ProfilingCase.StaticRootSmallSlotBlur,
        Scene2ProfilingCase.StaticRootLargeSlotBlur,
        Scene2ProfilingCase.AnimatedSizeSlotContent,
        Scene2ProfilingCase.MaterialBottomSheetVisualBounds -> false
        else -> true
    }

private val Scene2ProfilingCase.needsInvisibleRootTick: Boolean
    get() = this == Scene2ProfilingCase.StaticRootFrozenSlotBlur ||
            this == Scene2ProfilingCase.StaticRootLargeSlotBlur ||
            this == Scene2ProfilingCase.MaterialBottomSheetVisualBounds

private val Scene2ProfilingCase.movesSlotGeometry: Boolean
    get() = this == Scene2ProfilingCase.Translation ||
            this == Scene2ProfilingCase.StaticRootGeometrySlotBlur

private fun Scene2ProfilingCase.slotWidth(progress: Float) = when (this) {
        Scene2ProfilingCase.StaticRootSmallSlotBlur -> 110.dp
        Scene2ProfilingCase.StaticRootLargeSlotBlur -> 520.dp
        Scene2ProfilingCase.AnimatedSizeSlotContent -> (180f + 64f * progress).dp
        Scene2ProfilingCase.MaterialBottomSheetVisualBounds -> 360.dp
        else -> 220.dp
    }

private fun Scene2ProfilingCase.slotHeight(progress: Float) = when (this) {
        Scene2ProfilingCase.StaticRootSmallSlotBlur -> 68.dp
        Scene2ProfilingCase.StaticRootLargeSlotBlur -> 360.dp
        Scene2ProfilingCase.AnimatedSizeSlotContent -> (112f + 32f * progress).dp
        Scene2ProfilingCase.MaterialBottomSheetVisualBounds -> 360.dp
        else -> 136.dp
    }

private val Scene2ProfilingCase.slotSizeLabel: String
    get() = when (this) {
        Scene2ProfilingCase.AnimatedSizeSlotContent -> "180..244x112..144dp"
        Scene2ProfilingCase.MaterialBottomSheetVisualBounds -> "bottom-sheet visual rect"
        else -> "${slotWidth(0f).value.toInt()}x${slotHeight(0f).value.toInt()}dp"
    }

private fun Scene2ProfilingCase.slotContentProgress(progress: Float): Float {
    return when (this) {
        Scene2ProfilingCase.StaticRootFrozenSlotBlur,
        Scene2ProfilingCase.StaticRootGeometrySlotBlur,
        Scene2ProfilingCase.StaticRootLargeSlotBlur -> 0.5f
        else -> progress
    }
}
