package dev.serhiiyaremych.imla.internal.layer.resources

import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.os.Build
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import androidx.graphics.CanvasBufferedRenderer
import androidx.hardware.SyncFenceCompat
import dev.serhiiyaremych.imla.internal.capture.BufferLease
import dev.serhiiyaremych.imla.internal.capture.CanvasTextureCapture
import dev.serhiiyaremych.imla.internal.capture.CanvasTextureCaptureFactory
import dev.serhiiyaremych.imla.internal.capture.CaptureThread
import dev.serhiiyaremych.imla.internal.capture.CapturedHardwareBufferFrame
import dev.serhiiyaremych.imla.internal.capture.CapturedLayerFrame
import dev.serhiiyaremych.imla.internal.capture.LeaseFence
import dev.serhiiyaremych.imla.internal.capture.OnCaptureThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SceneCanvasFrameRenderer private constructor(
    private val label: String,
    private val renderNode: RenderNode,
    private val canvasRenderer: CanvasBufferedRenderer?,
    private val textureCapture: CanvasTextureCapture?,
    private val captureThread: CaptureThread
) {
    private val lock = Any()
    private var leasedBuffers = 0
    private var closeRequested = false
    private var closed = false

    // Blocks the calling (main) thread: offloads the draw to the capture thread via
    // captureThread.executor, then awaits the result.
    @MainThread
    @RequiresApi(Build.VERSION_CODES.Q)
    fun captureFrame(
        size: IntSize,
        timeoutMs: Long,
        contentSize: IntSize = size
    ): CapturedLayerFrame? {
        val renderer = canvasRenderer ?: return textureCapture?.capture(
            size = size,
            timeoutMs = timeoutMs
        ) { canvas ->
            canvas.drawRenderNode(renderNode)
        }
        val pendingResult = SceneCanvasRenderResult(renderer)
        renderer.obtainRenderRequest().drawAsync(captureThread.executor, pendingResult::complete)
        val renderResult = pendingResult.await(timeoutMs) ?: return null
        return renderResult.toCapturedFrame(size, contentSize)
    }

    @MainThread
    fun requestClose() {
        textureCapture?.close()
        val rendererToClose = synchronized(lock) {
            closeRequested = true
            rendererToCloseIfReady()
        }
        // rendererToClose is a CanvasBufferedRenderer, which only exists on API 29+; below that
        // the renderer is null and this is a no-op. The guard makes the API floor explicit to lint.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rendererToClose?.close()
        }
    }

    @MainThread
    private fun leaseBuffer() {
        synchronized(lock) {
            check(!closed) { "Cannot lease a buffer from a closed $label renderer" }
            leasedBuffers++
        }
    }

    // Invoked as the BufferLease onRelease callback from whatever thread releases the
    // lease (typically the GL thread); lock-guarded.
    @AnyThread
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun releaseBuffer(
        buffer: HardwareBuffer,
        fence: SyncFenceCompat?
    ) {
        requireNotNull(canvasRenderer).releaseBuffer(buffer, fence)
        val rendererToClose = synchronized(lock) {
            leasedBuffers--
            rendererToCloseIfReady()
        }
        rendererToClose?.close()
    }

    @MainThread
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun releaseUnretainedBuffer(
        buffer: HardwareBuffer,
        fence: SyncFenceCompat?
    ) {
        requireNotNull(canvasRenderer).releaseBuffer(buffer, fence)
    }

    // Reachable from the main thread (requestClose) and any thread (releaseBuffer);
    // lock-guarded.
    @AnyThread
    private fun rendererToCloseIfReady(): CanvasBufferedRenderer? {
        return if (closeRequested && leasedBuffers == 0 && !closed) {
            closed = true
            canvasRenderer
        } else {
            null
        }
    }

    @MainThread
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun CanvasBufferedRenderer.RenderResult.toCapturedFrame(
        size: IntSize,
        contentSize: IntSize
    ): CapturedHardwareBufferFrame? {
        return if (status == CanvasBufferedRenderer.RenderResult.SUCCESS) {
            leaseBuffer()
            CapturedHardwareBufferFrame(
                lease = BufferLease(
                    buffer = hardwareBuffer,
                    readyFence = fence?.let(LeaseFence::Compat),
                    onRelease = { releaseFence ->
                        releaseBuffer(hardwareBuffer, releaseFence)
                    }
                ),
                size = size,
                contentSize = contentSize,
            )
        } else {
            releaseUnretainedBuffer(hardwareBuffer, fence)
            null
        }
    }

    companion object {
        @MainThread
        fun create(
            label: String,
            size: IntSize,
            renderNode: RenderNode,
            textureCaptureFactory: CanvasTextureCaptureFactory?,
            captureThread: CaptureThread
        ): SceneCanvasFrameRenderer {
            val canvasRenderer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                renderNode.setPosition(0, 0, size.width, size.height)
                CanvasBufferedRenderer
                    .Builder(size.width, size.height)
                    .setBufferFormat(HardwareBuffer.RGBA_8888)
                    .setMaxBuffers(2)
                    .build()
                    .also { it.setContentRoot(renderNode) }
            } else {
                null
            }
            return SceneCanvasFrameRenderer(
                label = label,
                renderNode = renderNode,
                canvasRenderer = canvasRenderer,
                textureCapture = if (canvasRenderer == null) {
                    requireNotNull(textureCaptureFactory) {
                        "SurfaceTexture capture is required below API 29"
                    }.create(label)
                } else {
                    null
                },
                captureThread = captureThread
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private class SceneCanvasRenderResult(
    private val renderer: CanvasBufferedRenderer
) {
    private val lock = Any()
    private val latch = CountDownLatch(1)
    private var result: CanvasBufferedRenderer.RenderResult? = null
    private var abandoned = false

    @OnCaptureThread
    fun complete(renderResult: CanvasBufferedRenderer.RenderResult) {
        val shouldRelease = synchronized(lock) {
            if (abandoned) {
                true
            } else {
                result = renderResult
                latch.countDown()
                false
            }
        }
        if (shouldRelease) {
            release(renderResult)
        }
    }

    @MainThread
    fun await(timeoutMs: Long): CanvasBufferedRenderer.RenderResult? {
        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val resolvedResult = synchronized(lock) {
            if (completed) {
                result
            } else {
                abandoned = true
                result.also { result = null }
            }
        }
        if (!completed) {
            resolvedResult?.let(::release)
        }
        return if (completed) resolvedResult else null
    }

    // Reachable from the capture thread (complete) and the main thread (await);
    // lock-guarded by the caller.
    @AnyThread
    private fun release(renderResult: CanvasBufferedRenderer.RenderResult) {
        renderer.releaseBuffer(renderResult.hardwareBuffer, renderResult.fence)
    }
}
