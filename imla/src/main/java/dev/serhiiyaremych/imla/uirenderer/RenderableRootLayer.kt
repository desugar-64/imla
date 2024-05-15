/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.trace
import androidx.graphics.opengl.GLRenderer
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D

internal class RenderableRootLayer(
    private val layerDownsampleFactor: Int,
    private val density: Density,
    internal val graphicsLayer: GraphicsLayer,
    private val onLayerTextureUpdated: () -> Unit
) {
    val sizeInt: IntSize get() = graphicsLayer.size
    val sizeDec: Size get() = sizeInt.toSize()
    val layerTexture: Texture2D
        get() = frameBuffer.colorAttachmentTexture
    val scale: Float
        get() = 1.0f / layerDownsampleFactor

    val isReady: Boolean
        get() = sizeInt == IntSize.Zero

    private lateinit var renderableScope: RenderableScope
    private val drawingScope: CanvasDrawScope = CanvasDrawScope()
    private lateinit var layerExternalTexture: SurfaceTexture
    private lateinit var layerSurface: Surface
    private lateinit var extOesLayerTexture: Texture2D

    private lateinit var frameBuffer: Framebuffer

    private var isInitialized: Boolean = false
    private var isDestroyed: Boolean = false

    fun initialize() {
        require(!isDestroyed) { "Can't re-init destroyed layer" }
        if (!isReady) {
            trace("RenderableRootLayer#initialize") {
                renderableScope = RenderableScope(scale = scale, originalSizeInt = sizeInt)
                val specification = FramebufferSpecification(
                    size = sizeInt,
                    attachmentsSpec = FramebufferAttachmentSpecification(
                        listOf(FramebufferTextureSpecification(format = FramebufferTextureFormat.RGBA8))
                    ),
                    downSampleFactor = layerDownsampleFactor // downsample layer texture later
                )

                frameBuffer = Framebuffer.create(specification)

                extOesLayerTexture = Texture2D.create(
                    target = Texture.Target.TEXTURE_EXTERNAL_OES,
                    specification = Texture.Specification(size = sizeInt, flipTexture = false)
                )
                extOesLayerTexture.bind()
                layerExternalTexture = SurfaceTexture(extOesLayerTexture.id)
                layerExternalTexture.setDefaultBufferSize(sizeInt.width, sizeInt.height)
                layerSurface = Surface(layerExternalTexture)

                layerExternalTexture.setOnFrameAvailableListener {
                    it.updateTexImage()
                    copyTextureToFrameBuffer()
                    onLayerTextureUpdated()
                }
                isInitialized = true
            }
        }
    }

    fun resize() {
        TODO("Implement runtime layer resizing")
    }

    private fun copyTextureToFrameBuffer() = trace(
        sectionName = "RenderableRootLayer#copyExtTextureToFrameBuffer"
    ) {
        with(renderableScope) {
            bindFrameBuffer(frameBuffer) {
                RenderCommand.setViewPort(
                    x = 0,
                    y = 0,
                    width = scaledSize.x.toInt(),
                    height = scaledSize.y.toInt()
                )
                drawScene {
                    drawQuad(
                        position = scaledCenter,
                        size = scaledSize,
                        texture = extOesLayerTexture
                    )
                }
            }
        }
    }

    context(GLRenderer.RenderCallback)
    fun updateTex() {
        require(!isDestroyed) { "Can't update destroyed layer" }
        require(!graphicsLayer.isReleased) { "GraphicsLayer has been released!" }
        require(isInitialized) { "RenderableRootLayer not initialized!" }

        trace("RenderableRootLayer#drawLayerToExtTexture") {
            val hwCanvas = layerSurface.lockHardwareCanvas()
            hwCanvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
            drawingScope.draw(density, LayoutDirection.Ltr, Canvas(hwCanvas), sizeDec) {
                drawLayer(graphicsLayer)
            }
            layerSurface.unlockCanvasAndPost(hwCanvas)
        }
    }

    fun destroy() {
        layerExternalTexture.releaseTexImage()
        layerExternalTexture.release()
        layerSurface.release()
        extOesLayerTexture.destroy()
        isDestroyed = true
    }
}

internal operator fun IntSize.compareTo(other: IntSize): Int {
    return (width.toLong() * height).compareTo((other.width.toLong() * other.height))
}
