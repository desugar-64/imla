/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import android.content.res.AssetManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.trace
import androidx.graphics.opengl.GLRenderer
import dev.serhiiyaremych.imla.internal.ext.logw
import dev.serhiiyaremych.imla.internal.render.GraphicsContext
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Renderer2D
import dev.serhiiyaremych.imla.internal.render.SimpleRenderer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.opengl.OpenGLRendererAPI
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import dev.serhiiyaremych.imla.internal.legacy.scene.CommittedSceneFrame
import dev.serhiiyaremych.imla.internal.legacy.scene.ImlaSceneCoordinator
import dev.serhiiyaremych.imla.internal.legacy.scene.RenderReason
import dev.serhiiyaremych.imla.internal.legacy.scene.SceneGlRenderer
import dev.serhiiyaremych.imla.internal.legacy.scene.SceneLayerRepository
import dev.serhiiyaremych.imla.internal.legacy.scene.SceneMaskRepository
import dev.serhiiyaremych.imla.internal.legacy.scene.SceneResourceStore
import dev.serhiiyaremych.imla.internal.legacy.scene.MutableSceneRenderGate
import dev.serhiiyaremych.imla.internal.legacy.scene.SceneRenderScheduler
import dev.serhiiyaremych.imla.internal.legacy.scene.SceneTraceCounters

internal class ImlaSceneRendererObserver<T>(
    val renderer: T,
    private val destroyRenderer: (T) -> Unit
) : RememberObserver {
    override fun onAbandoned() {
        destroyRenderer(renderer)
    }

    override fun onForgotten() {
        destroyRenderer(renderer)
    }

    override fun onRemembered() {
        logw("ImlaSceneRenderer", "ImlaSceneRenderer $renderer has been instantiated")
    }
}

/**
 * Creates a page-local scene renderer and releases it when the remembered composition leaves.
 *
 * Internal legacy bridge code owns this object and passes it through the legacy host scope.
 */
@Composable
internal fun rememberImlaSceneRenderer(
    downSampleFactor: Int = 2
): ImlaSceneRenderer {
    val density = LocalDensity.current
    val assetManager = LocalContext.current.assets
    val observer = remember(density, assetManager, downSampleFactor) {
        ImlaSceneRendererObserver(
            renderer = ImlaSceneRenderer(
                density = density,
                downSampleFactor = downSampleFactor,
                assetManager = assetManager
            ),
            destroyRenderer = ImlaSceneRenderer::destroy
        )
    }
    return observer.renderer
}

/**
 * Scene-only renderer for one hosted blur scene.
 *
 * The renderer owns the scene coordinator, GL resources, and one surface session. It expects
 * all surface lifecycle calls to come from the legacy host, all root captures to come from the
 * legacy source modifier, and render work to stay on the GLRenderer thread.
 * It deliberately does not expose legacy render-object mutation APIs or act as a child modifier
 * dependency.
 */
