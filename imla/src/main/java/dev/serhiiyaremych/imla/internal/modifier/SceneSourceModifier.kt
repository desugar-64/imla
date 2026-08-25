package dev.serhiiyaremych.imla.internal.modifier

import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.layer.registry.LocalSceneRegistry
import dev.serhiiyaremych.imla.internal.layer.registry.SceneRegistry
import dev.serhiiyaremych.imla.internal.render.LocalSceneRenderer
import dev.serhiiyaremych.imla.internal.render.SceneRenderer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

// OnDraw waits before ThreadedRenderer.draw, not after HWUI.
internal fun Modifier.effectGroupInternal(): Modifier {
    return this then SceneSourceElement
}

private class SceneSourceNode : Modifier.Node(),
    DrawModifierNode,
    LayoutAwareModifierNode,
    ObserverModifierNode,
    CompositionLocalConsumerModifierNode {

    private val layerRing = PingPongLayerRing()
    private var currentView: View? by mutableStateOf(null)
    private var sceneRenderer: SceneRenderer? = null
    private var sceneRegistry: SceneRegistry? = null
    private var rootCoordinates: LayoutCoordinates? = null
    private var recordedSize: IntSize = IntSize.Zero
    private var recordedDensity: Density = Density(1f)
    private var recordedLayoutDirection: LayoutDirection = LayoutDirection.Ltr
    private var recordedNanos: Long = 0L
    private var rootGeneration: Long = nextRootGenerationBase()
    private val vsyncFrameTime = SceneVsyncFrameTime()
    private var captureFlushPending: Boolean = false
    private var pendingKickSlotRenders: Boolean = false
    private var captureFlushView: View? = null

    private var presentInProgress: Boolean = false

    private val onPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        beginHostSyncPresent()
        true
    }

    private val onDrawListener = ViewTreeObserver.OnDrawListener {
        if (!presentInProgress) return@OnDrawListener
        try {
            sceneRenderer?.completeSyncPresent()
        } finally {
            presentInProgress = false
        }
    }

    private val requestDrainCaptureFlush = {
        scheduleCaptureFlush(kickSlotRenders = false)
    }

    private val requestKickCaptureFlush = {
        scheduleCaptureFlush(kickSlotRenders = true)
    }

    private val postDrawCaptureFlush = Runnable {
        val postedView = captureFlushView
        val kickSlotRenders = pendingKickSlotRenders
        clearPendingCaptureFlush()

        val renderer = sceneRenderer ?: return@Runnable
        // Re-validate: this runs a message loop after it was posted, so attach
        // state, the host view, and the present target may all have changed
        // since scheduleCaptureFlush() queued it.
        if (isAttached && currentView === postedView && renderer.hasGlSession) {
            flushCapture(renderer, kickSlotRenders)
        }
    }

    override fun onAttach() {
        super.onAttach()
        vsyncFrameTime.start()
        onObservedReadsChanged()
    }

    override fun onObservedReadsChanged() {
        observeReads {
            val nextView = currentValueOf(LocalView)
            if (currentView != nextView) {
                removeViewTreeObservers()
                currentView = nextView
                subscribeViewTreeObservers()
            }
            updateSceneRenderer(currentValueOf(LocalSceneRenderer))
            sceneRegistry = currentValueOf(LocalSceneRegistry)
            sceneRenderer?.hostView = currentView
        }
    }

    override fun ContentDrawScope.draw() {
        if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            drawContent()
            return
        }
        recordContentLayer()
        layerRing.latestRecorded?.let { drawLayer(it) } ?: drawContent()
        if (Build.VERSION.SDK_INT >= 29) {
            sceneRenderer?.ensureGlSession()
            sceneRegistry?.setSource(
                layer = layerRing.latestRecorded,
                coordinates = rootCoordinates
            )
        }
    }

    private fun beginHostSyncPresent() {
        if (!isAttached) return
        if (Build.VERSION.SDK_INT < 29) return
        val recordedLayer = layerRing.latestRecorded ?: return
        val renderer = sceneRenderer ?: return
        if (recordedSize == IntSize.Zero || !renderer.hasGlSession) return
        if (presentInProgress) {
            renderer.cancelSyncPresent()
            presentInProgress = false
        }
        presentInProgress = trace("SceneCaptureKick") {
            renderer.beginSyncPresent(
                layer = recordedLayer,
                size = recordedSize,
                density = recordedDensity,
                layoutDirection = recordedLayoutDirection,
                rootGeneration = rootGeneration,
                rootRecordedNanos = recordedNanos,
                slots = sceneRegistry?.snapshot(rootCoordinates)?.slots.orEmpty(),
                flushStartedNanos = System.nanoTime(),
                vsyncTimeNanos = vsyncFrameTime.latestFrameTimeNanos,
                kickSlotRenders = true
            )
        }
    }

    private fun ContentDrawScope.recordContentLayer() {
        val layer = layerRing.acquire(this@SceneSourceNode, sceneRenderer?.rootCaptureLayer)
        val contentScope = this

        sceneRegistry?.resetDrawOrder()
        layer.record { contentScope.drawContent() }

        rootGeneration += 1
        recordedSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        // Snapshot the density value: `this` is a reused LayoutNodeDrawScope whose
        // density resets to 1.0 after the draw pass, so the async capture flush would
        // otherwise read a stale density and rasterize clip/mask shapes at 1x.
        recordedDensity = Density(density = density, fontScale = fontScale)
        recordedLayoutDirection = layoutDirection
        recordedNanos = System.nanoTime()
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        rootCoordinates = coordinates
        sceneRegistry?.setSource(
            layer = layerRing.latestRecorded,
            coordinates = coordinates
        )
    }

    override fun onDetach() {
        super.onDetach()
        sceneRenderer?.cancelSyncPresent()
        presentInProgress = false
        sceneRegistry?.setSource(layer = null, coordinates = null)
        sceneRenderer?.onRootDetached()
        updateSceneRenderer(null)
        removeViewTreeObservers()
        vsyncFrameTime.stop()
        layerRing.releaseAll(this)
    }

    private fun updateSceneRenderer(nextRenderer: SceneRenderer?) {
        val previousRenderer = sceneRenderer
        if (previousRenderer === nextRenderer) return
        previousRenderer?.cancelSyncPresent()
        presentInProgress = false
        if (previousRenderer?.captureFlushRequester === requestDrainCaptureFlush) {
            previousRenderer.captureFlushRequester = null
        }
        if (previousRenderer?.captureKickRequester === requestKickCaptureFlush) {
            previousRenderer.captureKickRequester = null
        }
        if (previousRenderer != null && previousRenderer.hostView === currentView) {
            previousRenderer.hostView = null
        }
        sceneRenderer = nextRenderer
        nextRenderer?.captureFlushRequester = requestDrainCaptureFlush
        nextRenderer?.captureKickRequester = requestKickCaptureFlush
    }

    private fun scheduleCaptureFlush(kickSlotRenders: Boolean) {
        val view = currentView ?: return
        pendingKickSlotRenders = pendingKickSlotRenders || kickSlotRenders
        if (captureFlushPending) return
        captureFlushPending = true
        captureFlushView = view
        // Vsync-align the capture dispatch via the animation callback queue rather
        // than a plain main-thread post: tightens kick-to-vsync phase (-68% StdDev)
        // and cuts GL-present stalls (-21% P95) at no throughput cost. See
        // diagnostics/apa/pacing/results.md.
        view.postOnAnimation(postDrawCaptureFlush)
    }

    private fun flushCapture(renderer: SceneRenderer, kickSlotRenders: Boolean) {
        val layer = layerRing.latestRecorded
        if (layer != null && recordedSize != IntSize.Zero) {
            // Pacing probe: one instant per dispatched capture. Inter-section
            // interval and offset from Choreographer#doFrame measure how vsync-
            // aligned the capture cadence is. See CapturePacingMetric.
            trace("SceneCaptureKick") {
                val flushStartedNanos = System.nanoTime()
                val registrySnapshot = sceneRegistry?.snapshot(rootCoordinates)
                renderer.onRootDirty(
                    layer = layer,
                    size = recordedSize,
                    density = recordedDensity,
                    layoutDirection = recordedLayoutDirection,
                    rootGeneration = rootGeneration,
                    rootRecordedNanos = recordedNanos,
                    slots = registrySnapshot?.slots.orEmpty(),
                    flushStartedNanos = flushStartedNanos,
                    vsyncTimeNanos = vsyncFrameTime.latestFrameTimeNanos,
                    kickSlotRenders = kickSlotRenders
                )
            }
        }
    }

    private fun subscribeViewTreeObservers() {
        if (Build.VERSION.SDK_INT < 29) return
        val observer = currentView?.viewTreeObserver ?: return
        observer.addOnPreDrawListener(onPreDrawListener)
        observer.addOnDrawListener(onDrawListener)
    }

    private fun removeViewTreeObservers() {
        currentView?.let { view ->
            view.viewTreeObserver.removeOnPreDrawListener(onPreDrawListener)
            view.viewTreeObserver.removeOnDrawListener(onDrawListener)
            view.removeCallbacks(postDrawCaptureFlush)
            if (captureFlushView === view) {
                clearPendingCaptureFlush()
            }
        }
    }

    private fun clearPendingCaptureFlush() {
        pendingKickSlotRenders = false
        captureFlushPending = false
        captureFlushView = null
    }
}

private data object SceneSourceElement : ModifierNodeElement<SceneSourceNode>() {
    override fun create(): SceneSourceNode = SceneSourceNode()

    override fun update(node: SceneSourceNode) {
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "effectSource"
    }
}

private class SceneVsyncFrameTime : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private var started: Boolean = false
    private var latest: Long = 0L

    val latestFrameTimeNanos: Long?
        get() = latest.takeIf { it > 0L }

    fun start() {
        if (!started) {
            started = true
            choreographer.postFrameCallback(this)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        choreographer.removeFrameCallback(this)
        latest = 0L
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (started) {
            latest = frameTimeNanos
            choreographer.postFrameCallback(this)
        }
    }
}

private fun nextRootGenerationBase(): Long {
    return rootGenerationBase.addAndGet(ROOT_GENERATION_SOURCE_STRIDE)
}

private val rootGenerationBase = AtomicLong(0L)
private const val ROOT_GENERATION_SOURCE_STRIDE = 1_000_000_000_000L
