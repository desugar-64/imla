/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render

import androidx.tracing.trace


internal class SimpleRenderer {
    internal val data: StaticRendererData
        get() = _data ?: error("StaticRenderer not initialised!")
    private var _data: StaticRendererData? = null
    private var commands: RenderCommands = RenderCommand.commands

    // Vertex data: 4 vertices × 3 floats each (x, y, tintPacked)
    private val vertexData = floatArrayOf(
        // @formatter:off
        // x,     y,      tintPacked
        -1.0f, -1.0f,     0f,  // bottom left
         1.0f, -1.0f,     0f,  // bottom right
         1.0f,  1.0f,     0f,  // top right
        -1.0f,  1.0f,     0f,  // top left
        // @formatter:on
    )
    private var currentTintPacked: Float = 0f

    fun init(commands: RenderCommands = RenderCommand.commands) {
        this.commands = commands
        // vec2 uv[4];         // x, y
        // vec2 size;          // width, height
        // float flipTexture;  //
        // float alpha;        //
        val texDataUboElements = 20
        val textureDataUBO = UniformBuffer.create(
            count = texDataUboElements,
            bindingPoint = TEXTURE_DATA_UBO_BINDING_POINT
        )
        val vbo = allocateVertexBuffer()
        val rendererData = StaticRendererData(
            textureDataUBO = textureDataUBO,
            vao = VertexArray.create(),
            vbo = vbo
        )
        rendererData.vao.bind()
        rendererData.vao.indexBuffer = allocateIndexBuffer(indices = 6)
        rendererData.vao.addVertexBuffer(vbo)
        _data = rendererData
    }

    fun updateTint(packedTint: Float) {
        if (currentTintPacked == packedTint) return
        currentTintPacked = packedTint
        // Update tint for all 4 vertices (index 2, 5, 8, 11)
        vertexData[2] = packedTint
        vertexData[5] = packedTint
        vertexData[8] = packedTint
        vertexData[11] = packedTint
        data.vbo.setData(vertexData)
    }

    private fun allocateVertexBuffer(): VertexBuffer {
        // Create dynamic VBO for per-draw tint updates
        return VertexBuffer.create(count = VERTEX_DATA_SIZE).apply {
            layout = BufferLayout {
                addElement("aPosition", ShaderDataType.Float2)
                addElement("a_TintPacked", ShaderDataType.Float)
            }
            setData(vertexData)
        }
    }

    private fun allocateIndexBuffer(indices: Int = MAX_INDICES): IndexBuffer {
        val quadIndices = IntArray(indices)
        var offset = 0
        // simple quad indices
        for (i in quadIndices.indices step 6) {
            quadIndices[i + 0] = offset + 0
            quadIndices[i + 1] = offset + 1
            quadIndices[i + 2] = offset + 2

            quadIndices[i + 3] = offset + 2
            quadIndices[i + 4] = offset + 3
            quadIndices[i + 5] = offset + 0

            offset += 4
        }
        return IndexBuffer.create(quadIndices)
    }

    fun flush() = trace("SimpleRenderer#flush") {
        commands.drawIndexed(data.vao, 6)
    }

    fun shutdown() {
        val data = _data ?: return
        data.vao.indexBuffer?.destroy()
        data.vbo.destroy()
        data.vao.destroy()
        data.textureDataUBO.destroy()
        _data = null
    }

    companion object {
        const val TEXTURE_DATA_UBO_BLOCK = "TextureDataUBO"
        const val TEXTURE_DATA_UBO_BINDING_POINT = 0
        private const val VERTEX_DATA_SIZE = 12  // 4 vertices × 3 floats (x, y, tintPacked)
    }
}

internal class StaticRendererData(
    val textureDataUBO: UniformBuffer,
    val vao: VertexArray,
    val vbo: VertexBuffer
)
