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
import dev.serhiiyaremych.imla.uirenderer.postprocessing.blur.DualBlurEffect
import dev.serhiiyaremych.imla.uirenderer.postprocessing.mask.MaskEffect
import dev.serhiiyaremych.imla.uirenderer.postprocessing.noise.NoiseEffect

internal class EffectCoordinator(
    density: Density,
    private val simpleQuadRenderer: SimpleQuadRenderer,
    private val assetManager: AssetManager
) : Density by density {

    private val effectCache: MutableMap<String, EffectsHolder> = mutableMapOf()

    private fun createEffects(renderObject: RenderObject): EffectsHolder {
        val effectSize = renderObject.lowResLayer.subTextureSize

        return EffectsHolder(
//            blurEffect = BlurEffect(assetManager, simpleQuadRenderer).apply { setup(effectSize) },
            blurEffect = DualBlurEffect(assetManager, simpleQuadRenderer),
            noiseEffect = NoiseEffect(assetManager, simpleQuadRenderer),
            maskEffect = MaskEffect(assetManager, simpleQuadRenderer)
        )
    }

    fun applyEffects(renderObject: RenderObject) = with(renderObject.renderableScope) {
        val effects = effectCache.getOrPut(renderObject.id) {
            createEffects(renderObject)
        }
        RenderCommand.useDefaultProgram()
        RenderCommand.bindDefaultFramebuffer()
        RenderCommand.setViewPort(0, 0, scaledSize.x.toInt(), scaledSize.y.toInt())
        RenderCommand.clear()

        val maskTexture = renderObject.mask
        val (blur, noise, mask) = effects

        mask.applyEffect(
            backgroundFramebuffer = renderObject.highResFBO,
            backgroundRect = renderObject.highResRect,
            blur = noise.applyEffect(
                texture = blur.applyEffect(
                    highResFBO = renderObject.highResFBO,
                    fboRect = renderObject.highResRect,
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
                RenderCommand.clear()
                RenderCommand.blitFramebuffer(
                    srcX0 = 0,
                    srcY0 = 0,
                    srcX1 = finalFb.colorAttachmentTexture.width,
                    srcY1 = finalFb.colorAttachmentTexture.height,
                    dstX0 = 0,
                    dstY0 = 0,
                    dstX1 = size.x.toInt(),
                    dstY1 = size.y.toInt()
                )
            }
        } else {
            // all effects are disabled, just show original background
            trace("cutMainBackgroundRegion") {
                renderObject.highResFBO.bind(Bind.READ)
                RenderCommand.bindDefaultFramebuffer(bind = Bind.DRAW)
                RenderCommand.clear()

                RenderCommand.blitFramebuffer(
                    srcX0 = 0,
                    srcY0 = renderObject.highResRect.top.toInt(),
                    srcX1 = renderObject.highResRect.width.toInt(),
                    srcY1 = renderObject.highResRect.height.toInt(),
                    dstX0 = 0,
                    dstY0 = 0,
                    dstX1 = renderObject.highResRect.width.toInt(),
                    dstY1 = renderObject.highResRect.height.toInt(),
                    mask = RenderCommand.colorBufferBit,
                    filter = RenderCommand.linearTextureFilter,
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