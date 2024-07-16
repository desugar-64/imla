/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.renderer

import androidx.compose.ui.graphics.Color
import dev.serhiiyaremych.imla.renderer.opengl.OpenGLRendererAPI

internal object RenderCommand {
    private val rendererAPI: RendererApi = OpenGLRendererAPI()

    val colorBufferBit: Int = rendererAPI.colorBufferBit
    val linearTextureFilter: Int = rendererAPI.linearTextureFilter

    fun init() {
        rendererAPI.init()
    }

    fun setClearColor(color: Color) {
        rendererAPI.setClearColor(color)
    }

    fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
        rendererAPI.colorMask(red, green, blue, alpha)
    }

    fun clear() {
        rendererAPI.clear()
    }

    fun clear(color: Color) {
        setClearColor(color)
        rendererAPI.clear()
    }

    fun drawIndexed(vertexArray: VertexArray, indexCount: Int = 0) {
        rendererAPI.drawIndexed(vertexArray, indexCount)
    }

    fun setViewPort(x: Int, y: Int, width: Int, height: Int) {
        rendererAPI.setViewPort(x, y, width, height)
    }

    fun disableDepthTest() {
        rendererAPI.disableDepthTest()
    }

    fun enableBlending() {
        rendererAPI.enableBlending()
    }

    fun disableBlending() {
        rendererAPI.disableBlending()
    }

    fun bindDefaultFramebuffer(bind: Bind) {
        rendererAPI.bindDefaultFramebuffer(bind)
    }

    fun blitFramebuffer(
        srcX0: Int,
        srcY0: Int,
        srcX1: Int,
        srcY1: Int,
        dstX0: Int,
        dstY0: Int,
        dstX1: Int,
        dstY1: Int,
        mask: Int = colorBufferBit,
        filter: Int = linearTextureFilter,
    ) {
        rendererAPI.blitFramebuffer(
            srcX0 = srcX0,
            srcY0 = srcY0,
            srcX1 = srcX1,
            srcY1 = srcY1,
            dstX0 = dstX0,
            dstY0 = dstY0,
            dstX1 = dstX1,
            dstY1 = dstY1,
            mask = mask,
            filter = filter
        )
    }
}
