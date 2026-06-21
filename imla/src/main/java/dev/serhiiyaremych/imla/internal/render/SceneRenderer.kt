package dev.serhiiyaremych.imla.internal.render

import android.content.res.AssetManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.annotation.MainThread
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.capture.CapturedLayerFrame
import dev.serhiiyaremych.imla.internal.capture.CaptureThread
import dev.serhiiyaremych.imla.internal.capture.GraphicsLayerCapture
import dev.serhiiyaremych.imla.internal.render.gl.SceneGlOwner
import dev.serhiiyaremych.imla.internal.render.gl.SceneRenderTarget
import dev.serhiiyaremych.imla.internal.metrics.SceneMetrics
import dev.serhiiyaremych.imla.internal.metrics.SceneMetricsFrame
import dev.serhiiyaremych.imla.internal.metrics.SceneMetricsSnapshot
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderMetricsLog
import dev.serhiiyaremych.imla.internal.layer.model.SceneBackdropEffect
import dev.serhiiyaremych.imla.internal.layer.model.SceneBackdropOperation
import dev.serhiiyaremych.imla.internal.layer.model.SceneClipShapeKey
import dev.serhiiyaremych.imla.internal.layer.model.SceneProgressiveMaskKey
import dev.serhiiyaremych.imla.internal.layer.model.UiSceneCapture
import dev.serhiiyaremych.imla.internal.layer.model.UiCaptures
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotDeclaration
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import dev.serhiiyaremych.imla.internal.layer.resources.SceneClipShapeCache
import dev.serhiiyaremych.imla.internal.layer.resources.SceneClipShapeCaptureResult
import dev.serhiiyaremych.imla.internal.layer.resources.SceneProgressiveMaskCache
import dev.serhiiyaremych.imla.internal.layer.resources.SceneProgressiveMaskCaptureResult
import dev.serhiiyaremych.imla.internal.render.scheduler.SceneFrameDropReason
import dev.serhiiyaremych.imla.internal.render.scheduler.SceneFrameRequest
import dev.serhiiyaremych.imla.internal.layer.registry.SceneRegisteredSlot
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Scene renderer for the new pipeline. Owns the root capture mechanism and will eventually own
 * the GL thread, child slot registry, and KHR image import.
 *
 * Created by [dev.serhiiyaremych.imla.ImlaHost] and provided through [LocalSceneRenderer]. The
 * [effectGroup][dev.serhiiyaremych.imla.effectGroup] modifier reads it to signal when the root
 * content is dirty and needs re-capture.
 */
