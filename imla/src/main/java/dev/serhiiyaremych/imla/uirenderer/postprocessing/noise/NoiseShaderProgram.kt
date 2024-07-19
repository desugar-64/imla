/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing.noise

import android.content.res.AssetManager
import dev.serhiiyaremych.imla.renderer.BufferLayout
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.ShaderProgram
import dev.serhiiyaremych.imla.renderer.objects.defaultQuadBufferLayout
import dev.serhiiyaremych.imla.renderer.objects.defaultQuadVertexMapper
import dev.serhiiyaremych.imla.renderer.primitive.QuadVertex

internal class NoiseShaderProgram(assetManager: AssetManager) : ShaderProgram {
    override val shader: Shader = Shader.create(
        assetManager = assetManager,
        vertexAsset = "shader/default_quad.vert",
        fragmentAsset = "shader/noise.frag",
    )
    override val vertexBufferLayout: BufferLayout = defaultQuadBufferLayout
    override val componentsCount: Int = vertexBufferLayout.elements.sumOf { it.type.components }

    override fun mapVertexData(quadVertexBufferBase: List<QuadVertex>) =
        defaultQuadVertexMapper(quadVertexBufferBase)

    override fun destroy() {
        shader.destroy()
    }
}