/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.ext.logw
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.SimpleRenderer
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import kotlin.math.abs

/**
 * Generic renderer that converts Canvas drawing to OpenGL texture via SurfaceTexture.
 * Consolidates the common OES texture → FBO pipeline used across multiple renderers.
 *
 * Usage:
 * 1. Call [ensureInitialized] from GL thread with desired size
 * 2. Call [draw] to render content to the Surface
 * 3. Receive texture via [onTextureReady] callback when GPU processes frame
 */
internal class SurfaceTextureRenderer(
    private val shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder,
    private val simpleQuadRenderer: SimpleQuadRenderer,
    private val fboFormat: FramebufferTextureFormat = FramebufferTextureFormat.RGBA8,
    private val flipOutput: Boolean = false,
    private val commandsProvider: () -> RenderCommands = { RenderCommand.commands },
    private val executeOnGlThread: (() -> Unit) -> Unit = { it() },
    private val onTextureReady: (Texture2D) -> Unit
) {
    private var extOesTexture: Texture2D? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var framebuffer: Framebuffer? = null

    var currentSize: IntSize = IntSize.Zero
        private set

    /** True after content has been drawn at least once after initialization/resize */
    var hasValidContent: Boolean = false
        private set

    val isInitialized: Boolean
        get() = surface != null && currentSize != IntSize.Zero

    val outputTexture: Texture2D?
        get() = framebuffer?.colorAttachmentTexture

    val outputFramebuffer: Framebuffer?
        get() = framebuffer

    /**
     * Initialize or resize the renderer.
     * Must be called from GL thread.
     */
    fun ensureInitialized(size: IntSize) {
        if (size == IntSize.Zero) return
        if (currentSize == size && isInitialized) return
        if (hasMeaningfulSizeChange(size)) {
            release()
            hasValidContent = false
        }

        trace("SurfaceTextureRenderer#init[$size]") {
            // Create FBO
            framebuffer = Framebuffer.create(
                FramebufferSpecification(
                    size = size,
                    attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                        format = fboFormat,
                        coordinateOrigin = if (flipOutput) CoordinateOrigin.TOP_LEFT else CoordinateOrigin.BOTTOM_LEFT
                    )
                ),
                commandsProvider()
            )

            // Create OES texture
            extOesTexture = Texture2D.create(
                target = Texture.Target.TEXTURE_EXTERNAL_OES,
                specification = Texture.Specification(size = size)
            ).also { it.bind() }

            // Create SurfaceTexture and attach frame listener
            surfaceTexture = SurfaceTexture(extOesTexture!!.id).apply {
                trace("SurfaceTextureRenderer#setBufferSize[$size]") {
                    setDefaultBufferSize(size.width, size.height)
                }
                setOnFrameAvailableListener { st ->
                    executeOnGlThread {
                        try {
                            trace("surfaceTexture#updateTexImage") {
                                st.updateTexImage()
                            }
                        } catch (e: RuntimeException) {
                            logw(TAG, "updateTexImage failed: ${e.message}")
                            return@executeOnGlThread
                        }
                        copyToFramebuffer()
                        framebuffer?.colorAttachmentTexture?.let(onTextureReady)
                    }
                }
            }

            // Create Surface for Canvas drawing
            surface = Surface(surfaceTexture)

            // Initialize shared shader on first use (GL thread)
            ensureShaderInitialized()

            currentSize = size
        }
    }

    private fun ensureShaderInitialized() {
        if (sharedExtOesShader == null) {
            sharedExtOesShader = shaderLibrary.loadShaderFromFile(
                vertFileName = "simple_quad",
                fragFileName = "simple_ext_quad"
            ).apply {
                bind(shaderBinder)
                bindUniformBlock(
                    SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
                    SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
                )
            }
        }
    }

    /**
     * Draw content to the Surface. Content will be available as texture
     * via [onTextureReady] callback after GPU processes the frame.
     *
     * @param block Drawing lambda that receives the hardware Canvas
     */
    inline fun draw(crossinline block: (Canvas) -> Unit) {
        val s = surface ?: return

        s.lockHardwareCanvas()?.let { canvas ->
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                block(canvas)
            } finally {
                try {
                    s.unlockCanvasAndPost(canvas)
                } catch (e: IllegalArgumentException) {
                    logw(TAG, "unlockCanvas failed: ${e.message}")
                    recoverFromCanvasError(s)
                }
            }
        }
    }

    private fun recoverFromCanvasError(surface: Surface) {
        runCatching {
            val fallback = surface.lockCanvas(null)
            surface.unlockCanvasAndPost(fallback)
        }.onFailure {
            logw(TAG, "fallback unlock failed: ${it.message}")
        }
    }

    private fun copyToFramebuffer() = trace("SurfaceTextureRenderer#copyToFbo") {
        val texture = extOesTexture ?: return
        val fb = framebuffer ?: return
        val shader = sharedExtOesShader ?: return

        val commands = commandsProvider()
        fb.bind(commands, Bind.DRAW, updateViewport = true)
        commands.clear()
        simpleQuadRenderer.draw(shader, texture as Texture, flipY = false)
        hasValidContent = true
    }

    /**
     * Release all resources. Safe to call multiple times.
     */
    fun release() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        surface?.release()
        surfaceTexture?.release()
        extOesTexture?.destroy()
        framebuffer?.destroy()

        surface = null
        surfaceTexture = null
        extOesTexture = null
        framebuffer = null
        currentSize = IntSize.Zero
        hasValidContent = false
    }

    private fun hasMeaningfulSizeChange(newSize: IntSize): Boolean {
        if (currentSize == IntSize.Zero) return true
        return abs(currentSize.width - newSize.width) > 1 ||
                abs(currentSize.height - newSize.height) > 1
    }

    companion object {
        private const val TAG = "SurfaceTextureRenderer"

        /** Shared OES shader across all instances. Initialized on first use from GL thread. */
        private var sharedExtOesShader: Shader? = null

        /**
         * Release the shared shader. Call when all renderers are destroyed
         * and GL context is being torn down.
         */
        fun releaseSharedResources() {
            sharedExtOesShader = null
        }
    }
}
