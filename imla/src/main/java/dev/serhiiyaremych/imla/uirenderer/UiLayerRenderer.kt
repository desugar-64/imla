/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package dev.serhiiyaremych.imla.uirenderer

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.trace
import androidx.graphics.opengl.GLRenderer
import androidx.graphics.opengl.egl.EGLManager
import dev.serhiiyaremych.imla.BuildConfig
import dev.serhiiyaremych.imla.ext.logw
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Renderer2D
import java.util.concurrent.atomic.AtomicBoolean

internal class UiRendererObserver(
    val uiLayerRenderer: UiLayerRenderer
) : RememberObserver {
    override fun onAbandoned() {
        uiLayerRenderer.destroy()
    }

    override fun onForgotten() {
        uiLayerRenderer.destroy()
    }

    override fun onRemembered() {
        // no-op
    }
}

@Composable
public fun rememberUiLayerRenderer(downSampleFactor: Int = 2): UiLayerRenderer {
    val density = LocalDensity.current
    val graphicsLayer = rememberGraphicsLayer()
    val assetManager = LocalContext.current.assets
    return remember(density, graphicsLayer, assetManager, downSampleFactor) {
        UiRendererObserver(
            UiLayerRenderer(
                density,
                graphicsLayer,
                downSampleFactor,
                assetManager
            )
        ).uiLayerRenderer
    }
}

