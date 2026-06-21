package dev.serhiiyaremych.imla.internal.render.gl

import dev.serhiiyaremych.imla.internal.render.BufferLayout
import dev.serhiiyaremych.imla.internal.render.objects.defaultQuadBufferLayout
import dev.serhiiyaremych.imla.internal.render.objects.defaultQuadVertexMapper
import dev.serhiiyaremych.imla.internal.render.primitive.QuadVertex
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderProgram

internal class SceneBackdropBlurShaderProgram(
    shaderBinder: ShaderBinder,
    override val shader: Shader
) : ShaderProgram {
    override val vertexBufferLayout: BufferLayout = defaultQuadBufferLayout
    override val componentsCount: Int = vertexBufferLayout.elements.sumOf { it.type.components }

    init {
        shader.bind(shaderBinder)
        shader.setInt("u_SourceTexture", 1)
        shader.setInt("u_MaskTexture", 0)
    }

    override fun mapVertexData(quadVertexBufferBase: List<QuadVertex>, buffer: FloatArray): Int {
        return defaultQuadVertexMapper(quadVertexBufferBase, buffer)
    }

    override fun destroy() {
        shader.destroy()
    }
}
