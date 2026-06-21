package dev.serhiiyaremych.imla.internal.capture

import android.graphics.HardwareRenderer
import android.graphics.ImageFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import androidx.hardware.SyncFenceCompat
import androidx.tracing.trace
import java.io.IOException
import java.util.concurrent.Executor

@RequiresApi(Build.VERSION_CODES.Q)
internal class SingleBufferRenderer(
    private val label: String,
    private val size: IntSize,
    private val contentRoot: RenderNode,
    private val captureThread: CaptureThread
) : AutoCloseable {
    private val lock = Any()
    private val imageReader = createImageReader()
    private val hardwareRenderer = HardwareRenderer().apply {
        isOpaque = true
        setName(label)
        setContentRoot(contentRoot)
        setSurface(imageReader.surface)
        start()
    }

    private var state: SingleBufferRendererState = SingleBufferRendererState.Free
    private var disposed = false

    init {
        imageReader.setOnImageAvailableListener(
            /* listener = */ { acquireAvailableImage() },
            /* handler = */ captureThread.handler
        )
    }

    @MainThread
    fun renderAsync(
        vsyncTimeNanos: Long?,
        record: () -> Unit,
        onResult: (BufferLease?) -> Unit
    ): Boolean {
        checkMainThread()
        val pendingResult = PendingBufferLease(onResult)
        if (!startRender(pendingResult)) return false

        // Record into the content node only after the write gate is held so a
        // caller that ping-pongs across renderers records into the renderer it
        // actually got. Release the gate if recording throws so it cannot wedge
        // future renders.
        try {
            record()
        } catch (throwable: Throwable) {
            failWriting(pendingResult.request)
            throw throwable
        }
        captureThread.handler.post {
            drawOnCaptureThread(pendingResult.request, vsyncTimeNanos)
        }
        return true
    }

    @MainThread
    override fun close() {
        var closedWritingResult: PendingBufferLease? = null
        val disposeNow = synchronized(lock) {
            when (val current = state) {
                SingleBufferRendererState.Free -> {
                    state = SingleBufferRendererState.Closed
                    true
                }

                is SingleBufferRendererState.Writing -> {
                    closedWritingResult = current.request.pendingResult
                    state = SingleBufferRendererState.Closed
                    false
                }

                is SingleBufferRendererState.Leased -> {
                    state = SingleBufferRendererState.Closed
                    false
                }

                SingleBufferRendererState.Closed -> false
            }
        }
        closedWritingResult?.let { pendingResult ->
            captureThread.handler.post {
                pendingResult.abandon()
            }
        }
        if (disposeNow) {
            captureThread.handler.post { disposeRenderer(null) }
        }
    }

    private fun createImageReader(): ImageReader {
        return ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.PRIVATE,
            IMAGE_BUFFER_COUNT,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
    }

    @MainThread
    private fun startRender(pendingResult: PendingBufferLease): Boolean {
        val request = pendingResult.request
        val accepted = synchronized(lock) {
            when (state) {
                SingleBufferRendererState.Free -> {
                    state = SingleBufferRendererState.Writing(request)
                    true
                }

                is SingleBufferRendererState.Writing,
                is SingleBufferRendererState.Leased,
                SingleBufferRendererState.Closed -> false
            }
        }
        return accepted
    }

    @OnCaptureThread
    private fun drawOnCaptureThread(
        request: SingleBufferRenderRequest,
        vsyncTimeNanos: Long?
    ) {
        try {
            if (isClosed()) {
                failWriting(request)
                return
            }
            val renderRequest = hardwareRenderer
                .createRenderRequest()
                .setWaitForPresent(false)
            if (vsyncTimeNanos != null) {
                renderRequest.setVsyncTime(vsyncTimeNanos)
            }

            val syncResult = trace("SingleBufferRenderer#syncAndDraw") {
                renderRequest.syncAndDraw()
            }
            if (!isSuccess(syncResult)) {
                Log.w(TAG, "Platform capture sync failed for $label status=$syncResult")
                failWriting(request)
            }
        } catch (throwable: RuntimeException) {
            Log.w(TAG, "Platform capture draw failed for $label", throwable)
            failWriting(request)
        }
    }

    @OnCaptureThread
    private fun acquireAvailableImage() {
        trace("SingleBufferRenderer#acquireImage") {
            val image = try {
                imageReader.acquireNextImage()
            } catch (_: IllegalStateException) {
                Log.w(TAG, "Platform capture image queue is full for $label")
                failCurrentWriting()
                null
            } ?: run {
                failCurrentWriting()
                return@trace
            }

            val buffer = image.hardwareBuffer
            if (buffer == null) {
                image.close()
                failCurrentWriting()
                return@trace
            }

            val readyFence = try {
                readyFenceFor(image)
            } catch (exception: IOException) {
                Log.w(TAG, "Platform capture ready fence failed for $label", exception)
                image.close()
                buffer.close()
                failCurrentWriting()
                return@trace
            }

            acceptImage(CapturedImage(image = image, buffer = buffer), readyFence)
        }
    }

    @OnCaptureThread
    private fun acceptImage(capturedImage: CapturedImage, readyFence: LeaseFence?) {
        var pendingResult: PendingBufferLease? = null
        var lease: BufferLease? = null
        var closeImmediately = false
        var disposeAfterClose = false

        synchronized(lock) {
            when (val current = state) {
                is SingleBufferRendererState.Writing -> {
                    state = SingleBufferRendererState.Leased(capturedImage)
                    lease = BufferLease(
                        buffer = capturedImage.buffer,
                        readyFence = readyFence,
                        onRelease = { releaseFence ->
                            releaseLeasedImage(capturedImage, releaseFence)
                        }
                    )
                    pendingResult = current.request.pendingResult
                }

                SingleBufferRendererState.Closed -> {
                    closeImmediately = true
                    disposeAfterClose = true
                }

                SingleBufferRendererState.Free,
                is SingleBufferRendererState.Leased -> {
                    closeImmediately = true
                }
            }
        }

        if (closeImmediately) {
            try {
                readyFence?.close()
                releaseCapturedImage(capturedImage, null)
            } finally {
                if (disposeAfterClose) {
                    disposeRenderer(null)
                }
            }
            return
        }

        val acceptedLease = requireNotNull(lease)
        if (pendingResult?.complete(acceptedLease) == false) {
            acceptedLease.release(null)
        }
    }

    // Invoked as the BufferLease onRelease callback from whatever thread releases the
    // lease (typically the GL thread); hops to the capture thread to do the work.
    @AnyThread
    private fun releaseLeasedImage(
        capturedImage: CapturedImage,
        releaseFence: SyncFenceCompat?
    ) {
        captureThread.handler.post {
            try {
                releaseCapturedImage(capturedImage, releaseFence)
            } finally {
                val disposeNow = synchronized(lock) {
                    when (val current = state) {
                        is SingleBufferRendererState.Leased -> {
                            if (current.image === capturedImage) {
                                state = SingleBufferRendererState.Free
                            }
                            false
                        }

                        SingleBufferRendererState.Closed -> true

                        SingleBufferRendererState.Free,
                        is SingleBufferRendererState.Writing -> false
                    }
                }
                if (disposeNow) {
                    postDisposeRenderer(null)
                }
            }
        }
    }

    @OnCaptureThread
    private fun releaseCapturedImage(
        capturedImage: CapturedImage,
        releaseFence: SyncFenceCompat?
    ) {
        try {
            releaseFence?.awaitForever()
        } finally {
            try {
                capturedImage.image.close()
            } finally {
                try {
                    releaseFence?.close()
                } finally {
                    capturedImage.buffer.close()
                }
            }
        }
    }

    // Reachable from the main thread (record() throwing in renderAsync) and the
    // capture thread (drawOnCaptureThread); lock-guarded.
    @AnyThread
    private fun failWriting(request: SingleBufferRenderRequest) {
        val disposeNow = synchronized(lock) {
            when (val current = state) {
                is SingleBufferRendererState.Writing -> {
                    if (current.request === request) {
                        state = SingleBufferRendererState.Free
                        request.pendingResult.abandon()
                    }
                    false
                }

                SingleBufferRendererState.Closed -> {
                    request.pendingResult.abandon()
                    true
                }

                SingleBufferRendererState.Free,
                is SingleBufferRendererState.Leased -> false
            }
        }
        if (disposeNow) {
            postDisposeRenderer(null)
        }
    }

    @OnCaptureThread
    private fun failCurrentWriting() {
        val disposeNow = synchronized(lock) {
            when (val current = state) {
                is SingleBufferRendererState.Writing -> {
                    current.request.pendingResult.abandon()
                    state = SingleBufferRendererState.Free
                    false
                }

                SingleBufferRendererState.Closed -> true

                SingleBufferRendererState.Free,
                is SingleBufferRendererState.Leased -> false
            }
        }
        if (disposeNow) {
            postDisposeRenderer(null)
        }
    }

    @AnyThread
    private fun postDisposeRenderer(ownedImage: CapturedImage?) {
        captureThread.handler.post {
            disposeRenderer(ownedImage)
        }
    }

    @OnCaptureThread
    private fun disposeRenderer(ownedImage: CapturedImage?) {
        val shouldDispose = synchronized(lock) {
            if (disposed) {
                false
            } else {
                disposed = true
                true
            }
        }
        if (!shouldDispose) {
            ownedImage?.let { releaseCapturedImage(it, null) }
            return
        }
        try {
            ownedImage?.let { releaseCapturedImage(it, null) }
        } finally {
            hardwareRenderer.stop()
            hardwareRenderer.destroy()
            imageReader.close()
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SingleBufferRenderer.renderAsync must run on the main thread"
        }
    }

    @AnyThread
    private fun isClosed(): Boolean {
        return synchronized(lock) {
            state == SingleBufferRendererState.Closed
        }
    }

    @OnCaptureThread
    @Throws(IOException::class)
    private fun readyFenceFor(image: Image): LeaseFence? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LeaseFence.Platform(image.fence)
        } else {
            null
        }
    }

    private data class CapturedImage(
        val image: Image,
        val buffer: HardwareBuffer
    )

    private class SingleBufferRenderRequest(
        val pendingResult: PendingBufferLease
    )

    private class PendingBufferLease(
        private val onResult: (BufferLease?) -> Unit
    ) {
        private val lock = Any()
        val request = SingleBufferRenderRequest(this)
        private var completed = false

        fun complete(lease: BufferLease): Boolean {
            val accepted = synchronized(lock) {
                if (completed) {
                    false
                } else {
                    completed = true
                    true
                }
            }
            if (accepted) onResult(lease)
            return accepted
        }

        fun abandon() {
            val notify = synchronized(lock) {
                if (completed) {
                    false
                } else {
                    completed = true
                    true
                }
            }
            if (notify) onResult(null)
        }
    }

    private sealed interface SingleBufferRendererState {
        data object Free : SingleBufferRendererState

        data class Writing(
            val request: SingleBufferRenderRequest
        ) : SingleBufferRendererState

        data class Leased(
            val image: CapturedImage
        ) : SingleBufferRendererState

        data object Closed : SingleBufferRendererState
    }

    private companion object {
        private const val TAG = "SingleBufferRenderer"

        // Two buffers keep HWUI dequeueBuffer from blocking the shared
        // RenderThread while the previous lease's release fence is pending;
        // with one buffer that wait deadlocks against the main thread's
        // window draw (postAndWait) until the 4s BufferQueue timeout.
        // Renders stay single-in-flight via the Free/Writing/Leased gate.
        private const val IMAGE_BUFFER_COUNT = 2

        // SYNC_FRAME_DROPPED is a failure here: HWUI produces no image for a
        // dropped frame, so accepting it would leave the gate stuck in Writing.
        private fun isSuccess(result: Int): Boolean {
            return result == HardwareRenderer.SYNC_OK
        }
    }
}

internal class CaptureThread(
    name: String = "ImlaCaptureThread"
) : AutoCloseable {
    private val thread = HandlerThread(name).apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor(handler::post)

    override fun close() {
        thread.quitSafely()
    }
}
