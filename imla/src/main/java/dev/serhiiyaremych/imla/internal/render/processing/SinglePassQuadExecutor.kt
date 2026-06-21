/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing

import androidx.compose.ui.graphics.Color
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.shader.Shader

/**
 * Executes one single-pass quad draw into the currently configured render target.
 *
 * The caller owns destination FBO binding, viewport/scissor setup, and any state it needs restored.
 * The provided shader is bound through the underlying renderer's ShaderBinder. If no shader is
 * supplied, the underlying default texture shader is used. Non-primary texture uniforms and
 * bindings must already be configured by the pass. When a primary texture is supplied, its
 * origin drives the inherited flip value unless [flipY] is explicit. This executor does not
 * enable blending, change stencil state, or batch multiple draws.
 */
internal class SinglePassQuadExecutor(
    private val drawSink: SinglePassQuadDrawSink
) {
    constructor(simpleQuadRenderer: SimpleQuadRenderer) : this(
        SimpleRendererSinglePassQuadDrawSink(simpleQuadRenderer)
    )

    fun draw(
        shader: Shader,
        texture: Texture2D? = null,
        textureCoordinatesFlat: FloatArray? = null,
        alpha: Float = 1.0f,
        flipY: Boolean? = null,
        tint: Color = Color.Transparent
    ) {
        drawSink.draw(
            shader = shader,
            texture = texture,
            textureCoordinatesFlat = textureCoordinatesFlat,
            alpha = alpha,
            flipY = flipY,
            tint = tint
        )
    }

    fun draw(
        texture: Texture2D,
        textureCoordinatesFlat: FloatArray? = null,
        alpha: Float = 1.0f,
        flipY: Boolean? = null,
        tint: Color = Color.Transparent
    ) {
        drawSink.draw(
            texture = texture,
            textureCoordinatesFlat = textureCoordinatesFlat,
            alpha = alpha,
            flipY = flipY,
            tint = tint
        )
    }
}

internal interface SinglePassQuadDrawSink {
    fun draw(
        shader: Shader,
        texture: Texture2D?,
        textureCoordinatesFlat: FloatArray?,
        alpha: Float,
        flipY: Boolean?,
        tint: Color
    )

    fun draw(
        texture: Texture2D,
        textureCoordinatesFlat: FloatArray?,
        alpha: Float,
        flipY: Boolean?,
        tint: Color
    )
}

private class SimpleRendererSinglePassQuadDrawSink(
    private val simpleQuadRenderer: SimpleQuadRenderer
) : SinglePassQuadDrawSink {
    override fun draw(
        shader: Shader,
        texture: Texture2D?,
        textureCoordinatesFlat: FloatArray?,
        alpha: Float,
        flipY: Boolean?,
        tint: Color
    ) {
        simpleQuadRenderer.draw(
            shader = shader,
            texture = texture,
            textureCoordinatesFlat = textureCoordinatesFlat,
            alpha = alpha,
            flipY = flipY,
            tint = tint
        )
    }

    override fun draw(
        texture: Texture2D,
        textureCoordinatesFlat: FloatArray?,
        alpha: Float,
        flipY: Boolean?,
        tint: Color
    ) {
        simpleQuadRenderer.draw(
            texture = texture,
            textureCoordinatesFlat = textureCoordinatesFlat,
            alpha = alpha,
            flipY = flipY,
            tint = tint
        )
    }
}
