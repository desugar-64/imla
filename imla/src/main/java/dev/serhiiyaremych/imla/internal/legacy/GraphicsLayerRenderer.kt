/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.opengl.GLRenderer
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer

/**
 * Renders GraphicsLayer into a CPU-facing buffer on the main thread and returns a GL-importable frame.
 */
internal class GraphicsLayerRenderer(
    private val density: Density,
    shaderLibrary: ShaderLibrary,
    shaderBinder: ShaderBinder,
    simpleQuadRenderer: SimpleQuadRenderer,
    private val onRenderComplete: (GraphicsLayerTextureFrame?) -> Unit,
    private val glRenderer: GLRenderer,
    commandsProvider: () -> RenderCommands = { RenderCommand.commands }
) : ContentCaptureDelegate {
    private val graphicsLayerTexture = createGraphicsLayerTexture(
        glRenderer = glRenderer,
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder,
        simpleQuadRenderer = simpleQuadRenderer,
        fboFormat = FramebufferTextureFormat.RGBA8,
        textureOrigin = CoordinateOrigin.TOP_LEFT,
        commandsProvider = commandsProvider
    )

    override fun renderGraphicsLayer(
        graphicsLayer: GraphicsLayer?,
        size: IntSize,
        contentOffset: Offset
    ) = trace("GraphicsLayerRenderer#renderGraphicsLayer") {
        if (graphicsLayer == null || size == IntSize.Zero) return@trace
        val frame = graphicsLayerTexture.captureGraphicsLayer(
            sizePx = size,
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            graphicsLayer = graphicsLayer,
            textureOrigin = CoordinateOrigin.TOP_LEFT,
            isCanvasFlippedY = true,
            contentOffset = contentOffset
        )
        onRenderComplete(frame)
    }

    override fun releaseCurrentLayer() {
        graphicsLayerTexture.release()
        onRenderComplete(null)
    }

    override fun destroy() {
        releaseCurrentLayer()
    }
}
