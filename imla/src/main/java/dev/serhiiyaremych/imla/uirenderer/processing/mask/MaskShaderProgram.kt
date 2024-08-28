/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.mask

import android.content.res.AssetManager
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D

internal class MaskShaderProgram(assetManager: AssetManager) {
    val shader: Shader = Shader.create(
        assetManager = assetManager,
        vertexAsset = "shader/simple_quad.vert",
        fragmentAsset = "shader/simple_mask.frag"
    ).apply {
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
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

    fun destroy() {
        shader.destroy()
    }
}