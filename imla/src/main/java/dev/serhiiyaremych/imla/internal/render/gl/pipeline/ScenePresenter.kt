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

@RequiresApi(29)
internal class HardwareBitmapPresenter(
    private val onPresented: (SceneSlotId, ImageBitmap) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rings = mutableMapOf<SceneSlotId, SceneHwBufferRing>()

    fun acquire(size: IntSize, slotId: SceneSlotId): SceneRenderBuffer {
        val hwBuffer = rings.getOrPut(slotId) { SceneHwBufferRing() }.obtain(size)
        return SceneRenderBuffer(size = size, hwBuffer = hwBuffer)
    }

    fun present(frame: SceneGlRenderFrame, buffer: SceneRenderBuffer) {
        val slotId = frame.slot.id
        val startedNanos = System.nanoTime()
        val fence = SyncFenceCompat.createNativeSyncFence()
        trace("HardwareBitmapPresenter#awaitFence") {
            fence.await(FENCE_AWAIT_TIMEOUT_NANOS)
        }
        fence.close()
        val image = buffer.hwBuffer.image
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
        if (result != null) {
            mainHandler.post { onPresented(result.first, result.second) }
        }
    }

    fun release(buffer: SceneRenderBuffer) = Unit

    fun close() {
        rings.values.forEach { it.close() }
        rings.clear()
    }

    private companion object {
        const val FENCE_AWAIT_TIMEOUT_NANOS = 200_000_000L // 200 ms
    }
}
