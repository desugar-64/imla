/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.serhiiyaremych.imla.BuildConfig
import dev.serhiiyaremych.imla.DemoEffectStyle
import dev.serhiiyaremych.imla.effectLayer

private val TopBarHeight = 64.dp

@Composable
fun BlurredTopBar(
    modifier: Modifier = Modifier,
    avatarUrl: String = "file:///android_asset/avatars/1.jpg",
    title: String = "Home",
    onSettingsClick: () -> Unit = {},
    debugMenuOpen: Boolean = false,
    onDebugMenuToggle: () -> Unit = {}
) {
    val density = LocalDensity.current
    val topBarHeightPx = with(density) { TopBarHeight.toPx() }
    val statusBarHeightPx = with(density) {
        WindowInsets.statusBars.getTop(this).toFloat()
    }
    val totalHeightPx = topBarHeightPx + statusBarHeightPx

    val gradientMask = remember(totalHeightPx) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.White,
                0.66f to Color.White,
                0.75f to Color.White.copy(alpha = 0.5f),
                0.83f to Color.White.copy(alpha = 0.3f),
                0.91f to Color.White.copy(alpha = 0.15f),
                0.96f to Color.White.copy(alpha = 0.05f),
                1.0f to Color.Transparent
            ),
            startY = 0f,
            endY = totalHeightPx
        )
    }

    val blurStyle = remember {
        DemoEffectStyle.default.copy(
            sigma = 10f,
            tint = Color.White.copy(alpha = 0.1f),
            noiseAlpha = 0.15f,
            blurOpacity = 1f
        )
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TopBarHeight + statusBarPadding.calculateTopPadding())
            .topBarBackdropBlur(
                style = blurStyle,
                blurMask = gradientMask,
                zIndex = 10f
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(TopBarHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = "Profile",
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.LightGray),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.weight(1f))

            if (BuildConfig.DEBUG) {
                IconButton(
                    onClick = onDebugMenuToggle
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Debug Stats",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun Modifier.topBarBackdropBlur(
    style: DemoEffectStyle,
    blurMask: Brush,
    zIndex: Float
): Modifier = effectLayer(
    style = style,
    blurMask = blurMask,
    zIndex = zIndex
)
