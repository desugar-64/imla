/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing

import android.content.res.AssetManager
import androidx.compose.ui.unit.Density
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.uirenderer.RenderObject
import dev.serhiiyaremych.imla.uirenderer.postprocessing.blur.BlurEffect
import dev.serhiiyaremych.imla.uirenderer.postprocessing.mask.MaskEffect
import dev.serhiiyaremych.imla.uirenderer.postprocessing.noise.NoiseEffect

internal class EffectCoordinator(
    density: Density,
    private val assetManager: AssetManager
) : Density by density {

    private val effectCache: MutableMap<String, MutableList<PostProcessingEffect>> = mutableMapOf()

    private fun createEffects(renderObject: RenderObject): MutableList<PostProcessingEffect> {
        val effectSize = renderObject.scaledLayer.subTextureSize

        return mutableListOf<PostProcessingEffect>().apply {
            val blurEffect = BlurEffect(assetManager)
            blurEffect.setup(effectSize)
            add(blurEffect)
            add(NoiseEffect(assetManager))
//            add(MaskEffect(assetManager))
        }
    }

    fun applyEffects(renderObject: RenderObject) = with(renderObject.renderableScope) {
        val effects = effectCache.getOrPut(renderObject.id) {
            createEffects(renderObject)
        }
        var finalTexture: Texture? = null
        RenderCommand.setViewPort(0, 0, scaledSize.x.toInt(), scaledSize.y.toInt())

        val maskTexture = renderObject.mask

        effects.forEach { effect ->
            if (effect is BlurEffect) {
                effect.bluerRadius = renderObject.style.blurRadiusPx()
                effect.tint = renderObject.style.tint
            }
            if (effect is NoiseEffect) {
                effect.noiseAlpha = renderObject.style.noiseAlpha
            }
            if (effect is MaskEffect) {
                effect.maskTexture = maskTexture
            }
            val result = when (effect) {
                is MaskEffect -> {
                    effect.applyEffect(renderObject.originalLayer)
                }

                else -> {
                    effect.applyEffect(finalTexture ?: renderObject.scaledLayer)
                }
            }
            finalTexture = result
        }
        RenderCommand.setViewPort(0, 0, size.x.toInt(), size.y.toInt()) // screen effect size
        val result = finalTexture
        if (result != null) {
            drawScene(cameraController.camera) {
                drawQuad(
                    position = center,
                    size = size,
                    texture = result
                )
            }
        } else {
            drawScene(cameraController.camera) {
                drawQuad(position = center, size = size, subTexture = renderObject.originalLayer)
            }
        }
    }

    fun removeEffectsOf(id: String?) {
        effectCache.remove(id)?.forEach { it.dispose() }
    }

    fun destroy() {
        effectCache.forEach { (_, effects) ->
            effects.forEach { fx -> fx.dispose() }
            effects.clear()
        }
        effectCache.clear()
    }

}