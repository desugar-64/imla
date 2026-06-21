/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace

internal class ImlaSceneCoordinator(
    private val scheduler: SceneRenderScheduler,
    private val onFrameCommitted: (CommittedSceneFrame) -> Unit
) {
    private val registry = BlurSlotRegistry()
    private var nextGeneration: Long = 1L
    private var activeTransaction: CaptureTransaction? = null
    private var committedFrame: CommittedSceneFrame? = null

    val hasCommittedFrame: Boolean
        get() = committedFrame != null

    fun registerSlot(
        id: BlurSlotId,
        debugName: String?,
        geometryProvider: BlurSlotGeometryProvider?
    ): BlurSlotNodeHandle {
        val handle = registry.register(id, debugName, geometryProvider)
        scheduler.request(RenderReason.SlotChanged)
        return handle
    }

    fun beginCapture(rootSize: IntSize): CaptureTransaction? = trace("ImlaSceneCoordinator#beginCapture") {
        if (rootSize == IntSize.Zero) return@trace null
        val transaction = CaptureTransaction(
            generation = nextGeneration,
            rootSize = rootSize
        )
        activeTransaction = transaction
        transaction
    }

    fun currentTransaction(): CaptureTransaction? {
        return activeTransaction
    }

    fun submitSlot(record: BlurSlotRecord) {
        registry.update(record)
        activeTransaction?.records?.add(record)
    }

    fun markSlotDirty(id: BlurSlotId, reason: BlurSlotDirtyReason) {
        registry.markDirty(id, reason)
        scheduler.request(RenderReason.SlotChanged)
    }

    fun finishCapture() {
        activeTransaction = null
    }

    fun commitRootCapture(rootSize: IntSize): Boolean = trace("ImlaSceneCoordinator#commitRootCapture") {
        if (rootSize == IntSize.Zero) return@trace false
        val reasons = scheduler.consumePendingReasons() + RenderReason.RootCaptured
        SceneTraceCounters.rootCaptureCommitted()
        commitRegistrySnapshot(rootSize, reasons).also { frame ->
            onFrameCommitted(frame)
            scheduler.request(RenderReason.RootCaptured)
        }
        true
    }

    fun refreshSlotGeometry(): Boolean = trace("ImlaSceneCoordinator#refreshSlotGeometry") {
        val current = committedFrame ?: return@trace false
        if (!registry.refreshGeometry()) return@trace false

        val reasons = scheduler.consumePendingReasons() + RenderReason.SlotChanged
        val frame = commitRegistrySnapshot(current.rootSize, reasons)
        onFrameCommitted(frame)
        SceneTraceCounters.geometryRefreshed()
        scheduler.request(RenderReason.SlotChanged)
        true
    }

    fun commitSlotUpdate(record: BlurSlotRecord): Boolean = trace("ImlaSceneCoordinator#commitSlotUpdate") {
        val dirtyFlags = registry.update(record)
        if (!dirtyFlags.isDirty) return@trace false
        val current = committedFrame ?: return@trace false
        val reasons = scheduler.consumePendingReasons() + RenderReason.SlotChanged
        val frame = commitRegistrySnapshot(current.rootSize, reasons)
        onFrameCommitted(frame)
        scheduler.request(RenderReason.SlotChanged)
        true
    }

    fun commitSlotRemoval(): Boolean = trace("ImlaSceneCoordinator#commitSlotRemoval") {
        val current = committedFrame ?: return@trace false
        val reasons = scheduler.consumePendingReasons() + RenderReason.SlotChanged
        val frame = commitRegistrySnapshot(current.rootSize, reasons)
        onFrameCommitted(frame)
        scheduler.request(RenderReason.SlotChanged)
        true
    }

    fun consumeFrameForRender(): CommittedSceneFrame? {
        val frame = committedFrame ?: return null
        val extraReasons = scheduler.consumePendingReasons()
        val renderFrame = if (extraReasons.isEmpty()) {
            frame
        } else {
            frame.copy(reasons = frame.reasons + extraReasons)
        }
        SceneTraceCounters.renderFrameConsumed(renderFrame)
        return renderFrame
    }

    fun onSurfaceChanged() {
        scheduler.request(RenderReason.SurfaceChanged)
    }

    fun requestRender(reason: RenderReason) {
        scheduler.request(reason)
    }

    private fun commitRegistrySnapshot(
        rootSize: IntSize,
        reasons: Set<RenderReason>
    ): CommittedSceneFrame {
        val frame = CommittedSceneFrame(
            generation = nextGeneration,
            rootSize = rootSize,
            slots = registry.snapshot(),
            reasons = reasons
        )
        committedFrame = frame
        registry.clearDirty()
        nextGeneration += 1L
        SceneTraceCounters.frameCommitted(frame)
        return frame
    }
}
