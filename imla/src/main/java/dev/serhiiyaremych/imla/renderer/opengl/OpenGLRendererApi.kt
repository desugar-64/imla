/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.opengl

import android.opengl.GLES30
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.tracing.trace
import dev.serhiiyaremych.imla.ext.checkGlError
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.RendererApi
import dev.serhiiyaremych.imla.renderer.VertexArray
import dev.serhiiyaremych.imla.renderer.opengl.buffer.toGlTarget

internal class OpenGLRendererAPI : RendererApi {

    override val colorBufferBit: Int = GLES30.GL_COLOR_BUFFER_BIT
    override val linearTextureFilter: Int = GLES30.GL_LINEAR

    override fun init() {
        // LOG
        Log.d(TAG, "vendor: " + GLES30.glGetString(GLES30.GL_VENDOR))
        Log.d(TAG, "renderer: " + GLES30.glGetString(GLES30.GL_RENDERER))
        Log.d(TAG, "version: " + GLES30.glGetString(GLES30.GL_VERSION))
        checkGlError(GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA))
        checkGlError(GLES30.glDisable(GLES30.GL_DEPTH_TEST))
        checkGlError(GLES30.glDisable(GLES30.GL_STENCIL_TEST))
        checkGlError(GLES30.glDisable(GLES30.GL_SCISSOR_TEST))
        checkGlError(GLES30.glDisable(GLES30.GL_CULL_FACE))
        setClearColor(Color.Transparent)
    }

    override fun setClearColor(color: Color) = trace("setClearColor") {
        checkGlError(GLES30.glClearColor(color.red, color.green, color.blue, color.alpha))
    }

    override fun clear() = trace("glClear") {
        checkGlError(GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT))
    }

    // @formatter:off
    override fun drawIndexed(vertexArray: VertexArray, indexCount: Int) = trace("drawIndexed") {
        vertexArray.bind()
        GLES30.glDrawElements(
            /* mode = */ GLES30.GL_TRIANGLES,
            /* count = */ if (indexCount == 0) requireNotNull(vertexArray.indexBuffer).elements else indexCount,
            /* type = */ GLES30.GL_UNSIGNED_INT,
            /* offset = */ 0
        )
    }
    // @formatter:on

    override fun setViewPort(x: Int, y: Int, width: Int, height: Int) = trace("glViewport") {
        checkGlError(GLES30.glViewport(x, y, width, height))
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

    override fun bindDefaultFramebuffer(bind: Bind) = trace("bindDefaultFBO") {
        checkGlError(GLES30.glBindFramebuffer(bind.toGlTarget(), 0))
    }

    override fun bindDefaultProgram() = trace("useDefaultProgram") {
        checkGlError(GLES30.glUseProgram(0))
    }

    override fun blitFramebuffer(
        srcX0: Int,
        srcY0: Int,
        srcX1: Int,
        srcY1: Int,
        dstX0: Int,
        dstY0: Int,
        dstX1: Int,
        dstY1: Int,
        mask: Int,
        filter: Int
    ) = trace("glBlitFramebuffer") {
        // @formatter:off
        checkGlError(
            GLES30.glBlitFramebuffer(
                /* srcX0 = */  srcX0,
                /* srcY0 = */  srcY0,
                /* srcX1 = */  srcX1,
                /* srcY1 = */  srcY1,
                /* dstX0 = */  dstX0,
                /* dstY0 = */  dstY0,
                /* dstX1 = */  dstX1,
                /* dstY1 = */  dstY1,
                /* mask = */   mask,
                /* filter = */ filter
            )
        )
        // @formatter:on
    }

    companion object {
        private const val TAG = "OpenGLRendererAPI"
    }
}