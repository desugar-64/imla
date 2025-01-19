/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.mask

import dev.serhiiyaremych.imla.renderer.shader.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.renderer.shader.ShaderBinder
import dev.serhiiyaremych.imla.renderer.shader.ShaderLibrary

internal class MaskShaderProgram(
    shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder
) {
    val shader: Shader = shaderLibrary
        .loadShaderFromFile(vertFileName = "simple_quad", fragFileName = "simple_mask")
        .apply {
            bindUniformBlock(
                SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
                SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
            )
        }

    fun setMask(mask: Texture2D) {
        shader.bind(shaderBinder)
        mask.bind(2)
        shader.setInt("u_Mask", 2)
    }

    fun setBackground(background: Texture) {
        shader.bind(shaderBinder)
        background.bind(3)
        shader.setInt("u_Background", 3)
    }

    fun destroy() {
        shader.destroy()
    }
}