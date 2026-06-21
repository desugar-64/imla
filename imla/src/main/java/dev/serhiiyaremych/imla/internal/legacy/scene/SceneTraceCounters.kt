/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicLong

internal fun interface SceneTraceCounterRecorder {
    fun setCounter(name: String, value: Long)
}

private object AndroidSceneTraceCounterRecorder : SceneTraceCounterRecorder {
    override fun setCounter(name: String, value: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.setCounter(name, value)
        }
    }
}

/**
 * Internal Perfetto counter facade for scene-renderer diagnostics.
 *
 * Atlas counters exist because atlas-on and atlas-off frames can be visually identical while taking
 * different render paths. They prove that enabled frames planned atlas work, executed the isolated
 * atlas pipeline, produced copy/blur batches, composited slots from atlas lookups, or deliberately
 * fell back to the per-slot path. They are proof hooks only: they do not enable atlas rendering,
 * own atlas state, or influence pass decisions.
 */
internal object SceneTraceCounters {
    private const val PREFIX = "ImlaScene"

    private var recorder: SceneTraceCounterRecorder = AndroidSceneTraceCounterRecorder

    private val rootCaptureCommits = Counter("root.capture.commit")
    private val rootCaptureFailures = Counter("root.capture.failed")
    private val frameCommits = Counter("frame.commit.total")
    private val styleOnlyFrameCommits = Counter("frame.commit.styleOnly")
    private val geometryRefreshes = Counter("geometry.refresh")
    private val renderRequests = Counter("render.request.total")
    private val renderConsumes = Counter("render.consume.total")
    private val renders = Counter("render.total")
    private val styleOnlyRenders = Counter("render.styleOnly")
    private val rootTextureMissingRenders = Counter("render.rootTextureMissing")
    private val sceneFrameMissingRenders = Counter("render.sceneFrameMissing")
    private val slotContentCaptures = Counter("slot.content.capture")
    private val slotContentNormalCaptures = Counter("slot.content.capture.normal")
    private val slotContentBudgetForcedCaptures = Counter("slot.content.capture.budgetForced")
    private val slotContentStaleGeometryReuses = Counter("slot.content.reuse.staleGeometry")
    private val slotContentSkipNoContent = Counter("slot.content.skip.noContent")
    private val slotContentSkipNoLayer = Counter("slot.content.skip.noLayer")
    private val slotContentSkipNoGeometry = Counter("slot.content.skip.noGeometry")
    private val slotContentStaleRemovals = Counter("slot.content.staleRemove")
    private val maskCaptures = Counter("slot.mask.capture")
    private val clipCaptures = Counter("slot.clip.capture")
    private val maskRemovals = Counter("slot.mask.remove")
    private val clipRemovals = Counter("slot.clip.remove")
    private val slotCompositePasses = Counter("render.slot.composite")
    private val backdropCompositePasses = Counter("render.backdrop.composite")
    private val contentCompositePasses = Counter("render.content.composite")
    private val stencilSetups = Counter("render.stencil.setup")
    private val atlasPreflightFrames = Counter("atlas.preflight.frame")
    private val atlasEligibleRequests = Counter("atlas.preflight.eligibleRequest")
    private val atlasFallbackRequests = Counter("atlas.preflight.fallbackRequest")
    private val atlasSkippedRequests = Counter("atlas.preflight.skippedRequest")
    private val atlasNoEligibleFrames = Counter("atlas.preflight.noEligibleFrame")
    private val atlasPipelineExecutions = Counter("atlas.pipeline.execute")
    private val atlasCopyBatches = Counter("atlas.copy.batch")
    private val atlasBlurBatches = Counter("atlas.blur.batch")
    private val atlasLookupEntries = Counter("atlas.lookup.entry")
    private val atlasCompositeSlots = Counter("atlas.composite.slot")
    private val atlasFallbackSlots = Counter("atlas.fallback.slot")
    private val atlasEligibilityReasonCounters = SceneBlurAtlasEligibilityReason.entries.associateWith {
        Counter("atlas.preflight.eligible.${it.name}")
    }
    private val atlasFallbackReasonCounters = SceneBlurAtlasFallbackReason.entries.associateWith {
        Counter("atlas.preflight.fallback.${it.name}")
    }
    private val atlasSkipReasonCounters = SceneBlurAtlasSkipReason.entries.associateWith {
        Counter("atlas.preflight.skipped.${it.name}")
    }

    private val renderRequestReasonCounters = RenderReason.entries.associateWith {
        Counter("render.request.${it.name}")
    }
    private val renderConsumeReasonCounters = RenderReason.entries.associateWith {
        Counter("render.consume.${it.name}")
    }
    private val frameCommitReasonCounters = RenderReason.entries.associateWith {
        Counter("frame.commit.${it.name}")
    }

