/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl.pipeline

import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.hardware.SyncFenceCompat
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.ext.logd
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderPassMetric
import dev.serhiiyaremych.imla.internal.render.gl.SceneGlRenderFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal interface ScenePresenter {
    fun acquire(size: IntSize, requiresStencil: Boolean, slotId: SceneSlotId?): SceneRenderBuffer
    fun present(frame: SceneGlRenderFrame, buffer: SceneRenderBuffer)
    fun release(buffer: SceneRenderBuffer)
    fun close()
}

@RequiresApi(29)
internal class HardwareBitmapPresenter(
    private val onPresented: (SceneSlotId, ImageBitmap) -> Unit
) : ScenePresenter {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rings = mutableMapOf<SceneSlotId, SceneHwBufferRing>()
    private val syncLock = Any()
    private var syncLatch: CountDownLatch? = null
    private var syncResult: Pair<SceneSlotId, ImageBitmap>? = null

    fun beginSynchronousPresent() {
        synchronized(syncLock) {
            syncLatch = CountDownLatch(1)
            syncResult = null
        }
    }

    fun awaitSynchronousPresent(timeoutMs: Long): Pair<SceneSlotId, ImageBitmap>? {
        val latch = synchronized(syncLock) { syncLatch } ?: return null
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return synchronized(syncLock) {
            val result = syncResult
            syncLatch = null
            syncResult = null
            result
        }
    }

    override fun acquire(
        size: IntSize,
        requiresStencil: Boolean,
        slotId: SceneSlotId?
    ): SceneRenderBuffer {
        val ring = if (slotId != null) {
            rings.getOrPut(slotId) { SceneHwBufferRing() }
        } else {
            rings.getOrPut(SceneSlotId(Long.MIN_VALUE)) { SceneHwBufferRing() }
        }
        val hwBuffer = ring.obtain(size)
        return SceneRenderBuffer(
            size = size,
            hasStencil = true,
            backing = SceneRenderBuffer.Backing.Hw(hwBuffer)
        )
    }

    override fun present(frame: SceneGlRenderFrame, buffer: SceneRenderBuffer) {
        val slotId = frame.slots.firstOrNull()?.id
        if (slotId == null) {
            completePresent(result = null)
            return
        }
        val hwBuffer = (buffer.backing as SceneRenderBuffer.Backing.Hw).hwBuffer
        val startedNanos = System.nanoTime()
        val fence = SyncFenceCompat.createNativeSyncFence()
        trace("HardwareBitmapPresenter#awaitFence") {
            fence.await(FENCE_AWAIT_TIMEOUT_NANOS)
        }
        fence.close()
        val image = hwBuffer.image
        val outputPixels = buffer.size.width.toLong() * buffer.size.height.toLong()
        frame.metricsFrame?.recordRenderPass(
            pass = SceneRenderPassMetric.FinalPresent,
            startedNanos = startedNanos,
            finishedNanos = System.nanoTime(),
            outputPixels = outputPixels,
            textureSamples = outputPixels
        )
        completePresent(slotId to image)
        logd(
            "ImlaPresent",
            "${buffer.size.width}x${buffer.size.height} fence=${
                (System.nanoTime() - startedNanos) / 1_000_000
            }ms slot=${slotId.value}"
        )
    }

    private fun completePresent(result: Pair<SceneSlotId, ImageBitmap>?) {
        val latch = synchronized(syncLock) { syncLatch }
        if (latch != null) {
            synchronized(syncLock) {
                syncResult = result
            }
            latch.countDown()
            return
        }
        if (result != null) {
            mainHandler.post { onPresented(result.first, result.second) }
        }
    }

    override fun release(buffer: SceneRenderBuffer) = Unit

    override fun close() {
        synchronized(syncLock) {
            syncLatch?.countDown()
            syncLatch = null
            syncResult = null
        }
        rings.values.forEach { it.close() }
        rings.clear()
    }

    private companion object {
        const val FENCE_AWAIT_TIMEOUT_NANOS = 200_000_000L // 200 ms
    }
}
