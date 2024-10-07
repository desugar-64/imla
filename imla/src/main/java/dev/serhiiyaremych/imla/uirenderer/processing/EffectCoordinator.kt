/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toIntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.SubTexture2D
import dev.serhiiyaremych.imla.uirenderer.RenderObject
import dev.serhiiyaremych.imla.uirenderer.RenderableRootLayer
import dev.serhiiyaremych.imla.uirenderer.processing.blend.BlendEffect
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
            blendEffect = BlendEffect(assetManager, simpleQuadRenderer)
        )
    }

    fun applyEffects(renderObject: RenderObject) = with(renderObject.renderableScope) {

        val effects = effectCache.getOrPut(renderObject.id) {
            createEffects()
        }
        RenderCommand.bindDefaultFramebuffer()
        RenderCommand.useDefaultProgram()
        RenderCommand.clear()

        val maskTexture = renderObject.mask
        val (prePrecess, blur, noise, mask, blendEffect) = effects

        mask.applyEffect(
            backgroundFramebuffer = rootLayer.highResFBO,
            backgroundRect = renderObject.area,
            blur = noise.applyEffect(
                texture = blur.applyEffect(
                    inputFbo = prePrecess.preProcess(rootLayer.highResFBO, renderObject.area),
                    offset = renderObject.style.offset,
                    passes = renderObject.style.passes,
                    tint = renderObject.style.tint
                ),
                noiseAlpha = renderObject.style.noiseAlpha
            ),
            mask = maskTexture
        )

        val finalFb = /*prePrecess.preProcess(rootLayer.highResFBO, renderObject.area)*/
            output(blur, noise, mask)
        if (finalFb != null) {
            trace("blitFinalToScreen") {
                RenderCommand.bindDefaultFramebuffer(Bind.DRAW)
                RenderCommand.setViewPort(
                    x = 0, y = 0,
                    width = renderObject.area.width.toInt(),
                    height = renderObject.area.height.toInt()
                )
                RenderCommand.clear()
                val blurOpacity = when {
                    renderObject.style.blurOpacity < 0.1f -> 0.0f
                    renderObject.style.blurOpacity > 0.95f -> 1.0f
                    else -> renderObject.style.blurOpacity
                }
                when {
                    blurOpacity == 0.0f -> {
                        RenderCommand.enableBlending()
                        simpleQuadRenderer.draw(
                            texture = SubTexture2D.createFromCoords(
                                texture = rootLayer.highResFBO.colorAttachmentTexture,
                                rect = renderObject.area.translate(
                                    Offset(
                                        x = 0f,
                                        y = rootLayer.highResFBO.specification.size.height - renderObject.area.height
                                    )
                                )
                            )
                        )
                        RenderCommand.disableBlending()
                    }

                    blurOpacity < 1.0 -> {
                        blendEffect.blendToDefaultBuffer(
                            background = rootLayer.highResFBO,
                            cutBackgroundRegion = renderObject.area,
                            foreground = finalFb,
                            cutForegroundRegion = prePrecess.contentCrop,
                            opacity = blurOpacity,
                        )
                    }

                    else -> {
                        // Cut
                        finalFb.bind(Bind.READ, updateViewport = false)
                        RenderCommand.blitFramebuffer(
                            srcX0 = 0 + prePrecess.contentCrop.left.toInt(),
                            srcY0 = 0 + prePrecess.contentCrop.top.toInt(),
                            srcX1 = prePrecess.contentCrop.right.toInt(),
                            srcY1 = prePrecess.contentCrop.bottom.toInt(),
                            dstX0 = 0,
                            dstY0 = 0,
                            dstX1 = renderObject.area.width.toInt(),
                            dstY1 = renderObject.area.height.toInt()
                        )
                    }
                }

                // Full
//                RenderCommand.blitFramebuffer(
//                    srcX0 = 0,
//                    srcY0 = 0,
//                    srcX1 = finalFb.colorAttachmentTexture.width,
//                    srcY1 = finalFb.colorAttachmentTexture.height,
//                    dstX0 = 0,
//                    dstY0 = 0,
//                    dstX1 = renderObject.area.width.toInt(),
//                    dstY1 = renderObject.area.height.toInt()
//                )
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