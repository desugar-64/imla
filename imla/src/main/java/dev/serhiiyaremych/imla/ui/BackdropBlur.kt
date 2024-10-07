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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.uirenderer.Style
import dev.serhiiyaremych.imla.uirenderer.UiLayerRenderer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.util.UUID

@Composable
public fun BackdropBlur(
    modifier: Modifier,
    style: Style = Style.default,
    uiLayerRenderer: UiLayerRenderer,
    blurMask: Brush? = null,
    clipShape: Shape = RectangleShape,
    content: @Composable BoxScope.(onOffsetChanged: (Offset) -> Unit) -> Unit = {}
) {
    val contentBoundingBoxState = remember { mutableStateOf(Rect.Zero) }
    val id = remember { trace("BlurBehindView#id") { UUID.randomUUID().toString() } }

    val drawingSurfaceState = remember { mutableStateOf<Surface?>(null) }
    val drawingSurfaceSizeState = remember { mutableStateOf(IntSize.Zero) }
    val contentOffset = remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { layoutCoordinates ->
                contentBoundingBoxState.value = layoutCoordinates.boundsInRoot()
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
                    uiLayerRenderer.detachRenderObject(id)
                    drawingSurfaceState.value = null
                }
            }
        }

        val isRendererInitialized by uiLayerRenderer.isInitialized

        val topOffset = Offset(
            x = contentBoundingBox.left,
            y = contentBoundingBox.top
        )
        LaunchedEffect(id, drawingSurfaceState.value, uiLayerRenderer, contentBoundingBox) {
            val rendererFlow = snapshotFlow { isRendererInitialized }
            val surfaceFlow = snapshotFlow { drawingSurfaceState.value }
            combine(rendererFlow, surfaceFlow) { a, b -> a to b }
                .filter { it.first && it.second != null }
                .distinctUntilChanged()
                .collect {
                    uiLayerRenderer.attachRendererSurface(
                        surface = it.second,
                        id = id,
                        size = drawingSurfaceSizeState.value,
                    )
                    uiLayerRenderer.updateOffset(id, topOffset + contentOffset.value)
                    uiLayerRenderer.updateStyle(id, style)
                    uiLayerRenderer.updateMask(id, blurMask)
                }
        }


        uiLayerRenderer.updateMask(id, blurMask)
        uiLayerRenderer.updateOffset(id, topOffset + contentOffset.value)
        trace("BackdropBlurView#renderObject.style") {
            uiLayerRenderer.updateStyle(id, style)
        }

        // Render the content and handle offset changes
        content { offset ->
            contentOffset.value = offset
        }
    }
}