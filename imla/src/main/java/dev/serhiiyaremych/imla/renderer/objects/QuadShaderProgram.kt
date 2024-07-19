/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.objects

import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.BufferLayout
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.ShaderDataType
import dev.serhiiyaremych.imla.renderer.ShaderProgram
import dev.serhiiyaremych.imla.renderer.addElement
import dev.serhiiyaremych.imla.renderer.primitive.QuadVertex

internal val defaultQuadBufferLayout = BufferLayout {
    addElement("a_TexCoord", ShaderDataType.Float2)
    addElement("a_Position", ShaderDataType.Float4)
    addElement("a_TexIndex", ShaderDataType.Float)
    addElement("a_FlipTexture", ShaderDataType.Float)
    addElement("a_IsExternalTexture", ShaderDataType.Float)
    addElement("a_Alpha", ShaderDataType.Float)
    addElement("a_Mask", ShaderDataType.Float)
    addElement("a_MaskCoord", ShaderDataType.Float2)
}

internal fun defaultQuadVertexMapper(
    quadVertexBufferBase: List<QuadVertex>
): FloatArray = trace("defaultQuadVertexMapper") {
    // TODO: Use Bytebuffer to ensure vertex data is aligned
    val verticesData =
        FloatArray(quadVertexBufferBase.count() * QuadVertex.NUMBER_OF_COMPONENTS)

    var lastVertexIndex = 0
    for (quad in quadVertexBufferBase) {
        // a_TexCoord
        verticesData[lastVertexIndex + 0] = quad.texCoord.x
        verticesData[lastVertexIndex + 1] = quad.texCoord.y
        // a_Position
        verticesData[lastVertexIndex + 2] = quad.position.x
        verticesData[lastVertexIndex + 3] = quad.position.y
        verticesData[lastVertexIndex + 4] = quad.position.z
        verticesData[lastVertexIndex + 5] = quad.position.w
        // a_TexIndex
        verticesData[lastVertexIndex + 6] = quad.texIndex
        // a_FlipTexture
        verticesData[lastVertexIndex + 7] = quad.flipTexture
        // a_IsExternalTexture
        verticesData[lastVertexIndex + 8] = quad.isExternalTexture
        // a_Alpha
        verticesData[lastVertexIndex + 9] = quad.alpha
        // a_Mask
        verticesData[lastVertexIndex + 10] = quad.mask
        // a_MaskCoord
        verticesData[lastVertexIndex + 11] = quad.maskCoord.x
        verticesData[lastVertexIndex + 12] = quad.maskCoord.y

        lastVertexIndex += QuadVertex.NUMBER_OF_COMPONENTS
    }
    return verticesData
}


internal class QuadShaderProgram(override val shader: Shader) : ShaderProgram {
    override val vertexBufferLayout: BufferLayout = defaultQuadBufferLayout
    override val componentsCount: Int = vertexBufferLayout.elements.sumOf { it.type.components }

    override fun mapVertexData(quadVertexBufferBase: List<QuadVertex>): FloatArray {
        return defaultQuadVertexMapper(quadVertexBufferBase)
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