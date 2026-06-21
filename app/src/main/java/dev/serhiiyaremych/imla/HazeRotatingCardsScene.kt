/*
 * Copyright 2026, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 *
 * Reference scene for the backdrop-blur cost experiment. Mirrors
 * MainActivity.RotatingCardsScene (9x 110dp 3D-rotating tiles over an animated
 * blob field) but renders the backdrop blur with Haze (RenderEffect path on
 * API 31+) instead of the Imla GL pipeline. Used to measure the platform
 * backdrop-blur floor on the same device/workload.
 */

package dev.serhiiyaremych.imla

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val vibrantTints = listOf(
    Color(0xFFFF6B6B),
    Color(0xFF4ECDC4),
    Color(0xFF45B7D1),
    Color(0xFF96CEB4),
    Color(0xFFFECA57),
    Color(0xFFFF9FF3),
    Color(0xFF54A0FF),
    Color(0xFF48DBFB),
    Color(0xFFFD79A8),
)

@Composable
fun HazeRotatingCardsScene(modifier: Modifier = Modifier) {
    val hazeState = rememberHazeState()
    val transition = rememberInfiniteTransition(label = "blobs")
    val blobProgress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "blob-progress",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState),
        ) {
            drawRect(Color(0xFF0D0830))
            val w = size.width
            val h = size.height
            val t = blobProgress.value * 2f * kotlin.math.PI.toFloat()
            fun blob(color: Color, cx: Float, cy: Float, r: Float) {
                val off = androidx.compose.ui.geometry.Offset(cx, cy)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color, Color.Transparent),
                        center = off,
                        radius = r,
                    ),
                    radius = r,
                    center = off,
                    blendMode = androidx.compose.ui.graphics.BlendMode.Screen,
                )
            }
            blob(Color(0xFFFF0033), w * 0.18f + kotlin.math.sin(t) * w * 0.22f, h * 0.20f + kotlin.math.cos(2f * t) * h * 0.14f, w * 0.72f)
            blob(Color(0xFF00EEDD), w * 0.80f + kotlin.math.cos(t) * w * 0.18f, h * 0.36f + kotlin.math.sin(2f * t) * h * 0.16f, w * 0.66f)
            blob(Color(0xFFFFCC00), w * 0.50f + kotlin.math.cos(2f * t) * w * 0.28f, h * 0.70f + kotlin.math.sin(t) * h * 0.16f, w * 0.64f)
            blob(Color(0xFFDD00FF), w * 0.26f + kotlin.math.sin(3f * t) * w * 0.24f, h * 0.80f + kotlin.math.cos(t) * h * 0.14f, w * 0.62f)
            blob(Color(0xFFFF6600), w * 0.88f + kotlin.math.sin(2f * t) * w * 0.10f, h * 0.12f + kotlin.math.cos(3f * t) * h * 0.10f, w * 0.40f)
            blob(Color(0xFF00AAFF), w * 0.10f + kotlin.math.cos(2f * t) * w * 0.08f, h * 0.55f + kotlin.math.sin(3f * t) * h * 0.12f, w * 0.38f)
            blob(Color(0xFF00FF66), w * 0.65f + kotlin.math.sin(t) * w * 0.14f, h * 0.90f + kotlin.math.cos(2f * t) * h * 0.08f, w * 0.36f)
            blob(Color(0xFFFF007F), w * 0.42f + kotlin.math.cos(3f * t) * w * 0.12f, h * 0.45f + kotlin.math.sin(2f * t) * h * 0.10f, w * 0.32f)
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.9f)
                .aspectRatio(1f),
        ) {
            val tileSize = 110.dp
            HazeRotating3dTile(hazeState, tileSize, Modifier.align(Alignment.TopStart).zIndex(1f), 1000, 1f, vibrantTints[0])
            HazeRotating3dTile(hazeState, tileSize, Modifier.align(Alignment.TopCenter).zIndex(2f), 2000, 2f, vibrantTints[1])
            HazeRotating3dTile(hazeState, tileSize, Modifier.align(Alignment.TopEnd).zIndex(3f), 3000, 3f, vibrantTints[2])
            HazeRotating3dTile(hazeState, tileSize, Modifier.align(Alignment.CenterStart).zIndex(4f), 4000, 4f, vibrantTints[3])
            HazeRotating3dTile(hazeState, tileSize, Modifier.align(Alignment.Center).zIndex(5f), 5000, 5f, vibrantTints[4])
            HazeRotating3dTile(hazeState, tileSize, Modifier.align(Alignment.CenterEnd).zIndex(6f), 6000, 6f, vibrantTints[5])
            HazeRotating3dTile(hazeState, tileSize, Modifier.align(Alignment.BottomStart).zIndex(7f), 7000, 7f, vibrantTints[6])
            HazeRotating3dTile(hazeState, tileSize, Modifier.align(Alignment.BottomCenter).zIndex(8f), 8000, 8f, vibrantTints[7])
            HazeRotating3dTile(hazeState, tileSize, Modifier.align(Alignment.BottomEnd).zIndex(9f), 9000, 9f, vibrantTints[8])
        }
    }
}

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun HazeRotating3dTile(
    hazeState: HazeState,
    size: Dp,
    modifier: Modifier = Modifier,
    startDelay: Int,
    zIndex: Float,
    tint: Color,
) {
    val transition = rememberInfiniteTransition(label = "3d-blur-rotation")
    val rotationX = transition.animateFloat(
        initialValue = -180f, targetValue = 180f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, delayMillis = startDelay, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rx",
    )
    val rotationY = transition.animateFloat(
        initialValue = -180f, targetValue = 180f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11000, delayMillis = startDelay / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ry",
    )
    val rotationZ = transition.animateFloat(
        initialValue = -180f, targetValue = 180f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 17000, delayMillis = startDelay / 3, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rz",
    )
    val density = LocalDensity.current
    val cameraDistancePx = with(density) { 10.dp.toPx() }
    val shape = remember { RoundedCornerShape(22.dp) }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                this.rotationX = rotationX.value
                this.rotationY = rotationY.value
                this.rotationZ = rotationZ.value
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                cameraDistance = cameraDistancePx
            }
            .clip(shape)
            .hazeEffect(hazeState) {
                blurRadius = 24.dp
                noiseFactor = 0.2f
                tints = listOf(HazeTint(tint.copy(alpha = 0.05f)))
                inputScale = HazeInputScale.Auto
            }
            .border(1.dp, Color.Cyan.copy(alpha = 0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Haze",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = "X/Y/Z rotation",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
            )
        }
    }
}
