/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer

import android.content.res.AssetManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.trace
import androidx.graphics.opengl.GLRenderer
import dev.serhiiyaremych.imla.uirenderer.postprocessing.EffectCoordinator
import java.util.concurrent.ConcurrentHashMap

internal class RenderingPipeline(private val density: Density, assetManager: AssetManager) {
    private val masks: MutableMap<String, MaskTextureRenderer> = ConcurrentHashMap()
    private val renderObjects: MutableMap<String, RenderObject> = ConcurrentHashMap()
    private val effectCoordinator = EffectCoordinator(density, assetManager)

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
        trace("RenderingPipeline#renderObject") {
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
        renderObjects.forEach { (_, renderObject) ->
            renderObject.invalidate {
                if (--remainingRenders == 0) {
                    onRenderComplete()
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