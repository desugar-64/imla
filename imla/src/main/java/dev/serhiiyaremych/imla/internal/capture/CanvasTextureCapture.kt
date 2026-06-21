package dev.serhiiyaremych.imla.internal.capture

import android.graphics.Canvas
import androidx.compose.ui.unit.IntSize

internal fun interface CanvasTextureCaptureFactory {
    fun create(label: String): CanvasTextureCapture
}

internal interface CanvasTextureCapture : AutoCloseable {
    fun capture(
        size: IntSize,
        logicalSize: IntSize = size,
        timeoutMs: Long,
        drawCanvas: (Canvas) -> Unit
    ): CapturedTextureFrame?
}
