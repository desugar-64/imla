/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer.postprocessing.blur

import android.content.res.AssetManager
import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float4
import dev.serhiiyaremych.imla.renderer.BufferLayout
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.ShaderProgram
import dev.serhiiyaremych.imla.renderer.objects.defaultQuadBufferLayout
import dev.serhiiyaremych.imla.renderer.objects.defaultQuadVertexMapper
import dev.serhiiyaremych.imla.renderer.primitive.QuadVertex

internal class BlurShaderProgram(assetManager: AssetManager) : ShaderProgram {
    private val horizontalDirection = Float2(x = 1.0f)
    private val verticalDirection = Float2(y = 1.0f)

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

    // horiz=(1.0, 0.0), vert=(0.0, 1.0)
    fun setHorizontalPass() {
        shader.bind()
        shader.setFloat2("u_BlurDirection", horizontalDirection)
    }

    fun setVerticalPass() {
        shader.bind()
        shader.setFloat2("u_BlurDirection", verticalDirection)
    }

    override fun mapVertexData(quadVertexBufferBase: List<QuadVertex>): FloatArray {
        return defaultQuadVertexMapper(quadVertexBufferBase)
    }

    fun setBlurringTextureSize(size: IntSize) {
        shader.bind()
        shader.setFloat2("u_TexelSize", Float2(size.width.toFloat(), size.height.toFloat()))
    }

    fun setTintColor(tint: Color) {
        shader.bind()
        shader.setFloat4("u_BlurTint", Float4(tint.red, tint.green, tint.blue, tint.alpha))
    }

    override fun destroy() {
        shader.destroy()
    }
}