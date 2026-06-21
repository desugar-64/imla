package dev.serhiiyaremych.imla.internal.capture

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.Texture2D

internal sealed interface CapturedLayerFrame : AutoCloseable {
    val size: IntSize
    val contentSize: IntSize
    val logicalSize: IntSize

    fun awaitReady()
}

internal class CapturedTextureFrame internal constructor(
    private var texture: Texture2D?,
    override val size: IntSize,
    override val contentSize: IntSize = size,
    override val logicalSize: IntSize = size,
    private val releaseTexture: (Texture2D) -> Unit = { it.destroy() }
) : CapturedLayerFrame {
    fun takeTexture(): Texture2D? {
        val capturedTexture = texture
        texture = null
        return capturedTexture
    }

    override fun awaitReady() = Unit

    override fun close() {
        texture?.let(releaseTexture)
        texture = null
    }
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
