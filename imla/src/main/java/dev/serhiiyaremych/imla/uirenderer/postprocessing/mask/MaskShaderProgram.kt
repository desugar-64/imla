/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing.mask

import android.content.res.AssetManager
import dev.serhiiyaremych.imla.renderer.BufferLayout
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.ShaderProgram
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.renderer.objects.defaultQuadBufferLayout
import dev.serhiiyaremych.imla.renderer.objects.defaultQuadVertexMapper
import dev.serhiiyaremych.imla.renderer.primitive.QuadVertex

internal class MaskShaderProgram(assetManager: AssetManager) : ShaderProgram {
    override val shader: Shader = Shader.create(
        assetManager = assetManager,
        vertexAsset = "shader/default_quad.vert",
        fragmentAsset = "shader/mask.frag"
    )

    override val vertexBufferLayout: BufferLayout = defaultQuadBufferLayout
    override val componentsCount: Int = vertexBufferLayout.elements.sumOf { it.type.components }

    override fun mapVertexData(quadVertexBufferBase: List<QuadVertex>): FloatArray {
        return defaultQuadVertexMapper(quadVertexBufferBase)
    }

    fun setMask(mask: Texture2D) {
        shader.bind()
        mask.bind(2)
        shader.setInt("u_Mask", 2)
    }

    fun setBackground(background: Texture) {
        shader.bind()
        background.bind(3)
        shader.setInt("u_Background", 3)
    }
}