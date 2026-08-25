package dev.serhiiyaremych.imla.internal.render

import android.content.res.AssetManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.annotation.MainThread
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.capture.BACKDROP_HARDWARE_CAPTURE_SCALE
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
import dev.serhiiyaremych.imla.internal.ext.logd
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/**
 * Scene renderer for the new pipeline. Owns the root capture mechanism and will eventually own
 * the GL thread, child slot registry, and KHR image import.
 *
 * Created by [dev.serhiiyaremych.imla.ImlaHost] and provided through [LocalSceneRenderer]. The
 * [effectGroup][dev.serhiiyaremych.imla.effectSource] modifier reads it to signal when the source
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
        afterClipImport = ::recordGlClipKeys,
        onHardwareBitmapPresented = ::installPresentedImage
    )
    private val backdropCaptures = mutableMapOf<SceneSlotId, GraphicsLayerCapture>()
    private val slotCaptures = mutableMapOf<SceneSlotId, GraphicsLayerCapture>()
    private val glResidentContent: MutableSet<SceneSlotId> =
        Collections.newSetFromMap(ConcurrentHashMap<SceneSlotId, Boolean>())
    private val glMaskKeys = ConcurrentHashMap<SceneSlotId, SceneProgressiveMaskKey>()
    private val glClipKeys = ConcurrentHashMap<SceneSlotId, SceneClipShapeKey>()
    private val progressiveMaskCache =
        SceneProgressiveMaskCache(glOwner::createCanvasTextureCapture, captureThread)
    private val clipShapeCache = SceneClipShapeCache(glOwner::createCanvasTextureCapture, captureThread)
    private var renderTarget: SceneRenderTarget? = null
    private var attachedSize: IntSize = IntSize.Zero
    private var presentedImages = mutableMapOf<SceneSlotId, ImageBitmap>()
    private val presentInvalidates = mutableMapOf<SceneSlotId, () -> Unit>()

    @Volatile
    private var lastSceneRenderCompleteNanos: Long = 0L

    private var frameBudgetNanos: Long = DEFAULT_FRAME_BUDGET_NANOS
    private var lastFlushMeasuredUiDataNanos: Long = 0L
    private var lastFrameBuildMeasuredUiDataNanos: Long = 0L
    private var flushSequence: Long = 0L
    private var inFlightBackdropIds = mutableSetOf<SceneSlotId>()
    private val pendingBackdropRequests = mutableMapOf<SceneSlotId, RootCaptureRequest>()

    // GraphicsLayer instance currently being crop-captured for any in-flight
    // backdrop. SceneSourceModifier ping-pongs away from this instance.
    @get:MainThread
    internal var rootCaptureLayer: GraphicsLayer? = null
        private set

    private val pendingPresentVsyncBySlot = mutableMapOf<SceneSlotId, Long>()
    private val drawnPresentVsyncBySlot = mutableMapOf<SceneSlotId, Long>()
    private var syncPresentJobs: ArrayList<SyncPresentJob> = ArrayList()

    // Retries a backdrop crop that was refused because every capture buffer was busy.
    // (backpressure). Without it a one-shot dirty refused at kick time would wait for
    // the next unrelated dirty. Main-thread only; paced to ~one frame to avoid a busy
    // spin while GL still holds the leases.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rootRetryScheduled: Boolean = false
    private val rootRetryRunnable = Runnable {
        rootRetryScheduled = false
        if (!hasPresentTarget) {
            pendingBackdropRequests.clear()
            return@Runnable
        }
        kickNextBackdropCapture()
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

    internal fun setPresentInvalidate(id: SceneSlotId, invalidate: (() -> Unit)?) {
        if (invalidate == null) {
            presentInvalidates.remove(id)
        } else {
            presentInvalidates[id] = invalidate
        }
    }

    internal fun presentedImage(id: SceneSlotId?): ImageBitmap? {
        return id?.let { presentedImages[it] }
    }

    @MainThread
    internal fun onPresentedImageDrawn(id: SceneSlotId?) {
        val slotId = id ?: return
        val captureVsyncNanos = pendingPresentVsyncBySlot[slotId] ?: return
        if (drawnPresentVsyncBySlot[slotId] == captureVsyncNanos) return
        drawnPresentVsyncBySlot[slotId] = captureVsyncNanos
        val lagNanos = System.nanoTime() - captureVsyncNanos
        metrics.recordPresentLag(lagNanos)
        val lagMs = lagNanos / 1_000_000.0
        val frames = lagNanos.toDouble() / frameBudgetNanos.coerceAtLeast(1L)
        Trace.setCounter("ImlaPresentLagUs", lagNanos / 1_000L)
        logd(
            "ImlaPipelineLag",
            String.format(Locale.US, "lag=%.1fms frames=%.2f", lagMs, frames)
        )
    }

    internal val hasPresentTarget: Boolean
        get() = renderTarget != null

    internal fun onRootDirty(
        layer: GraphicsLayer,
        size: IntSize,
        density: Density,
        layoutDirection: LayoutDirection,
        rootGeneration: Long,
        rootRecordedNanos: Long = 0L,
        slots: List<SceneRegisteredSlot> = emptyList(),
        flushStartedNanos: Long = System.nanoTime(),
        vsyncTimeNanos: Long? = null,
        kickSlotRenders: Boolean = true
    ) {
        if (Build.VERSION.SDK_INT >= 29) {
            ensureGlTarget()
        }
        if (!hasPresentTarget) {
            return
        }
        val liveSlotIds = slots.mapTo(mutableSetOf()) { it.id }
        pruneSlotCapturesTo(liveSlotIds)
        pruneBackdropCapturesTo(liveSlotIds)
        prunePresentedTo(liveSlotIds)
        val sourceBounds = IntRect(0, 0, size.width, size.height)
        slots.forEach { slot ->
            val crop = slot.geometry.rootBounds.intersect(sourceBounds)
            if (crop.isEmpty || crop.width <= 0 || crop.height <= 0) return@forEach
            captureRoot(
                RootCaptureRequest(
                    layer = layer,
                    size = IntSize(crop.width, crop.height),
                    cropOffset = Offset(crop.left.toFloat(), crop.top.toFloat()),
                    density = density,
                    layoutDirection = layoutDirection,
                    rootGeneration = rootGeneration,
                    rootRecordedNanos = rootRecordedNanos,
                    slot = slot,
                    liveSlotIds = liveSlotIds,
                    flushStartedNanos = flushStartedNanos,
                    vsyncTimeNanos = vsyncTimeNanos,
                    kickSlotRenders = kickSlotRenders
                )
            )
        }
    }

    @MainThread
    internal fun beginSyncPresent(
        layer: GraphicsLayer,
        size: IntSize,
        density: Density,
        layoutDirection: LayoutDirection,
        rootGeneration: Long,
        rootRecordedNanos: Long = 0L,
        slots: List<SceneRegisteredSlot> = emptyList(),
        flushStartedNanos: Long = System.nanoTime(),
        vsyncTimeNanos: Long? = null,
        kickSlotRenders: Boolean = true
    ): Boolean {
        if (syncPresentJobs.isNotEmpty()) {
            cancelSyncPresent()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ensureGlTarget()
        }
        if (!hasPresentTarget) return false
        val liveSlotIds = slots.mapTo(mutableSetOf()) { it.id }
        pruneSlotCapturesTo(liveSlotIds)
        pruneBackdropCapturesTo(liveSlotIds)
        prunePresentedTo(liveSlotIds)
        rootCaptureLayer = layer
        val sourceBounds = IntRect(0, 0, size.width, size.height)
        slots.forEach { slot ->
            val crop = slot.geometry.rootBounds.intersect(sourceBounds)
            if (crop.isEmpty || crop.width <= 0 || crop.height <= 0) return@forEach
            val request = RootCaptureRequest(
                layer = layer,
                size = IntSize(crop.width, crop.height),
                cropOffset = Offset(crop.left.toFloat(), crop.top.toFloat()),
                density = density,
                layoutDirection = layoutDirection,
                rootGeneration = rootGeneration,
                rootRecordedNanos = rootRecordedNanos,
                slot = slot,
                liveSlotIds = liveSlotIds,
                flushStartedNanos = flushStartedNanos,
                vsyncTimeNanos = vsyncTimeNanos,
                kickSlotRenders = kickSlotRenders
            )
            pendingPresentVsyncBySlot[request.slot.id] =
                request.vsyncTimeNanos ?: request.rootRecordedNanos
            val metricsFrame = metrics.beginRootFrame(request.flushStartedNanos)
            recordUiDataFlushAge(request)
            val captureStartedNanos = System.nanoTime()
            val started = backdropCapture(request.slot.id).submit(
                layer = request.layer,
                size = request.size,
                density = request.density,
                layoutDirection = request.layoutDirection,
                bucketPhysicalSize = false,
                logicalSize = request.size,
                contentOffset = request.cropOffset,
                vsyncTimeNanos = request.vsyncTimeNanos,
                captureScale = BACKDROP_HARDWARE_CAPTURE_SCALE
            )
            if (!started) {
                recordRootCaptureMetrics(
                    success = false,
                    captureStartedNanos = captureStartedNanos,
                    captureFinishedNanos = System.nanoTime()
                )
                return@forEach
            }
            syncPresentJobs += SyncPresentJob(
                request = request,
                metricsFrame = metricsFrame,
                captureStartedNanos = captureStartedNanos
            )
        }
        if (syncPresentJobs.isEmpty()) {
            rootCaptureLayer = null
            return false
        }
        return true
    }

    @MainThread
    internal fun completeSyncPresent() {
        val jobs = takeSyncPresentJobs()
        try {
            if (jobs.isEmpty()) return
            val deadlineElapsedRealtime = SystemClock.elapsedRealtime() + SYNC_WAIT_TIMEOUT_MS
            jobs.forEach { job ->
                val remaining = deadlineElapsedRealtime - SystemClock.elapsedRealtime()
                val rootFrame = backdropCapture(job.request.slot.id).finish(
                    remaining.coerceAtLeast(0)
                )
                val captureFinishedNanos = System.nanoTime()
                job.rootFrame = rootFrame
                job.captureFinishedNanos = captureFinishedNanos
                recordRootCaptureMetrics(
                    success = rootFrame != null,
                    captureStartedNanos = job.captureStartedNanos,
                    captureFinishedNanos = captureFinishedNanos
                )
            }
            jobs.forEach { job ->
                val rootFrame = job.rootFrame ?: return@forEach
                submitCapturedRoot(
                    rootFrame = rootFrame,
                    request = job.request,
                    rootCaptureDurationNanos = job.captureFinishedNanos - job.captureStartedNanos,
                    metricsFrame = job.metricsFrame
                )
            }
        } finally {
            rootCaptureLayer = null
        }
    }

    @MainThread
    internal fun cancelSyncPresent() {
        val jobs = takeSyncPresentJobs()
        try {
            jobs.forEach { job ->
                backdropCapture(job.request.slot.id).finish(timeoutMs = 0)?.close()
            }
        } finally {
            rootCaptureLayer = null
        }
    }

    private fun takeSyncPresentJobs(): ArrayList<SyncPresentJob> {
        val taken = syncPresentJobs
        syncPresentJobs = ArrayList()
        return taken
    }

    private fun captureRoot(request: RootCaptureRequest) {
        val slotId = request.slot.id
        // Ping-pong source layers assume a single in-flight HardwareRenderer
        // read of the source GraphicsLayer. Concurrent crops of the same layer
        // blank the on-screen feed.
        if (inFlightBackdropIds.isNotEmpty()) {
            pendingBackdropRequests[slotId] = request
            return
        }
        pendingBackdropRequests.remove(slotId)
        kickRootCapture(request)
    }

    private fun kickNextBackdropCapture(
        preferOtherThan: SceneSlotId? = null,
        recapturePreferred: Boolean = true
    ) {
        if (inFlightBackdropIds.isNotEmpty()) return
        val next = takeNextPendingBackdrop(preferOtherThan, recapturePreferred)
        if (next != null) {
            kickRootCapture(next)
        } else {
            rootCaptureLayer = null
        }
    }

    private fun takeNextPendingBackdrop(
        preferOtherThan: SceneSlotId?,
        recapturePreferred: Boolean
    ): RootCaptureRequest? {
        val other = pendingBackdropRequests.entries.firstOrNull { it.key != preferOtherThan }
        if (other != null) {
            pendingBackdropRequests.remove(other.key)
            return other.value
        }
        if (!recapturePreferred) return null
        return preferOtherThan?.let { pendingBackdropRequests.remove(it) }
    }

    private fun kickRootCapture(request: RootCaptureRequest) {
        flushSequence++
        val metricsFrame = metrics.beginRootFrame(request.flushStartedNanos)
        recordUiDataFlushAge(request)
        val captureStartedNanos = System.nanoTime()
        val slotId = request.slot.id
        pendingPresentVsyncBySlot[slotId] =
            request.vsyncTimeNanos ?: request.rootRecordedNanos
        inFlightBackdropIds += slotId
        rootCaptureLayer = request.layer
        val capture = backdropCapture(slotId)
        val started = capture.kickCapture(
            layer = request.layer,
            size = request.size,
            density = request.density,
            layoutDirection = request.layoutDirection,
            bucketPhysicalSize = false,
            logicalSize = request.size,
            contentOffset = request.cropOffset,
            vsyncTimeNanos = request.vsyncTimeNanos,
            onCompleted = { onRootCaptureCompleted(request, metricsFrame, captureStartedNanos) },
            captureScale = BACKDROP_HARDWARE_CAPTURE_SCALE
        )
        if (!started) {
            inFlightBackdropIds -= slotId
            recordRootCaptureMetrics(
                success = false,
                captureStartedNanos = captureStartedNanos,
                captureFinishedNanos = System.nanoTime()
            )
            if (request.size != IntSize.Zero) {
                pendingBackdropRequests[slotId] = request
                scheduleRootRetry()
            }
            kickNextBackdropCapture(
                preferOtherThan = slotId,
                recapturePreferred = false
            )
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
        val slotId = request.slot.id
        inFlightBackdropIds -= slotId
        val rootFrame = backdropCapture(slotId).takeLatestFrame()
        when {
            rootFrame == null -> recordRootCaptureMetrics(
                success = false,
                captureStartedNanos = captureStartedNanos,
                captureFinishedNanos = captureFinishedNanos
            )

            !hasPresentTarget -> rootFrame.close()

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
        kickNextBackdropCapture(preferOtherThan = slotId)
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
        val remappedSlot = request.slot.copy(
            geometry = request.slot.geometry.relativeTo(request.cropOffset)
        )
        val uiCapture = SceneRenderMetricsLog.time(
            phase = "snapshot.assemble.total",
            details = "slots=1"
        ) {
            assembleSnapshot(
                rootFrame = rootFrame,
                request = request,
                remappedSlot = remappedSlot,
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
        remappedSlot: SceneRegisteredSlot,
        rootCaptureDurationNanos: Long,
        metricsFrame: SceneMetricsFrame?
    ): UiSceneCapture = trace("SceneSnapshot#assemble") {
        val density = request.density
        val layoutDirection = request.layoutDirection
        val slots = listOf(remappedSlot)
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
            val renderSlots = selectRenderSlots(slots, contentFrames, request.liveSlotIds)

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
                        liveSlotIds = request.liveSlotIds,
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
        contentFrames: Map<SceneSlotId, CapturedLayerFrame>,
        liveSlotIds: Set<SceneSlotId>
    ): List<SceneRegisteredSlot> = SceneRenderMetricsLog.time(
        phase = "snapshot.pruneSlots",
        details = "available=${contentFrames.size}"
    ) {
        trace("SceneSnapshot#pruneSlots") {
            pruneSlotCapturesTo(liveSlotIds)
            slots.filter { slot ->
                slot.id in contentFrames ||
                    slot.id in glResidentContent ||
                    slot.backdrop != null
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

    @MainThread
    @android.annotation.SuppressLint("NewApi")
    internal fun ensureGlTarget() {
        if (Build.VERSION.SDK_INT < 29) return
        if (renderTarget != null) return
        val size = EGL_PLACEHOLDER_SIZE
        metrics.recordTargetSize(size)
        renderTarget = glOwner.attachOffscreenRenderTarget(size)
        attachedSize = size
    }

    @MainThread
    internal fun detachPresentTarget() {
        renderTarget?.detach()
        renderTarget = null
        attachedSize = IntSize.Zero
        presentedImages.clear()
        metrics.recordTargetSize(IntSize.Zero)
    }

    @MainThread
    private fun installPresentedImage(slotId: SceneSlotId, image: ImageBitmap) {
        val invalidate = presentInvalidates[slotId] ?: return
        presentedImages[slotId] = image
        invalidate()
    }

    internal fun onRootDetached() {
        releaseSlotResources()
        detachPresentTarget()
    }

    internal fun release() {
        mainHandler.removeCallbacks(rootRetryRunnable)
        rootRetryScheduled = false
        pendingBackdropRequests.clear()
        inFlightBackdropIds.clear()
        detachPresentTarget()
        backdropCaptures.values.forEach { it.release() }
        backdropCaptures.clear()
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

    private fun pruneBackdropCapturesTo(liveIds: Set<SceneSlotId>) {
        val removedIds = backdropCaptures.keys - liveIds
        removedIds.forEach { slotId ->
            backdropCaptures.remove(slotId)?.release()
            inFlightBackdropIds.remove(slotId)
            pendingBackdropRequests.remove(slotId)
        }
        if (inFlightBackdropIds.isEmpty()) {
            rootCaptureLayer = null
        }
    }

    private fun prunePresentedTo(liveIds: Set<SceneSlotId>) {
        val removedIds = presentedImages.keys - liveIds
        removedIds.forEach { slotId ->
            presentedImages.remove(slotId)
            presentInvalidates.remove(slotId)
            pendingPresentVsyncBySlot.remove(slotId)
            drawnPresentVsyncBySlot.remove(slotId)
        }
    }

    private fun backdropCapture(slotId: SceneSlotId): GraphicsLayerCapture {
        return backdropCaptures.getOrPut(slotId) {
            GraphicsLayerCapture(
                executorName = "ImlaBackdrop${slotId.value}",
                textureCaptureFactory = glOwner::createCanvasTextureCapture,
                captureThread = captureThread,
                platformRendererCount = 2
            )
        }
    }

    private fun releaseSlotResources() {
        slotCaptures.values.forEach { it.release() }
        slotCaptures.clear()
        backdropCaptures.values.forEach { it.release() }
        backdropCaptures.clear()
        inFlightBackdropIds.clear()
        pendingBackdropRequests.clear()
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

    private fun recordUiDataFlushAge(request: RootCaptureRequest) {
        val uiDataNanos = request.latestUiDataNanos()
        if (uiDataNanos <= lastFlushMeasuredUiDataNanos) return
        lastFlushMeasuredUiDataNanos = uiDataNanos
        metrics.recordUiDataFlushAge(
            ageNanos = request.flushStartedNanos - uiDataNanos
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
private const val SYNC_WAIT_TIMEOUT_MS = 100L
private val EGL_PLACEHOLDER_SIZE = IntSize(64, 64)

private data class RootCaptureRequest(
    val layer: GraphicsLayer,
    val size: IntSize,
    val cropOffset: Offset,
    val density: Density,
    val layoutDirection: LayoutDirection,
    val rootGeneration: Long,
    val rootRecordedNanos: Long,
    val slot: SceneRegisteredSlot,
    val liveSlotIds: Set<SceneSlotId>,
    val flushStartedNanos: Long,
    val vsyncTimeNanos: Long?,
    val kickSlotRenders: Boolean
)

private data class SyncPresentJob(
    val request: RootCaptureRequest,
    val metricsFrame: SceneMetricsFrame?,
    val captureStartedNanos: Long,
    var rootFrame: CapturedLayerFrame? = null,
    var captureFinishedNanos: Long = 0L
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
        slots = listOf(slot)
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
