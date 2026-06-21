package dev.serhiiyaremych.imla.internal.metrics

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.stats.ShaderStats
import dev.serhiiyaremych.imla.internal.render.stats.ShaderStatsSnapshot
import dev.serhiiyaremych.imla.internal.render.scheduler.SceneFrameDropReason

/**
 * Per-renderer metrics collector for the scene2 prototype.
 *
 * Main-thread capture code and the GL thread both record phase boundaries here. Readers only get
 * immutable [SceneMetricsSnapshot] values, so Compose can poll the latest view without observing
 * the mutable accumulators directly. The collector is diagnostic only: it must not feed render
 * scheduling, throttling, invalidation, or lifecycle decisions.
 */
internal class SceneMetrics {
    private val lock = Any()
    private val mainSubmitTime = DurationWindow()
    private val mainRootCaptureTime = DurationWindow()
    private val mainSlotCaptureTime = DurationWindow()
    private val mainSlotDiscoveryTime = DurationWindow()
    private val mainSlotRendererTime = DurationWindow()
    private val mainSlotLayerCaptureTime = DurationWindow()
    private val mainSlotBookkeepingTime = DurationWindow()
    private val mainProgressiveMaskCaptureTime = DurationWindow()
    private val mainClipShapeCaptureTime = DurationWindow()
    private val uiDataPreDrawAge = DurationWindow()
    private val uiDataFrameBuildAge = DurationWindow()
    private val rootCaptureAfterRenderCompleteTime = DurationWindow()
    private val mainPostCaptureWait = DurationWindow()
    private val glQueueDelay = DurationWindow()
    private val glRootImportTime = DurationWindow()
    private val glSlotImportTime = DurationWindow()
    private val glMaskImportTime = DurationWindow()
    private val glClipImportTime = DurationWindow()
    private val glRenderTime = DurationWindow()
    private val glFrameTime = DurationWindow()
    private val drawLatency = DurationWindow()
    private val renderedFrameTimes = LongWindow()
    private val slotCaptureAttempted = LatestLongMetric()
    private val slotCaptureCaptured = LatestLongMetric()
    private val renderPasses = SceneRenderPassMetric.entries.associateWith {
        SceneRenderPassWindow()
    }
    private val featureStats = SceneFeatureStatsWindow()

    private var nextFrameId = 0L
    private var submittedFrames = 0L
    private var renderedFrames = 0L
    private var droppedFrames = 0L
    private var capturePacedFrames = 0L
    private var renderRequestPacedFrames = 0L
    private var sceneWorkCoalescedFrames = 0L
    private var rootCaptureDeferredFrames = 0L
    private var captureFailures = 0L
    private var targetSize = IntSize.Zero
    private var dropDebugMarkerPending = false

    fun beginRootFrame(preDrawStartedNanos: Long): SceneMetricsFrame {
        return synchronized(lock) {
            SceneMetricsFrame(
                id = nextFrameId++,
                preDrawStartedNanos = preDrawStartedNanos
            )
        }
    }

    fun recordRootCapture(
        startedNanos: Long,
        finishedNanos: Long,
        success: Boolean,
        renderCompleteGapNanos: Long
    ) {
        synchronized(lock) {
            mainRootCaptureTime.add(finishedNanos - startedNanos)
            if (renderCompleteGapNanos > 0L) {
                rootCaptureAfterRenderCompleteTime.add(renderCompleteGapNanos)
            }
            if (!success) {
                captureFailures++
            }
        }
    }

    fun recordSlotCapture(
        startedNanos: Long,
        finishedNanos: Long,
        discoveryNanos: Long,
        rendererNanos: Long,
        layerCaptureNanos: Long,
        bookkeepingNanos: Long,
        attemptedSlots: Long,
        capturedSlots: Long
    ) {
        synchronized(lock) {
            mainSlotCaptureTime.add(finishedNanos - startedNanos)
            mainSlotDiscoveryTime.add(discoveryNanos)
            mainSlotRendererTime.add(rendererNanos)
            mainSlotLayerCaptureTime.add(layerCaptureNanos)
            mainSlotBookkeepingTime.add(bookkeepingNanos)
            slotCaptureAttempted.add(attemptedSlots)
            slotCaptureCaptured.add(capturedSlots)
        }
    }

    fun recordProgressiveMaskCapture(
        startedNanos: Long,
        finishedNanos: Long
    ) {
        synchronized(lock) {
            mainProgressiveMaskCaptureTime.add(finishedNanos - startedNanos)
        }
    }

