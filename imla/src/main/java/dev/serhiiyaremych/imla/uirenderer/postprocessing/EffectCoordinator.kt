/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing

import android.content.res.AssetManager
import androidx.compose.ui.unit.Density
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
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
        val effectSize = renderObject.lowResLayer.subTextureSize

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

        mask.applyEffect(
            backgroundFramebuffer = renderObject.highResFBO,
            backgroundRect = renderObject.highResRect,
            blur = noise.applyEffect(
                texture = blur.applyEffect(
                    texture = renderObject.lowResLayer,
                    blurRadius = renderObject.style.blurRadiusPx(),
                    tint = renderObject.style.tint
                ),
                noiseAlpha = renderObject.style.noiseAlpha
            ),
            mask = maskTexture
        )

        RenderCommand.setViewPort(0, 0, size.x.toInt(), size.y.toInt())
        val finalFb = output(blur, noise, mask)
        if (finalFb != null) {
            trace("blitFinalToScreen") {
                finalFb.bind(Bind.READ)
                RenderCommand.bindDefaultFramebuffer(Bind.DRAW)
                finalFb.readBuffer(0)
                RenderCommand.blitFramebuffer(
                    srcX0 = 0,
                    srcY0 = 0,
                    srcX1 = size.x.toInt(),
                    srcY1 = size.y.toInt(),
                    dstX0 = 0,
                    dstY0 = 0,
                    dstX1 = size.x.toInt(),
                    dstY1 = size.y.toInt(),
                    mask = RenderCommand.colorBufferBit,
                    filter = RenderCommand.linearTextureFilter
                )
                RenderCommand.bindDefaultFramebuffer(Bind.BOTH)
            }
        } else {
            RenderCommand.setViewPort(0, 0, size.x.toInt(), size.y.toInt())
            drawScene(cameraController.camera) {
                drawQuad(
                    position = center,
                    size = size,
                    texture = renderObject.lowResLayer
                )
            }
        }
    }

    private fun output(blur: BlurEffect, noise: NoiseEffect, mask: MaskEffect): Framebuffer? {
        return when {
            mask.isEnabled() -> mask.outputFramebuffer
            noise.isEnabled() -> noise.outputFramebuffer
            blur.isEnabled() -> blur.outputFramebuffer
            else -> null
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