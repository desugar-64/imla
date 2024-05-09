/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer

import dev.serhiiyaremych.imla.renderer.opengl.OpenGLVertexArray


internal interface VertexArray {

    fun bind()
    fun unbind()
    fun destroy()

    fun addVertexBuffer(vertexBuffer: VertexBuffer)
    var indexBuffer: IndexBuffer?

    companion object {
        fun create(): VertexArray {
            return OpenGLVertexArray()
        }
    }
}