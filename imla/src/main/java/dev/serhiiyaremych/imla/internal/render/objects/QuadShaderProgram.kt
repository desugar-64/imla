/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.objects

import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.BufferLayout
import dev.serhiiyaremych.imla.internal.render.DEFAULT_TEXTURE_SLOTS
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import dev.serhiiyaremych.imla.internal.render.ShaderDataType
import dev.serhiiyaremych.imla.internal.render.shader.ShaderProgram
import dev.serhiiyaremych.imla.internal.render.addElement
import dev.serhiiyaremych.imla.internal.render.primitive.QuadVertex
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder

internal val defaultQuadBufferLayout = BufferLayout {
    addElement("a_TexCoord", ShaderDataType.Float2)
    addElement("a_Position", ShaderDataType.Float4)
    addElement("a_TexIndex", ShaderDataType.Float)
    addElement("a_Flags", ShaderDataType.Float)
    addElement("a_Alpha", ShaderDataType.Float)
    addElement("a_MaskCoord", ShaderDataType.Float2)
    addElement("a_TintPacked", ShaderDataType.Float)
}

internal fun defaultQuadVertexMapper(
    quadVertexBufferBase: List<QuadVertex>,
    buffer: FloatArray
): Int = trace("defaultQuadVertexMapper") {
    var lastVertexIndex = 0
    for (quad in quadVertexBufferBase) {
        // a_TexCoord
        buffer[lastVertexIndex + 0] = quad.texCoord.x
        buffer[lastVertexIndex + 1] = quad.texCoord.y
        // a_Position
        buffer[lastVertexIndex + 2] = quad.position.x
        buffer[lastVertexIndex + 3] = quad.position.y
        buffer[lastVertexIndex + 4] = quad.position.z
        buffer[lastVertexIndex + 5] = quad.position.w
        // a_TexIndex
        buffer[lastVertexIndex + 6] = quad.texIndex
        // a_Flags
        buffer[lastVertexIndex + 7] = quad.flags
        // a_Alpha
        buffer[lastVertexIndex + 8] = quad.alpha
        // a_MaskCoord
        buffer[lastVertexIndex + 9] = quad.maskCoord.x
        buffer[lastVertexIndex + 10] = quad.maskCoord.y
        // a_TintPacked
        buffer[lastVertexIndex + 11] = quad.tintPacked

        lastVertexIndex += QuadVertex.NUMBER_OF_COMPONENTS
    }
    return lastVertexIndex
}


internal class QuadShaderProgram(
    shaderBinder: ShaderBinder,
    override val shader: Shader,
    textureSlots: Int = DEFAULT_TEXTURE_SLOTS
) : ShaderProgram {
    override val vertexBufferLayout: BufferLayout = defaultQuadBufferLayout
    override val componentsCount: Int = vertexBufferLayout.elements.sumOf { it.type.components }

    init {
        shader.bind(shaderBinder)
        val samplers = IntArray(textureSlots) { index -> index }
        shader.setIntArray("u_Textures", samplers)
    }

    override fun mapVertexData(quadVertexBufferBase: List<QuadVertex>, buffer: FloatArray): Int {
        return defaultQuadVertexMapper(quadVertexBufferBase, buffer)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as QuadShaderProgram

        return shader == other.shader
    }

    override fun hashCode(): Int {
        return shader.hashCode()
    }

    override fun destroy() {
        shader.destroy()
    }
}
