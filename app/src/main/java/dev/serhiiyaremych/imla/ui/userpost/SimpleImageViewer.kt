/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui.userpost

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size

@Composable
fun SimpleImageViewer(
    modifier: Modifier,
    imageUrl: String,
    onDismiss: () -> Unit
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
    AsyncImage(
        modifier = modifier.clickable(onClick = onDismiss),
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit
    )
}