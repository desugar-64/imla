/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.ui

import android.view.Surface
import android.view.ViewTreeObserver
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntRect
import dev.serhiiyaremych.imla.uirenderer.UiEffectRenderer

@Composable
public fun RootBlurSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val assetManager = LocalContext.current.assets
    val uiEffectRenderer: UiEffectRenderer = remember(assetManager) { UiEffectRenderer(assetManager) }

    var rootGraphicsLayer: GraphicsLayer? = null
    val positionState: MutableState<IntRect?> = remember { mutableStateOf<IntRect?>(null) }
    val surfaceState: MutableState<Surface?> = remember { mutableStateOf<Surface?>(null) }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .onLayoutRectChanged { positionState.value = it.boundsInWindow }
                .drawWithCache {
                    val graphicsLayer = obtainGraphicsLayer()
                    rootGraphicsLayer = graphicsLayer
                    onDrawWithContent {
                        graphicsLayer.record {
                            this@onDrawWithContent.drawContent()
                        }
                    }
                }
        ) {
            content()
        }
        AndroidExternalSurface(Modifier.matchParentSize()) {
            onSurface { surface: Surface, width: Int, height: Int ->
                surfaceState.value = surface
                surface.onDestroyed {
                    // todo: clean up renderer
                }
            }
        }
    }

    val density = LocalDensity.current
    LaunchedEffect(surfaceState.value, uiEffectRenderer, positionState.value, rootGraphicsLayer, density) {
        val position = positionState.value
        val surface = surfaceState.value
        if (surface != null && position != null && rootGraphicsLayer != null) {
            uiEffectRenderer.setRootLayer(rootGraphicsLayer, position, density, surface)
        }
    }
    val currentView = LocalView.current
    DisposableEffect(currentView, uiEffectRenderer) {
        val onPreDrawCallback = ViewTreeObserver.OnPreDrawListener {
            uiEffectRenderer.onRootUpdated()
            true
        }
        currentView.viewTreeObserver.addOnPreDrawListener(onPreDrawCallback)
        onDispose {
            currentView.viewTreeObserver.removeOnPreDrawListener(onPreDrawCallback)
        }
    }
}