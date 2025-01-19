/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer

import android.view.View
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.trace
import androidx.graphics.opengl.GLRenderer
import androidx.tracing.Trace
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Renderer2D
import dev.serhiiyaremych.imla.renderer.shader.ShaderBinder
import dev.serhiiyaremych.imla.renderer.shader.ShaderLibrary
import dev.serhiiyaremych.imla.renderer.stats.ShaderStats
import dev.serhiiyaremych.imla.uirenderer.processing.EffectCoordinator
import dev.serhiiyaremych.imla.uirenderer.processing.SimpleQuadRenderer
import java.util.concurrent.ConcurrentHashMap

internal class RenderingPipeline(
    rootLayer: RenderableRootLayer,
    private val simpleRenderer: SimpleQuadRenderer,
    private val shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder,
    private val renderer2D: Renderer2D,
    private val density: Density
) {
    private val masks: MutableMap<String, MaskTextureRenderer> = ConcurrentHashMap()
    private val renderObjects: MutableMap<String, RenderObject> = ConcurrentHashMap()
    private val effectCoordinator = EffectCoordinator(
        density = density,
        rootLayer = rootLayer,
        simpleQuadRenderer = simpleRenderer,
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder
    )

    fun getRenderObject(id: String?): RenderObject? {
        return id?.let { renderObjects[it] }
    }

    fun addRenderObject(renderObject: RenderObject) {
        renderObjects[renderObject.id] = renderObject.apply { setRenderCallback(renderCallback) }
    }

    fun updateMask(glRenderer: GLRenderer, renderObjectId: String?, mask: Brush?) {
        val renderObject = renderObjectId?.let { renderObjects[it] }
        if (renderObject != null) {
            val maskRenderer = masks.getOrPut(renderObject.id) {
                MaskTextureRenderer(
                    density = density,
                    renderer2D = renderer2D,
                    shaderLibrary = shaderLibrary,
                    shaderBinder = shaderBinder,
                    simpleQuadRenderer = simpleRenderer,
                    onRenderComplete = { tex ->
                        renderObject.mask = tex
                        renderObject.invalidate()
                    }
                )
            }
            if (mask != null) {
                maskRenderer.renderMask(
                    glRenderer = glRenderer,
                    brush = mask,
                    size = IntSize(
                        width = renderObject.renderableScope.size.x.toInt(),
                        height = renderObject.renderableScope.size.y.toInt()
                    )
                )
            } else {
                maskRenderer.releaseCurrentMask()
                renderObject.mask = null
                renderObject.invalidate()
            }
        }
    }

    private val renderCallback = fun(renderObject: RenderObject) {
        trace("RenderingPipeline#applyAllEffects") {
            effectCoordinator.applyEffects(renderObject)
        }
    }

    fun requestRender(onRenderComplete: () -> Unit) {
        val renderObjectsCount = renderObjects.size
        if (renderObjectsCount == 0) {
            onRenderComplete()
            return
        }

        var remainingRenders = renderObjectsCount
        val id = View.generateViewId()
        Trace.beginAsyncSection("requestRender", id)
        renderObjects.forEach { (_, renderObject) ->
            renderObject.invalidate {
                if (--remainingRenders == 0) {
                    onRenderComplete()

                    RenderCommand.bindDefaultFramebuffer()
                    RenderCommand.useDefaultProgram()
                    RenderCommand.clear()
                    Trace.endAsyncSection("requestRender", id)
                    ShaderStats.printStats()
                    ShaderStats.reset()
                }
            }
        }
    }

    fun removeRenderObject(renderObjectId: String?) {
        val renderObject = renderObjects.remove(renderObjectId)
        renderObject?.setRenderCallback(null)
        effectCoordinator.removeEffectsOf(renderObjectId)
    }

    fun destroy() {
        renderObjects.forEach { (_, ro) ->
            ro.detachFromRenderer()
            ro.setRenderCallback(null)
            effectCoordinator.removeEffectsOf(ro.id)
        }
        masks.forEach { (_, mask) ->
            mask.destroy()
        }
        renderObjects.clear()
        masks.clear()
        effectCoordinator.destroy()
    }
}