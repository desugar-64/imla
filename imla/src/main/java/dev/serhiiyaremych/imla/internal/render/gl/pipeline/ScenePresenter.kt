/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl.pipeline

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import androidx.hardware.SyncFenceCompat
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.ext.logd
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderPassMetric
import dev.serhiiyaremych.imla.internal.render.gl.SceneGlRenderFrame

/**
 * Owns how a finished scene buffer reaches Compose: wrap the scene [HardwareBuffer] as a
 * hardware [Bitmap] and post it to the main thread for `drawImage`.
 *
 * Each frame is `acquire → render → present → release`, driven from the GL thread.
 */
internal interface ScenePresenter {
    fun acquire(size: IntSize, requiresStencil: Boolean, slotId: SceneSlotId?): SceneRenderBuffer
    fun present(frame: SceneGlRenderFrame, buffer: SceneRenderBuffer)
    fun release(buffer: SceneRenderBuffer)
    fun close()
}

/**
 * Zero-copy present into Compose: wrap the scene HardwareBuffer as a hardware Bitmap after GPU
 * completion. A 2-deep ring keeps GL from writing the buffer HWUI is still sampling. Wrapping
 * takes a HardwareBuffer ref, so the GL slot may `close()` its handle independently.
 */
@RequiresApi(29)
internal class HardwareBitmapPresenter(
    private val onPresented: (SceneSlotId, Bitmap) -> Unit
) : ScenePresenter {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rings = mutableMapOf<SceneSlotId, SceneHwBufferRing>()

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
        val slotId = frame.slots.firstOrNull()?.id ?: return
        val hwBuffer = (buffer.backing as SceneRenderBuffer.Backing.Hw).hwBuffer
        val startedNanos = System.nanoTime()
        val fence = SyncFenceCompat.createNativeSyncFence()
        trace("HardwareBitmapPresenter#awaitFence") {
            fence.await(FENCE_AWAIT_TIMEOUT_NANOS)
        }
        fence.close()
        val bitmap = trace("HardwareBitmapPresenter#wrap") {
            Bitmap.wrapHardwareBuffer(
                hwBuffer.hardwareBuffer,
                ColorSpace.get(ColorSpace.Named.SRGB)
            )
        } ?: return
        val outputPixels = buffer.size.width.toLong() * buffer.size.height.toLong()
        frame.metricsFrame?.recordRenderPass(
            pass = SceneRenderPassMetric.FinalPresent,
            startedNanos = startedNanos,
            finishedNanos = System.nanoTime(),
            outputPixels = outputPixels,
            textureSamples = outputPixels
        )
        mainHandler.post { onPresented(slotId, bitmap) }
        logd(
            "ImlaPresent",
            "${buffer.size.width}x${buffer.size.height} fence=${
                (System.nanoTime() - startedNanos) / 1_000_000
            }ms slot=${slotId.value}"
        )
    }

    override fun release(buffer: SceneRenderBuffer) = Unit

    override fun close() {
        rings.values.forEach { it.close() }
        rings.clear()
    }

    private companion object {
        const val FENCE_AWAIT_TIMEOUT_NANOS = 200_000_000L // 200 ms
    }
}
