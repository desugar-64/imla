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

internal class RenderObject internal constructor(
    internal val id: String,
    internal val rect: Rect,
    internal var layerArea: SubTexture2D,
    internal val renderableScope: RenderableScope,
    internal val style: Style,
) {
    private var onRender: (RenderableScope.(RenderObject) -> Unit)? = null

    val glCallback = object : GLRenderer.RenderCallback {
        override fun onDrawFrame(eglManager: EGLManager) {
            trace("RenderObject#onRender") {
                onRender?.invoke(renderableScope, this@RenderObject)
            }
        }
    }

    var renderTarget: GLRenderer.RenderTarget? = null

    fun invalidate(onRenderComplete: (GLRenderer.RenderTarget) -> Unit) {
        renderTarget?.requestRender(onRenderComplete)
    }

    fun onRender(onRender: (RenderableScope.(RenderObject) -> Unit)?) {
        this.onRender = onRender
    }

    fun updateOffset(offset: IntOffset) {
        val (x, y) = offset
        val d = layerArea.texture.height - (y * renderableScope.scale) - rect.height
        val r = rect.translate(translateX = x.toFloat(), translateY = d)
        layerArea = SubTexture2D.createFromCoords(
            texture = layerArea.texture,
            rect = r
        )
        invalidate { }
    }

    override fun toString(): String {
        return "RenderObject(id='$id', rect=$rect)"
    }


    fun detachFromRenderer(glRenderer: GLRenderer) {
        renderTarget?.let {
            glRenderer.detach(it, true)
            if (it.isAttached()) {
                it.detach(true)
            }
        }
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
            style: Style
        ): RenderObject {
            val texture = renderableLayer.layerTexture // downsampled texture
            val region = rect
            val scaledRegion = Matrix().apply {
                scale(renderableLayer.scale, renderableLayer.scale)
            }.map(region)

            val renderObject = RenderObject(
                id = id,
                rect = scaledRegion,
                layerArea = SubTexture2D.createFromCoords(
                    texture = texture,
                    rect = scaledRegion
                ),
                renderableScope = RenderableScope(
                    scale = renderableLayer.scale,
                    originalSizeInt = region.size.toIntSize()
                ),
                style = style
            ).apply {
                renderTarget = glRenderer.attach(
                    surface = surface,
                    width = rect.width.toInt(),
                    height = rect.height.toInt(),
                    renderer = glCallback
                )
            }
            return renderObject
        }
    }
}