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
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import kotlin.properties.Delegates

internal class SimpleBlurShaderProgram(assetManager: AssetManager) {

    val shader: Shader = Shader.create(
        assetManager = assetManager,
        vertexAsset = "shader/simple_quad.vert",
        fragmentAsset = "shader/simple_blur.frag",
    ).apply {
        bind()
        setInt("u_Texture", 0)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    private var direction: Float2 by Delegates.observable(zeroDir) { _, old, new ->
        if (old != new && new != zeroDir) {
            shader.bind()
            shader.setFloat2("u_BlurDirection", new)
        }
    }

    private var blurRadiusPx: Float by Delegates.observable(0f) { _, old, new ->
        if (old != new && new > 0) {
            shader.bind()
            val clampedRadius = new.coerceIn(1f, 72f)
            shader.setFloat("u_BlurSigma", clampedRadius)
        }
    }

    private var blurTintColor: Color by Delegates.observable(Color.Unspecified) { _, old, new ->
        if (old != new && new != Color.Unspecified) {
            shader.bind()
            shader.setFloat4("u_BlurTint", Float4(new.red, new.green, new.blue, new.alpha))
        }
    }

    private var blurTexSize: IntSize by Delegates.observable(IntSize.Zero) { _, old, new ->
        if (old != new && new != IntSize.Zero) {
            shader.bind()
            shader.setFloat2("u_TexelSize", Float2(new.width.toFloat(), new.height.toFloat()))
        }
    }

    fun setBlurRadius(@FloatRange(from = 1.0, to = 72.0) radius: Float) {
        blurRadiusPx = radius
    }

    // horiz=(1.0, 0.0), vert=(0.0, 1.0)
    fun setHorizontalPass() {
        direction = horizontalDirection
    }

    fun setVerticalPass() {
        direction = verticalDirection
    }

    fun setTintColor(tint: Color) {
        blurTintColor = tint
    }

    fun setTexSize(size: IntSize) {
        blurTexSize = size
    }

    fun destroy() {
        shader.destroy()
    }

    private companion object {
        val zeroDir = Float2()
        val horizontalDirection = Float2(x = 1.0f)
        val verticalDirection = Float2(y = 1.0f)
    }
}