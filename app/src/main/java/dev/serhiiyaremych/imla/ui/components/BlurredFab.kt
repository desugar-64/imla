/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.imla.DemoEffectStyle
import dev.serhiiyaremych.imla.effectLayer

private val FabSize = 56.dp

/**
 * A floating action button with a frosted glass blur backdrop effect.
 *
 * @param onClick Callback when the FAB is clicked
 * @param modifier Optional modifier for positioning and layout
 * @param icon The icon to display (default: Create icon)
 * @param contentDescription Accessibility description for the icon
 * @param size Size of the FAB (default: 56.dp)
 *
 * Note: the blur effect uses gaussian blur, with `sigma` controlling radius.
 */
@Composable
fun BlurredFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Search,
    contentDescription: String = "Search",
    size: Dp = FabSize
) {
    val blurStyle = remember {
        DemoEffectStyle.default.copy(
            sigma = 10f,
            noiseAlpha = 0.1f,
            blurOpacity = 1.0f
        )
    }

    val glassyRimGradient = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.White.copy(alpha = 0.3f),
                1.0f to Color.White.copy(alpha = 0.1f)
            )
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .fabBackdropBlur(
                style = blurStyle,
                clipShape = CircleShape,
                zIndex = 15f
            )
            .border(
                width = 0.5.dp,
                brush = glassyRimGradient,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(size)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun Modifier.fabBackdropBlur(
    style: DemoEffectStyle,
    clipShape: androidx.compose.ui.graphics.Shape,
    zIndex: Float
): Modifier = effectLayer(
    style = style,
    clipShape = clipShape,
    zIndex = zIndex
)