@Stable
public class UiLayerRenderer(
    density: Density,
    graphicsLayer: GraphicsLayer,
    downSampleFactor: Int,
    private val assetManager: AssetManager
) : Density by density {
    private val renderer2D: Renderer2D = Renderer2D()
    private val glRenderer: GLRenderer = GLRenderer().apply {
        start("GLUiLayerRenderer")
    }

    private val renderingPipeline: RenderingPipeline =
        RenderingPipeline(assetManager, renderer2D, this)

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    internal val renderableLayer: RenderableRootLayer = RenderableRootLayer(
        layerDownsampleFactor = downSampleFactor,
        density = density,
        graphicsLayer = graphicsLayer,
        renderer2D = renderer2D,
        onLayerTextureUpdated = {
            // graphics layer texture updated, request pipeline render
            renderingPipeline.requestRender {
                isRendering.set(false)
            }
        }
    )

    private val isGLInitialized = AtomicBoolean(false)

    public val isInitialized: MutableState<Boolean> = mutableStateOf(false)

    private lateinit var mainRenderTarget: GLRenderer.RenderTarget

    private val mainDrawCallback = object : GLRenderer.RenderCallback {
        override fun onDrawFrame(eglManager: EGLManager) {
            // TODO: implement FPS counter
            if (isRendering.compareAndSet(false, true)) {
//                renderableLayer.updateTex()
            }
        }
    }

    private val isRendering: AtomicBoolean = AtomicBoolean(false)
    private val isRecording: AtomicBoolean = AtomicBoolean(false)

    internal val isRecorded: Boolean get() = renderableLayer.isReady.not()

    init {
        initializeIfNeed()
    }

    private fun initializeIfNeed() {
        if (renderableLayer.sizeInt == IntSize.Zero) {
            Log.w("UiLayerRenderer", "Warn: GraphicsLayer is empty.")
            return
        }
        if (!isGLInitialized.get()) {
            glRenderer.execute {
                RenderCommand.init()
                renderer2D.init(assetManager)

                renderableLayer.initialize()
                mainRenderTarget = glRenderer.createRenderTarget(
                    width = renderableLayer.sizeInt.width,
                    height = renderableLayer.sizeInt.height,
                    renderer = mainDrawCallback
                )
                if (isGLInitialized.compareAndSet(false, true)) {
                    Snapshot.withMutableSnapshot {
                        isInitialized.value = true
                    }
                }
            }
        }
    }

    context(DrawScope)
    public fun recordCanvas(block: DrawScope.() -> Unit): Unit =
        trace("UiLayerRenderer#recordCanvas") {
        if (isRendering.get() || !isRecording.compareAndSet(false, true)) {
            // Rendering is in progress or recording is already in progress, skip recording
            logw(TAG, "skipping recordCanvas during rendering")
            return
        }
        try {
            trace("recordCanvas") {
                if (BuildConfig.DEBUG) {
                    require(renderableLayer.graphicsLayer.isReleased.not())
                }
                renderableLayer.graphicsLayer.record(block = block)
            }
        } finally {
            isRecording.set(false)
        }
    }

    @MainThread
    public fun onUiLayerUpdated(): Unit = trace("UiLayerRenderer#onUiLayerUpdated") {
        initializeIfNeed()
        if (isRecording.get()) {
            logw(TAG, "skipping onUiLayerUpdated during recording")
            return
        }

        if (isGLInitialized.get()) {
//            mainRenderTarget.requestRender()
            renderableLayer.updateTex()
        } else {
            isRendering.set(false)
            glRenderer.execute {
                if (!renderableLayer.isReady && isGLInitialized.get()) {
//                    mainRenderTarget.requestRender()
                    mainThreadHandler.post { renderableLayer.updateTex() }
                }
            }
        }
    }

    private fun attachSurface(
        surface: Surface,
        id: String,
        size: IntSize
    ): RenderObject {
        val existingRenderObject = renderingPipeline.getRenderObject(id)
        if (existingRenderObject != null) {
            return existingRenderObject
        }
        val renderObject = RenderObject.createFromSurface(
            id = id,
            renderableLayer = renderableLayer,
            glRenderer = glRenderer,
            surface = surface,
            rect = Rect(offset = Offset.Zero, size.toSize()),
        )
        renderingPipeline.addRenderObject(renderObject)
        return renderObject
    }

    internal fun attachRendererSurface(
        surface: Surface?,
        id: String,
        size: IntSize
    ): String? {
        if (surface == null || isGLInitialized.get().not() || size == IntSize.Zero) return null
        return attachSurface(surface, id, size).id
    }

    @Composable
    internal fun attachRenderSurfaceAsState(
        id: String,
        surface: Surface?,
        size: IntSize
    ): State<RenderObject?> {
        return produceState<RenderObject?>(
            initialValue = null,
            isInitialized.value,
            id,
            renderableLayer,
            surface,
            size
        ) {
            val existingRo = renderingPipeline.getRenderObject(id)
            val ro = when {
                existingRo != null -> existingRo
                surface == null || size == IntSize.Zero || !isGLInitialized.get() -> null
                else -> {
                    val renderObject = RenderObject.createFromSurface(
                        id = id,
                        renderableLayer = renderableLayer,
                        glRenderer = glRenderer,
                        surface = surface,
                        rect = Rect(offset = Offset.Zero, size.toSize()),
                    )
                    renderingPipeline.addRenderObject(renderObject)
                    renderObject
                }
            }
            value = ro
        }
    }

    internal fun detachRenderObject(renderObjectId: String?): Unit = with(renderingPipeline) {
        getRenderObject(renderObjectId)?.detachFromRenderer()
        removeRenderObject(renderObjectId)
    }

    public fun destroy() {
        if (isInitialized.value) {
            isRendering.set(false)
            isRecording.set(false)
            isInitialized.value = false
            glRenderer.stop(true)
            renderableLayer.destroy()
            renderingPipeline.destroy()
        }
    }

    internal fun updateOffset(renderObjectId: String?, offset: IntOffset) {
        renderingPipeline
            .getRenderObject(renderObjectId)
            ?.updateOffset(offset = offset)
    }

    internal fun updateStyle(renderObjectId: String?, style: Style) {
        renderingPipeline.getRenderObject(renderObjectId)?.style = style
    }

    internal fun updateMask(renderObjectId: String?, brush: Brush?) {
        renderingPipeline.updateMask(glRenderer, renderObjectId, brush)
    }

    internal companion object {
        private const val TAG = "UiLayerRenderer"
    }
}