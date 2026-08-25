package dev.serhiiyaremych.imla.internal.capture

import androidx.compose.ui.unit.IntSize

internal sealed interface CapturedLayerFrame : AutoCloseable {
    val size: IntSize
    val contentSize: IntSize
    val logicalSize: IntSize

    fun awaitReady()
}

internal class CapturedHardwareBufferFrame internal constructor(
    lease: BufferLease,
    override val size: IntSize,
    override val contentSize: IntSize = size,
    override val logicalSize: IntSize = size
) : CapturedLayerFrame {
    private var lease: BufferLease? = lease
    private var closed: Boolean = false

    override fun awaitReady() {
        lease?.awaitReady()
    }

    fun takeLease(): BufferLease {
        check(!closed) { "Cannot take a buffer lease from a closed frame" }
        return requireNotNull(lease) { "Buffer lease has already been taken" }
            .also { lease = null }
    }

    override fun close() {
        if (!closed) {
            closed = true
            lease?.release(null)
            lease = null
        }
    }
}
