/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer

import android.content.res.AssetManager
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.trace
import androidx.graphics.opengl.GLRenderer
import dev.serhiiyaremych.imla.ext.isGLThread
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Renderer2D
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.uirenderer.postprocessing.SimpleQuadRenderer
import java.util.concurrent.atomic.AtomicBoolean

// TODO: Refactor it to custom shader
internal class MaskTextureRenderer(
    density: Density,
    private val assetManager: AssetManager,
    private val renderer2D: Renderer2D,
    private val simpleQuadRenderer: SimpleQuadRenderer,
    private val onRenderComplete: (Texture2D) -> Unit
) : Density by density {
    private val drawScope = CanvasDrawScope()

    private lateinit var frameBuffer: Framebuffer
    private lateinit var maskExternalTexture: Texture2D
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var renderableScope: RenderableScope
    private lateinit var extOesShaderProgram: Shader

    private lateinit var surface: Surface

    private var isInitialized: AtomicBoolean = AtomicBoolean(false)

    private var lastRenderedBrush: Brush? = null

    private fun initialize(size: IntSize) {
        require(isGLThread()) { "Initialization failed: An active GL context is required in the current thread." }

        if (isInitialized.get()) {
            destroy()
        }

        extOesShaderProgram = Shader.create(
            assetManager = assetManager,
            vertexAsset = "shader/simple_quad.vert",
            fragmentAsset = "shader/simple_ext_quad.frag"
        ).apply {
            bindUniformBlock(
                SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
                SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
            )
            setInt("u_Texture", 0)
        }

        frameBuffer = Framebuffer.create(
            FramebufferSpecification(
                size = size,
                attachmentsSpec = FramebufferAttachmentSpecification(
                    attachments = listOf(FramebufferTextureSpecification(format = FramebufferTextureFormat.R8))
                )
            )
        )
        val texSpec = Texture.Specification(
            size = size,
            format = Texture.ImageFormat.RGBA8,
            flipTexture = true
        )
        renderableScope = RenderableScope(1.0f, size, renderer2D)
        maskExternalTexture =
            Texture2D.create(target = Texture.Target.TEXTURE_EXTERNAL_OES, specification = texSpec)
        maskExternalTexture.bind()
        surfaceTexture = SurfaceTexture(maskExternalTexture.id)
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        surfaceTexture.setOnFrameAvailableListener {
            it.updateTexImage()
            copyTextureToFrameBuffer()
            onRenderComplete(frameBuffer.colorAttachmentTexture)
        }
        surface = Surface(surfaceTexture)
        isInitialized.set(true)
    }

    private fun copyTextureToFrameBuffer() = trace(
        sectionName = "copyExtTextureToFrameBuffer"
    ) {
        frameBuffer.bind(Bind.DRAW)
        RenderCommand.clear(Color.Transparent)
        simpleQuadRenderer.draw(shader = extOesShaderProgram, texture = maskExternalTexture)
    }


    private fun invalidateBySize(newSize: IntSize): Boolean {
        return !isInitialized.get() ||
                (maskExternalTexture.width != newSize.width || maskExternalTexture.height != newSize.height)
    }

    private fun shouldRedraw(brush: Brush): Boolean {
        return lastRenderedBrush != brush
    }

    fun renderMask(glRenderer: GLRenderer, brush: Brush, size: IntSize) =
        trace("MaskTextureRenderer#renderMask") {
        if (invalidateBySize(size)) glRenderer.execute { this.initialize(size) }

        if (shouldRedraw(brush)) {
            glRenderer.execute {
                trace("MaskTextureRenderer#shouldRedraw") {
                    val hwCanvas = surface.lockHardwareCanvas()
                    hwCanvas.drawColor(Color.Transparent.toArgb(), PorterDuff.Mode.CLEAR)
                    drawScope.draw(
                        density = this,
                        layoutDirection = LayoutDirection.Ltr,
                        canvas = Canvas(hwCanvas),
                        size = size.toSize()
                    ) {
                        drawRect(brush)
                    }
                    surface.unlockCanvasAndPost(hwCanvas)
                }
            }
        } else {
            this.onRenderComplete(maskExternalTexture)
        }
    }

    fun destroy() {
        if (isInitialized.get()) {
            surfaceTexture.release()
            surface.release()
            maskExternalTexture.destroy()
            isInitialized.set(false)
            frameBuffer.destroy()
        }
    }

    fun releaseCurrentMask() {
        destroy()
    }
}