    fun recordClipShapeCapture(
        startedNanos: Long,
        finishedNanos: Long
    ) {
        synchronized(lock) {
            mainClipShapeCaptureTime.add(finishedNanos - startedNanos)
        }
    }

    fun recordUiDataPreDrawAge(ageNanos: Long) {
        synchronized(lock) {
            uiDataPreDrawAge.add(ageNanos)
        }
    }

    fun recordUiDataFrameBuildAge(ageNanos: Long) {
        synchronized(lock) {
            uiDataFrameBuildAge.add(ageNanos)
        }
    }

    fun recordSubmitted(frame: SceneMetricsFrame, readyAtNanos: Long) {
        val submittedNanos = System.nanoTime()
        frame.submittedNanos = submittedNanos
        synchronized(lock) {
            submittedFrames++
            mainSubmitTime.add(submittedNanos - frame.preDrawStartedNanos)
            mainPostCaptureWait.add(submittedNanos - readyAtNanos)
        }
    }

    fun recordDropped(reason: SceneFrameDropReason) {
        synchronized(lock) {
            droppedFrames++
            dropDebugMarkerPending = true
            if (reason == SceneFrameDropReason.RenderRequestInFlight) {
                renderRequestPacedFrames++
            }
        }
    }

    fun consumeDropDebugMarker(): Boolean {
        return synchronized(lock) {
            val pending = dropDebugMarkerPending
            dropDebugMarkerPending = false
            pending
        }
    }

    fun recordCapturePaced() {
        synchronized(lock) {
            capturePacedFrames++
        }
    }

    fun recordSceneWorkCoalesced() {
        synchronized(lock) {
            sceneWorkCoalescedFrames++
        }
    }

    fun recordRootCaptureDeferred() {
        synchronized(lock) {
            rootCaptureDeferredFrames++
        }
    }

    fun recordTargetSize(size: IntSize) {
        synchronized(lock) {
            targetSize = size
        }
    }

    fun recordRootImport(
        frame: SceneMetricsFrame?,
        startedNanos: Long,
        finishedNanos: Long
    ) {
        if (frame == null) return
        frame.rootImportStartedNanos = startedNanos
        frame.rootImportDurationNanos = finishedNanos - startedNanos
        synchronized(lock) {
            val submittedNanos = frame.submittedNanos
            if (submittedNanos > 0L) {
                glQueueDelay.add(startedNanos - submittedNanos)
            }
            glRootImportTime.add(frame.rootImportDurationNanos)
        }
    }

    fun recordSlotImport(
        frame: SceneMetricsFrame?,
        startedNanos: Long,
        finishedNanos: Long
    ) {
        if (frame == null) return
        val durationNanos = finishedNanos - startedNanos
        frame.slotImportDurationNanos = durationNanos
        synchronized(lock) {
            glSlotImportTime.add(durationNanos)
        }
    }

    fun recordMaskImport(
        frame: SceneMetricsFrame?,
        startedNanos: Long,
        finishedNanos: Long
    ) {
        if (frame == null) return
        val durationNanos = finishedNanos - startedNanos
        frame.maskImportDurationNanos = durationNanos
        synchronized(lock) {
            glMaskImportTime.add(durationNanos)
        }
    }

    fun recordClipImport(
        frame: SceneMetricsFrame?,
        startedNanos: Long,
        finishedNanos: Long
    ) {
        if (frame == null) return
        val durationNanos = finishedNanos - startedNanos
        frame.clipImportDurationNanos = durationNanos
        synchronized(lock) {
            glClipImportTime.add(durationNanos)
        }
    }

    fun recordGlRender(
        frame: SceneMetricsFrame?,
        startedNanos: Long,
        finishedNanos: Long
    ) {
        if (frame == null) return
        val renderDurationNanos = finishedNanos - startedNanos
        synchronized(lock) {
            frame.renderPassStats().forEach { passStats ->
                renderPasses.getValue(passStats.pass).add(passStats)
            }
            featureStats.add(frame.featureStats)
            glRenderTime.add(renderDurationNanos)
            glFrameTime.add(
                frame.rootImportDurationNanos +
                        frame.slotImportDurationNanos +
                        frame.maskImportDurationNanos +
                        frame.clipImportDurationNanos +
                        renderDurationNanos
            )
        }
    }

