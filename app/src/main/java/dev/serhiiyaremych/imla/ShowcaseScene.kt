/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.serhiiyaremych.imla.ImlaHost
import dev.serhiiyaremych.imla.effectGroup
import dev.serhiiyaremych.imla.effectLayer
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Single-surface effects showcase: every tile is one `Modifier.effectLayer { … }`
 * sampling vivid content over a light, muted calibration grid. Each tile carries its
 * own colour circles so the effect reads on its own and crops cleanly for the README.
 */
@Composable
internal fun ShowcaseScene(modifier: Modifier = Modifier) {
    val tileShape = remember { RoundedCornerShape(20.dp) }
    val progressiveMask = remember {
        // Transparent (mask 0) at the top keeps the backdrop crisp; White (mask 1) at the
        // bottom is fully blurred. The crisp top exercises the composite-time crisp blend,
        // so the sharp circle edge stays sharp instead of showing the downsampled prepare.
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,   // top third: fully crisp
                0.34f to Color.Transparent,
                0.66f to Color.White,        // middle third: crisp -> blurred
                1.0f to Color.White          // bottom third: fully blurred
            )
        )
    }

    ImlaHost(modifier = modifier, showMetricsOverlay = false) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .effectGroup()
        ) {
            ShowcaseGrid()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
            ) {
                Text(
                    text = "Imla effects",
                    color = ShowcaseColors.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LabeledTile(
                        caption = "Blur",
                        primary = ShowcaseColors.composeBlue,
                        secondary = ShowcaseColors.composeGreen,
                        modifier = Modifier
                            .size(TILE_SIZE)
                            .effectLayer {
                                backdropBlur(sigmaPx = 16f)
                                clip(tileShape)
                            }
                            .tileBorder(tileShape)
                    )
                    LabeledTile(
                        caption = "Tint",
                        primary = ShowcaseColors.composeGreen,
                        secondary = ShowcaseColors.composeBlue,
                        modifier = Modifier
                            .size(TILE_SIZE)
                            .effectLayer {
                                backdropBlur(sigmaPx = 14f)
                                tint(ShowcaseColors.tint)
                                clip(tileShape)
                            }
                            .tileBorder(tileShape)
                    )
                    LabeledTile(
                        caption = "Frosted noise",
                        primary = ShowcaseColors.composeBlue,
                        secondary = ShowcaseColors.composeForest,
                        modifier = Modifier
                            .size(TILE_SIZE)
                            .effectLayer {
                                backdropBlur(sigmaPx = 14f)
                                noise(alpha = 0.6f)
                                clip(tileShape)
                            }
                            .tileBorder(tileShape)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LabeledTile(
                        caption = "Progressive",
                        primary = ShowcaseColors.composeBlue,
                        secondary = ShowcaseColors.composeGreen,
                        modifier = Modifier
                            .size(TILE_SIZE)
                            .effectLayer {
                                backdropBlur(sigmaPx = 40f, progressiveMask = progressiveMask)
                                clip(tileShape)
                            }
                            .tileBorder(tileShape)
                    )
                    LabeledTile(
                        caption = "Shape mask",
                        primary = ShowcaseColors.composeGreen,
                        secondary = ShowcaseColors.composeBlue,
                        modifier = Modifier
                            .size(TILE_SIZE)
                            .effectLayer {
                                backdropBlur(sigmaPx = 16f)
                                noise(alpha = 0.25f)
                                clip(StarShape)
                            }
                    )
                    RotationTile(shape = tileShape)
                }

                CompositeTile(shape = tileShape)
            }
        }
    }
}

