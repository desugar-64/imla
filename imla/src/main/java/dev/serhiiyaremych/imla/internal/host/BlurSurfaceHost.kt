/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.host

import android.view.Surface
import android.view.ViewTreeObserver
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.serhiiyaremych.imla.internal.ext.logd
import dev.serhiiyaremych.imla.internal.ext.threadTag
import dev.serhiiyaremych.imla.internal.legacy.ImlaSceneRenderer
import dev.serhiiyaremych.imla.internal.legacy.scene.LocalImlaSceneCoordinator
import dev.serhiiyaremych.imla.internal.legacy.scene.LocalImlaSceneRenderer

/**
 * Hosts a scene-only renderer for one Compose scene.
 *
 * The caller creates and remembers [renderer], and this host owns only the visible surface
 * lifetime for that renderer. The content subtree receives renderer scope through internal locals;
 * source and blur modifiers should keep using modifier APIs instead of threading the renderer
 * through child composables.
 */
@Composable
internal fun ImlaSceneHost(
    modifier: Modifier = Modifier,
    renderer: ImlaSceneRenderer,
    content: @Composable () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    LaunchedEffect(renderer) {
        renderer.setSceneVisible(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                view.isAttachedToWindow &&
                view.hasWindowFocus()
        )
    }

    DisposableEffect(renderer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                renderer.setSceneVisible(false)
            } else if (event == Lifecycle.Event.ON_START) {
                renderer.setSceneVisible(
                    view.isAttachedToWindow && view.hasWindowFocus()
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(renderer) {
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            renderer.setSceneVisible(
                hasFocus && view.isAttachedToWindow &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            )
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener) }
    }

    CompositionLocalProvider(
        LocalImlaSceneRenderer provides renderer,
        LocalImlaSceneCoordinator provides renderer.sceneCoordinator
    ) {
        RootSurfaceHost(
            modifier = modifier,
            rendererKey = renderer,
            onAttachSurface = renderer::attachRootSurface,
            onDetachSurface = renderer::detachRootSurface,
            content = content
        )
    }
}

@Composable
private fun RootSurfaceHost(
    modifier: Modifier,
    rendererKey: Any,
    onAttachSurface: (Surface?, IntSize) -> Unit,
    onDetachSurface: () -> Unit,
    content: @Composable () -> Unit
) {
    val surfaceSize = remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier) {
        Box(modifier = Modifier.matchParentSize()) {
            content()
        }

        AndroidExternalSurface(
            modifier = Modifier
                .matchParentSize()
                .onGloballyPositioned { coords ->
                    surfaceSize.value = coords.size
                },
            surfaceSize = surfaceSize.value,
        ) {
            onSurface { surface, width, height ->
                val size = IntSize(width, height)
                logd("BlurSurfaceHost", "attachRootSurface size=$size ${threadTag()}")
                onAttachSurface(surface, size)
                surface.onChanged { newWidth, newHeight ->
                    if (newWidth > 0 && newHeight > 0) {
                        val newSize = IntSize(newWidth, newHeight)
                        logd("BlurSurfaceHost", "re-attachRootSurface size=$newSize ${threadTag()}")
                        onAttachSurface(surface, newSize)
                    }
                }
                surface.onDestroyed { onDetachSurface() }
            }
        }

        DisposableEffect(rendererKey) {
            onDispose {
                onDetachSurface()
            }
        }
    }
}