    fun recordRenderComplete(frame: SceneMetricsFrame?) {
        if (frame == null) return
        val completedNanos = System.nanoTime()
        synchronized(lock) {
            renderedFrames++
            renderedFrameTimes.add(completedNanos)
            drawLatency.add(completedNanos - frame.preDrawStartedNanos)
        }
    }

    fun snapshot(): SceneMetricsSnapshot {
        val nowNanos = System.nanoTime()
        return synchronized(lock) {
            SceneMetricsSnapshot(
                submittedFrames = submittedFrames,
                renderedFrames = renderedFrames,
                droppedFrames = droppedFrames,
                capturePacedFrames = capturePacedFrames,
                renderRequestPacedFrames = renderRequestPacedFrames,
                sceneWorkCoalescedFrames = sceneWorkCoalescedFrames,
                rootCaptureDeferredFrames = rootCaptureDeferredFrames,
                captureFailures = captureFailures,
                targetSize = targetSize,
                fps = renderedFrameTimes.fps(nowNanos),
                mainSubmitMs = mainSubmitTime.averageMs(),
                mainRootCaptureMs = mainRootCaptureTime.averageMs(),
                mainSlotCaptureMs = mainSlotCaptureTime.averageMs(),
                mainSlotDiscoveryMs = mainSlotDiscoveryTime.averageMs(),
                mainSlotRendererMs = mainSlotRendererTime.averageMs(),
                mainSlotLayerCaptureMs = mainSlotLayerCaptureTime.averageMs(),
                mainSlotBookkeepingMs = mainSlotBookkeepingTime.averageMs(),
                latestSlotCaptureAttempted = slotCaptureAttempted.latest(),
                latestSlotCaptureCaptured = slotCaptureCaptured.latest(),
                mainProgressiveMaskCaptureMs = mainProgressiveMaskCaptureTime.averageMs(),
                mainClipShapeCaptureMs = mainClipShapeCaptureTime.averageMs(),
                uiDataPreDrawAgeMs = uiDataPreDrawAge.averageMs(),
                uiDataFrameBuildAgeMs = uiDataFrameBuildAge.averageMs(),
                latestUiDataPreDrawAgeMs = uiDataPreDrawAge.latestMs(),
                latestUiDataFrameBuildAgeMs = uiDataFrameBuildAge.latestMs(),
                rootCaptureAfterRenderCompleteMs = rootCaptureAfterRenderCompleteTime.averageMs(),
                latestRootCaptureAfterRenderCompleteMs = rootCaptureAfterRenderCompleteTime.latestMs(),
                mainPostCaptureWaitMs = mainPostCaptureWait.averageMs(),
                glQueueDelayMs = glQueueDelay.averageMs(),
                glRootImportMs = glRootImportTime.averageMs(),
                glSlotImportMs = glSlotImportTime.averageMs(),
                glMaskImportMs = glMaskImportTime.averageMs(),
                glClipImportMs = glClipImportTime.averageMs(),
                glRenderMs = glRenderTime.averageMs(),
                glFrameMs = glFrameTime.averageMs(),
                drawLatencyMs = drawLatency.averageMs(),
                latestMainSubmitMs = mainSubmitTime.latestMs(),
                latestGlFrameMs = glFrameTime.latestMs(),
                latestDrawLatencyMs = drawLatency.latestMs(),
                featureStats = featureStats.snapshot(),
                resourceStats = ShaderStats.snapshot(),
                renderPasses = SceneRenderPassMetric.entries.map { pass ->
                    renderPasses.getValue(pass).snapshot(pass)
                }
            )
        }
    }
}

