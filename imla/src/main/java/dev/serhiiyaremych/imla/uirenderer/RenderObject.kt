/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused", "CanBeParameter")

package dev.serhiiyaremych.imla.uirenderer

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.view.Surface
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.util.trace
import androidx.graphics.opengl.GLRenderer
import androidx.graphics.opengl.egl.EGLManager
import dev.serhiiyaremych.imla.renderer.SubTexture2D
import dev.serhiiyaremych.imla.renderer.Texture2D
import kotlin.properties.Delegates

internal class RenderObject internal constructor(
    internal val id: String,
    internal val rect: Rect,
    internal val scaledRect: Rect,
    internal var originalLayer: SubTexture2D,
    internal var scaledLayer: SubTexture2D,
    internal val renderableScope: RenderableScope,
) {
    private var renderCallback: ((RenderObject) -> Unit)? = null

    private val openGLCallback = object : GLRenderer.RenderCallback {
        override fun onDrawFrame(eglManager: EGLManager) {
            trace("RenderObject#onRender") {
                renderCallback?.invoke(this@RenderObject)
            }
        }
    }

    internal var style: Style by Delegates.observable(Style.default) { _, old, new ->
        if (old != new) {
            invalidate()
        }
    }

    internal var mask: Texture2D? = null

    var renderTarget: GLRenderer.RenderTarget? = null

    fun invalidate(onRenderComplete: ((GLRenderer.RenderTarget) -> Unit)? = null) {
        renderTarget?.requestRender(onRenderComplete)
    }

    fun setRenderCallback(onRender: ((RenderObject) -> Unit)?) {
        this.renderCallback = onRender
    }

    fun updateOffset(offset: IntOffset) {
        val (x, y) = offset
        val scaledTranslateY =
            scaledLayer.texture.height - (y * renderableScope.scale) - scaledRect.height
        val scaledRect = scaledRect.translate(
            translateX = x.toFloat() * renderableScope.scale,
            translateY = scaledTranslateY
        )
        // todo: update coordinates in place
        scaledLayer = SubTexture2D.createFromCoords(
            texture = scaledLayer.texture,
            rect = scaledRect
        )

        val rect = rect.translate(
            translateX = x.toFloat(),
            translateY = originalLayer.texture.height - y - rect.height
        )
        originalLayer = SubTexture2D.createFromCoords(
            texture = originalLayer.texture,
            rect = rect
        )

        invalidate()
    }

    override fun toString(): String {
        return "RenderObject(id='$id', rect='$rect', layer='${scaledLayer.id}, ${scaledLayer.subTextureSize}')"
    }


    fun detachFromRenderer() {
        renderTarget?.detach(true)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RenderObject

        if (id != other.id) return false
        if (rect != other.rect) return false
        if (style != other.style) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + rect.hashCode()
        result = 31 * result + style.hashCode()
        return result
    }


    companion object {

        private fun createImageReader(width: Int, height: Int): ImageReader {
            val maxImages = 2
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ImageReader.newInstance(
                    /* width = */ width,
                    /* height = */ height,
                    /* format = */ PixelFormat.RGBA_8888,
                    /* maxImages = */ maxImages,
                    /* usage = */
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                )
            } else {
                ImageReader.newInstance(
                    /* width = */ width,
                    /* height = */ height,
                    /* format = */ PixelFormat.RGBA_8888,
                    /* maxImages = */ maxImages
                )
            }
        }

        fun createFromSurface(
            id: String,
            renderableLayer: RenderableRootLayer,
            glRenderer: GLRenderer,
            surface: Surface,
            rect: Rect,
        ): RenderObject {
            val originalTexture = renderableLayer.layerTexture
            val scaledTexture = renderableLayer.scaledLayerTexture
            val region = rect
            val scaledRegion = Matrix().apply {
                scale(renderableLayer.scale, renderableLayer.scale)
            }.map(region)

            val renderObject = RenderObject(
                id = id,
                rect = region,
                scaledRect = scaledRegion,
                originalLayer = SubTexture2D.createFromCoords(
                    texture = originalTexture,
                    rect = region
                ),
                scaledLayer = SubTexture2D.createFromCoords(
                    texture = scaledTexture,
                    rect = scaledRegion
                ),
                renderableScope = RenderableScope(
                    scale = renderableLayer.scale,
                    originalSizeInt = region.size.toIntSize(),
                    renderer = renderableLayer.renderer2D
                ),
            ).apply {
                renderTarget = glRenderer.attach(
                    surface = surface,
                    width = rect.width.toInt(),
                    height = rect.height.toInt(),
                    renderer = openGLCallback
                )
            }
            return renderObject
        }
    }
}