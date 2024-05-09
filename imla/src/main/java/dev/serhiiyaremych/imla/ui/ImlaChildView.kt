/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui

import android.view.Surface
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.uirenderer.UiLayerRenderer
import java.util.UUID

@Composable
public fun BlurBehindView(
    modifier: Modifier,
    uiLayerRenderer: UiLayerRenderer,
    content: @Composable BoxScope.(onOffsetChanged: (IntOffset) -> Unit) -> Unit
) {
    val contentBoundingBoxState = remember {
        mutableStateOf(Rect.Zero)
    }
    val id = remember {
        trace("BlurBehindView#id") { UUID.randomUUID().toString() }
    }
    Box(
        modifier = modifier.onPlaced { layoutCoordinates ->
            contentBoundingBoxState.value = layoutCoordinates.boundsInParent()
        }
    ) {
        val contentBoundingBox = contentBoundingBoxState.value
        val behindSurfaceState = remember {
            mutableStateOf<Surface?>(null)
        }
        AndroidEmbeddedExternalSurface(
            modifier = Modifier.matchParentSize(),
            surfaceSize = contentBoundingBox.size.toIntSize()
        ) {
            onSurface { surface, _, _ ->
                behindSurfaceState.value = surface

                surface.onChanged { _, _ ->
                    // todo
                }
                surface.onDestroyed {
                    behindSurfaceState.value = null
                }
            }
        }

        val surface = behindSurfaceState.value
        val renderObject by uiLayerRenderer.attachRenderSurfaceAsState(
            id = id,
            surface = surface,
            size = contentBoundingBox.size.toIntSize()
        )

        content { contentOffset ->
            renderObject?.updateOffset(contentOffset)
        }
    }
}