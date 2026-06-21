/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import android.graphics.PorterDuff
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.trace
import androidx.graphics.opengl.GLRenderer
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import kotlin.math.abs

internal class MaskTextureRenderer(
    density: Density,
    shaderLibrary: ShaderLibrary,
    shaderBinder: ShaderBinder,
    simpleQuadRenderer: SimpleQuadRenderer,
    private val onRenderComplete: (GraphicsLayerTextureFrame?) -> Unit,
    glRenderer: GLRenderer,
    commandsProvider: () -> RenderCommands = { RenderCommand.commands }
) : Density by density {
    private val drawScope = CanvasDrawScope()
    private val graphicsLayerTexture = createGraphicsLayerTexture(
        glRenderer = glRenderer,
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder,
        simpleQuadRenderer = simpleQuadRenderer,
        fboFormat = FramebufferTextureFormat.R8,
        textureOrigin = CoordinateOrigin.TOP_LEFT,
        commandsProvider = commandsProvider
    )

    private var lastRenderedBrush: Brush? = null
    private var lastRenderedShape: Shape? = null
    private var currentSizePx: IntSize = IntSize.Zero
    private var lastRenderedFrame: GraphicsLayerTextureFrame? = null

    private val reusablePath = Path()

    private fun shouldRedraw(brush: Brush?, shape: Shape?): Boolean {
        return lastRenderedBrush != brush || lastRenderedShape != shape
    }

    private fun drawShapeMask(sizePx: IntSize, shape: Shape, canvas: android.graphics.Canvas) {
        canvas.drawColor(Color.Transparent.toArgb(), PorterDuff.Mode.CLEAR)
        drawScope.draw(
            density = this,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(canvas),
            size = sizePx.toSize()
        ) {
            reusablePath.reset()
            val outline = shape.createOutline(sizePx.toSize(), LayoutDirection.Ltr, this)
            reusablePath.addOutline(outline)
            clipPath(reusablePath) {
                drawRect(Color.White)
            }
        }
    }

    private fun drawBrushMask(sizePx: IntSize, brush: Brush, canvas: android.graphics.Canvas) {
        canvas.drawColor(Color.Transparent.toArgb(), PorterDuff.Mode.CLEAR)
        drawScope.draw(
            density = this,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(canvas),
            size = sizePx.toSize()
        ) {
            drawRect(brush)
        }
    }

    private fun drawCombinedMask(sizePx: IntSize, brush: Brush, shape: Shape, canvas: android.graphics.Canvas) {
        canvas.drawColor(Color.Transparent.toArgb(), PorterDuff.Mode.CLEAR)
        drawScope.draw(
            density = this,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(canvas),
            size = sizePx.toSize()
        ) {
            reusablePath.reset()
            val outline = shape.createOutline(sizePx.toSize(), LayoutDirection.Ltr, this)
            reusablePath.addOutline(outline)
            clipPath(reusablePath) {
                drawRect(brush)
            }
        }
    }

    fun renderMask(brush: Brush?, shape: Shape = RectangleShape, size: IntSize) =
        trace("MaskTextureRenderer#renderMask") {
            if (size == IntSize.Zero) return@trace
            val redraw = hasMeaningfulSizeChange(size) || shouldRedraw(brush, shape)
            if (!redraw) {
                lastRenderedFrame?.let(onRenderComplete)
                return@trace
            }

            val frame = graphicsLayerTexture.captureCanvas(
                sizePx = size,
                textureOrigin = CoordinateOrigin.TOP_LEFT
            ) { canvas ->
                when {
                    brush != null && shape != RectangleShape -> drawCombinedMask(size, brush, shape, canvas)
                    brush != null -> drawBrushMask(size, brush, canvas)
                    shape != RectangleShape -> drawShapeMask(size, shape, canvas)
                    else -> canvas.drawColor(Color.Transparent.toArgb(), PorterDuff.Mode.CLEAR)
                }
            }

            lastRenderedBrush = brush
            lastRenderedShape = shape
            currentSizePx = size
            lastRenderedFrame = frame
            onRenderComplete(frame)
        }

    fun destroy() {
        graphicsLayerTexture.release()
        lastRenderedBrush = null
        lastRenderedShape = null
        currentSizePx = IntSize.Zero
        lastRenderedFrame = null
    }

    fun releaseCurrentMask() {
        destroy()
    }

    private fun hasMeaningfulSizeChange(newSizePx: IntSize): Boolean {
        if (currentSizePx == IntSize.Zero) return true
        return abs(currentSizePx.width - newSizePx.width) > 1 ||
            abs(currentSizePx.height - newSizePx.height) > 1
    }
}
