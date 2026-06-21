/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import android.os.Build
import android.os.Trace
import android.util.Log
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.BuildConfig
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ImlaCaptureImport"
private const val MAX_WARNING_LOGS = 16
private const val MAX_TIMING_LOGS = 120

internal object GraphicsLayerCaptureDiagnostics {
    private val warningLogs = AtomicInteger()
    private val timingLogs = AtomicInteger()
    private val renderResultFailures = AtomicInteger()
    private val importNullFailures = AtomicInteger()

    fun renderResultFailed(status: Int, sizePx: IntSize) {
        warn("CanvasBufferedRenderer failed status=$status size=${sizePx.width}x${sizePx.height}")
        setCounter("failure.renderResult", renderResultFailures.incrementAndGet().toLong())
    }

    fun hardwareBufferImportReturnedNull(sizePx: IntSize, origin: CoordinateOrigin) {
        warn("HardwareBuffer import returned null size=${sizePx.width}x${sizePx.height} origin=$origin")
        setCounter("failure.importNull", importNullFailures.incrementAndGet().toLong())
    }

    fun timing(
        sizePx: IntSize,
        origin: CoordinateOrigin,
        renderNs: Long,
        fenceNs: Long,
        importNs: Long,
        totalNs: Long
    ) {
        setCounter("timing.render.us", renderNs.toMicros())
        setCounter("timing.fence.us", fenceNs.toMicros())
        setCounter("timing.import.us", importNs.toMicros())
        setCounter("timing.total.us", totalNs.toMicros())

        if (BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG) && timingLogs.getAndIncrement() < MAX_TIMING_LOGS) {
            Log.d(
                TAG,
                "capture timing size=${sizePx.width}x${sizePx.height} origin=$origin " +
                        "renderUs=${renderNs.toMicros()} fenceUs=${fenceNs.toMicros()} " +
                        "importUs=${importNs.toMicros()} totalUs=${totalNs.toMicros()}"
            )
        }
    }

    private fun warn(message: String) {
        if (BuildConfig.DEBUG && warningLogs.getAndIncrement() < MAX_WARNING_LOGS) {
            Log.w(TAG, message)
        }
    }

    private fun setCounter(name: String, value: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.setCounter("ImlaCaptureImport.$name", value)
        }
    }

    private fun Long.toMicros(): Long = this / 1_000L
}
