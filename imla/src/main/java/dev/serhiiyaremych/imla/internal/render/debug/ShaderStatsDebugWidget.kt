/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.debug

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.imla.internal.render.stats.ShaderStats

/**
 * Debug composable widget that displays real-time shader statistics.
 *
 * Typically used in development/debug builds to visualize shader performance metrics.
 * Call this composable in your debug layout to visualize:
 * - FBO instances count
 * - Shader instances count
 * - Shader bind calls
 * - Uniform block binds
 * - Shader data uploads
 */
@Composable
public fun ShaderStatsDebugWidget(
    modifier: Modifier = Modifier,
    refreshIntervalMs: Long = 500,
) {
    var expanded by remember { mutableStateOf(false) }
    var fboInstances by remember { mutableIntStateOf(0) }
    var shaderInstances by remember { mutableIntStateOf(0) }
    var shaderBinds by remember { mutableIntStateOf(0) }
    var shaderBindUniformBlock by remember { mutableIntStateOf(0) }
    var shaderUploads by remember { mutableIntStateOf(0) }

    // Only update stats when expanded
    if (expanded) {
        LaunchedEffect(Unit) {
            var lastUpdateMs = 0L
            while (true) {
                withFrameMillis { frameTimeMs ->
                    // Throttle updates to avoid excessive recomposition
                    if (frameTimeMs - lastUpdateMs >= refreshIntervalMs) {
                        lastUpdateMs = frameTimeMs
                        // Read all values atomically to avoid tearing during renderer updates
                        val snapshot = ShaderStats.snapshot()
                        // Update UI state with consistent snapshot
                        fboInstances = snapshot.fboInstances
                        shaderInstances = snapshot.shaderInstances
                        shaderBinds = snapshot.shaderBinds
                        shaderBindUniformBlock = snapshot.shaderBindUniformBlock
                        shaderUploads = snapshot.shaderUploads
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.3f),
                shape = if (expanded) RoundedCornerShape(8.dp) else RoundedCornerShape(50),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { expanded = !expanded }
            .padding(if (expanded) 12.dp else 0.dp)
            .sizeIn(minWidth = 24.dp, minHeight = 24.dp)
            .animateContentSize(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (expanded) {
            Column {
                BasicText(
                    text = "Shader Stats",
                    style = TextStyle(
                        color = Color(0xFF00FF00),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                StatRow("FBO Instances", fboInstances)
                StatRow("Shader Instances", shaderInstances)
                StatRow("Shader Binds", shaderBinds)
                StatRow("Bind Uniform Block", shaderBindUniformBlock)
                StatRow("Shader Uploads", shaderUploads)
            }
        } else {
            // Collapsed state - show just an icon/indicator
            BasicText(
                text = "◈", // Diamond icon to indicate shader/graphics
                style = TextStyle(
                    color = Color(0xFF00FF00),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
    BasicText(
        text = "$label: $value",
        style = TextStyle(
            color = Color(0xFF00FF00),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        ),
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
