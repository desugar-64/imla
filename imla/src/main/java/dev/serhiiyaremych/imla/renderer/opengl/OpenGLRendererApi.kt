/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.opengl

import android.opengl.GLES30
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.RendererApi
import dev.serhiiyaremych.imla.renderer.VertexArray

internal class OpenGLRendererAPI : RendererApi {

    override fun init() {
        // LOG
        Log.d(TAG, "vendor: " + GLES30.glGetString(GLES30.GL_VENDOR))
        Log.d(TAG, "renderer: " + GLES30.glGetString(GLES30.GL_RENDERER))
        Log.d(TAG, "version: " + GLES30.glGetString(GLES30.GL_VERSION))
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    override fun setClearColor(color: Color) = trace("setClearColor") {
        GLES30.glClearColor(color.red, color.green, color.blue, color.alpha)
    }

    override fun clear() = trace("glClear") {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
    }

    // @formatter:off
    override fun drawIndexed(vertexArray: VertexArray, indexCount: Int) = trace("drawIndexed") {
        vertexArray.bind()
        GLES30.glDrawElements(
            /* mode = */ GLES30.GL_TRIANGLES,
            /* count = */ if (indexCount == 0) requireNotNull(vertexArray.indexBuffer).count else indexCount,
            /* type = */ GLES30.GL_UNSIGNED_INT,
            /* offset = */ 0
        )
    }
    // @formatter:on

    override fun setViewPort(x: Int, y: Int, width: Int, height: Int) = trace("glViewport") {
        GLES30.glViewport(x, y, width, height)
    }

    override fun disableDepthTest() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    }

    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
        // @formatter:off
        GLES30.glColorMask(
            /* red = */ red,
            /* green = */ green,
            /* blue = */ blue,
            /* alpha = */ alpha
        )
        // @formatter:on
    }

    override fun enableBlending() = trace("enableBlending") {
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    override fun disableBlending() = trace("disableBlending") {
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    companion object {
        private const val TAG = "OpenGLRendererAPI"
    }
}