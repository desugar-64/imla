/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer

import androidx.compose.ui.util.trace
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.uirenderer.postprocessing.PostProcessingEffect

internal class RenderingPipeline() {
    private val renderObjects: MutableMap<String, RenderObject> = mutableMapOf()
    private val effects: MutableList<PostProcessingEffect> = mutableListOf()

    fun getRenderObject(id: String): RenderObject? {
        return renderObjects[id]
    }

    fun addRenderObject(renderObject: RenderObject) {
        renderObjects[renderObject.id] = renderObject.apply { onRender(renderCallback) }
    }

    private val renderCallback = fun RenderableScope.(renderObject: RenderObject) {
        trace("RenderingPipeline#renderObject") {
            RenderCommand.setViewPort(0, 0, scaledSize.x.toInt(), scaledSize.y.toInt())
            if (effects.isNotEmpty()) {
                for (fx in effects) {
                    val result = fx.applyEffect(renderObject)
                    RenderCommand.setViewPort(0, 0, size.x.toInt(), size.y.toInt())
                    drawScene(cameraController.camera) {
                        drawQuad(
                            position = center,
                            size = size,
                            texture = result
                        )
                    }
                }
            } else {
                drawScene(cameraController.camera) {
                    drawQuad(
                        position = center,
                        size = size,
                        subTexture = renderObject.layerArea
                    )
                }
            }
        }
    }

    fun addEffect(postProcessingEffect: PostProcessingEffect) {
        effects += postProcessingEffect
    }

    fun requestRender(onRenderComplete: () -> Unit) {
        if (renderObjects.isNotEmpty()) {
            var index = 0
            val lastIndex = renderObjects.size - 1
            renderObjects.forEach { (_, ro) ->
                ro.invalidate {
                    if (index > lastIndex) onRenderComplete()
                }
                index += 1
            }
        } else {
            onRenderComplete()
        }
    }

    fun removeRenderObject(renderObject: RenderObject?) {
        renderObject?.onRender(null)
        renderObjects.remove(renderObject?.id)
    }
}