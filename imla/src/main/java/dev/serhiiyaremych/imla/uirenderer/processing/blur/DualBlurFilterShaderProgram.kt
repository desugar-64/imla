/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.blur

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float4
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import kotlin.properties.Delegates

internal class DualBlurFilterShaderProgram(
    assetManager: AssetManager
) {
    val downShader: Shader = Shader.create(
        assetManager = assetManager,
        vertexAsset = "shader/simple_quad.vert",
        fragmentAsset = "shader/blur_down.frag"
    ).apply {
        bind()
        setInt("u_Texture", 0)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    val upShader: Shader = Shader.create(
        assetManager = assetManager,
        vertexAsset = "shader/simple_quad.vert",
        fragmentAsset = "shader/blur_up.frag"
    ).apply {
        bind()
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