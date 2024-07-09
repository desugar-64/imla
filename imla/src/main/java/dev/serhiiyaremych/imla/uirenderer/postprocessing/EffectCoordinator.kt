/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing

import android.content.res.AssetManager
import androidx.compose.ui.unit.Density
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.uirenderer.RenderObject
import dev.serhiiyaremych.imla.uirenderer.postprocessing.blur.BlurEffect
import dev.serhiiyaremych.imla.uirenderer.postprocessing.mask.MaskEffect
import dev.serhiiyaremych.imla.uirenderer.postprocessing.noise.NoiseEffect

internal class EffectCoordinator(
    density: Density,
    private val assetManager: AssetManager
) : Density by density {

    private val effectCache: MutableMap<String, EffectsHolder> = mutableMapOf()

    private fun createEffects(renderObject: RenderObject): EffectsHolder {
        val effectSize = renderObject.scaledLayer.subTextureSize

        return EffectsHolder(
            blurEffect = BlurEffect(assetManager).apply { setup(effectSize) },
            noiseEffect = NoiseEffect(assetManager),
            maskEffect = MaskEffect(assetManager)
        )
    }

    fun applyEffects(renderObject: RenderObject) = with(renderObject.renderableScope) {
        val effects = effectCache.getOrPut(renderObject.id) {
            createEffects(renderObject)
        }
        RenderCommand.setViewPort(0, 0, scaledSize.x.toInt(), scaledSize.y.toInt())

        val maskTexture = renderObject.mask
        val (blur, noise, mask) = effects

        val style = renderObject.style

        val blurredTexture = blur.applyEffect(
            texture = renderObject.scaledLayer,
            blurRadius = style.blurRadiusPx(),
            tint = style.tint
        )
        RenderCommand.enableBlending()
        val textureWithNoise = noise.applyEffect(blurredTexture, style.noiseAlpha)
        RenderCommand.disableBlending()

        val finalComposition =
            mask.applyEffect(renderObject.originalLayer, textureWithNoise, maskTexture)

        RenderCommand.setViewPort(0, 0, size.x.toInt(), size.y.toInt()) // screen effect size
        drawScene(cameraController.camera) {
            drawQuad(
                position = center,
                size = size,
                texture = finalComposition
            )
        }
    }

    fun removeEffectsOf(id: String?) {
        effectCache.remove(id)?.dispose()
    }

    fun destroy() {
        effectCache.forEach { (_, effects) ->
            effects.dispose()
        }
        effectCache.clear()
    }

}