    private val renderGateBlockedRequests = Counter("render.gate.blocked.total")
    private val renderGateBlockedReasonCounters = RenderReason.entries.associateWith {
        Counter("render.gate.blocked.${it.name}")
    }
    private val renderGateDrained = Counter("render.gate.drained.total")

    fun rootCaptureCommitted() {
        rootCaptureCommits.increment()
    }

    fun rootCaptureFailed() {
        rootCaptureFailures.increment()
    }

    fun geometryRefreshed() {
        geometryRefreshes.increment()
    }

    fun frameCommitted(frame: CommittedSceneFrame) {
        frameCommits.increment()
        setGauge("gauge.frame.generation", frame.generation)
        setGauge("gauge.slot.count", frame.slots.size.toLong())
        frame.reasons.forEach { reason ->
            frameCommitReasonCounters.getValue(reason).increment()
        }
        if (frame.isStyleOnlyUpdate()) {
            styleOnlyFrameCommits.increment()
        }
    }

    fun renderRequested(reason: RenderReason) {
        renderRequests.increment()
        renderRequestReasonCounters.getValue(reason).increment()
    }

    fun renderFrameConsumed(frame: CommittedSceneFrame) {
        renderConsumes.increment()
        setGauge("gauge.render.frame.generation", frame.generation)
        setGauge("gauge.render.slot.count", frame.slots.size.toLong())
        frame.reasons.forEach { reason ->
            renderConsumeReasonCounters.getValue(reason).increment()
        }
        if (frame.isStyleOnlyUpdate()) {
            styleOnlyRenders.increment()
        }
    }

    fun renderStarted(slotCount: Int) {
        renders.increment()
        setGauge("gauge.render.currentSlotCount", slotCount.toLong())
    }

    fun rootTextureMissingRender() {
        rootTextureMissingRenders.increment()
    }

    fun sceneFrameMissingRender() {
        sceneFrameMissingRenders.increment()
    }

    fun slotContentCaptured() {
        slotContentCaptures.increment()
    }

    fun slotContentNormalCaptured() {
        slotContentNormalCaptures.increment()
    }

    fun slotContentBudgetCaptureForced() {
        slotContentBudgetForcedCaptures.increment()
    }

    fun slotContentReusedStaleGeometry() {
        slotContentStaleGeometryReuses.increment()
    }

    fun slotContentSkippedNoContent() {
        slotContentSkipNoContent.increment()
    }

    fun slotContentSkippedNoLayer() {
        slotContentSkipNoLayer.increment()
    }

    fun slotContentSkippedNoGeometry() {
        slotContentSkipNoGeometry.increment()
    }

    fun slotContentStaleRemoved() {
        slotContentStaleRemovals.increment()
    }

    fun maskCaptured() {
        maskCaptures.increment()
    }

    fun clipCaptured() {
        clipCaptures.increment()
    }

    fun maskRemoved() {
        maskRemovals.increment()
    }

    fun clipRemoved() {
        clipRemovals.increment()
    }

    fun slotCompositePass() {
        slotCompositePasses.increment()
    }

    fun backdropCompositePass() {
        backdropCompositePasses.increment()
    }

    fun contentCompositePass() {
        contentCompositePasses.increment()
    }

    fun stencilSetup() {
        stencilSetups.increment()
    }

    fun atlasPreflightComputed(preflight: SceneBlurAtlasPipelinePreflight) {
        val atlasPlan = preflight.atlasPlan
        val eligibleRequestCount = atlasPlan.batches.sumOf { batch -> batch.requests.size }
        val fallbackRequestCount = atlasPlan.fallbacks.size
        val skippedRequestCount = atlasPlan.skipped.size
        atlasPreflightFrames.increment()
        atlasEligibleRequests.incrementBy(eligibleRequestCount.toLong())
        atlasFallbackRequests.incrementBy(fallbackRequestCount.toLong())
        atlasSkippedRequests.incrementBy(skippedRequestCount.toLong())
        atlasPlan.diagnostics.forEach { decision ->
            atlasPreflightSlotDiagnostic(decision)
            when (decision.outcome) {
                SceneBlurAtlasEligibilityOutcome.AtlasEligible -> {
                    decision.eligibilityReason?.let { reason ->
                        atlasEligibilityReasonCounters.getValue(reason).increment()
                    }
                }

                SceneBlurAtlasEligibilityOutcome.Fallback -> {
                    decision.fallbackReason?.let { reason ->
                        atlasFallbackReasonCounters.getValue(reason).increment()
                        atlasFallbackSlotDiagnostic(
                            slotId = decision.slotId,
                            slotDebugName = decision.slotDebugName,
                            drawIndex = decision.drawIndex,
                            reason = reason
                        )
                    }
                }

                SceneBlurAtlasEligibilityOutcome.SkippedInvalid -> {
                    decision.skipReason?.let { reason ->
                        atlasSkipReasonCounters.getValue(reason).increment()
                    }
                }
            }
        }
        setGauge("gauge.atlas.preflight.eligibleRequestCount", eligibleRequestCount.toLong())
        setGauge("gauge.atlas.preflight.fallbackRequestCount", fallbackRequestCount.toLong())
        setGauge("gauge.atlas.preflight.skippedRequestCount", skippedRequestCount.toLong())
        setGauge("gauge.atlas.preflight.batchCount", atlasPlan.batches.size.toLong())
        if (!preflight.hasEligiblePlacements) {
            atlasNoEligibleFrames.increment()
        }
    }

