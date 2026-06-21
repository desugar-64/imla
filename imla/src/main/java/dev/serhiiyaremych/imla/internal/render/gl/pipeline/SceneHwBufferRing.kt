/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl.pipeline

import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import androidx.hardware.SyncFenceCompat
import androidx.tracing.Trace
import androidx.tracing.trace
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Triple-buffered; capacity 2 stalled obtain() on the release fence every frame.
private const val RING_CAPACITY = 3

// Proceed-on-timeout backstops so a lost fence/release can't freeze obtain() forever.
private const val RELEASE_FENCE_TIMEOUT_NANOS = 500_000_000L // 500 ms
private const val PRESENT_INFLIGHT_TIMEOUT_NANOS = 500_000_000L // 500 ms

@RequiresApi(26)
internal class SceneHwBufferRing(private val capacity: Int = RING_CAPACITY) : Closeable {
    // slots[] and per-slot state are guarded by [lock] across the GL, present, and compositor
    // threads; blocking fence awaits stay outside it.
    private val lock = ReentrantLock()
    private val presentReleased = lock.newCondition()
    private val slots = arrayOfNulls<Slot>(capacity)
    private var next = 0
    private var releaseFenceTimeouts = 0
    private var presentInFlightTimeouts = 0

    private class Slot(val buffer: SceneHwBuffer) {
        var compositorFence: SyncFenceCompat? = null
        var presentInFlight = false // handed to async present, not yet released
    }

    fun obtain(size: IntSize): SceneHwBuffer {
        val idx = Math.floorMod(next++, capacity)

        val slot: Slot?
        val pendingFence: SyncFenceCompat?
        lock.withLock {
            val candidate = slots[idx]
            // Don't reuse a buffer still owned by async present or the compositor.
            candidate?.let { awaitPresentReleasedLocked(it) }
            slot = candidate
            pendingFence = slot?.compositorFence
            slot?.compositorFence = null
        }
        pendingFence?.let { fence ->
            val signalled = trace("SceneHwBufferRing#awaitRelease") {
                fence.await(RELEASE_FENCE_TIMEOUT_NANOS)
            }
            if (!signalled) {
                Trace.setCounter("SceneHwBufferRing#ReleaseFenceTimeouts", ++releaseFenceTimeouts)
            }
            fence.close()
        }

        if (slot != null &&
            slot.buffer.texture.width == size.width &&
            slot.buffer.texture.height == size.height
        ) {
            return slot.buffer
        }

        val buffer = SceneHwBuffer.create(size)
        lock.withLock {
            // A late release may have landed during the await; close it before dropping the slot.
            slots[idx]?.let {
                it.buffer.close()
                it.compositorFence?.close()
            }
            slots[idx] = Slot(buffer)
        }
        return buffer
    }

    // Set on the GL thread before the present is posted, so it precedes any re-obtain of this slot.
    fun markPresentInFlight(buffer: SceneHwBuffer) {
        lock.withLock {
            slots.firstOrNull { it?.buffer === buffer }?.presentInFlight = true
        }
    }

    // Runs on the compositor thread (SurfaceControlCompat release callback).
    fun onCompositorRelease(buffer: SceneHwBuffer, fence: SyncFenceCompat) {
        lock.withLock {
            val slot = slots.firstOrNull { it?.buffer === buffer }
            if (slot == null) {
                fence.close()
                return
            }
            slot.compositorFence?.close() // don't leak an unconsumed fence
            slot.compositorFence = fence
            slot.presentInFlight = false
            presentReleased.signalAll()
        }
    }

    private fun awaitPresentReleasedLocked(slot: Slot) {
        var remainingNanos = PRESENT_INFLIGHT_TIMEOUT_NANOS
        while (slot.presentInFlight) {
            if (remainingNanos <= 0) {
                Trace.setCounter(
                    "SceneHwBufferRing#PresentInFlightTimeouts",
                    ++presentInFlightTimeouts,
                )
                // Commit to reuse; clearing stops future obtain()s of this slot from re-stalling.
                slot.presentInFlight = false
                return
            }
            remainingNanos = presentReleased.awaitNanos(remainingNanos)
        }
    }

    override fun close() {
        lock.withLock {
            slots.forEachIndexed { idx, slot ->
                slot?.buffer?.close()
                slot?.compositorFence?.close()
                slots[idx] = null
            }
        }
    }
}
