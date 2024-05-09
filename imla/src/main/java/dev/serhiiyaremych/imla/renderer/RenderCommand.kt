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

    fun init() {
        rendererAPI.init()
    }

    fun setClearColor(color: Color) {
        rendererAPI.setClearColor(color)
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
}
