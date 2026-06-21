/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.imla.DemoEffectStyle
import dev.serhiiyaremych.imla.effectLayer
import dev.serhiiyaremych.imla.internal.render.stats.ShaderStats

/**
 * Debug stats dropdown menu with frosted glass aesthetic.
 *
 * Displays shader statistics (FBO instances, shader instances, etc.) in a floating
 * dropdown menu with blur effect. Positioned at top-right of screen.
 * Auto-dismisses when clicked on the panel itself.
 *
 * @param isOpen Whether the dropdown is currently visible
 * @param onDismiss Callback when dropdown should close
 * @param refreshIntervalMs How often to update stats (default 500ms)
 */
@Composable
fun DebugStatsDropdown(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    refreshIntervalMs: Long = 500
) {
    if (!isOpen) return

    val fboCount = remember { mutableStateOf(0) }
    val shaderCount = remember { mutableStateOf(0) }
    val shaderBinds = remember { mutableStateOf(0) }
    val bindUniformBlocks = remember { mutableStateOf(0) }
    val shaderUploads = remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        var lastUpdateMs = 0L
        while (true) {
            withFrameMillis { frameTimeMs ->
                if (frameTimeMs - lastUpdateMs >= refreshIntervalMs) {
                    val snapshot = ShaderStats.snapshot()
                    fboCount.value = snapshot.fboInstances
                    shaderCount.value = snapshot.shaderInstances
                    shaderBinds.value = snapshot.shaderBinds
                    bindUniformBlocks.value = snapshot.shaderBindUniformBlock
                    shaderUploads.value = snapshot.shaderUploads
                    lastUpdateMs = frameTimeMs
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 16.dp, top = 80.dp)
    ) {
        AnimatedVisibility(
            visible = isOpen,
            enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            DebugStatsContent(
                fboCount = fboCount.value,
                shaderCount = shaderCount.value,
                shaderBinds = shaderBinds.value,
                bindUniformBlocks = bindUniformBlocks.value,
                shaderUploads = shaderUploads.value,
                onClickContent = onDismiss
            )
        }
    }
}

@Composable
private fun DebugStatsContent(
    fboCount: Int,
    shaderCount: Int,
    shaderBinds: Int,
    bindUniformBlocks: Int,
    shaderUploads: Int,
    onClickContent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val blurStyle = remember {
        DemoEffectStyle.default.copy(
            sigma = 10f,
            tint = Color.White.copy(alpha = 0.5f),
            noiseAlpha = 0.15f,
            blurOpacity = 1f
        )
    }

    val glassyRimGradient = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color(0xFF87CEEB).copy(alpha = 0.4f),
                1.0f to Color(0xFF87CEEB).copy(alpha = 0.15f)
            )
        )
    }
    val shape = remember { RoundedCornerShape(12.dp) }

    Box(
        modifier = modifier
            .debugStatsBackdropBlur(
                style = blurStyle,
                clipShape = shape,
                zIndex = 50f
            )
            .border(
                width = 0.5.dp,
                brush = glassyRimGradient,
                shape = shape
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClickContent() }
            .wrapContentHeight()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Shader Stats",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            StatRow("FBO Instances", fboCount.toString())
            StatRow("Shader Instances", shaderCount.toString())
            StatRow("Shader Binds", shaderBinds.toString())
            StatRow("Bind Uniform Block", bindUniformBlocks.toString())
            StatRow("Shader Uploads", shaderUploads.toString())
        }
    }
}

private fun Modifier.debugStatsBackdropBlur(
    style: DemoEffectStyle,
    clipShape: RoundedCornerShape,
    zIndex: Float
): Modifier = effectLayer(
    style = style,
    clipShape = clipShape,
    zIndex = zIndex
)

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(140.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}
