/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.graphics.CanvasBufferedRenderer
import androidx.graphics.opengl.GLRenderer
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.ext.isGLThread
import dev.serhiiyaremych.imla.internal.ext.logw
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.opengl.OpenGLHardwareBufferTexture2D
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

internal interface GraphicsLayerTexture {
    fun captureCanvas(
        sizePx: IntSize,
        textureOrigin: CoordinateOrigin,
        drawCanvas: (Canvas) -> Unit
    ): GraphicsLayerTextureFrame?

    fun captureGraphicsLayer(
        sizePx: IntSize,
        density: Density,
        layoutDirection: LayoutDirection,
        graphicsLayer: GraphicsLayer,
        textureOrigin: CoordinateOrigin,
        isCanvasFlippedY: Boolean,
        contentOffset: Offset
    ): GraphicsLayerTextureFrame?

    fun release()
}

internal sealed class GraphicsLayerTextureFrame(
    open val sizePx: IntSize,
    open val textureOrigin: CoordinateOrigin
) {
    abstract var texture2D: Texture2D?
    abstract fun release()

    @Deprecated("Use textureOrigin.needsFlipForOpenGL()", ReplaceWith("textureOrigin.needsFlipForOpenGL()"))
    val isTextureFlippedY: Boolean get() = textureOrigin.needsFlipForOpenGL()
}

internal class GlTextureFrame(
    override val sizePx: IntSize,
    override val textureOrigin: CoordinateOrigin,
    override var texture2D: Texture2D?
) : GraphicsLayerTextureFrame(sizePx, textureOrigin) {
    override fun release() {
        texture2D?.destroy()
        texture2D = null
    }
}

internal abstract class BaseGraphicsLayerTexture : GraphicsLayerTexture {
    private val drawScope = CanvasDrawScope()

