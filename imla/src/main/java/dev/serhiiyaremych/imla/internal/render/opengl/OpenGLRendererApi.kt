/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.opengl

import android.opengl.GLES30
import android.opengl.GLES31Ext.glCopyImageSubDataEXT
import androidx.compose.ui.graphics.Color
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.ext.checkGlError
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.RendererApi
import dev.serhiiyaremych.imla.internal.render.VertexArray
import dev.serhiiyaremych.imla.internal.render.opengl.buffer.toGlTarget

internal class OpenGLRendererAPI : RendererApi {

    override val colorBufferBit: Int = GLES30.GL_COLOR_BUFFER_BIT
    override val stencilBufferBit: Int = GLES30.GL_STENCIL_BUFFER_BIT
    override val linearTextureFilter: Int = GLES30.GL_LINEAR

    // Stencil function constants
    override val stencilAlways: Int = GLES30.GL_ALWAYS
    override val stencilEqual: Int = GLES30.GL_EQUAL
    override val stencilKeep: Int = GLES30.GL_KEEP
    override val stencilReplace: Int = GLES30.GL_REPLACE

    override fun init() {
        // Note: Don't check for GL errors during init. The EGL context might be in a
        // transitional state (e.g., after screen on/off), and we don't want to fail
        // initialization just because there's a stale error in the queue.
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        setClearColorNoCheck(Color.Transparent)

        // Clear any pending errors after initialization
        GLES30.glGetError()
    }

    override fun setClearColor(color: Color) = trace("setClearColor") {
        GLES30.glClearColor(color.red, color.green, color.blue, color.alpha)
        checkGlError()
    }

    private fun setClearColorNoCheck(color: Color) {
        GLES30.glClearColor(color.red, color.green, color.blue, color.alpha)
    }

    override fun clear() = trace("glClear") {
        checkGlError(GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT))
    }

    override fun clear(mask: Int) = trace("glClear") {
        checkGlError(GLES30.glClear(mask))
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

    // Stencil operations for hardware clipping
    override fun enableStencilTest() = trace("enableStencilTest") {
        GLES30.glEnable(GLES30.GL_STENCIL_TEST)
    }

    override fun disableStencilTest() = trace("disableStencilTest") {
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
    }

    override fun stencilFunc(func: Int, ref: Int, mask: Int) {
        GLES30.glStencilFunc(func, ref, mask)
    }

    override fun stencilOp(sfail: Int, dpfail: Int, dppass: Int) {
        GLES30.glStencilOp(sfail, dpfail, dppass)
    }

    override fun stencilMask(mask: Int) {
        GLES30.glStencilMask(mask)
    }

    override fun clearStencil(value: Int) {
        GLES30.glClearStencil(value)
    }

    // Scissor operations for optimized rendering
    override fun enableScissorTest() = trace("enableScissorTest") {
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
    }

    override fun disableScissorTest() = trace("disableScissorTest") {
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    override fun setScissor(x: Int, y: Int, width: Int, height: Int) = trace("glScissor") {
        checkGlError(GLES30.glScissor(x, y, width, height))
    }

    override fun bindFramebuffer(bind: Bind, fboId: Int) = trace("bindFBO") {
        checkGlError(GLES30.glBindFramebuffer(bind.toGlTarget(), fboId))
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

    override fun copyImageSubData(
        srcName: Int,
        srcTarget: Int,
        srcLevel: Int,
        srcX: Int,
        srcY: Int,
        srcZ: Int,
        dstName: Int,
        dstTarget: Int,
        dstLevel: Int,
        dstX: Int,
        dstY: Int,
        dstZ: Int,
        srcWidth: Int,
        srcHeight: Int,
        srcDepth: Int
    ) = trace("glCopyImageSubData") {
        // @formatter:off
        checkGlError(
            glCopyImageSubDataEXT(
                /* srcName = */   srcName,
                /* srcTarget = */ srcTarget,
                /* srcLevel = */  srcLevel,
                /* srcX = */      srcX,
                /* srcY = */      srcY,
                /* srcZ = */      srcZ,
                /* dstName = */   dstName,
                /* dstTarget = */ dstTarget,
                /* dstLevel = */  dstLevel,
                /* dstX = */      dstX,
                /* dstY = */      dstY,
                /* dstZ = */      dstZ,
                /* srcWidth = */  srcWidth,
                /* srcHeight = */ srcHeight,
                /* srcDepth = */  srcDepth
            )
        )
        // @formatter:on
    }
}
