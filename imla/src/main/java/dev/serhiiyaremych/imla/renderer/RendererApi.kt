/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.renderer

import androidx.compose.ui.graphics.Color
import dev.serhiiyaremych.imla.renderer.framebuffer.Bind

internal interface RendererApi {
    val colorBufferBit: Int
    val linearTextureFilter: Int

    enum class Api { None, OpenGL }

    fun init()
    fun setClearColor(color: Color)
    fun clear()
    fun drawIndexed(vertexArray: VertexArray, indexCount: Int = 0)
    fun setViewPort(x: Int, y: Int, width: Int, height: Int)
    fun disableDepthTest()
    fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean)
    fun enableBlending()
    fun disableBlending()
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

    companion object {
        internal val api: Api = Api.OpenGL
    }
}
