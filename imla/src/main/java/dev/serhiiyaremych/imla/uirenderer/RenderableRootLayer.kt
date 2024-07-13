/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.annotation.MainThread
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Renderer2D
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D

internal class RenderableRootLayer(
    private val layerDownsampleFactor: Int,
    private val density: Density,
    internal val graphicsLayer: GraphicsLayer,
    internal val renderer2D: Renderer2D,
    private val onLayerTextureUpdated: () -> Unit
) {
    val sizeInt: IntSize get() = graphicsLayer.size
    val sizeDec: Size get() = sizeInt.toSize()
    val highResTexture: Texture2D
        get() = highResFBO.colorAttachmentTexture
    val lowResTexture: Texture2D
        get() = lowResFBO.colorAttachmentTexture
    val scale: Float
        get() = 1.0f / layerDownsampleFactor

    val isReady: Boolean
        get() = sizeInt == IntSize.Zero

    private lateinit var renderableScope: RenderableScope
    private val drawingScope: CanvasDrawScope = CanvasDrawScope()
    private lateinit var layerExternalTexture: SurfaceTexture
    private lateinit var layerSurface: Surface
    private lateinit var extOesLayerTexture: Texture2D

    private lateinit var lowResFBO: Framebuffer
    private lateinit var highResFBO: Framebuffer

    private var isInitialized: Boolean = false
    private var isDestroyed: Boolean = false

    fun initialize() {
        require(!isDestroyed) { "Can't re-init destroyed layer" }
        if (!isReady) {
            trace("RenderableRootLayer#initialize") {
                renderableScope =
                    RenderableScope(scale = scale, originalSizeInt = sizeInt, renderer = renderer2D)
                val specification = FramebufferSpecification(
                    size = sizeInt,
                    attachmentsSpec = FramebufferAttachmentSpecification(
                        listOf(FramebufferTextureSpecification(format = FramebufferTextureFormat.RGBA8))
                    ),
                    downSampleFactor = layerDownsampleFactor // downsample layer texture later
                )

                lowResFBO = Framebuffer.create(specification)
                highResFBO =
                    Framebuffer.create(specification.copy(downSampleFactor = 1)) // no downsampling

                extOesLayerTexture = Texture2D.create(
                    target = Texture.Target.TEXTURE_EXTERNAL_OES,
                    specification = Texture.Specification(size = sizeInt, flipTexture = false)
                )
                extOesLayerTexture.bind()
                layerExternalTexture = SurfaceTexture(extOesLayerTexture.id)
                layerExternalTexture.setDefaultBufferSize(sizeInt.width, sizeInt.height)
                layerSurface = Surface(layerExternalTexture)

                layerExternalTexture.setOnFrameAvailableListener {
                    trace("surfaceTexture#updateTexImage") { it.updateTexImage() }
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
        "copyExtTextureToFrameBuffer"
    ) {
        with(renderableScope) {
            trace("fullSizeBuffer") {
                highResFBO.bind(Bind.DRAW)

                drawScene(camera = cameraController.camera) {
                    drawQuad(
                        position = center,
                        size = size,
                        texture = extOesLayerTexture
                    )
                }
                highResFBO.colorAttachmentTexture.bind()
                highResFBO.colorAttachmentTexture.generateMipMaps()
            }
            trace("scaledSizeBuffer") {
                highResFBO.bind(Bind.READ)
                lowResFBO.bind(Bind.DRAW)

                val highResTexSize = highResFBO.specification.size

                highResFBO.readBuffer(0)
                RenderCommand.blitFramebuffer(
                    srcX0 = 0,
                    srcY0 = 0,
                    srcX1 = highResTexSize.width,
                    srcY1 = highResTexSize.height,
                    dstX0 = 0,
                    dstY0 = 0,
                    dstX1 = lowResFBO.colorAttachmentTexture.width,
                    dstY1 = lowResFBO.colorAttachmentTexture.height,
                    mask = RenderCommand.colorBufferBit,
                    filter = RenderCommand.linearTextureFilter,
                )
                lowResFBO.colorAttachmentTexture.bind()
                lowResFBO.colorAttachmentTexture.generateMipMaps()
            }
        }
    }

    //    context(GLRenderer.RenderCallback)
    @MainThread
    fun updateTex() = trace("RenderableRootLayer#updateTex") {
        require(!isDestroyed) { "Can't update destroyed layer" }
        require(!graphicsLayer.isReleased) { "GraphicsLayer has been released!" }
        require(isInitialized) { "RenderableRootLayer not initialized!" }

        trace("drawLayerToExtTexture[$sizeInt]") {
            val hwCanvas = trace("lockHardwareCanvas") { layerSurface.lockHardwareCanvas() }
            trace("hwCanvasClear") {
                hwCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
            drawingScope.draw(density, LayoutDirection.Ltr, Canvas(hwCanvas), sizeDec) {
                trace("drawGraphicsLayer") {
                    drawLayer(graphicsLayer)
                }
            }
            trace("unlockCanvasAndPost") { layerSurface.unlockCanvasAndPost(hwCanvas) }
        }
    }

    fun destroy() {
        layerExternalTexture.release()
        layerSurface.release()
        extOesLayerTexture.destroy()
        isDestroyed = true
    }
}

internal operator fun IntSize.compareTo(other: IntSize): Int {
    return (width.toLong() * height).compareTo((other.width.toLong() * other.height))
}
