/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.opengl

import android.opengl.GLES30
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.IndexBuffer
import dev.serhiiyaremych.imla.renderer.ShaderDataType
import dev.serhiiyaremych.imla.renderer.VertexArray
import dev.serhiiyaremych.imla.renderer.VertexBuffer

internal class OpenGLVertexArray : VertexArray {
    private var rendererId: Int = 0

    private val _vertexBuffers: MutableList<VertexBuffer> = ArrayList()
    val vertexBuffers: List<VertexBuffer> get() = _vertexBuffers

    override var indexBuffer: IndexBuffer? = null
        set(value) {
            field = value
            GLES30.glBindVertexArray(rendererId)
            value?.bind()
        }

    init {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        rendererId = ids[0]
    }

    override fun bind() = trace("vaoBind") {
        GLES30.glBindVertexArray(rendererId)
    }

    override fun unbind() {
        GLES30.glBindVertexArray(0)
    }

    override fun destroy() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(rendererId), 0)
    }

    override fun addVertexBuffer(vertexBuffer: VertexBuffer) {
        GLES30.glBindVertexArray(rendererId)
        vertexBuffer.bind()
        vertexBuffer.layout?.let { bufferLayout ->
            bufferLayout.forEachIndexed { index, element ->
                when (element.type) {
                    ShaderDataType.None -> { /* no-op */
                    }

                    ShaderDataType.Float,
                    ShaderDataType.Float2,
                    ShaderDataType.Float3,
                    ShaderDataType.Float4 -> {
                        GLES30.glEnableVertexAttribArray(index)
                        GLES30.glVertexAttribPointer(
                            /* indx = */ index,
                            /* size = */ element.type.components,
                            /* type = */ element.type.openGLBaseType,
                            /* normalized = */ element.normalized,
                            /* stride = */ bufferLayout.stride,
                            /* offset = */ element.offset
                        )
                    }

                    ShaderDataType.Int,
                    ShaderDataType.Int2,
                    ShaderDataType.Int3,
                    ShaderDataType.Int4,
                    ShaderDataType.Bool -> {
                        GLES30.glEnableVertexAttribArray(index)
                        GLES30.glVertexAttribIPointer(
                            /* index = */ index,
                            /* size = */ element.type.components,
                            /* type = */ element.type.openGLBaseType,
                            /* stride = */ bufferLayout.stride,
                            /* offset = */ element.offset
                        )
                    }

                    ShaderDataType.Mat3,
                    ShaderDataType.Mat4 -> {
                        val count = element.type.components
                        for (i in 0 until count) {
                            GLES30.glEnableVertexAttribArray(index)
                            GLES30.glVertexAttribPointer(
                                /* indx = */ index,
                                /* size = */ count,
                                /* type = */ element.type.openGLBaseType,
                                /* normalized = */ element.normalized,
                                /* stride = */ bufferLayout.stride,
                                /* offset = */ (element.offset + Float.SIZE_BYTES * count * i)
                            )
                            GLES30.glVertexAttribDivisor(index, 1)
                        }
                    }
                }

            }
        }

        _vertexBuffers.add(vertexBuffer)
    }

    private val ShaderDataType.openGLBaseType: Int
        get() = when (this) {
            ShaderDataType.None -> error("Unconvertable shader data type: ${this.name}")
            ShaderDataType.Float -> GLES30.GL_FLOAT
            ShaderDataType.Float2 -> GLES30.GL_FLOAT
            ShaderDataType.Float3 -> GLES30.GL_FLOAT
            ShaderDataType.Float4 -> GLES30.GL_FLOAT
            ShaderDataType.Mat3 -> GLES30.GL_FLOAT
            ShaderDataType.Mat4 -> GLES30.GL_FLOAT
            ShaderDataType.Int -> GLES30.GL_INT
            ShaderDataType.Int2 -> GLES30.GL_INT
            ShaderDataType.Int3 -> GLES30.GL_INT
            ShaderDataType.Int4 -> GLES30.GL_INT
            ShaderDataType.Bool -> GLES30.GL_BOOL
        }
}
