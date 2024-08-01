/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer

import androidx.tracing.trace


internal class SimpleRenderer {
    internal val data: StaticRendererData
        get() = _data ?: error("StaticRenderer not initialised!")
    private var _data: StaticRendererData? = null

    fun init() {
        // vec2 uv[4];         // x, y
        // vec2 size;          // width, height
        // float flipTexture;  //
        // float alpha;        //
        val texDataUboElements = 20
        val textureDataUBO = UniformBuffer.create(
            count = texDataUboElements,
            bindingPoint = TEXTURE_DATA_UBO_BINDING_POINT
        )
        val rendererData = StaticRendererData(
            textureDataUBO = textureDataUBO,
            vao = VertexArray.create(),
        )
        rendererData.vao.bind()
        rendererData.vao.indexBuffer = allocateIndexBuffer(indices = 6)
        rendererData.vao.addVertexBuffer(allocateVertexBuffer())
        _data = rendererData
    }

    private fun allocateVertexBuffer(): VertexBuffer {


        return VertexBuffer.create(
            // @formatter:off
            floatArrayOf(
                -1.0f, -1.0f,  // bottom left
                 1.0f, -1.0f,  // bottom right
                 1.0f,  1.0f,  // top right
                -1.0f,  1.0f,  // top left
            )
            // @formatter:on
        ).apply {
            layout = BufferLayout {
                addElement("aPosition", ShaderDataType.Float2)
            }
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
        RenderCommand.drawIndexed(data.vao, 6)
    }

    companion object {
        const val TEXTURE_DATA_UBO_BLOCK = "TextureDataUBO"
        const val TEXTURE_DATA_UBO_BINDING_POINT = 0
    }
}

internal class StaticRendererData(
    val textureDataUBO: UniformBuffer,
    val vao: VertexArray,
)