/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing

import android.content.res.AssetManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toIntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.uirenderer.RenderObject
import dev.serhiiyaremych.imla.uirenderer.RenderableRootLayer
import dev.serhiiyaremych.imla.uirenderer.processing.blur.DualBlurEffect
import dev.serhiiyaremych.imla.uirenderer.processing.mask.MaskEffect
import dev.serhiiyaremych.imla.uirenderer.processing.noise.NoiseEffect
import dev.serhiiyaremych.imla.uirenderer.processing.preprocess.PreProcess

internal class EffectCoordinator(
    density: Density,
    private val rootLayer: RenderableRootLayer,
    private val simpleQuadRenderer: SimpleQuadRenderer,
    private val assetManager: AssetManager
) : Density by density {

    private val effectCache: MutableMap<String, EffectsHolder> = mutableMapOf()

    private fun createEffects(renderObject: RenderObject): EffectsHolder {
        return EffectsHolder(
            preProcess = PreProcess(assetManager, simpleQuadRenderer),
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
        RenderCommand.bindDefaultFramebuffer()
        RenderCommand.useDefaultProgram()
        RenderCommand.clear()

        val maskTexture = renderObject.mask
        val (prePrecess, blur, noise, mask) = effects

        mask.applyEffect(
            backgroundFramebuffer = rootLayer.highResFBO,
            backgroundRect = renderObject.area,
            blur = noise.applyEffect(
                texture = blur.applyEffect(
                    inputFbo = prePrecess.preProcess(rootLayer.highResFBO, renderObject.area),
                    renderTargetSize = renderObject.area.size.toIntSize(),
                    blurRadius = renderObject.style.blurRadiusPx(),
                    tint = renderObject.style.tint
                ),
                noiseAlpha = renderObject.style.noiseAlpha
            ),
            mask = maskTexture
        )

        val finalFb = output(blur, noise, mask)
        if (finalFb != null) {
            trace("blitFinalToScreen") {
                finalFb.bind(Bind.READ, updateViewport = false)
                RenderCommand.bindDefaultFramebuffer(Bind.DRAW)
                RenderCommand.setViewPort(
                    0,
                    0,
                    renderObject.area.width.toInt(),
                    renderObject.area.height.toInt()
                )
                RenderCommand.clear()
                RenderCommand.blitFramebuffer(
                    srcX0 = 0,
                    srcY0 = 0,
                    srcX1 = finalFb.colorAttachmentTexture.width,
                    srcY1 = finalFb.colorAttachmentTexture.height,
                    dstX0 = 0,
                    dstY0 = 0,
                    dstX1 = renderObject.area.width.toInt(),
                    dstY1 = renderObject.area.height.toInt()
                )
//                simpleQuadRenderer.draw(texture = finalFb.colorAttachmentTexture)
            }
        } else {
            // all effects are disabled, just show original background
            trace("cutMainBackgroundRegion") {
                rootLayer.highResFBO.bind(Bind.READ)
                RenderCommand.bindDefaultFramebuffer(bind = Bind.DRAW)
                RenderCommand.clear()

                RenderCommand.blitFramebuffer(
                    srcX0 = 0,
                    srcY0 = renderObject.area.top.toInt(),
                    srcX1 = renderObject.area.width.toInt(),
                    srcY1 = renderObject.area.height.toInt(),
                    dstX0 = 0,
                    dstY0 = 0,
                    dstX1 = renderObject.area.width.toInt(),
                    dstY1 = renderObject.area.height.toInt(),
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