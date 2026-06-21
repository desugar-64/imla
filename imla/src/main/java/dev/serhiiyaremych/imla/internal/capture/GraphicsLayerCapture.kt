package dev.serhiiyaremych.imla.internal.capture

import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderMetricsLog
import kotlin.math.ceil

/**
 * Captures a [GraphicsLayer] into a renderable frame.
 *
 * API 29+ uses platform [android.graphics.HardwareRenderer] output into an
 * [android.media.ImageReader]. Older APIs use a GL-thread-backed SurfaceTexture
 * bridge supplied by the renderer owner.
 */
@MainThread
internal class GraphicsLayerCapture(
    private val executorName: String = "ImlaLayerCapture",
    private val textureCaptureFactory: CanvasTextureCaptureFactory? = null,
    private val captureThread: CaptureThread,
    platformRendererCount: Int = 1
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val drawScope = CanvasDrawScope()

    // Each platform unit pairs its own RenderNode with its own single-in-flight
    // SingleBufferRenderer (hence its own ImageReader). kickCapture ping-pongs
    // across units: while GL still holds one unit's lease, the next capture
    // renders into a free unit. Keeping every renderer single-in-flight on its
    // own 2-buffer queue avoids the producer starvation and single-thread release
    // deadlock that a shared queue with overlapping leases hit.
    private val rendererCount = platformRendererCount.coerceAtLeast(1)
    private val platformUnits = ArrayList<PlatformRenderUnit>(rendererCount)
    private var currentPlatformSize: IntSize = IntSize.Zero
    private var nextPlatformUnit: Int = 0
    private var textureCapture: CanvasTextureCapture? = null
    private var latestFrame: CapturedLayerFrame? = null
    private var mailboxGeneration: Long = 0L

    @MainThread
    fun kickCapture(
        layer: GraphicsLayer,
        size: IntSize,
        density: Density,
        layoutDirection: LayoutDirection,
        bucketPhysicalSize: Boolean,
        logicalSize: IntSize,
        contentOffset: Offset,
        vsyncTimeNanos: Long?,
        onCompleted: () -> Unit
    ): Boolean {
        checkMainThread()
        if (size == IntSize.Zero) return false

        val captureScale = 1f
        val contentSize = size.scaledBy(captureScale)
        val sizes = CaptureSizes(
            logical = logicalSize,
            content = contentSize,
            capture = captureSize(
                contentSize = contentSize,
                bucketPhysicalSize = bucketPhysicalSize
            )
        )

        // Two distinct capture backends, selected by API level:
        //  • API 29+  → kickHardwareBufferCapture: async HardwareRenderer → ImageReader,
        //               ping-ponged across single-in-flight SingleBufferRenderer units.
        //  • API < 29 → kickTextureCapture: synchronous SurfaceTexture bridge.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            kickHardwareBufferCapture(
                layer = layer,
                sizes = sizes,
                captureScale = captureScale,
                density = density,
                layoutDirection = layoutDirection,
                contentOffset = contentOffset,
                vsyncTimeNanos = vsyncTimeNanos,
                onCompleted = onCompleted
            )
        } else {
            kickTextureCapture(
                layer = layer,
                sizes = sizes,
                captureScale = captureScale,
                density = density,
                layoutDirection = layoutDirection,
                contentOffset = contentOffset,
                onCompleted = onCompleted
            )
        }
    }

    /**
     * Pre-API 29 backend: synchronous SurfaceTexture-backed capture
     * ([CanvasTextureCapture]). Always signals completion — even on failure (null
     * frame) — so the caller's in-flight gate releases instead of wedging.
     */
    private fun kickTextureCapture(
        layer: GraphicsLayer,
        sizes: CaptureSizes,
        captureScale: Float,
        density: Density,
        layoutDirection: LayoutDirection,
        contentOffset: Offset,
        onCompleted: () -> Unit
    ): Boolean {
        captureToTexture(
            layer = layer,
            sizes = sizes,
            captureScale = captureScale,
            density = density,
            layoutDirection = layoutDirection,
            contentOffset = contentOffset
        )?.let(::publishLatestFrame)
        onCompleted()
        return true
    }

    /**
     * API 29+ backend: async HardwareRenderer → ImageReader capture, ping-ponged
     * across single-in-flight [SingleBufferRenderer] units. Returns false when every
     * unit is busy (backpressure); a started capture delivers its result later on the
     * capture thread, which hops to main and signals [onCompleted].
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun kickHardwareBufferCapture(
        layer: GraphicsLayer,
        sizes: CaptureSizes,
        captureScale: Float,
        density: Density,
        layoutDirection: LayoutDirection,
        contentOffset: Offset,
        vsyncTimeNanos: Long?,
        onCompleted: () -> Unit
    ): Boolean {
        val bufferSize = ensurePlatformRenderers(size = sizes.capture) ?: return false

        val generation = mailboxGeneration
        return SceneRenderMetricsLog.time(
            phase = if (vsyncTimeNanos != null) {
                "graphicsLayer.captureCanvas.drawHardwareBuffer.vsyncTime.async"
            } else {
                "graphicsLayer.captureCanvas.drawHardwareBuffer.platform.async"
            },
            details = "$executorName ${sizes.capture.width}x${sizes.capture.height}"
        ) {
            trace(
                if (vsyncTimeNanos != null) {
                    "GraphicsLayerCapture#kickHardwareBufferVsyncTime"
                } else {
                    "GraphicsLayerCapture#kickHardwareBufferPlatform"
                }
            ) {
                kickOnFreeUnit(
                    vsyncTimeNanos = vsyncTimeNanos,
                    record = { node ->
                        recordLayerToRenderNode(
                            renderNode = node,
                            layer = layer,
                            sizes = sizes,
                            captureScale = captureScale,
                            density = density,
                            layoutDirection = layoutDirection,
                            contentOffset = contentOffset
                        )
                    },
                    // onResult is delivered on the capture thread (see
                    // SingleBufferRenderer); hop back to main before touching the
                    // main-confined mailbox state. Always signal completion for the
                    // current generation, even on failure (lease == null), so the
                    // caller's in-flight gate releases instead of wedging.
                    onResult = { lease ->
                        val frame = lease?.let {
                            CapturedHardwareBufferFrame(
                                lease = it,
                                // The buffer may be larger than this capture's bucket (grow-only
                                // reuse); tag the frame with the real buffer size so contentUv
                                // crops the content sub-rect correctly.
                                size = bufferSize,
                                contentSize = sizes.content,
                                logicalSize = sizes.logical
                            )
                        }
                        // Async message so the completion hand-back bypasses the
                        // Choreographer vsync sync-barrier instead of queuing behind
                        // doFrame. A plain post lands at a variable point relative to
                        // the frame, smearing the otherwise vsync-paced capture
                        // cadence into the submit. Mirrors the kick's postOnAnimation
                        // fix (Option A) for the completion path.
                        mainHandler.postAsync {
                            if (generation != mailboxGeneration) {
                                frame?.close()
                            } else {
                                frame?.let(::publishLatestFrame)
                                onCompleted()
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * Records and renders into the first free unit, ping-ponging across units so a
     * capture can overlap GL's hold on another unit's lease. Returns false only
     * when every unit is busy (all leases still held) — correct backpressure.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun kickOnFreeUnit(
        vsyncTimeNanos: Long?,
        record: (RenderNode) -> Unit,
        onResult: (BufferLease?) -> Unit
    ): Boolean {
        val count = platformUnits.size
        if (count == 0) return false
        for (offset in 0 until count) {
            val index = (nextPlatformUnit + offset) % count
            val unit = platformUnits[index]
            val started = unit.renderer.renderAsync(
                vsyncTimeNanos = vsyncTimeNanos,
                record = { record(unit.renderNode) },
                onResult = onResult
            )
            if (started) {
                nextPlatformUnit = (index + 1) % count
                return true
            }
        }
        return false
    }

    @MainThread
    fun takeLatestFrame(): CapturedLayerFrame? {
        checkMainThread()
        val frame = latestFrame
        latestFrame = null
        return frame
    }

    fun release() {
        mailboxGeneration++
        latestFrame?.close()
        latestFrame = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            releasePlatformUnits()
        }
        currentPlatformSize = IntSize.Zero
        textureCapture?.close()
        textureCapture = null
    }

    private fun publishLatestFrame(frame: CapturedLayerFrame) {
        latestFrame?.close()
        latestFrame = frame
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "GraphicsLayerCapture.capture must run on the main thread"
        }
    }

    // Posts an async message so it runs during the vsync sync-barrier window
    // rather than queuing behind it. setAsynchronous is API 22+, below minSdk.
    private fun Handler.postAsync(block: () -> Unit) {
        val message = Message.obtain(this, block)
        message.isAsynchronous = true
        sendMessage(message)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun recordLayerToRenderNode(
        renderNode: RenderNode,
        layer: GraphicsLayer,
        sizes: CaptureSizes,
        captureScale: Float,
        density: Density,
        layoutDirection: LayoutDirection,
        contentOffset: Offset
    ) {
        SceneRenderMetricsLog.time(
            phase = "graphicsLayer.captureCanvas.recordRenderNode",
            details = "$executorName ${sizes.logical.width}x${sizes.logical.height}"
        ) {
            trace(captureTraceLabel("replayLayerToRenderNode", sizes.capture)) {
                replayLayerToRenderNode(
                    renderNode = renderNode,
                    layer = layer,
                    logicalSize = sizes.logical,
                    captureScale = captureScale,
                    density = density,
                    layoutDirection = layoutDirection,
                    contentOffset = contentOffset
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun replayLayerToRenderNode(
        renderNode: RenderNode,
        layer: GraphicsLayer,
        logicalSize: IntSize,
        captureScale: Float,
        density: Density,
        layoutDirection: LayoutDirection,
        contentOffset: Offset
    ) {
        val canvas = renderNode.beginRecording()
        try {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.scale(captureScale, captureScale)
            drawScope.draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = ComposeCanvas(canvas),
                size = logicalSize.toSize()
            ) {
                drawLayer(layer, contentOffset)
            }
        } finally {
            renderNode.endRecording()
        }
    }

    private fun captureToTexture(
        layer: GraphicsLayer,
        sizes: CaptureSizes,
        captureScale: Float,
        density: Density,
        layoutDirection: LayoutDirection,
        contentOffset: Offset
    ): CapturedTextureFrame? = trace(captureTraceLabel("textureCapture", sizes.capture)) {
        val capture = textureCapture()
        val frame = capture.capture(
            size = sizes.capture,
            logicalSize = sizes.logical,
            timeoutMs = MAIN_THREAD_DRAW_WAIT_TIMEOUT_MS
        ) { canvas ->
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.scale(captureScale, captureScale)
            drawScope.draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = ComposeCanvas(canvas),
                size = sizes.logical.toSize()
            ) {
                drawLayer(layer, contentOffset)
            }
        } ?: return@trace null
        val texture = frame.takeTexture()
        frame.close()
        CapturedTextureFrame(
            texture = texture,
            size = frame.size,
            contentSize = sizes.content,
            logicalSize = frame.logicalSize
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    // Grow-only platform buffer: a smaller capture renders into the corner of an existing larger
    // buffer (the content's contentUv already crops the sampled sub-rect), so a resize only
    // reallocates when it needs MORE room, never when it shrinks. Recreating on every size-bucket
    // step made the first capture after each recreation come back blank, which showed as the
    // content "pop"/flicker during resize. Returns the actual buffer size to tag the frame with,
    // or null if no renderers are available.
    private fun ensurePlatformRenderers(
        size: IntSize
    ): IntSize? {
        return when {
            size == IntSize.Zero -> currentPlatformSize.takeIf { platformUnits.isNotEmpty() }
            platformUnits.size == rendererCount &&
                currentPlatformSize.width >= size.width &&
                currentPlatformSize.height >= size.height -> currentPlatformSize
            else -> {
                val grown = IntSize(
                    width = maxOf(size.width, currentPlatformSize.width),
                    height = maxOf(size.height, currentPlatformSize.height)
                )
                releasePlatformUnits()
                repeat(rendererCount) { index ->
                    val node = RenderNode("$executorName#$index").apply {
                        setPosition(0, 0, grown.width, grown.height)
                    }
                    val renderer = SingleBufferRenderer(
                        label = "$executorName#$index",
                        size = grown,
                        contentRoot = node,
                        captureThread = captureThread
                    )
                    platformUnits += PlatformRenderUnit(node, renderer)
                }
                currentPlatformSize = grown
                nextPlatformUnit = 0
                grown.takeIf { platformUnits.isNotEmpty() }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun releasePlatformUnits() {
        platformUnits.forEach { it.renderer.close() }
        platformUnits.clear()
        nextPlatformUnit = 0
    }

    private fun captureTraceLabel(phase: String, size: IntSize): String {
        return "GraphicsLayerCapture#$phase[${size.width}x${size.height}]"
    }

    private fun textureCapture(): CanvasTextureCapture {
        val existingCapture = textureCapture
        return existingCapture ?: run {
            requireNotNull(textureCaptureFactory) {
                "SurfaceTexture capture is required below API 29"
            }.create(executorName).also {
                textureCapture = it
            }
        }
    }

    private fun DrawScope.drawLayer(layer: GraphicsLayer, contentOffset: Offset) {
        if (contentOffset == Offset.Zero) {
            drawLayer(layer)
        } else {
            translate(left = -contentOffset.x, top = -contentOffset.y) {
                drawLayer(layer)
            }
        }
    }

    private fun IntSize.scaledBy(scale: Float): IntSize {
        return IntSize(
            width = ceil(width * scale).toInt().coerceAtLeast(1),
            height = ceil(height * scale).toInt().coerceAtLeast(1)
        )
    }

    private fun captureSize(
        contentSize: IntSize,
        bucketPhysicalSize: Boolean
    ): IntSize {
        return if (bucketPhysicalSize) {
            CaptureSizeBuckets.bucket(contentSize)
        } else {
            contentSize
        }
    }

    /**
     * The three sizes that describe a single capture, bundled so the same-typed
     * values cannot be transposed at call sites and so their relationship is
     * documented in one place. They form a pipeline:
     *
     * [logical] → [content] → [capture]
     *
     * @property logical layout (logical-pixel) size of the captured content; the
     *   [DrawScope] draws into this coordinate space.
     * @property content [logical] scaled by the capture scale — the rendered pixel
     *   footprint before bucketing.
     * @property capture the framebuffer/`ImageReader` size actually allocated and
     *   rendered: [content], optionally snapped up to a [CaptureSizeBuckets] bucket.
     */
    private data class CaptureSizes(
        val logical: IntSize,
        val content: IntSize,
        val capture: IntSize
    )

    @RequiresApi(Build.VERSION_CODES.Q)
    private class PlatformRenderUnit(
        val renderNode: RenderNode,
        val renderer: SingleBufferRenderer
    )

    private companion object {
        private const val MAIN_THREAD_DRAW_WAIT_TIMEOUT_MS = 500L
    }
}
