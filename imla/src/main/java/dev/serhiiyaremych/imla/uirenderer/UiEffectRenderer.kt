/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.uirenderer

import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.annotation.MainThread
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.opengl.GLRenderer
import androidx.graphics.opengl.egl.EGLManager
import androidx.tracing.trace
import dev.serhiiyaremych.imla.ext.ifNotNull
import dev.serhiiyaremych.imla.ext.logd
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.renderer.VertexDataManager
import dev.serhiiyaremych.imla.renderer.camera.OrthographicCameraController
import dev.serhiiyaremych.imla.renderer.commands.CommandBufferPool
import dev.serhiiyaremych.imla.renderer.commands.CommandBufferRenderer
import dev.serhiiyaremych.imla.renderer.commands.CommandEncoder
import dev.serhiiyaremych.imla.renderer.framebuffer.Bind
import dev.serhiiyaremych.imla.renderer.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.shader.Shader
import dev.serhiiyaremych.imla.renderer.shader.ShaderBinder
import dev.serhiiyaremych.imla.renderer.shader.ShaderLibrary
import dev.serhiiyaremych.imla.renderer.shader.ShaderManager
import dev.serhiiyaremych.imla.uirenderer.processing.SimpleQuadRenderer

@Retention(AnnotationRetention.SOURCE)
internal annotation class GLThread

internal class AndroidLayer(
    val oesTexture: Texture2D,
    val surfaceTexture: SurfaceTexture,
    val drawSurface: Surface,
    val density: Density
) {
    private val drawingScope: CanvasDrawScope = CanvasDrawScope()

    @GLThread
    fun renderAsExternalTexture(graphicsLayer: GraphicsLayer) {

        trace("renderRootLayerToOES") {

            drawSurface.lockHardwareCanvas()?.let { canvas ->
                require(canvas.width == graphicsLayer.size.width) { "Canvas width mismatch" }
                require(canvas.height == graphicsLayer.size.height) { "Canvas height mismatch" }

                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawingScope.draw(
                    density = density,
                    layoutDirection = LayoutDirection.Ltr,
                    canvas = Canvas(canvas),
                    size = Size(canvas.width.toFloat(), canvas.height.toFloat())
                ) {
                    trace("drawGraphicsLayer") {
                        scale(scaleX = 1.0f, scaleY = -1f) {
                            drawLayer(graphicsLayer)
                        }
                    }
                }
                drawSurface.unlockCanvasAndPost(canvas)
            }
        }
        fun release() {
            surfaceTexture.release()
            drawSurface.release()
            oesTexture.destroy()
        }
    }

    companion object {
        @GLThread
        fun create(size: IntSize, density: Density, onTextureUpdated: () -> Unit): AndroidLayer {
            val oesTexture = Texture2D.create(
                target = Texture.Target.TEXTURE_EXTERNAL_OES,
                specification = Texture.Specification(size = size)
            )
            val surfaceTexture = SurfaceTexture(oesTexture.id)
            surfaceTexture.setDefaultBufferSize(size.width, size.height)
            surfaceTexture.setOnFrameAvailableListener {
                it.updateTexImage()
                onTextureUpdated()
            }
            val drawSurface = Surface(surfaceTexture)
            return AndroidLayer(oesTexture, surfaceTexture, drawSurface, density)
        }
    }
}

internal class RootLayer(
    private val shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder,
    private val simpleQuadRenderer: SimpleQuadRenderer
) {
    val position: IntRect = IntRect.Zero
    var layerFramebuffer: Framebuffer? = null
    var layerCopyShader: Shader? = null
    var androidLayer: AndroidLayer? = null

    var graphicsLayer: GraphicsLayer? = null

    @GLThread
    fun initialize(size: IntSize, density: Density, onTextureReady: () -> Unit) {
        check(androidLayer == null) { "AndroidLayer has been already initialized" }
        androidLayer = AndroidLayer.create(size, density) {
            copyOESTexture()
            onTextureReady()
        }
        layerCopyShader = shaderLibrary.loadShaderFromFile(
            vertFileName = "simple_quad",
            fragFileName = "simple_ext_quad"
        ).apply {
            bind(shaderBinder)
            bindUniformBlock(
                SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
                SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
            )
        }

        layerFramebuffer = Framebuffer.create(
            FramebufferSpecification(
                size = size,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(flip = true)
            )
        )
    }

    @MainThread
    fun updateTex() {
        ifNotNull(androidLayer, graphicsLayer) { layer, graphicsLayer ->
            layer.renderAsExternalTexture(graphicsLayer)
        }
    }

    @GLThread
    private fun copyOESTexture() {
        trace("fullSizeBuffer") {
            ifNotNull(layerFramebuffer, androidLayer, layerCopyShader) { fbo, layer, shader ->
                fbo.bind(Bind.DRAW)
                RenderCommand.clear()
                simpleQuadRenderer.draw(shader, layer.oesTexture)
            }
        }

    }
}

public class UiEffectRenderer(assetManager: AssetManager) {
    private val TAG = "UiEffectRenderer"
    private val glRenderer: GLRenderer = GLRenderer()

    private val shaderLibrary: ShaderLibrary = ShaderLibrary(assetManager)
    private val shaderBinder = ShaderBinder()
    private val simpleRenderer: SimpleRenderer = SimpleRenderer()
    private val simpleQuadRenderer: SimpleQuadRenderer = SimpleQuadRenderer(shaderLibrary, simpleRenderer, shaderBinder)

    private val commandEncoder = CommandEncoder(CommandBufferPool())
    private val commandBufferRenderer =
        CommandBufferRenderer(commandEncoder, ShaderManager(assetManager), VertexDataManager())
    private var cameraController: OrthographicCameraController? = null
    private var rootLayer: RootLayer? = null
    private var rootLayerTarget: GLRenderer.RenderTarget? = null

    init {
        glRenderer.start("UiEffectRendererThread")
    }

    internal fun setRootLayer(rootGraphicsLayer: GraphicsLayer, positionInWindow: IntRect, density: Density, surface: Surface) {
        check(rootLayer == null) { "RootLayer has been already initialized" }
        check(rootLayerTarget == null) { "RootLayerTarget has been already initialized" }
        rootLayer = RootLayer(shaderLibrary, shaderBinder, simpleQuadRenderer).apply {
            graphicsLayer = rootGraphicsLayer
        }
        while (glRenderer.isRunning().not()) {
            // block until GL thread is ready
            // consider other approaches
        }
        cameraController = OrthographicCameraController.createPixelUnitsController(
            viewportWidth = positionInWindow.width,
            viewportHeight = positionInWindow.height
        ).apply {
            zoomLevel = 1.0f
            updateCameraProjection()
        }
        glRenderer.execute {
            rootLayerTarget = glRenderer.attach(
                surface = surface,
                width = positionInWindow.width,
                height = positionInWindow.height,
                renderer = object : GLRenderer.RenderCallback {
                    override fun onDrawFrame(eglManager: EGLManager) {
                        logd(TAG, "onDrawFrame")
                        // todo: update all effect areas
                    }
                }
            )
            rootLayer?.initialize(
                size = rootGraphicsLayer.size,
                density = density,
                onTextureReady = {
                    // todo: update all effect areas
                    rootLayerTarget?.requestRender()
                }
            )
        }
    }

    internal fun onRootUpdated() {
        // todo: root view updated, update its texture
        rootLayer?.updateTex()
    }
}