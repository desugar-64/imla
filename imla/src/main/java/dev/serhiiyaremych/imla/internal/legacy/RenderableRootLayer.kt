/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import androidx.annotation.MainThread
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Renderer2D
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.ext.isGLThread
import dev.serhiiyaremych.imla.internal.ext.logd
import dev.serhiiyaremych.imla.internal.ext.threadTag
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import androidx.graphics.opengl.GLRenderer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

internal class RenderableRootLayer(
    private val shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder,
    private val layerDownsampleFactor: Int,
    private val density: Density,
    internal val renderer2D: Renderer2D,
    private val glRenderer: GLRenderer,
    simpleQuadRenderer: SimpleQuadRenderer,
    commandsProvider: () -> RenderCommands = { RenderCommand.commands }
) {
    val sizeInt: IntSize get() = lastSize
    val scale: Float
        get() = 1.0f / layerDownsampleFactor

    val isReady: Boolean
        get() = lastSize != IntSize.Zero

    private val graphicsLayerTexture = createGraphicsLayerTexture(
        glRenderer = glRenderer,
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder,
        simpleQuadRenderer = simpleQuadRenderer,
        fboFormat = FramebufferTextureFormat.RGBA8,
        textureOrigin = CoordinateOrigin.TOP_LEFT,
        commandsProvider = commandsProvider
    )
    private val pendingFrame = AtomicReference<GraphicsLayerTextureFrame?>(null)
    private val releaseQueue = ConcurrentLinkedQueue<GraphicsLayerTextureFrame>()
    private var currentFrame: GraphicsLayerTextureFrame? = null
    private var currentTexture: Texture2D? = null
    private var lastSize: IntSize = IntSize.Zero

    private var isDestroyed: Boolean = false

    fun initialize(overrideSize: IntSize? = null) {
        require(!isDestroyed) { "Can't re-init destroyed layer" }
        val size = overrideSize ?: sizeInt
        if (size == IntSize.Zero) return
        trace("RenderableRootLayer#initialize") {
            val pending = pendingFrame.getAndSet(null)
            enqueueRelease(pending)
            enqueueRelease(currentFrame)
            currentFrame = null
            currentTexture = null
            lastSize = size
            releasePendingFrames()
        }
    }

    internal fun isTextureReady(): Boolean = currentTexture != null && !isDestroyed

    @MainThread
    fun captureLayer(graphicsLayer: GraphicsLayer, size: IntSize): Boolean =
        trace("RenderableRootLayer#captureLayer") {
        require(!isDestroyed) { "Can't update destroyed layer" }
        require(!graphicsLayer.isReleased) { "GraphicsLayer has been released!" }
        if (size == IntSize.Zero) return@trace false
        lastSize = size

//        logd("RenderableRootLayer", "captureLayer size=$size ${threadTag()}")
        val frame = graphicsLayerTexture.captureGraphicsLayer(
            sizePx = size,
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            graphicsLayer = graphicsLayer,
            textureOrigin = CoordinateOrigin.TOP_LEFT,
            isCanvasFlippedY = false,
            contentOffset = Offset.Zero
        ) ?: return@trace false

        val oldPending = pendingFrame.getAndSet(frame)
        if (oldPending != null) {
            enqueueRelease(oldPending)
        }
        true
    }

    fun consumePendingFrame(): Texture2D? = trace("RenderableRootLayer#consumePendingFrame") {
        if (isDestroyed) return@trace null
        if (!isGLThread()) return@trace currentTexture
        val pending = pendingFrame.getAndSet(null) ?: return@trace currentTexture
        if (pending === currentFrame) return@trace currentTexture

        val texture = resolveFrameTexture(pending) ?: run {
            enqueueRelease(pending)
            releasePendingFrames()
            return@trace currentTexture
        }

        val oldFrame = currentFrame
        currentFrame = pending
        currentTexture = texture
        enqueueRelease(oldFrame)
        releasePendingFrames()
        currentTexture
    }

    fun texture(): Texture2D? = currentTexture

    private fun enqueueRelease(frame: GraphicsLayerTextureFrame?) {
        if (frame != null) {
            releaseQueue.add(frame)
        }
    }

    private fun releasePendingFrames() {
        if (!isGLThread()) return
        while (true) {
            val frame = releaseQueue.poll() ?: return
            frame.release()
        }
    }

    private fun resolveFrameTexture(frame: GraphicsLayerTextureFrame): Texture2D? {
        // Texture is already imported - just return it
        return frame.texture2D
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        val pending = pendingFrame.getAndSet(null)
        enqueueRelease(pending)
        enqueueRelease(currentFrame)
        currentFrame = null
        currentTexture = null
        releasePendingFrames()
        graphicsLayerTexture.release()
    }
}

internal operator fun IntSize.compareTo(other: IntSize): Int {
    return (width.toLong() * height).compareTo((other.width.toLong() * other.height))
}
