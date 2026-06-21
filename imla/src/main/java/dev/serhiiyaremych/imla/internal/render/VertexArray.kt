/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render

import dev.serhiiyaremych.imla.internal.render.opengl.OpenGLVertexArray


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