@Stable
internal class ImlaSceneRenderer internal constructor(
    density: Density,
    downSampleFactor: Int,
    assetManager: AssetManager
) : Density by density {
    private val shaderLibrary: ShaderLibrary = ShaderLibrary(assetManager)
    private val renderer2D: Renderer2D = Renderer2D()
    private val shaderBinder: ShaderBinder = ShaderBinder()
    private val simpleRenderer: SimpleRenderer = SimpleRenderer()
    private val simpleQuadRenderer: SimpleQuadRenderer =
        SimpleQuadRenderer(shaderLibrary, simpleRenderer, shaderBinder)
    private val glRenderer: GLRenderer = GLRenderer().apply {
        start("GLImlaSceneRenderer")
    }

    private val renderableLayer: RenderableRootLayer = RenderableRootLayer(
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder,
        layerDownsampleFactor = downSampleFactor,
        density = density,
        renderer2D = renderer2D,
        glRenderer = glRenderer,
        simpleQuadRenderer = simpleQuadRenderer,
        commandsProvider = { requireNotNull(graphicsContext).commands }
    )

    private val sceneVisibilityGate: MutableSceneRenderGate = MutableSceneRenderGate(true)

    private val sceneRenderScheduler: SceneRenderScheduler =
        SceneRenderScheduler(
            renderGate = sceneVisibilityGate,
            onRenderRequested = ::requestRender
        )

    internal val sceneCoordinator: ImlaSceneCoordinator =
        ImlaSceneCoordinator(
            scheduler = sceneRenderScheduler,
            onFrameCommitted = ::captureSceneFrameResources
        )

    private val sceneResourceStore: SceneResourceStore = SceneResourceStore(
        rootLayer = renderableLayer,
        layerRepository = SceneLayerRepository(
            density = this,
            shaderLibrary = shaderLibrary,
            shaderBinder = shaderBinder,
            simpleQuadRenderer = simpleQuadRenderer,
            glRenderer = glRenderer,
            commandsProvider = { requireNotNull(graphicsContext).commands }
        ),
        maskRepository = SceneMaskRepository(
            density = this,
            shaderLibrary = shaderLibrary,
            shaderBinder = shaderBinder,
            simpleQuadRenderer = simpleQuadRenderer,
            glRenderer = glRenderer,
            commandsProvider = { requireNotNull(graphicsContext).commands }
        )
    )

    private val sceneGlRenderer: SceneGlRenderer = SceneGlRenderer(
        resourceStore = sceneResourceStore,
        simpleRenderer = simpleQuadRenderer,
        renderer2D = renderer2D,
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder,
        graphicsContextProvider = { requireNotNull(graphicsContext) },
        coordinator = sceneCoordinator
    )

    private var graphicsContext: GraphicsContext? = null

    /**
     * True while the hosted surface is attached and the scene GL target is ready.
     *
     * This is a read-only lifecycle signal for UI diagnostics; it does not transfer ownership of
     * the renderer or guarantee that a fresh root capture has been committed.
     */
    public val isInitialized: MutableState<Boolean> = mutableStateOf(false)

    private val sceneSession: ImlaSceneSession = ImlaSceneSession.create(
        glRenderer = glRenderer,
        initializeGlResources = ::initializeGlResources,
        releaseSharedSurfaceResources = SurfaceTextureRenderer::releaseSharedResources,
        onSurfaceAttached = { size ->
            renderableLayer.initialize(overrideSize = size)
            Snapshot.withMutableSnapshot { isInitialized.value = true }
        },
        onSurfaceDetached = { Snapshot.withMutableSnapshot { isInitialized.value = false } },
        onSurfaceChanged = {
            sceneCoordinator.onSurfaceChanged()
            sceneRenderScheduler.drainPendingReasonsIfGateAllows()
        },
        onRenderStarted = sceneRenderScheduler::onRenderStarted,
        onRenderFinished = sceneRenderScheduler::onRenderFinished,
        onRender = sceneGlRenderer::render,
        onRenderCancelled = sceneRenderScheduler::cancelRenderRequest,
        renderGate = sceneVisibilityGate::canRenderScene
    )

    init {
        initializeIfNeed(IntSize.Zero)
    }

    internal fun onRootLayerUpdated(graphicsLayer: GraphicsLayer, size: IntSize): Unit =
        trace("ImlaSceneRenderer#onRootLayerUpdated") {
            initializeIfNeed(size)
            if (size == IntSize.Zero) {
                return@trace
            }
            if (!sceneSession.canCaptureFreshRoot(size)) {
                logw(TAG, "GL renderer is not initialized or root surface is missing - skipping update")
                return@trace
            }

            if (!sceneSession.acceptFreshRootCapture(size)) {
                return@trace
            }
            if (!renderableLayer.captureLayer(graphicsLayer, size)) {
                SceneTraceCounters.rootCaptureFailed()
                sceneCoordinator.requestRender(RenderReason.RootCaptured)
                return@trace
            }
            sceneCoordinator.commitRootCapture(size)
        }

    internal fun refreshSceneSlotGeometry(): Boolean {
        return sceneCoordinator.refreshSlotGeometry()
    }

    internal fun attachRootSurface(surface: Surface?, size: IntSize) {
        sceneSession.attachSurface(surface, size)
    }

    internal fun detachRootSurface() {
        sceneSession.detachSurface()
    }

    internal fun setSceneVisible(visible: Boolean) {
        val wasEnabled = sceneVisibilityGate.enabled
        sceneVisibilityGate.enabled = visible
        SceneTraceCounters.renderGateState(visible)
        if (!wasEnabled && visible) {
            sceneRenderScheduler.drainPendingReasonsIfGateAllows()
        }
    }

    public fun destroy() {
        sceneSession.destroy(
            destroyGlResources = {
                renderer2D.shutdown()
                renderableLayer.destroy()
                sceneGlRenderer.destroy()
                sceneResourceStore.destroy()
                graphicsContext?.destroy()
                graphicsContext = null
            },
            stopGlRenderer = { glRenderer.stop(true) }
        )
    }

    private fun initializeIfNeed(size: IntSize) {
        if (size == IntSize.Zero || sceneSession.isDestroyed) {
            logw(TAG, "GraphicsLayer is empty, postpone GL init.")
            return
        }
        sceneSession.initializeIfNeeded(size)
    }

    private fun initializeGlResources(size: IntSize) {
        val context = graphicsContext ?: run {
            val commands = RenderCommands(OpenGLRendererAPI())
            GraphicsContext(
                rendererApi = commands.rendererApi,
                shaderLibrary = shaderLibrary,
                shaderBinder = shaderBinder,
                fboPool = FramebufferLendingPool(commands),
                commands = commands
            ).also { nextContext ->
                nextContext.initialize()
                graphicsContext = nextContext
            }
        }
        simpleRenderer.init(context.commands)
        renderer2D.init(
            graphicsContext = context,
            shaderLibrary = shaderLibrary,
            shaderBinder = shaderBinder
        )
        renderableLayer.initialize(overrideSize = size.takeIf { it != IntSize.Zero })
    }

    private fun captureSceneFrameResources(frame: CommittedSceneFrame) {
        sceneResourceStore.captureFrameResources(frame)
    }

    private fun requestRender(): Boolean {
        return sceneSession.requestRender()
    }

    internal companion object {
        private const val TAG = "ImlaSceneRenderer"
    }
}
