/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.imla.ImlaHost
import dev.serhiiyaremych.imla.effectGroup
import dev.serhiiyaremych.imla.effectLayer
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

/**
 * Single-card scene that continuously expands and collapses a backdrop-blur card by changing its
 * physical layout height (not a scale transform). Additional content is shown/hidden in the
 * expanded state so the captured slot content also reflows. Used to profile capture re-record and
 * FBO/sample-region churn during bounds resize.
 */
@Composable
internal fun ResizeCardScene(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_400)
            expanded = !expanded
        }
    }
    val cardHeight by animateDpAsState(
        targetValue = if (expanded) 380.dp else 132.dp,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "resizeCardHeight"
    )

    ImlaHost(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .effectGroup()
                .drawResizeBackground()
        ) {
            val shape = RoundedCornerShape(28.dp)
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .height(cardHeight)
                    .effectLayer {
                        backdropBlur(radius = 24.dp)
                        tint(Color.White.copy(alpha = 0.20f))
                        clip(shape, clipContent = true)
                    },
                shape = shape,
                color = Color.White.copy(alpha = 0.30f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    Text(
                        text = "Resize card",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (expanded) "expanded" else "collapsed",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 16.sp
                    )
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn(tween(400)) + expandVertically(tween(700)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(700))
                    ) {
                        Column {
                            Spacer(Modifier.height(16.dp))
                            repeat(4) { index ->
                                ResizeContentRow(index)
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResizeContentRow(index: Int) {
    val accent = remember(index) {
        listOf(
            Color(0xFFFF6B6B),
            Color(0xFF4ECDC4),
            Color(0xFFFECA57),
            Color(0xFF54A0FF)
        )[index % 4]
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .height(36.dp)
                .fillMaxWidth(0.18f)
                .background(accent, RoundedCornerShape(8.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(28.dp)
                .background(Color.White.copy(alpha = 0.6f), CircleShape)
        )
    }
}

private fun Modifier.drawResizeBackground(): Modifier = drawBehind {
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFF1A2980),
                Color(0xFF26D0CE),
                Color(0xFFFF6B6B)
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
    )
    val cell = size.width / 7f
    if (cell <= 0f) return@drawBehind
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    for (x in 0 until cols) {
        for (y in 0 until rows) {
            if ((x + y) % 2 == 0) {
                drawRect(
                    color = Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(x * cell, y * cell),
                    size = Size(cell, cell)
                )
            }
        }
    }
}