@Stable
internal class SceneRenderer internal constructor(
    assetManager: AssetManager,
    metricsDirectory: File
) {
    init {
        SceneRenderMetricsLog.configure(metricsDirectory)
    }

    private val metrics = SceneMetrics()
    private val captureThread = CaptureThread("ImlaSceneCapture")
    private val glOwner = SceneGlOwner(
        assetManager = assetManager,
        metrics = metrics,
        afterRenderComplete = ::recordSceneRenderComplete,
        afterContentImport = ::recordGlResidentContent,
        afterProgressiveMaskImport = ::recordGlMaskKeys,
        afterClipImport = ::recordGlClipKeys
    )
    private val rootCapture = GraphicsLayerCapture(
        executorName = "ImlaRootCapture",
        textureCaptureFactory = glOwner::createCanvasTextureCapture,
        captureThread = captureThread,
        // Ping-pong two single-lease renderers so the producer can capture frame
        // N+1 while GL still holds N's lease, without overlapping leases on one
        // queue. Slots stay single-lease (default).
        platformRendererCount = 2
    )
    private val slotCaptures = mutableMapOf<SceneSlotId, GraphicsLayerCapture>()
    private val glResidentContent: MutableSet<SceneSlotId> =
        Collections.newSetFromMap(ConcurrentHashMap<SceneSlotId, Boolean>())
    private val glMaskKeys = ConcurrentHashMap<SceneSlotId, SceneProgressiveMaskKey>()
    private val glClipKeys = ConcurrentHashMap<SceneSlotId, SceneClipShapeKey>()
    private val progressiveMaskCache =
        SceneProgressiveMaskCache(glOwner::createCanvasTextureCapture, captureThread)
    private val clipShapeCache = SceneClipShapeCache(glOwner::createCanvasTextureCapture, captureThread)
    private var renderTarget: SceneRenderTarget? = null
    private var outputSurface: Surface? = null
    @RequiresApi(29)
    private var outputSurfaceView: SurfaceView? = null

    @Volatile
    private var lastSceneRenderCompleteNanos: Long = 0L

    private var frameBudgetNanos: Long = DEFAULT_FRAME_BUDGET_NANOS
    private var lastPreDrawMeasuredUiDataNanos: Long = 0L
    private var lastFrameBuildMeasuredUiDataNanos: Long = 0L
    private var flushSequence: Long = 0L
    private var rootCaptureInFlight: Boolean = false
    private var pendingRootRequest: RootCaptureRequest? = null

    // The GraphicsLayer instance the in-flight root capture is reading, or null
    // when no capture is in flight. Set on the main thread alongside
    // rootCaptureInFlight and read on the main thread by SceneSourceModifier so
    // it never re-records the RenderNode the capture thread is currently
    // syncing. Captures are single-in-flight, so at most one layer is busy.
    @get:MainThread
    internal var rootCaptureLayer: GraphicsLayer? = null
        private set

    // Retries a root capture that was refused because every capture buffer was busy
    // (backpressure). Without it a one-shot dirty refused at kick time would wait for
    // the next unrelated dirty. Main-thread only; paced to ~one frame to avoid a busy
    // spin while GL still holds the leases.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rootRetryScheduled: Boolean = false
    private val rootRetryRunnable = Runnable {
        rootRetryScheduled = false
        val next = pendingRootRequest
        if (next == null || rootCaptureInFlight) return@Runnable
        if (!hasOutputSurface) {
            pendingRootRequest = null
            return@Runnable
        }
        pendingRootRequest = null
        kickRootCapture(next)
    }

    // Main-thread only. SceneSourceModifier installs this to request one trailing drain flush.
    internal var captureFlushRequester: (() -> Unit)? = null

    // Main-thread only. SceneSourceModifier installs this so a slot living in a
    // separate window (e.g. a ModalBottomSheet overlay) can request a full
    // capture kick. The effect group's own draw listener only fires for the main
    // window, so out-of-window slots would otherwise never pump a frame.
    internal var captureKickRequester: (() -> Unit)? = null

    // Main-thread only. The effect group's host view (main window). Out-of-window
    // slots compare their own view's window against this to decide whether they
    // must self-drive frames.
    @get:MainThread @set:MainThread
    internal var hostView: View? = null

    internal val hasOutputSurface: Boolean
        get() = renderTarget != null

    internal fun onRootDirty(
        layer: GraphicsLayer,
        size: IntSize,
        density: Density,
        layoutDirection: LayoutDirection,
        rootGeneration: Long,
        rootRecordedNanos: Long = 0L,
        slots: List<SceneRegisteredSlot> = emptyList(),
        preDrawStartedNanos: Long = System.nanoTime(),
        vsyncTimeNanos: Long? = null,
        kickSlotRenders: Boolean = true
    ) {
        if (!hasOutputSurface) {
            return
        }
        captureRoot(
            RootCaptureRequest(
                layer = layer,
                size = size,
                density = density,
                layoutDirection = layoutDirection,
                rootGeneration = rootGeneration,
                rootRecordedNanos = rootRecordedNanos,
                slots = slots.toList(),
                preDrawStartedNanos = preDrawStartedNanos,
                vsyncTimeNanos = vsyncTimeNanos,
                kickSlotRenders = kickSlotRenders
            )
        )
    }

    private fun captureRoot(request: RootCaptureRequest) {
        if (rootCaptureInFlight) {
            pendingRootRequest = request
            return
        }
        // A fresh direct kick supersedes any request left pending by a prior
        // backpressure refusal, so newest still wins.
        pendingRootRequest = null
        kickRootCapture(request)
    }

    private fun kickRootCapture(request: RootCaptureRequest) {
        flushSequence++
        val metricsFrame = metrics.beginRootFrame(request.preDrawStartedNanos)
        recordUiDataPreDrawAge(request)
        val captureStartedNanos = System.nanoTime()
        rootCaptureInFlight = true
        rootCaptureLayer = request.layer
        val started = rootCapture.kickCapture(
            layer = request.layer,
            size = request.size,
            density = request.density,
            layoutDirection = request.layoutDirection,
            bucketPhysicalSize = false,
            logicalSize = request.size,
            contentOffset = Offset.Zero,
            vsyncTimeNanos = request.vsyncTimeNanos,
            onCompleted = { onRootCaptureCompleted(request, metricsFrame, captureStartedNanos) }
        )
        if (!started) {
            rootCaptureInFlight = false
            rootCaptureLayer = null
            recordRootCaptureMetrics(
                success = false,
                captureStartedNanos = captureStartedNanos,
                captureFinishedNanos = System.nanoTime()
            )
            // A non-zero size that still failed to start means every capture buffer
            // was busy (backpressure), not "nothing to capture". Retain the request
            // and retry next frame; a newer request overwrites it first via
            // captureRoot, so newest still wins.
            if (request.size != IntSize.Zero) {
                pendingRootRequest = request
                scheduleRootRetry()
            }
        }
    }

    private fun scheduleRootRetry() {
        if (!rootRetryScheduled) {
            rootRetryScheduled = true
            val delayMs = (frameBudgetNanos / NANOS_PER_MILLI).coerceAtLeast(1L)
            mainHandler.postDelayed(rootRetryRunnable, delayMs)
        }
    }

    // Invoked from GraphicsLayerCapture's onCompleted: API<29 signals synchronously on
    // main; API29+ delivers the lease on the capture thread, then hops to main via
    // mainHandler before signalling. Either way this body runs on the main thread and
    // mutates main-confined capture state (rootCaptureInFlight, pendingRootRequest).
    @MainThread
    private fun onRootCaptureCompleted(
        request: RootCaptureRequest,
        metricsFrame: SceneMetricsFrame?,
        captureStartedNanos: Long
    ) {
        val captureFinishedNanos = System.nanoTime()
        rootCaptureInFlight = false
        rootCaptureLayer = null
        val rootFrame = rootCapture.takeLatestFrame()
        when {
            rootFrame == null -> recordRootCaptureMetrics(
                success = false,
                captureStartedNanos = captureStartedNanos,
                captureFinishedNanos = captureFinishedNanos
            )

            !hasOutputSurface -> rootFrame.close()

            else -> {
                submitCapturedRoot(
                    rootFrame = rootFrame,
                    request = request,
                    rootCaptureDurationNanos = captureFinishedNanos - captureStartedNanos,
                    metricsFrame = metricsFrame
                )
                recordRootCaptureMetrics(
                    success = true,
                    captureStartedNanos = captureStartedNanos,
                    captureFinishedNanos = captureFinishedNanos
                )
            }
        }
        val next = pendingRootRequest
        pendingRootRequest = null
        if (next != null) {
            kickRootCapture(next)
        }
    }

    private fun recordRootCaptureMetrics(
        success: Boolean,
        captureStartedNanos: Long,
        captureFinishedNanos: Long
    ) {
        metrics.recordRootCapture(
            startedNanos = captureStartedNanos,
            finishedNanos = captureFinishedNanos,
            success = success,
            renderCompleteGapNanos = renderCompleteGapNanos(captureStartedNanos)
        )
    }

    private fun submitCapturedRoot(
        rootFrame: CapturedLayerFrame,
        request: RootCaptureRequest,
        rootCaptureDurationNanos: Long,
        metricsFrame: SceneMetricsFrame?
    ) {
        val uiCapture = SceneRenderMetricsLog.time(
            phase = "snapshot.assemble.total",
            details = "slots=${request.slots.size}"
        ) {
            assembleSnapshot(
                rootFrame = rootFrame,
                request = request,
                rootCaptureDurationNanos = rootCaptureDurationNanos,
                metricsFrame = metricsFrame
            )
        }
        val readyAtNanos = System.nanoTime()
        val request = SceneFrameRequest(
            uiCapture = uiCapture,
            readyAtNanos = readyAtNanos
        )
        submitFrame(request)
    }

    /**
     * Terminal hand-off for an assembled frame: routes the owning [request] straight to GL
     * (the direct route — no scheduler/vsync queue) and resolves its ownership exactly once.
     *
     * - Handed off ([tryHandOffToGl] returns true, a [renderTarget] exists): GL now owns the
     *   capture and releases each hardware buffer as it imports. The request is NOT closed here.
     * - Rejected (no render target): [dropFrame] closes the request, releasing every buffer that
     *   was never built into a GL texture, and records the drop.
     *
     * [SceneFrameDropReason.NoRenderTarget] is the only reason emitted here because a missing
     * target is [tryHandOffToGl]'s sole failure path; other drop reasons originate elsewhere.
     */
    @MainThread
    private fun submitFrame(request: SceneFrameRequest) {
        trace("SceneFrameRoute#Direct") {
            val handedOff = tryHandOffToGl(request)
            if (!handedOff) {
                dropFrame(request, SceneFrameDropReason.NoRenderTarget)
            }
        }
    }

    private fun renderCompleteGapNanos(captureStartedNanos: Long): Long {
        val lastRenderCompleteNanos = lastSceneRenderCompleteNanos
        return if (lastRenderCompleteNanos > 0L) {
            captureStartedNanos - lastRenderCompleteNanos
        } else {
            0L
        }
    }

    private fun assembleSnapshot(
        rootFrame: CapturedLayerFrame,
        request: RootCaptureRequest,
        rootCaptureDurationNanos: Long,
        metricsFrame: SceneMetricsFrame?
    ): UiSceneCapture = trace("SceneSnapshot#assemble") {
        val density = request.density
        val layoutDirection = request.layoutDirection
        val slots = request.slots
        recordUiDataFrameBuildAge(
            rootRecordedNanos = request.rootRecordedNanos,
            slots = slots,
            checkpointNanos = System.nanoTime()
        )
        buildCapture { capturedFrames: MutableList<AutoCloseable> ->
            capturedFrames += rootFrame
            val contentFrames = SceneRenderMetricsLog.time(
                phase = "content.captureLayer.total",
                details = "slots=${slots.size}"
            ) {
                captureSlotContentFrames(
                    slots = slots,
                    density = density,
                    layoutDirection = layoutDirection,
                    capturedFrames = capturedFrames,
                    vsyncTimeNanos = request.vsyncTimeNanos,
                    kickSlotRenders = request.kickSlotRenders
                )
            }
            val renderSlots = selectRenderSlots(slots, contentFrames)

            val clipShapeResult = captureClips(renderSlots, request)
                .also { capturedFrames += it.changedFrames.values }

            val progressiveMaskResult = captureMasks(renderSlots, request)
                .also { capturedFrames += it.changedFrames.values }

            SceneRenderMetricsLog.time(
                phase = "snapshot.create",
                details = "renderSlots=${renderSlots.size}"
            ) {
                trace("SceneSnapshot#create") {
                    UiSceneCapture(
                        rootFrame = rootFrame,
                        rootGeneration = request.rootGeneration,
                        rootCaptureDurationNanos = rootCaptureDurationNanos,
                        frameBudgetNanos = frameBudgetNanos,
                        slots = renderSlots.toDeclarations(
                            progressiveMaskIds = progressiveMaskResult.availableIds,
                            clipIds = clipShapeResult.availableIds
                        ),
                        uiCaptures = UiCaptures(
                            contentFrames = contentFrames,
                            maskFrames = progressiveMaskResult.changedFrames,
                            maskKeys = progressiveMaskResult.changedKeys,
                            clipFrames = clipShapeResult.changedFrames,
                            clipKeys = clipShapeResult.changedKeys
                        ),
                        metricsFrame = metricsFrame
                    )
                }
            }
        }
    }

    private fun selectRenderSlots(
        slots: List<SceneRegisteredSlot>,
        contentFrames: Map<SceneSlotId, CapturedLayerFrame>
    ): List<SceneRegisteredSlot> = SceneRenderMetricsLog.time(
        phase = "snapshot.pruneSlots",
        details = "available=${contentFrames.size}"
    ) {
        trace("SceneSnapshot#pruneSlots") {
            val liveSlotIds = slots.mapTo(mutableSetOf()) { it.id }
            pruneSlotCapturesTo(liveSlotIds)
            slots.filter { slot ->
                slot.id in contentFrames || slot.id in glResidentContent
            }
        }
    }

    private fun captureClips(
        renderSlots: List<SceneRegisteredSlot>,
        request: RootCaptureRequest
    ): SceneClipShapeCaptureResult = SceneRenderMetricsLog.time(
        phase = "clip.captureLayer.total",
        details = "slots=${renderSlots.size}"
    ) {
        trace("SceneSnapshot#captureClips") {
            val clipShapeStartedNanos = System.nanoTime()
            clipShapeCache.captureChangedClips(
                slots = renderSlots,
                density = request.density,
                layoutDirection = request.layoutDirection,
                glKeys = glClipKeys
            ).also {
                metrics.recordClipShapeCapture(
                    startedNanos = clipShapeStartedNanos,
                    finishedNanos = System.nanoTime()
                )
            }
        }
    }

    private fun captureMasks(
        renderSlots: List<SceneRegisteredSlot>,
        request: RootCaptureRequest
    ): SceneProgressiveMaskCaptureResult = SceneRenderMetricsLog.time(
        phase = "mask.captureLayer.total",
        details = "slots=${renderSlots.size}"
    ) {
        trace("SceneSnapshot#captureMasks") {
            val progressiveMaskStartedNanos = System.nanoTime()
            progressiveMaskCache.captureChangedMasks(
                slots = renderSlots,
                density = request.density,
                layoutDirection = request.layoutDirection,
                glKeys = glMaskKeys
            ).also {
                metrics.recordProgressiveMaskCapture(
                    startedNanos = progressiveMaskStartedNanos,
                    finishedNanos = System.nanoTime()
                )
            }
        }
    }

    private fun captureSlotContentFrames(
        slots: List<SceneRegisteredSlot>,
        density: Density,
        layoutDirection: LayoutDirection,
        capturedFrames: MutableList<AutoCloseable>,
        vsyncTimeNanos: Long?,
        kickSlotRenders: Boolean
    ): Map<SceneSlotId, CapturedLayerFrame> = trace("SceneSnapshot#captureSlotContent") {
        val slotCaptureStartedNanos = System.nanoTime()
        var discoveryNanos = 0L
        var rendererNanos = 0L
        var layerCaptureNanos = 0L
        var bookkeepingNanos = 0L
        var attemptedSlots = 0L
        var capturedSlots = 0L
        val contentFrames = mutableMapOf<SceneSlotId, CapturedLayerFrame>()
        slots.forEach { slot ->
            val contentLayer = measuredNanos({ discoveryNanos += it }) { slot.contentLayer }
            if (contentLayer == null) return@forEach
            attemptedSlots++

            val slotCapture = measuredNanos({ rendererNanos += it }) {
                slotCaptures.getOrPut(slot.id) {
                    GraphicsLayerCapture(
                        executorName = "ImlaSlot${slot.id.value}",
                        textureCaptureFactory = glOwner::createCanvasTextureCapture,
                        captureThread = captureThread,
                        // Ping-pong two buffers like the root: while GL holds frame N's lease, the
                        // slot can capture N+1 instead of refusing the kick. Single-buffered slots
                        // stall under continuous resize, so the content texture updated in
                        // multi-frame jumps while geometry moved every frame -> stretch/bounce.
                        platformRendererCount = 2
                    )
                }
            }

            measuredNanos({ bookkeepingNanos += it }) {
                slotCapture.takeLatestFrame()?.let { contentFrame ->
                    capturedFrames += contentFrame
                    contentFrames[slot.id] = contentFrame
                    capturedSlots++
                }
            }

            if (!kickSlotRenders) return@forEach

            measuredNanos({ layerCaptureNanos += it }) {
                val seqAtKick = flushSequence
                val started = slotCapture.kickCapture(
                    layer = contentLayer,
                    size = slot.contentSize,
                    density = density,
                    layoutDirection = layoutDirection,
                    bucketPhysicalSize = true,
                    logicalSize = slot.layoutSize,
                    contentOffset = slot.contentOffset,
                    vsyncTimeNanos = vsyncTimeNanos,
                    onCompleted = {
                        // The capture thread is done reading contentLayer's
                        // RenderNode by the time the image is available, so the
                        // slot modifier may record into it again.
                        slot.captureState.capturingLayer = null
                        if (flushSequence == seqAtKick) {
                            captureFlushRequester?.invoke()
                        }
                    }
                )
                // Mark the in-flight layer so the slot modifier records into a
                // different ring instance until this capture completes. Only a
                // started kick reads the layer; a refused one leaves any prior
                // in-flight layer marked.
                if (started) {
                    slot.captureState.capturingLayer = contentLayer
                }
            }
        }
        metrics.recordSlotCapture(
            startedNanos = slotCaptureStartedNanos,
            finishedNanos = System.nanoTime(),
            discoveryNanos = discoveryNanos,
            rendererNanos = rendererNanos,
            layerCaptureNanos = layerCaptureNanos,
            bookkeepingNanos = bookkeepingNanos,
            attemptedSlots = attemptedSlots,
            capturedSlots = capturedSlots
        )
        return contentFrames
    }

    private fun List<SceneRegisteredSlot>.toDeclarations(
        progressiveMaskIds: Set<SceneSlotId>,
        clipIds: Set<SceneSlotId>
    ): List<SceneSlotDeclaration> {
        return map { slot ->
            val backdrop = slot.backdrop?.withProgressiveMask(
                hasProgressiveMask = slot.id in progressiveMaskIds
            )
            val hasClip = slot.id in clipIds
            SceneSlotDeclaration(
                id = slot.id,
                drawOrder = slot.drawOrder,
                geometry = slot.geometry,
                backdrop = backdrop,
                debugColor = slot.debugColor,
                requiresBackdropClip = hasClip && backdrop != null,
                requiresContentClip = hasClip && slot.clipContent
            )
        }
    }

    internal fun setOutputSurface(surface: Surface, size: IntSize) {
        metrics.recordTargetSize(size)
        val target = renderTarget
        if (target != null && outputSurface === surface) {
            target.resize(size)
        } else {
            clearOutputSurface()
            renderTarget = glOwner.attachRenderTarget(surface, size)
            outputSurface = surface
        }
    }

    internal fun resizeOutputSurface(size: IntSize) {
        metrics.recordTargetSize(size)
        renderTarget?.resize(size)
    }

    internal fun clearOutputSurface(surface: Surface? = null) {
        if (surface == null || outputSurface === surface) {
            renderTarget?.detach()
            renderTarget = null
            outputSurface = null
            metrics.recordTargetSize(IntSize.Zero)
        }
    }

    @RequiresApi(29)
    internal fun setOutputSurfaceView(surfaceView: SurfaceView, size: IntSize) {
        metrics.recordTargetSize(size)
        val target = renderTarget
        if (target != null && outputSurfaceView === surfaceView) {
            target.resize(size)
        } else {
            clearOutputSurface()
            renderTarget = glOwner.attachRenderTargetWithSurfaceView(surfaceView, size)
            outputSurfaceView = surfaceView
        }
    }

    internal fun onRootDetached() {
        releaseSlotResources()
    }

    internal fun release() {
        mainHandler.removeCallbacks(rootRetryRunnable)
        rootRetryScheduled = false
        pendingRootRequest = null
        clearOutputSurface()
        rootCapture.release()
        releaseSlotResources()
        captureThread.close()
        glOwner.close()
    }

    internal fun metricsSnapshot(): SceneMetricsSnapshot = metrics.snapshot()

    internal fun setFrameBudgetNanos(frameBudgetNanos: Long) {
        if (frameBudgetNanos <= 0L) return
        this.frameBudgetNanos = frameBudgetNanos
    }

    private fun recordSceneRenderComplete() {
        lastSceneRenderCompleteNanos = System.nanoTime()
    }

    /**
     * Hands [request] to the GL owner's present mailbox. This is the main→GL ownership crossing,
     * not a render: a true result means GL has taken the capture, after which GL may still drop it
     * asynchronously (a newer frame supersedes it, or the target detaches). Those async drops close
     * the request on the GL side and are invisible to this return value.
     *
     * @return true once handed off (GL owns the request); false only when no [renderTarget] exists,
     *   in which case the caller still owns the request and must drop it.
     */
    @MainThread
    private fun tryHandOffToGl(request: SceneFrameRequest): Boolean =
        trace("SceneFrameSubmit#ToGl") {
            val target = renderTarget ?: return@trace false
            target.submit(request)
            true
        }

    private fun dropFrame(
        request: SceneFrameRequest,
        reason: SceneFrameDropReason
    ) {
        trace("SceneFrameDrop#$reason") {
            metrics.recordDropped(reason)
            request.close()
        }
    }

    private fun pruneSlotCapturesTo(liveIds: Set<SceneSlotId>) {
        val removedIds = slotCaptures.keys - liveIds
        removedIds.forEach { slotId ->
            slotCaptures.remove(slotId)?.release()
            glResidentContent.remove(slotId)
            glMaskKeys.remove(slotId)
            glClipKeys.remove(slotId)
        }
    }

    private fun releaseSlotResources() {
        slotCaptures.values.forEach { it.release() }
        slotCaptures.clear()
        glResidentContent.clear()
        glMaskKeys.clear()
        glClipKeys.clear()
        progressiveMaskCache.release()
        clipShapeCache.release()
    }

    private fun recordGlResidentContent(slotIds: Set<SceneSlotId>) {
        glResidentContent.addAll(slotIds)
    }

    private fun recordGlMaskKeys(keys: Map<SceneSlotId, SceneProgressiveMaskKey>) {
        glMaskKeys.putAll(keys)
    }

    private fun recordGlClipKeys(keys: Map<SceneSlotId, SceneClipShapeKey>) {
        glClipKeys.putAll(keys)
    }

    private fun List<AutoCloseable>.closeAll() {
        forEach { it.close() }
    }

    /**
     * Builds a value while accumulating capture frames into the passed list. On normal
     * return ownership transfers to the result and nothing is closed; if [block] throws,
     * every accumulated frame is closed (rollback). Inverse of [use]: commit on success,
     * close only on failure.
     */
    /** Runs [block], feeding its elapsed nanos to [accumulate], and returns its result. */
    private inline fun <T> measuredNanos(accumulate: (Long) -> Unit, block: () -> T): T {
        val startedNanos = System.nanoTime()
        try {
            return block()
        } finally {
            accumulate(System.nanoTime() - startedNanos)
        }
    }

    private inline fun <T> buildCapture(block: (MutableList<AutoCloseable>) -> T): T {
        val capturedFrames = mutableListOf<AutoCloseable>()
        var committed = false
        try {
            return block(capturedFrames).also { committed = true }
        } finally {
            if (!committed) capturedFrames.closeAll()
        }
    }

    private fun recordUiDataPreDrawAge(request: RootCaptureRequest) {
        val uiDataNanos = request.latestUiDataNanos()
        if (uiDataNanos <= lastPreDrawMeasuredUiDataNanos) return
        lastPreDrawMeasuredUiDataNanos = uiDataNanos
        metrics.recordUiDataPreDrawAge(
            ageNanos = request.preDrawStartedNanos - uiDataNanos
        )
    }

    private fun recordUiDataFrameBuildAge(
        rootRecordedNanos: Long,
        slots: List<SceneRegisteredSlot>,
        checkpointNanos: Long
    ) {
        val uiDataNanos = latestUiDataNanos(rootRecordedNanos, slots)
        if (uiDataNanos <= lastFrameBuildMeasuredUiDataNanos) return
        lastFrameBuildMeasuredUiDataNanos = uiDataNanos
        metrics.recordUiDataFrameBuildAge(
            ageNanos = checkpointNanos - uiDataNanos
        )
    }
}