/**
 * Immutable point-in-time view of one renderer instance.
 *
 * Average duration values use a small rolling window of recent samples. `latest*` values expose
 * the newest sample for quick spike detection. All durations are milliseconds.
 *
 * @property submittedFrames root snapshots handed from the frame scheduler to GL.
 * @property renderedFrames GL render-target completion callbacks observed after swap/flush.
 * @property droppedFrames captured root snapshots closed before GL import, including scheduler
 *   supersedes, output lifecycle misses, and presentation pacing.
 * @property capturePacedFrames dirty source ticks skipped before root capture by presentation
 *   cadence pacing.
 * @property renderRequestPacedFrames captured snapshots intentionally closed while a previous
 *   GLRenderer request callback is still in flight.
 * @property sceneWorkCoalescedFrames dirty source ticks skipped without root capture, snapshot
 *   assembly, GL submission, or final presentation under sustained production pressure.
 * @property rootCaptureDeferredFrames root captures deferred to a later Choreographer callback.
 * @property captureFailures root capture attempts that did not produce a hardware buffer frame.
 * @property targetSize current output surface size in physical pixels.
 * @property fps approximate GL-present FPS from render-complete callbacks in the last second.
 * @property mainSubmitMs average time from source modifier pre-draw to GL queue submission.
 * @property mainRootCaptureMs average time spent capturing the root GraphicsLayer to HardwareBuffer.
 * @property mainSlotCaptureMs average time spent capturing slot GraphicsLayers to HardwareBuffers.
 * @property mainSlotDiscoveryMs average time spent scanning slots for capturable content.
 * @property mainSlotRendererMs average time spent finding or creating per-slot capture renderers.
 * @property mainSlotLayerCaptureMs average time spent inside slot GraphicsLayer capture calls.
 * @property mainSlotBookkeepingMs average time spent storing successfully captured slot frames.
 * @property latestSlotCaptureAttempted latest number of slots that had content capture attempted.
 * @property latestSlotCaptureCaptured latest number of slots that produced a captured frame.
 * @property mainProgressiveMaskCaptureMs average time spent rasterizing changed progressive masks.
 * @property mainClipShapeCaptureMs average time spent rasterizing changed clip shapes.
 * @property uiDataPreDrawAgeMs average max age of UI data at pre-draw flush.
 * @property uiDataFrameBuildAgeMs average max age of UI data at snapshot assembly.
 * @property latestUiDataPreDrawAgeMs newest max UI data age at pre-draw flush.
 * @property latestUiDataFrameBuildAgeMs newest max UI data age at snapshot assembly.
 * @property rootCaptureAfterRenderCompleteMs average time from GL render-complete callback to the
 *   next root capture start.
 * @property latestRootCaptureAfterRenderCompleteMs newest render-complete to root-capture gap.
 * @property mainPostCaptureWaitMs average time from all captures ready to scheduler GL handoff.
 * @property glQueueDelayMs average time from main-thread submission to GL root import start.
 * @property glRootImportMs average time spent waiting/importing the root HardwareBuffer into GL.
 * @property glSlotImportMs average time spent waiting/importing content HardwareBuffers into GL.
 * @property glMaskImportMs average time spent waiting/importing progressive mask HardwareBuffers.
 * @property glClipImportMs average time spent waiting/importing clip shape HardwareBuffers.
 * @property glRenderMs average time spent rendering the scene pipeline into the output surface.
 * @property glFrameMs average GL work for one scene frame: imports and render.
 * @property drawLatencyMs average time from source modifier pre-draw to GL render completion.
 * @property latestMainSubmitMs newest main-thread pre-draw-to-submit sample.
 * @property latestGlFrameMs newest GL import-plus-draw sample.
 * @property latestDrawLatencyMs newest pre-draw-to-render-complete sample.
 * @property featureStats latest rendered frame feature counts.
 * @property resourceStats current global GL resource lifetime counters.
 * @property renderPasses GL-thread render pass issue-wall timings and estimated work volume.
 */
internal data class SceneMetricsSnapshot(
    val submittedFrames: Long = 0L,
    val renderedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val capturePacedFrames: Long = 0L,
    val renderRequestPacedFrames: Long = 0L,
    val sceneWorkCoalescedFrames: Long = 0L,
    val rootCaptureDeferredFrames: Long = 0L,
    val captureFailures: Long = 0L,
    val targetSize: IntSize = IntSize.Zero,
    val fps: Float = 0f,
    val mainSubmitMs: Float = 0f,
    val mainRootCaptureMs: Float = 0f,
    val mainSlotCaptureMs: Float = 0f,
    val mainSlotDiscoveryMs: Float = 0f,
    val mainSlotRendererMs: Float = 0f,
    val mainSlotLayerCaptureMs: Float = 0f,
    val mainSlotBookkeepingMs: Float = 0f,
    val latestSlotCaptureAttempted: Long = 0L,
    val latestSlotCaptureCaptured: Long = 0L,
    val mainProgressiveMaskCaptureMs: Float = 0f,
    val mainClipShapeCaptureMs: Float = 0f,
    val uiDataPreDrawAgeMs: Float = 0f,
    val uiDataFrameBuildAgeMs: Float = 0f,
    val latestUiDataPreDrawAgeMs: Float = 0f,
    val latestUiDataFrameBuildAgeMs: Float = 0f,
    val rootCaptureAfterRenderCompleteMs: Float = 0f,
    val latestRootCaptureAfterRenderCompleteMs: Float = 0f,
    val mainPostCaptureWaitMs: Float = 0f,
    val glQueueDelayMs: Float = 0f,
    val glRootImportMs: Float = 0f,
    val glSlotImportMs: Float = 0f,
    val glMaskImportMs: Float = 0f,
    val glClipImportMs: Float = 0f,
    val glRenderMs: Float = 0f,
    val glFrameMs: Float = 0f,
    val drawLatencyMs: Float = 0f,
    val latestMainSubmitMs: Float = 0f,
    val latestGlFrameMs: Float = 0f,
    val latestDrawLatencyMs: Float = 0f,
    val featureStats: SceneFeatureStats = SceneFeatureStats(),
    val resourceStats: ShaderStatsSnapshot = ShaderStatsSnapshot(),
    val renderPasses: List<SceneRenderPassSnapshot> = emptyList()
)

