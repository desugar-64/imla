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
import androidx.compose.runtime.DisposableEffect
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
import dev.serhiiyaremych.imla.uirenderer.Style
import dev.serhiiyaremych.imla.uirenderer.UiLayerRenderer
import java.util.UUID

@Composable
public fun BackdropBlurView(
    modifier: Modifier,
    style: Style,
    uiLayerRenderer: UiLayerRenderer,
    content: @Composable BoxScope.(onOffsetChanged: (IntOffset) -> Unit) -> Unit
) {
    val contentBoundingBoxState = remember { mutableStateOf(Rect.Zero) }
    val id = remember { trace("BlurBehindView#id") { UUID.randomUUID().toString() } }

    val behindSurfaceState = remember { mutableStateOf<Surface?>(null) }
    val contentOffset = remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = modifier.onPlaced { layoutCoordinates ->
            contentBoundingBoxState.value = layoutCoordinates.boundsInParent()
        }
    ) {
        val contentBoundingBox = contentBoundingBoxState.value

        // Render the external surface
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

        // Attach the render surface and update the offset
        val surface = behindSurfaceState.value
        val renderObject by uiLayerRenderer.attachRenderSurfaceAsState(
            id = id,
            surface = surface,
            size = contentBoundingBox.size.toIntSize(),
            style = style
        )
        val topOffset = IntOffset(
            x = contentBoundingBox.left.toInt(),
            y = contentBoundingBox.top.toInt()
        )
        renderObject?.updateOffset(topOffset + contentOffset.value)

        // Render the content and handle offset changes
        content { offset ->
            contentOffset.value = offset
        }

        // Detach the render object when the composable is disposed
        DisposableEffect(Unit) {
            onDispose {
                uiLayerRenderer.detachRenderObject(renderObject)
            }
        }
    }
}