internal val LocalSceneRenderer = staticCompositionLocalOf<SceneRenderer?> { null }

private const val DEFAULT_FRAME_BUDGET_NANOS = 16_666_667L
private const val NANOS_PER_MILLI = 1_000_000L

private data class RootCaptureRequest(
    val layer: GraphicsLayer,
    val size: IntSize,
    val density: Density,
    val layoutDirection: LayoutDirection,
    val rootGeneration: Long,
    val rootRecordedNanos: Long,
    val slots: List<SceneRegisteredSlot>,
    val preDrawStartedNanos: Long,
    val vsyncTimeNanos: Long?,
    val kickSlotRenders: Boolean
)

@Composable
internal fun rememberSceneRenderer(): SceneRenderer {
    val context = LocalContext.current
    val assetManager = context.assets
    val metricsDirectory = context.filesDir
    return remember(assetManager, metricsDirectory) {
        SceneRenderer(
            assetManager = assetManager,
            metricsDirectory = metricsDirectory
        )
    }
}

private fun SceneBackdropEffect.withProgressiveMask(hasProgressiveMask: Boolean): SceneBackdropEffect {
    if (!hasProgressiveMask) return this
    return SceneBackdropEffect(
        operations = operations.map { operation ->
            when (operation) {
                is SceneBackdropOperation.Blur -> operation.copy(hasProgressiveMask = true)
            }
        },
        composite = composite
    )
}

private fun RootCaptureRequest.latestUiDataNanos(): Long {
    return latestUiDataNanos(
        rootRecordedNanos = rootRecordedNanos,
        slots = slots
    )
}

private fun latestUiDataNanos(
    rootRecordedNanos: Long,
    slots: List<SceneRegisteredSlot>
): Long {
    val latestSlotUpdateNanos = slots.maxOfOrNull { it.updatedNanos } ?: 0L
    return listOf(rootRecordedNanos, latestSlotUpdateNanos)
        .filter { it > 0L }
        .maxOrNull()
        ?: 0L
}