    override fun captureGraphicsLayer(
        sizePx: IntSize,
        density: Density,
        layoutDirection: LayoutDirection,
        graphicsLayer: GraphicsLayer,
        textureOrigin: CoordinateOrigin,
        isCanvasFlippedY: Boolean,
        contentOffset: Offset
    ): GraphicsLayerTextureFrame? {
        return trace("GraphicsLayerTexture#captureGraphicsLayer") {
            captureCanvas(sizePx, textureOrigin) { canvas ->
                canvas.drawColor(Color.Transparent.toArgb(), PorterDuff.Mode.CLEAR)
                drawScope.draw(
                    density = density,
                    layoutDirection = layoutDirection,
                    canvas = ComposeCanvas(canvas),
                    size = sizePx.toSize()
                ) {
                    if (isCanvasFlippedY) {
                        scale(scaleX = 1.0f, scaleY = -1f) {
                            if (contentOffset != Offset.Zero) {
                                translate(left = -contentOffset.x, top = -contentOffset.y) {
                                    drawLayer(graphicsLayer)
                                }
                            } else {
                                drawLayer(graphicsLayer)
                            }
                        }
                    } else {
                        if (contentOffset != Offset.Zero) {
                            translate(left = -contentOffset.x, top = -contentOffset.y) {
                                drawLayer(graphicsLayer)
                            }
                        } else {
                            drawLayer(graphicsLayer)
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
internal class GraphicsLayerTextureV29Impl(
    private val glRenderer: GLRenderer
) : BaseGraphicsLayerTexture() {
    private val renderNode = RenderNode("GraphicsLayerTexture")
    private var canvasBufferedRenderer: CanvasBufferedRenderer? = null
    private var currentSizePx: IntSize = IntSize.Zero
    private val directExecutor = Executor { it.run() }

    override fun captureCanvas(
        sizePx: IntSize,
        textureOrigin: CoordinateOrigin,
        drawCanvas: (Canvas) -> Unit
    ): GraphicsLayerTextureFrame? {
        return trace("GraphicsLayerTexture#captureCanvas") {
            val captureStartNs = SystemClock.elapsedRealtimeNanos()
            if (!ensureRenderer(sizePx)) return@trace null
            val renderer = canvasBufferedRenderer ?: return@trace null

            // 1. Record drawing commands to RenderNode
            val canvas = renderNode.beginRecording()
            try {
                drawCanvas(canvas)
            } finally {
                renderNode.endRecording()
            }

            // 2. Render to HardwareBuffer
            val renderStartNs = SystemClock.elapsedRealtimeNanos()
            val result = drawToHardwareBuffer(renderer) ?: return@trace null
            val renderEndNs = SystemClock.elapsedRealtimeNanos()
            if (result.status != CanvasBufferedRenderer.RenderResult.SUCCESS) {
                GraphicsLayerCaptureDiagnostics.renderResultFailed(result.status, sizePx)
                renderer.releaseBuffer(result.hardwareBuffer, result.fence)
                return@trace null
            }

            // 3. Wait for GPU fence
            val fenceStartNs = SystemClock.elapsedRealtimeNanos()
            result.fence?.apply {
                trace("GraphicsLayerTexture#awaitFence") {
                    awaitForever()
                    close()
                }
            }
            val fenceEndNs = SystemClock.elapsedRealtimeNanos()

            // 4. Import to GL texture synchronously (blocks main thread)
            val importStartNs = SystemClock.elapsedRealtimeNanos()
            val texture = trace("GraphicsLayerTexture#syncImport") {
                importToGLTexture(result.hardwareBuffer, sizePx, textureOrigin)
            }
            val importEndNs = SystemClock.elapsedRealtimeNanos()

            // 5. Return buffer to pool immediately - no deferred release!
            renderer.releaseBuffer(result.hardwareBuffer)

            GraphicsLayerCaptureDiagnostics.timing(
                sizePx = sizePx,
                origin = textureOrigin,
                renderNs = renderEndNs - renderStartNs,
                fenceNs = fenceEndNs - fenceStartNs,
                importNs = importEndNs - importStartNs,
                totalNs = importEndNs - captureStartNs
            )
            if (texture == null) {
                GraphicsLayerCaptureDiagnostics.hardwareBufferImportReturnedNull(sizePx, textureOrigin)
            }

            // 6. Return frame with texture already imported
            texture?.let {
                GlTextureFrame(
                    sizePx = sizePx,
                    textureOrigin = textureOrigin,
                    texture2D = it
                )
            }
        }
    }

    private fun importToGLTexture(
        buffer: HardwareBuffer,
        sizePx: IntSize,
        textureOrigin: CoordinateOrigin
    ): Texture2D? {
        return trace("GraphicsLayerTexture#importToGLTexture") {
            val resultRef = AtomicReference<Texture2D?>()
            val latch = CountDownLatch(1)

            glRenderer.execute {
                // On GL thread - safe to create EGL image and texture
                resultRef.set(
                    OpenGLHardwareBufferTexture2D.createFromBuffer(buffer, sizePx, textureOrigin)
                )
                latch.countDown()
            }

            latch.await() // Block main thread until GL import complete
            resultRef.get()
        }
    }

    override fun release() {
        canvasBufferedRenderer?.close()
        canvasBufferedRenderer = null
        currentSizePx = IntSize.Zero
    }

    private fun ensureRenderer(sizePx: IntSize): Boolean {
        return trace("GraphicsLayerTexture#ensureRenderer") {
            if (sizePx == IntSize.Zero) return@trace false
            if (canvasBufferedRenderer == null || hasMeaningfulSizeChange(sizePx)) {
                canvasBufferedRenderer?.close() // Safe - no pending frames can exist
                canvasBufferedRenderer = CanvasBufferedRenderer.Builder(sizePx.width, sizePx.height)
                    .setBufferFormat(HardwareBuffer.RGBA_8888)
                    .setMaxBuffers(1)
                    .build()
                    .also { it.setContentRoot(renderNode) }
                renderNode.setPosition(0, 0, sizePx.width, sizePx.height)
                currentSizePx = sizePx
            }
            canvasBufferedRenderer != null
        }
    }

    private fun drawToHardwareBuffer(
        renderer: CanvasBufferedRenderer
    ): CanvasBufferedRenderer.RenderResult? {
        check(!renderer.isClosed) { "CanvasBufferedRenderer. Attempt to draw after renderer has been closed" }

        return trace("GraphicsLayerTexture#drawToHardwareBuffer") {
            val renderRequest = renderer.obtainRenderRequest()
            val resultRef = AtomicReference<CanvasBufferedRenderer.RenderResult?>()
            val latch = CountDownLatch(1)
            renderRequest.drawAsync(directExecutor) { result ->
                resultRef.set(result)
                latch.countDown()
            }
            latch.await()
            resultRef.get()
        }
    }

    private fun hasMeaningfulSizeChange(newSizePx: IntSize): Boolean {
        if (currentSizePx == IntSize.Zero) return true
        return abs(currentSizePx.width - newSizePx.width) > 1 ||
                abs(currentSizePx.height - newSizePx.height) > 1
    }
}

internal class GraphicsLayerTextureLegacyImpl(
    private val glRenderer: GLRenderer,
    shaderLibrary: ShaderLibrary,
    shaderBinder: ShaderBinder,
    simpleQuadRenderer: SimpleQuadRenderer,
    fboFormat: FramebufferTextureFormat,
    private val textureOrigin: CoordinateOrigin,
    commandsProvider: () -> RenderCommands = { RenderCommand.commands }
) : BaseGraphicsLayerTexture() {
    private val renderer = SurfaceTextureRenderer(
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder,
        simpleQuadRenderer = simpleQuadRenderer,
        fboFormat = fboFormat,
        flipOutput = textureOrigin.needsFlipForOpenGL(),
        commandsProvider = commandsProvider,
        executeOnGlThread = { block -> glRenderer.execute { block() } },
        onTextureReady = { texture -> handleTextureReady(texture) }
    )
    private var currentSizePx: IntSize = IntSize.Zero
    private var pendingFrameLatch: CountDownLatch? = null
    private var pendingTexture: Texture2D? = null

    override fun captureCanvas(
        sizePx: IntSize,
        textureOrigin: CoordinateOrigin,
        drawCanvas: (Canvas) -> Unit
    ): GraphicsLayerTextureFrame? {
        return trace("GraphicsLayerTextureLegacy#captureCanvas") {
            check(textureOrigin == this.textureOrigin) {
                "captureCanvas origin $textureOrigin does not match backend origin ${this.textureOrigin}"
            }
            if (isGLThread()) {
                logw(TAG, "captureCanvas ignored on GL thread")
                return@trace null
            }
            if (!glRenderer.isRunning() || sizePx == IntSize.Zero) return@trace null
            if (!ensureRenderer(sizePx)) return@trace null

            val latch = CountDownLatch(1)
            pendingFrameLatch = latch
            trace("GraphicsLayerTextureLegacy#draw") {
                renderer.draw { canvas ->
                    drawCanvas(canvas)
                }
                latch.await()
            }

            val texture = pendingTexture
            pendingTexture = null
            pendingFrameLatch = null

            texture?.let {
                GlTextureFrame(
                    sizePx = sizePx,
                    textureOrigin = textureOrigin,
                    texture2D = it
                )
            }
        }
    }

    override fun release() {
        pendingFrameLatch = null
        pendingTexture = null
        currentSizePx = IntSize.Zero
        glRenderer.execute {
            renderer.release()
        }
    }

    private fun ensureRenderer(sizePx: IntSize): Boolean {
        return trace("GraphicsLayerTextureLegacy#ensureRenderer") {
            if (renderer.isInitialized && !hasMeaningfulSizeChange(sizePx)) return@trace true
            val latch = CountDownLatch(1)
            glRenderer.execute {
                if (hasMeaningfulSizeChange(sizePx)) {
                    renderer.release()
                }
                renderer.ensureInitialized(sizePx)
                currentSizePx = renderer.currentSize
                latch.countDown()
            }
            latch.await()
            renderer.isInitialized
        }
    }

    private fun handleTextureReady(texture: Texture2D) {
        val latch = pendingFrameLatch ?: return
        pendingTexture = texture
        latch.countDown()
    }

    private fun hasMeaningfulSizeChange(newSizePx: IntSize): Boolean {
        if (currentSizePx == IntSize.Zero) return true
        return abs(currentSizePx.width - newSizePx.width) > 1 ||
                abs(currentSizePx.height - newSizePx.height) > 1
    }

    companion object {
        private const val TAG = "GraphicsLayerTextureLegacyImpl"
    }
}

internal fun createGraphicsLayerTexture(
    glRenderer: GLRenderer,
    shaderLibrary: ShaderLibrary,
    shaderBinder: ShaderBinder,
    simpleQuadRenderer: SimpleQuadRenderer,
    fboFormat: FramebufferTextureFormat,
    textureOrigin: CoordinateOrigin,
    commandsProvider: () -> RenderCommands = { RenderCommand.commands }
): GraphicsLayerTexture {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        GraphicsLayerTextureV29Impl(glRenderer)
    } else {
        GraphicsLayerTextureLegacyImpl(
            glRenderer = glRenderer,
            shaderLibrary = shaderLibrary,
            shaderBinder = shaderBinder,
            simpleQuadRenderer = simpleQuadRenderer,
            fboFormat = fboFormat,
            textureOrigin = textureOrigin,
            commandsProvider = commandsProvider
        )
    }
}
