package dev.serhiiyaremych.imla.internal.render.gl

import android.os.Looper
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.capture.CanvasTextureCapture
import dev.serhiiyaremych.imla.internal.capture.CapturedTextureFrame
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SceneSurfaceTextureCapture(
    private val label: String,
    shaderLibrary: ShaderLibrary,
    shaderBinder: ShaderBinder,
    private val simpleQuadRenderer: SimpleQuadRenderer,
    private val commandsProvider: () -> RenderCommands,
    private val executeOnGlThread: (() -> Unit) -> Unit,
    private val isOnGlThread: () -> Boolean
) : CanvasTextureCapture {
    private val lock = Any()
    private val renderer = SurfaceTextureRenderer(
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder,
        simpleQuadRenderer = simpleQuadRenderer,
        fboFormat = FramebufferTextureFormat.RGBA8,
        flipOutput = true,
        commandsProvider = commandsProvider,
        executeOnGlThread = executeOnGlThread,
        onTextureReady = ::completeCapture
    )

    private var pendingLatch: CountDownLatch? = null
    private var pendingFrame: CapturedTextureFrame? = null
    private var pendingSize: IntSize = IntSize.Zero
    private var pendingLogicalSize: IntSize = IntSize.Zero
    @Volatile
    private var closed = false

    override fun capture(
        size: IntSize,
        logicalSize: IntSize,
        timeoutMs: Long,
        drawCanvas: (android.graphics.Canvas) -> Unit
    ): CapturedTextureFrame? = trace("$label#SurfaceTextureCapture[${size.width}x${size.height}]") {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "$label SurfaceTexture capture must run on the main thread"
        }
        if (closed || size == IntSize.Zero) return@trace null
        if (!ensureInitialized(size, timeoutMs)) return@trace null

        val latch = CountDownLatch(1)
        synchronized(lock) {
            pendingFrame = null
            pendingSize = size
            pendingLogicalSize = logicalSize
            pendingLatch = latch
        }

        trace("$label#SurfaceTextureDraw") {
            renderer.draw(drawCanvas)
        }

        val completed = trace("$label#SurfaceTextureAwaitCopy") {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val frame = synchronized(lock) {
            val capturedFrame = pendingFrame
            pendingFrame = null
            pendingSize = IntSize.Zero
            pendingLogicalSize = IntSize.Zero
            pendingLatch = null
            capturedFrame
        }
        if (completed) {
            frame
        } else {
            frame?.close()
            null
        }
    }

    private fun ensureInitialized(size: IntSize, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var initialized = false
        executeOnGlThread {
            try {
                if (!closed) {
                    trace("$label#SurfaceTextureEnsure[${size.width}x${size.height}]") {
                        renderer.ensureInitialized(size)
                    }
                    initialized = renderer.isInitialized
                }
            } finally {
                latch.countDown()
            }
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && initialized
    }

    private fun completeCapture(sourceTexture: Texture2D) {
        val size: IntSize
        val logicalSize: IntSize
        synchronized(lock) {
            size = pendingSize
            logicalSize = pendingLogicalSize
        }
        if (size == IntSize.Zero) return

        val ownedFramebuffer = copyToOwnedFramebuffer(sourceTexture, size)
        val frame = CapturedTextureFrame(
            texture = ownedFramebuffer.colorAttachmentTexture,
            size = size,
            logicalSize = logicalSize,
            releaseTexture = { ownedFramebuffer.destroy() }
        )
        val latch = synchronized(lock) {
            val activeLatch = pendingLatch
            if (activeLatch != null && pendingSize == size && pendingLogicalSize == logicalSize) {
                pendingFrame = frame
                activeLatch
            } else {
                null
            }
        }
        if (latch != null) {
            latch.countDown()
        } else {
            frame.close()
        }
    }

    private fun copyToOwnedFramebuffer(sourceTexture: Texture2D, size: IntSize): Framebuffer {
        return trace("$label#SurfaceTextureCopyToOwnedFbo") {
            val commands = commandsProvider()
            val framebuffer = Framebuffer.create(
                FramebufferSpecification(
                    size = size,
                    attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                        format = FramebufferTextureFormat.RGBA8,
                        coordinateOrigin = CoordinateOrigin.TOP_LEFT
                    )
                ),
                commands
            )
            framebuffer.bind(commands, Bind.DRAW, updateViewport = true)
            commands.clear()
            simpleQuadRenderer.draw(
                texture = sourceTexture,
                textureCoordinatesFlat = null,
                flipY = false
            )
            framebuffer
        }
    }

    override fun close() {
        closed = true
        if (isOnGlThread()) {
            closeOnGlThread()
        } else {
            executeOnGlThread(::closeOnGlThread)
        }
    }

    private fun closeOnGlThread() {
        renderer.release()
        synchronized(lock) {
            pendingFrame?.close()
            pendingFrame = null
            pendingSize = IntSize.Zero
            pendingLogicalSize = IntSize.Zero
            pendingLatch = null
        }
    }
}
