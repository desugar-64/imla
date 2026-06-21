/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.internal.render

import androidx.compose.ui.graphics.Color
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind

internal interface RendererApi {
    val colorBufferBit: Int
    val stencilBufferBit: Int
    val linearTextureFilter: Int

    // Stencil function constants
    val stencilAlways: Int
    val stencilEqual: Int
    val stencilKeep: Int
    val stencilReplace: Int

    enum class Api { None, OpenGL }

    fun init()
    fun setClearColor(color: Color)
    fun clear()

    // Stencil operations for hardware clipping
    fun enableStencilTest()
    fun disableStencilTest()
    fun stencilFunc(func: Int, ref: Int, mask: Int)
    fun stencilOp(sfail: Int, dpfail: Int, dppass: Int)
    fun stencilMask(mask: Int)
    fun clearStencil(value: Int)

    // Scissor operations for optimized rendering
    fun enableScissorTest()
    fun disableScissorTest()
    fun setScissor(x: Int, y: Int, width: Int, height: Int)

    fun clear(mask: Int)
    fun drawIndexed(vertexArray: VertexArray, indexCount: Int = 0)
    fun setViewPort(x: Int, y: Int, width: Int, height: Int)
    fun disableDepthTest()
    fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean)
    fun enableBlending()
    fun disableBlending()
    fun bindFramebuffer(bind: Bind, fboId: Int)
    fun bindDefaultFramebuffer(bind: Bind)
    fun bindDefaultProgram()
    fun blitFramebuffer(
        srcX0: Int,
        srcY0: Int,
        srcX1: Int,
        srcY1: Int,
        dstX0: Int,
        dstY0: Int,
        dstX1: Int,
        dstY1: Int,
        mask: Int,
        filter: Int,
    )

    fun copyImageSubData(
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
    )

    companion object {
        internal val api: Api = Api.OpenGL
    }
}
