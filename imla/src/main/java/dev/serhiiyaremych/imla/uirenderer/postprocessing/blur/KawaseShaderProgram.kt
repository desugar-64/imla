/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing.blur

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.romainguy.kotlin.math.Float2
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import kotlin.properties.Delegates

internal class KawaseShaderProgram(
    assetManager: AssetManager
) {
    val downShader: Shader = Shader.create(
        assetManager = assetManager,
        vertexAsset = "shader/simple_quad.vert",
        fragmentAsset = "shader/kawase_blur_down.frag"
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
        fragmentAsset = "shader/kawase_blur_up.frag"
    ).apply {
        bind()
        setInt("u_Texture", 0)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    private var down: Boolean = true

    private var offset: Float by Delegates.observable(0f) { _, old, new ->
        if (old != new && new > 0f) {
            val shader = if (down) downShader else upShader
            shader.setFloat("u_Offset", new)
        }
    }

    private var halfPixel: Size by Delegates.observable(Size.Zero) { _, old, new ->
        if (old != new && new != Size.Zero) {
            val shader = if (down) downShader else upShader
            shader.setFloat2("u_Halfpixel", Float2(new.width, new.height))
        }
    }

    fun setOffset(offset: Float, down: Boolean) {
        this.down = down
        this.offset = offset
    }

    fun setHalfPixel(halfPixel: Size, down: Boolean) {
        this.down = down
        this.halfPixel = halfPixel
    }

    fun destroy() {
        downShader.destroy()
        upShader.destroy()
    }
}