/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer

import android.content.res.AssetManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.uirenderer.postprocessing.EffectCoordinator
import java.util.concurrent.ConcurrentHashMap

internal class RenderingPipeline(density: Density, assetManager: AssetManager) {
    private val renderObjects: MutableMap<String, RenderObject> = ConcurrentHashMap()
    private val effectCoordinator = EffectCoordinator(density, assetManager)

    fun getRenderObject(id: String): RenderObject? {
        return renderObjects[id]
    }

    fun addRenderObject(renderObject: RenderObject) {
        renderObjects[renderObject.id] = renderObject.apply { setRenderCallback(renderCallback) }
    }

    private val renderCallback = fun(renderObject: RenderObject) {
        trace("RenderingPipeline#renderObject") {
            effectCoordinator.applyEffects(renderObject)
        }
    }

    fun requestRender(onRenderComplete: () -> Unit) {
        if (renderObjects.isNotEmpty()) {
            var index = 0
            val lastIndex = renderObjects.size - 1
            renderObjects.forEach { (_, ro) ->
                ro.invalidate {
                    if (index >= lastIndex) onRenderComplete()
                    index += 1
                }

            }
        } else {
            onRenderComplete()
        }
    }

    fun removeRenderObject(renderObject: RenderObject?) {
        renderObject?.setRenderCallback(null)
        renderObjects.remove(renderObject?.id)
        effectCoordinator.removeEffectsOf(renderObject?.id)
    }
}