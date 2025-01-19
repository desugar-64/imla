/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.blur

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float4
import dev.serhiiyaremych.imla.renderer.shader.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import dev.serhiiyaremych.imla.renderer.shader.ShaderBinder
import dev.serhiiyaremych.imla.renderer.shader.ShaderLibrary
import kotlin.properties.Delegates

internal class DualBlurFilterShaderProgram(
    shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder
) {
    val downShader: Shader = shaderLibrary.loadShaderFromFile(
        vertFileName = "simple_quad",
        fragFileName = "blur_down"
    ).apply {
        bind(shaderBinder)
        setInt("u_Texture", 0)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    val upShader: Shader = shaderLibrary.loadShaderFromFile(
        vertFileName = "simple_quad",
        fragFileName = "blur_up"
    ).apply {
        bind(shaderBinder)
        setInt("u_Texture", 0)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    private var down: Boolean = true

    private var contentOffset: Offset by Delegates.observable(Offset.Zero) { _, old, new ->
        if (old != new && new != Offset.Zero) {
            val shader = if (down) downShader else upShader
            shader.setFloat2("u_ContentOffset", Float2(new.x, new.y))
        }
    }

    private var halfPixel: Size by Delegates.observable(Size.Zero) { _, old, new ->
        if (old != new && new != Size.Zero) {
            val shader = if (down) downShader else upShader
            shader.setFloat2("u_Texel", Float2(new.width, new.height))
        }
    }

    private var tintColor: Color by Delegates.observable(Color.Transparent) { _, old, new ->
        if (old != new) {
            upShader.setFloat4("u_Tint", Float4(new.red, new.green, new.blue, new.alpha))
        }
    }

    fun setContentOffset(offset: Offset, down: Boolean) {
        if (this.down != down) {
            this.contentOffset = Offset.Zero
        }
        this.down = down
        this.contentOffset = offset
    }

    fun setTexelSize(texel: Size, down: Boolean) {
        this.down = down
        this.halfPixel = texel
    }

    fun setTint(color: Color) {
        this.tintColor = color
    }


    fun destroy() {
        downShader.destroy()
        upShader.destroy()
    }
}