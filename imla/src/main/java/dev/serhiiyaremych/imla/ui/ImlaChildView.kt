/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui

import android.view.Surface
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.uirenderer.Style
import dev.serhiiyaremych.imla.uirenderer.UiLayerRenderer
import java.util.UUID

@Composable
public fun BackdropBlurView(
    modifier: Modifier,
    style: Style = Style.default,
    uiLayerRenderer: UiLayerRenderer,
    clipShape: Shape = RectangleShape,
    content: @Composable BoxScope.(onOffsetChanged: (IntOffset) -> Unit) -> Unit = {}
) {
    val contentBoundingBoxState = remember { mutableStateOf(Rect.Zero) }
    val id = remember { trace("BlurBehindView#id") { UUID.randomUUID().toString() } }

    val drawingSurfaceState = remember { mutableStateOf<Surface?>(null) }
    val drawingSurfaceSizeState = remember { mutableStateOf<IntSize>(IntSize.Zero) }
    val contentOffset = remember { mutableStateOf(IntOffset.Zero) }

    val renderObject = uiLayerRenderer.attachRendererSurface(
        surface = drawingSurfaceState.value,
        id = id,
        size = drawingSurfaceSizeState.value
    )
    Box(
        modifier = modifier.onPlaced { layoutCoordinates ->
            contentBoundingBoxState.value = layoutCoordinates.boundsInParent()
        }
    ) {
        val contentBoundingBox = contentBoundingBoxState.value
        val clipPath = remember { Path() }
        // Render the external surface
        AndroidExternalSurface(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val outline = clipShape.createOutline(size, layoutDirection, this)
                    clipPath.rewind()
                    clipPath.addOutline(outline)
                    onDrawWithContent {
                        clipPath(path = clipPath) {
                            this@onDrawWithContent.drawContent()
                        }
                    }
                },
            surfaceSize = contentBoundingBox.size.toIntSize(),
            isOpaque = false
        ) {
            onSurface { surface, w, h ->
                Snapshot.withMutableSnapshot {
                    drawingSurfaceState.value = surface
                    drawingSurfaceSizeState.value = IntSize(w, h)
                }
                surface.onChanged { _, _ ->
                    // todo
                }
                surface.onDestroyed {
                    renderObject?.let { uiLayerRenderer.detachRenderObject(it) }
                    drawingSurfaceState.value = null
                }
            }
        }

        val topOffset = IntOffset(
            x = contentBoundingBox.left.toInt(),
            y = contentBoundingBox.top.toInt()
        )
        renderObject?.updateOffset(topOffset + contentOffset.value)
        trace("BackdropBlurView#renderObject.style") {
            renderObject?.style = style
        }

        // Render the content and handle offset changes
        content { offset ->
            contentOffset.value = offset
        }
    }
}