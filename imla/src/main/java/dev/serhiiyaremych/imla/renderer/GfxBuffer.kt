/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.renderer

import dev.serhiiyaremych.imla.renderer.opengl.OpenGLUniformBuffer
import dev.serhiiyaremych.imla.renderer.opengl.buffer.OpenGLIndexBuffer
import dev.serhiiyaremych.imla.renderer.opengl.buffer.OpenGLVertexBuffer
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

internal interface GfxBuffer {
    val elements: Int
    val sizeBytes: Int
    fun bind()
    fun unbind()
    fun destroy()
}

internal enum class ShaderDataType(val components: kotlin.Int) {
    None(0),
    Float(1),
    Float2(2),
    Float3(3),
    Float4(4),
    Mat3(3 * 3),
    Mat4(4 * 4),
    Int(1),
    Int2(2),
    Int3(3),
    Int4(4),
    Bool(1);
}

internal data class BufferElement(
    val name: String,
    val type: ShaderDataType,
    val sizeBytes: Int,
    var offset: Int = 0,
    val normalized: Boolean = false
)

internal class BufferLayout(
    val elements: List<BufferElement>
) : Iterable<BufferElement> by elements {
    var stride: Int = 0
        private set

    constructor(action: MutableList<BufferElement>.() -> Unit) : this(buildList(action))

    init {
        calculateOffsetAndStride()
    }

    private fun calculateOffsetAndStride() {
        var offset = 0
        elements.forEach { element ->
            element.offset = offset
            offset += element.sizeBytes
            stride += element.sizeBytes
        }
    }

}

internal interface VertexBuffer : GfxBuffer {
    var layout: BufferLayout?
    fun setData(data: FloatArray)

    companion object {
        fun create(count: Int): VertexBuffer {
            return OpenGLVertexBuffer(count)
        }

        fun create(vertices: FloatArray): VertexBuffer {
            return OpenGLVertexBuffer(vertices)
        }
    }
}

internal interface IndexBuffer : GfxBuffer {
    companion object {
        fun create(indices: IntArray): IndexBuffer {
            return OpenGLIndexBuffer(indices)
        }
    }
}

internal interface UniformBuffer : GfxBuffer {
    fun setData(data: FloatArray)
    fun setData(data: Buffer)

    companion object {
        fun create(count: Int, bindingPoint: Int): UniformBuffer {
            return OpenGLUniformBuffer(count, bindingPoint)
        }
    }
}
internal fun FloatArray.toFloatBuffer(): FloatBuffer = ByteBuffer
    .allocateDirect(size * Float.SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .asFloatBuffer()
    .put(this)
    .position(0) as FloatBuffer

internal fun IntArray.toIntBuffer(): IntBuffer = ByteBuffer
    .allocateDirect(size * Int.SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .asIntBuffer()
    .put(this)
    .position(0) as IntBuffer

internal fun MutableList<BufferElement>.addElement(
    name: String,
    type: ShaderDataType,
    normalized: Boolean = false
) {
    val element = BufferElement(
        name = name,
        type = type,
        sizeBytes = type.sizeBytes,
        normalized = normalized
    )
    add(element)
}

// @formatter:off
internal val ShaderDataType.sizeBytes: Int
    get() = when (this) {
        ShaderDataType.Float  -> Float.SIZE_BYTES * 1
        ShaderDataType.Float2 -> Float.SIZE_BYTES * 2
        ShaderDataType.Float3 -> Float.SIZE_BYTES * 3
        ShaderDataType.Float4 -> Float.SIZE_BYTES * 4
        ShaderDataType.Mat3   -> Float.SIZE_BYTES * 3 * 3
        ShaderDataType.Mat4   -> Float.SIZE_BYTES * 4 * 4
        ShaderDataType.Int    -> Int.SIZE_BYTES   * 1
        ShaderDataType.Int2   -> Int.SIZE_BYTES   * 2
        ShaderDataType.Int3   -> Int.SIZE_BYTES   * 3
        ShaderDataType.Int4   -> Int.SIZE_BYTES   * 4
        ShaderDataType.Bool   -> 1
        ShaderDataType.None   -> 0
    }
// @formatter:on