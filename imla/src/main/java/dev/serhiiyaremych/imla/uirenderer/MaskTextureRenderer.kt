/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.trace
import androidx.graphics.opengl.GLRenderer
import dev.serhiiyaremych.imla.ext.isGLThread
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import java.util.concurrent.atomic.AtomicBoolean

internal class MaskTextureRenderer(
    density: Density,
    private val onRenderComplete: (Texture2D) -> Unit
) : Density by density {
    private val drawScope = CanvasDrawScope()

    private lateinit var maskExternalTexture: Texture2D
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var surface: Surface

    private var isInitialized: AtomicBoolean = AtomicBoolean(false)

    private var lastRenderedBrush: Brush? = null

    private fun initialize(size: IntSize) {
        require(isGLThread()) { "Initialization failed: An active GL context is required in the current thread." }

        if (isInitialized.get()) {
            destroy()
        }

        val texSpec = Texture.Specification(
            size = size,
            format = Texture.ImageFormat.R8
        )
        maskExternalTexture =
            Texture2D.create(target = Texture.Target.TEXTURE_EXTERNAL_OES, specification = texSpec)
        maskExternalTexture.bind()
        surfaceTexture = SurfaceTexture(maskExternalTexture.id)
        surfaceTexture.setOnFrameAvailableListener {
            it.updateTexImage()
            onRenderComplete(maskExternalTexture)
        }
        surface = Surface(surfaceTexture)
        isInitialized.set(true)
    }

    fun destroy() {
        if (isInitialized.get()) {
            surfaceTexture.release()
            surface.release()
            maskExternalTexture.destroy()
            isInitialized.set(false)
        }
    }

    private fun invalidateBySize(newSize: IntSize): Boolean {
        return !isInitialized.get() ||
                (maskExternalTexture.width != newSize.width || maskExternalTexture.height != newSize.height)
    }

    private fun shouldRedraw(brush: Brush): Boolean {
        return lastRenderedBrush != brush
    }

    fun GLRenderer.renderMask(brush: Brush, size: IntSize) {
        if (invalidateBySize(size)) execute { initialize(size) }

        if (shouldRedraw(brush)) {
            execute {
                trace("MaskTextureRenderer#renderMask") {
                    val hwCanvas = surface.lockHardwareCanvas()
                    drawScope.draw(
                        this@MaskTextureRenderer,
                        LayoutDirection.Ltr,
                        Canvas(hwCanvas),
                        size.toSize()
                    ) {
                        drawRect(brush)
                    }
                    surface.unlockCanvasAndPost(hwCanvas)
                }
            }
        } else {
            onRenderComplete(maskExternalTexture)
        }
    }

    fun releaseCurrentMask() {
        destroy()
    }
}