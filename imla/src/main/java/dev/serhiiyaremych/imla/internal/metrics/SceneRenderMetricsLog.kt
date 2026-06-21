package dev.serhiiyaremych.imla.internal.metrics

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal object SceneRenderMetricsLog {
    private const val TAG = "SceneRenderMetrics"
    private const val FILE_NAME = "imla-scene-render-metrics.csv"

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ImlaSceneMetrics").apply {
            isDaemon = true
        }
    }

    @Volatile
    private var file: File? = null

    fun configure(directory: File) {
        val metricsFile = File(directory, FILE_NAME)
        metricsFile.parentFile?.mkdirs()
        metricsFile.writeText("elapsedRealtimeNanos,thread,phase,durationMs,details\n")
        file = metricsFile
        Log.i(TAG, "Writing scene renderer metrics to ${metricsFile.absolutePath}")
    }

    inline fun <T> time(
        phase: String,
        details: String = "",
        block: () -> T
    ): T {
        val startedNanos = System.nanoTime()
        return try {
            block()
        } finally {
            record(
                phase = phase,
                durationNanos = System.nanoTime() - startedNanos,
                details = details
            )
        }
    }

    fun record(
        phase: String,
        durationNanos: Long,
        details: String = ""
    ) {
        val metricsFile = file ?: return
        val line = buildLine(
            phase = phase,
            durationNanos = durationNanos,
            details = details
        )
        writer.execute {
            runCatching {
                metricsFile.appendText(line)
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to append scene renderer metric", throwable)
            }
        }
    }

    private fun buildLine(
        phase: String,
        durationNanos: Long,
        details: String
    ): String {
        val durationMs = durationNanos / 1_000_000.0
        return buildString {
            append(SystemClock.elapsedRealtimeNanos())
            append(',')
            append(Thread.currentThread().name.sanitize())
            append(',')
            append(phase.sanitize())
            append(',')
            append(String.format(Locale.US, "%.3f", durationMs))
            append(',')
            append(details.sanitize())
            append('\n')
        }
    }

    private fun String.sanitize(): String {
        return replace(',', ';').replace('\n', ' ')
    }
}
