/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui.userpost

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size

@Composable
fun SimpleImageViewer(
    modifier: Modifier,
    imageUrl: String,
    onDismiss: () -> Unit,
    isVisible: Boolean = true
) {
    BackHandler(true) {
        onDismiss()
    }
    val context = LocalContext.current
    val request = remember(imageUrl, context) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(Size.ORIGINAL)
            .crossfade(true)
            .build()
    }

    // Animatable for image alpha - starts at 0, animates to 1 when visible
    val imageAlpha = remember { Animatable(0f) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            imageAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 300)
            )
        } else {
            imageAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 300)
            )
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    AsyncImage(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = imageAlpha.value }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onDismiss
            ),
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit
    )
}
