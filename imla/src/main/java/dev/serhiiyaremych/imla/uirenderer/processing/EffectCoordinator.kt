/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.uirenderer.RenderObject
import dev.serhiiyaremych.imla.uirenderer.RenderableRootLayer
import dev.serhiiyaremych.imla.uirenderer.processing.blend.PostBlendEffect
import dev.serhiiyaremych.imla.uirenderer.processing.blur.DualBlurEffect
import dev.serhiiyaremych.imla.uirenderer.processing.mask.MaskEffect
import dev.serhiiyaremych.imla.uirenderer.processing.noise.NoiseEffect
import dev.serhiiyaremych.imla.uirenderer.processing.preprocess.PreProcessFilter

internal class EffectCoordinator(
    density: Density,
    private val rootLayer: RenderableRootLayer,
    private val simpleQuadRenderer: SimpleQuadRenderer,
    private val assetManager: AssetManager
) : Density by density {

    private val effectCache: MutableMap<String, EffectsHolder> = mutableMapOf()

    private fun createEffects(): EffectsHolder {
        return EffectsHolder(
            preProcess = PreProcessFilter(assetManager, simpleQuadRenderer),
//            blurEffect = BlurEffect(assetManager, simpleQuadRenderer).apply { setup(effectSize) },
            blurEffect = DualBlurEffect(assetManager, simpleQuadRenderer),
            noiseEffect = NoiseEffect(assetManager, simpleQuadRenderer),
            maskEffect = MaskEffect(assetManager, simpleQuadRenderer),
            blendEffect = PostBlendEffect(simpleQuadRenderer)
        )
    }

    fun applyEffects(renderObject: RenderObject) = with(renderObject.renderableScope) {

        val effects = effectCache.getOrPut(renderObject.id) {
            createEffects()
        }

        val maskTexture = renderObject.mask
        val (prePrecess, blur, noise, mask, blendEffect) = effects

        mask.applyEffect(
            backgroundFramebuffer = rootLayer.highResFBO,
            backgroundCrop = renderObject.area,
            foreground = noise.applyEffect(
                texture = blur.applyEffect(
                    inputFbo = prePrecess.preProcess(rootLayer.highResFBO, renderObject.area),
                    offset = renderObject.style.offset,
                    passes = renderObject.style.passes,
                    tint = renderObject.style.tint
                ),
                noiseAlpha = renderObject.style.noiseAlpha
            ),
            foregroundCrop = prePrecess.contentCrop,
            mask = maskTexture
        )

        val finalFb = output(blur, noise, mask)
        if (finalFb != null) {
            trace("finalBlendLayers") {
                RenderCommand.bindDefaultFramebuffer(Bind.DRAW)
                RenderCommand.setViewPort(
                    x = 0, y = 0,
                    width = renderObject.area.width.toInt(),
                    height = renderObject.area.height.toInt()
                )
                RenderCommand.clear()
                blendEffect.blendToDefaultBuffer(
                    background = rootLayer.highResFBO,
                    cutBackgroundRegion = renderObject.area,
                    foreground = finalFb,
                    cutForegroundRegion = if (maskTexture != null) Rect(
                        offset = Offset.Zero,
                        size = finalFb.specification.size.toSize()
                    ) else prePrecess.contentCrop,
                    opacity = renderObject.style.blurOpacity,
                )
            }
        }
    }

    private fun output(
        blur: DualBlurEffect,
        noise: NoiseEffect,
        mask: MaskEffect
    ): Framebuffer? {
        return when {
            mask.isEnabled() -> mask.outputFramebuffer
            noise.isEnabled() -> noise.outputFramebuffer
            else -> blur.outputFramebuffer
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