internal data class SceneFeatureStats(
    val slots: Long = 0L,
    val backdropSlots: Long = 0L,
    val cumulativeBackdropSlots: Long = 0L,
    val backdropClipSlots: Long = 0L,
    val contentClipSlots: Long = 0L,
    val tintSlots: Long = 0L,
    val noiseSlots: Long = 0L
)

internal enum class SceneRenderPassMetric {
    RootPresent,
    NoiseGenerate,
    BackdropPrepare,
    BackdropBlur,
    BackdropComposite,
    StencilClip,
    SlotContent,
    FinalPresent
}

internal data class SceneRenderPassSnapshot(
    val pass: SceneRenderPassMetric,
    val issueMs: Float = 0f,
    val latestCalls: Long = 0L,
    val latestOutputPixels: Long = 0L,
    val latestTextureSamples: Long = 0L
)

/**
 * Correlation token for one root frame as it moves from main-thread capture into GL rendering.
 *
 * The token is intentionally small and carries only timing anchors that are needed to compute
 * cross-thread latency. It is attached to the submitted scene snapshot and then to the imported
 * root resource so render-complete can be attributed to the same root capture.
 */
internal class SceneMetricsFrame internal constructor(
    val id: Long,
    val preDrawStartedNanos: Long
) {
    @Volatile
    internal var submittedNanos: Long = 0L

    @Volatile
    internal var rootImportStartedNanos: Long = 0L

    @Volatile
    internal var rootImportDurationNanos: Long = 0L

    @Volatile
    internal var slotImportDurationNanos: Long = 0L

    @Volatile
    internal var maskImportDurationNanos: Long = 0L

    @Volatile
    internal var clipImportDurationNanos: Long = 0L

    private val renderPassDurationsNanos = LongArray(SceneRenderPassMetric.entries.size)
    private val renderPassCalls = LongArray(SceneRenderPassMetric.entries.size)
    private val renderPassOutputPixels = LongArray(SceneRenderPassMetric.entries.size)
    private val renderPassTextureSamples = LongArray(SceneRenderPassMetric.entries.size)
    private var mutableFeatureStats = SceneFeatureStats()

    internal val featureStats: SceneFeatureStats
        get() = mutableFeatureStats

    internal fun recordFeatureStats(stats: SceneFeatureStats) {
        mutableFeatureStats = stats
    }

    internal fun recordRenderPass(
        pass: SceneRenderPassMetric,
        startedNanos: Long,
        finishedNanos: Long,
        outputPixels: Long = 0L,
        textureSamples: Long = 0L
    ) {
        val index = pass.ordinal
        renderPassDurationsNanos[index] += (finishedNanos - startedNanos).coerceAtLeast(0L)
        renderPassCalls[index]++
        renderPassOutputPixels[index] += outputPixels.coerceAtLeast(0L)
        renderPassTextureSamples[index] += textureSamples.coerceAtLeast(0L)
    }

    internal fun renderPassStats(): List<SceneRenderPassFrameStats> {
        return SceneRenderPassMetric.entries.map { pass ->
            val index = pass.ordinal
            SceneRenderPassFrameStats(
                pass = pass,
                issueNanos = renderPassDurationsNanos[index],
                calls = renderPassCalls[index],
                outputPixels = renderPassOutputPixels[index],
                textureSamples = renderPassTextureSamples[index]
            )
        }
    }
}

