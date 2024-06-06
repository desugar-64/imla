/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.renderer

import androidx.compose.ui.graphics.Color

internal interface RendererApi {
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

    companion object {
        internal val api: Api = Api.OpenGL
    }
}