    fun atlasPipelineExecuted(preflight: SceneBlurAtlasPipelinePreflight) {
        atlasPipelineExecutions.increment()
        setGauge("gauge.atlas.pipeline.batchCount", preflight.preprocessResult.batches.size.toLong())
    }

    fun atlasCopyOutputProduced(output: SceneBlurAtlasCopyFrameOutput) {
        val batchCount = output.batches.size.toLong()
        atlasCopyBatches.incrementBy(batchCount)
        setGauge("gauge.atlas.copy.batchCount", batchCount)
    }

    fun atlasBlurOutputProduced(output: SceneBlurAtlasBlurFrameOutput) {
        val batchCount = output.batches.size.toLong()
        atlasBlurBatches.incrementBy(batchCount)
        setGauge("gauge.atlas.blur.batchCount", batchCount)
    }

    fun atlasLookupOutputProduced(output: SceneBlurAtlasCompositeLookupFrameOutput) {
        val entryCount = output.entries.size.toLong()
        atlasLookupEntries.incrementBy(entryCount)
        setGauge("gauge.atlas.lookup.entryCount", entryCount)
    }

    fun atlasCompositeSlot() {
        atlasCompositeSlots.increment()
    }

    fun atlasFallbackSlot() {
        atlasFallbackSlots.increment()
    }

    private fun atlasFallbackSlotDiagnostic(
        slotId: BlurSlotId,
        slotDebugName: String?,
        drawIndex: Int,
        reason: SceneBlurAtlasFallbackReason
    ) {
        val slotLabel = slotDebugName?.takeIf { it.isNotBlank() } ?: slotId.value
        publish("atlas.fallback.slot.diag/${counterPathSegment(slotLabel)}#$drawIndex/$reason", 1L)
    }

    private fun atlasPreflightSlotDiagnostic(decision: SceneBlurAtlasEligibilityDecision) {
        val slotLabel = decision.slotDebugName?.takeIf { it.isNotBlank() } ?: decision.slotId.value
        val reason = decision.eligibilityReason ?: decision.fallbackReason ?: decision.skipReason ?: decision.outcome
        publish(
            "atlas.preflight.slot.diag/${counterPathSegment(slotLabel)}#${decision.drawIndex}" +
                "/${decision.outcome}/$reason",
            1L
        )
    }

    fun renderGateBlocked(reason: RenderReason) {
        renderGateBlockedRequests.increment()
        renderGateBlockedReasonCounters.getValue(reason).increment()
    }

    fun renderGateDrained(reasonCount: Int) {
        renderGateDrained.increment()
        setGauge("gauge.render.gate.drainedCount", reasonCount.toLong())
    }

    fun renderGateState(open: Boolean) {
        publish("gauge.render.gate.open", if (open) 1L else 0L)
    }

    internal fun recordWith(recorder: SceneTraceCounterRecorder): AutoCloseable {
        val previous = this.recorder
        this.recorder = recorder
        return AutoCloseable {
            this.recorder = previous
        }
    }

    private fun setGauge(name: String, value: Long) {
        publish(name, value)
    }

    private fun publish(name: String, value: Long) {
        recorder.setCounter("$PREFIX/$name", value)
    }

    private fun counterPathSegment(value: String): String {
        return value.map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_' || character == '.') {
                character
            } else {
                '_'
            }
        }.joinToString("")
    }

    private fun CommittedSceneFrame.isStyleOnlyUpdate(): Boolean {
        val dirtyReasons = slots.flatMapTo(mutableSetOf()) { it.dirtyFlags.reasons }
        return dirtyReasons == setOf(BlurSlotDirtyReason.Style)
    }

    private class Counter(private val name: String) {
        private val value = AtomicLong()

        fun increment() {
            incrementBy(1L)
        }

        fun incrementBy(delta: Long) {
            if (delta <= 0L) return
            publish(name, value.addAndGet(delta))
        }
    }
}