@Composable
private fun RotationTile(shape: Shape) {
    // Static 3-axis tilt: the showcase proves rotated geometry stays aligned, no animation.
    val cameraDistancePx = with(LocalDensity.current) { 14.dp.toPx() }

    LabeledTile(
        caption = "Rotation",
        primary = ShowcaseColors.composeGreen,
        secondary = ShowcaseColors.composeBlue,
        modifier = Modifier
            .size(TILE_SIZE)
            .graphicsLayer {
                rotationX = 26f
                rotationY = -34f
                rotationZ = 14f
                transformOrigin = TransformOrigin.Center
                cameraDistance = cameraDistancePx
            }
            .effectLayer {
                backdropBlur(sigmaPx = 18f)
                tint(ShowcaseColors.rotationTint)
                clip(shape)
            }
            .tileBorder(shape)
    ) {
        Text(
            text = "Imla",
            color = ShowcaseColors.cardText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CompositeTile(shape: Shape) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(TILE_SIZE * 3 + 28.dp)
                .height(TILE_SIZE + 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawCircle(ShowcaseColors.composeBlue, min(size.width, size.height) * 0.34f, Offset(size.width * 0.22f, size.height * 0.5f))
                drawCircle(ShowcaseColors.composeGreen, min(size.width, size.height) * 0.34f, Offset(size.width * 0.5f, size.height * 0.46f))
                drawCircle(ShowcaseColors.composeForest, min(size.width, size.height) * 0.34f, Offset(size.width * 0.78f, size.height * 0.54f))
            }
            // Bottom card heavily blurred, each card above progressively sharper so the
            // stack reads layer-by-layer through the see-through tints.
            CompositeCard(offsetX = (-72).dp, rotation = -10f, tint = ShowcaseColors.compositeA, z = 1f, sigma = 28f, shape = shape)
            CompositeCard(offsetX = 0.dp, rotation = 0f, tint = ShowcaseColors.compositeB, z = 2f, sigma = 15f, shape = shape)
            CompositeCard(offsetX = 72.dp, rotation = 10f, tint = ShowcaseColors.compositeC, z = 3f, sigma = 7f, shape = shape)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Composite (stacked blur)",
            color = ShowcaseColors.caption,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CompositeCard(offsetX: Dp, rotation: Float, tint: Color, z: Float, sigma: Float, shape: Shape) {
    Box(
        modifier = Modifier
            .zIndex(z)
            .offset(x = offsetX)
            .size(width = 180.dp, height = TILE_SIZE)
            .graphicsLayer {
                rotationZ = rotation
                transformOrigin = TransformOrigin.Center
            }
            .effectLayer {
                backdropBlur(sigmaPx = sigma)
                tint(tint)
                clip(shape)
            }
            .tileBorder(shape)
    )
}

@Composable
private fun LabeledTile(
    caption: String,
    primary: Color,
    modifier: Modifier,
    secondary: Color? = null,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            TileAccents(primary = primary, secondary = secondary)
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center,
                content = content
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = caption,
            color = ShowcaseColors.caption,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TileAccents(primary: Color, secondary: Color?) {
    Canvas(modifier = Modifier.size(TILE_SIZE)) {
        val unit = min(size.width, size.height)
        drawCircle(
            color = primary,
            radius = unit * 0.34f,
            center = Offset(size.width * 0.42f, size.height * 0.40f)
        )
        if (secondary != null) {
            drawCircle(
                color = secondary,
                radius = unit * 0.22f,
                center = Offset(size.width * 0.68f, size.height * 0.68f)
            )
        }
    }
}

@Composable
private fun ShowcaseGrid() {
    val density = LocalDensity.current
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = ShowcaseColors.base)

        // Subtle checkerboard so the light backdrop still has structure to soften.
        val cell = with(density) { 56.dp.toPx() }
        var row = 0
        var y = 0f
        while (y < size.height) {
            var col = 0
            var x = 0f
            while (x < size.width) {
                if ((row + col) % 2 == 0) {
                    drawRect(
                        color = ShowcaseColors.checker,
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(cell, cell)
                    )
                }
                x += cell
                col++
            }
            y += cell
            row++
        }

        // Calibration crosshairs at cell intersections.
        val arm = with(density) { 4.dp.toPx() }
        val stroke = with(density) { 1.dp.toPx() }
        var gy = cell
        while (gy < size.height) {
            var gx = cell
            while (gx < size.width) {
                drawLine(
                    color = ShowcaseColors.crosshair,
                    start = Offset(gx - arm, gy),
                    end = Offset(gx + arm, gy),
                    strokeWidth = stroke
                )
                drawLine(
                    color = ShowcaseColors.crosshair,
                    start = Offset(gx, gy - arm),
                    end = Offset(gx, gy + arm),
                    strokeWidth = stroke
                )
                gx += cell
            }
            gy += cell
        }
    }
}

private fun Modifier.tileBorder(shape: Shape): Modifier =
    border(width = 1.dp, color = ShowcaseColors.tileBorder, shape = shape)

private val StarShape: Shape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outer = min(cx, cy)
    val inner = outer * 0.46f
    val points = 5
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) outer else inner
        val angle = (-Math.PI / 2 + i * Math.PI / points).toFloat()
        val px = cx + radius * cos(angle)
        val py = cy + radius * sin(angle)
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

private object ShowcaseColors {
    // Solarized-light grid: warm cream base with a slightly deeper cream checker.
    val base: Color = Color(0xFFFDF6E3) // base3
    val checker: Color = Color(0xFFEEE8D5) // base2
    val crosshair: Color = Color(0xFF93A1A1).copy(alpha = 0.55f) // base1
    val title: Color = Color(0xFF586E75) // base01
    val caption: Color = Color(0xFF657B83) // base00
    val cardText: Color = Color(0xFF073642) // base02
    val tileBorder: Color = Color(0xFF586E75).copy(alpha = 0.28f)
    val tint: Color = Color(0xFFD33682).copy(alpha = 0.5f) // magenta — heavy, this is the tint demo
    val rotationTint: Color = Color(0xFF2AA198).copy(alpha = 0.16f) // cyan — mild
    val compositeA: Color = Color(0xFF268BD2).copy(alpha = 0.18f) // blue — see-through
    val compositeB: Color = Color(0xFFD33682).copy(alpha = 0.18f) // magenta — see-through
    val compositeC: Color = Color(0xFF859900).copy(alpha = 0.18f) // green — see-through

    // Background circles in the official Jetpack Compose logo palette, lightened ~20%.
    val composeBlue: Color = Color(0xFF6B5EF9)
    val composeGreen: Color = Color(0xFF9BC367)
    val composeForest: Color = Color(0xFF4E9E61)
}

private val TILE_SIZE: Dp = 150.dp
