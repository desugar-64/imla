/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer.postprocessing

import android.content.res.AssetManager
import androidx.annotation.FloatRange
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Float2
import dev.serhiiyaremych.imla.renderer.BufferLayout
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.ShaderProgram
import dev.serhiiyaremych.imla.renderer.objects.defaultQuadBufferLayout
import dev.serhiiyaremych.imla.renderer.objects.defaultQuadVertexMapper
import dev.serhiiyaremych.imla.renderer.primitive.QuadVertex

internal class BlurShaderProgram(assetManager: AssetManager) : ShaderProgram {
    override val shader: Shader = Shader.create(
        assetManager = assetManager,
        vertexAsset = "shader/default_quad.vert",
        fragmentAsset = "shader/blur_quad.frag",
    )
    override val vertexBufferLayout: BufferLayout = defaultQuadBufferLayout
    override val componentsCount: Int = vertexBufferLayout.elements.sumOf { it.type.components }

    fun setBlurRadius(@FloatRange(from = 1.0, to = 72.0) radius: Float) {
        shader.bind()
        val clampedRadius = radius.coerceIn(1f, 72f)
        shader.setFloat("u_BlurSigma", clampedRadius)
    }

    fun setHorizontalPass() {
        shader.bind()
        shader.setFloat("u_BlurDirection", 0f)
    }

    fun setVerticalPass() {
        shader.bind()
        shader.setFloat("u_BlurDirection", 1f)
    }

    override fun mapVertexData(quadVertexBufferBase: List<QuadVertex>): FloatArray {
        return defaultQuadVertexMapper(quadVertexBufferBase)
    }

    fun setBlurringTextureSize(size: IntSize) {
        shader.bind()
        shader.setFloat2("u_TexelSize", Float2(size.width.toFloat(), size.height.toFloat()))
    }
}