internal data class SceneRenderPassFrameStats(
    val pass: SceneRenderPassMetric,
    val issueNanos: Long,
    val calls: Long,
    val outputPixels: Long,
    val textureSamples: Long
)

private class SceneFeatureStatsWindow {
    private val slots = LatestLongMetric()
    private val backdropSlots = LatestLongMetric()
    private val cumulativeBackdropSlots = LatestLongMetric()
    private val backdropClipSlots = LatestLongMetric()
    private val contentClipSlots = LatestLongMetric()
    private val tintSlots = LatestLongMetric()
    private val noiseSlots = LatestLongMetric()

    fun add(stats: SceneFeatureStats) {
        slots.add(stats.slots)
        backdropSlots.add(stats.backdropSlots)
        cumulativeBackdropSlots.add(stats.cumulativeBackdropSlots)
        backdropClipSlots.add(stats.backdropClipSlots)
        contentClipSlots.add(stats.contentClipSlots)
        tintSlots.add(stats.tintSlots)
        noiseSlots.add(stats.noiseSlots)
    }

    fun snapshot(): SceneFeatureStats {
        return SceneFeatureStats(
            slots = slots.latest(),
            backdropSlots = backdropSlots.latest(),
            cumulativeBackdropSlots = cumulativeBackdropSlots.latest(),
            backdropClipSlots = backdropClipSlots.latest(),
            contentClipSlots = contentClipSlots.latest(),
            tintSlots = tintSlots.latest(),
            noiseSlots = noiseSlots.latest()
        )
    }
}

private class SceneRenderPassWindow {
    private val issueTime = DurationWindow()
    private val calls = LatestLongMetric()
    private val outputPixels = LatestLongMetric()
    private val textureSamples = LatestLongMetric()

    fun add(stats: SceneRenderPassFrameStats) {
        issueTime.add(stats.issueNanos)
        calls.add(stats.calls)
        outputPixels.add(stats.outputPixels)
        textureSamples.add(stats.textureSamples)
    }

    fun snapshot(pass: SceneRenderPassMetric): SceneRenderPassSnapshot {
        return SceneRenderPassSnapshot(
            pass = pass,
            issueMs = issueTime.averageMs(),
            latestCalls = calls.latest(),
            latestOutputPixels = outputPixels.latest(),
            latestTextureSamples = textureSamples.latest()
        )
    }
}

private class DurationWindow(
    private val capacity: Int = 60
) {
    private val values = LongArray(capacity)
    private var index = 0
    private var count = 0
    private var sum = 0L
    private var latest = 0L

    fun add(durationNanos: Long) {
        val clampedDuration = durationNanos.coerceAtLeast(0L)
        latest = clampedDuration
        if (count == capacity) {
            sum -= values[index]
        } else {
            count++
        }
        values[index] = clampedDuration
        sum += clampedDuration
        index = (index + 1) % capacity
    }

    fun averageMs(): Float {
        return if (count == 0) 0f else sum.toMillisFloat() / count
    }

    fun latestMs(): Float = latest.toMillisFloat()
}

private class LongWindow(
    private val capacity: Int = 120
) {
    private val values = LongArray(capacity)
    private var index = 0
    private var count = 0

    fun add(value: Long) {
        values[index] = value
        index = (index + 1) % capacity
        if (count < capacity) {
            count++
        }
    }

    fun fps(nowNanos: Long): Float {
        var newest = 0L
        var oldest = Long.MAX_VALUE
        var visibleCount = 0
        val minNanos = nowNanos - FPS_WINDOW_NANOS
        repeat(count) { itemIndex ->
            val value = values[itemIndex]
            if (value >= minNanos) {
                visibleCount++
                newest = newest.coerceAtLeast(value)
                oldest = oldest.coerceAtMost(value)
            }
        }
        if (visibleCount < 2 || newest <= oldest) return 0f
        return (visibleCount - 1) * NANOS_PER_SECOND.toFloat() / (newest - oldest)
    }

    private companion object {
        private const val FPS_WINDOW_NANOS = 1_000_000_000L
    }
}

private class LatestLongMetric {
    private var latest = 0L

    fun add(value: Long) {
        latest = value.coerceAtLeast(0L)
    }

    fun latest(): Long = latest
}

private fun Long.toMillisFloat(): Float {
    return this / NANOS_PER_MILLISECOND.toFloat()
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
