/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.blend

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.legacy.computeUvBoundsFlippedYFloat
import dev.serhiiyaremych.imla.internal.legacy.computeUvBoundsNoFlipFloat
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer

internal class PostBlendEffect(
    private val simpleRenderer: SimpleQuadRenderer
) {
    private val cropCoordinates: Array<Offset> = Array<Offset>(4) { Offset.Zero }

    fun blendToDefaultBuffer(
        background: Framebuffer,
        cutBackgroundRegion: Rect,
        foreground: Framebuffer,
        cutForegroundRegion: Rect,
        opacity: Float,
    ) = trace("blendToDefaultBuffer") {
        val renderTargetSize = cutBackgroundRegion.size
        val clampedBlurOpacity = when {
            opacity < 0.1f -> 0.0f
            opacity > 0.95f -> 1.0f
            else -> opacity
        }

        when {
            clampedBlurOpacity == 0.0f -> { // blur is fully transparent, draw background only
                blitBackground(background, cutBackgroundRegion, renderTargetSize)
            }

            clampedBlurOpacity < 1.0f -> { // blur is translucent, mix background and blur
                blendLayers(
                    background = background,
                    cutBackgroundRegion = cutBackgroundRegion,
                    foreground = foreground,
                    cutForegroundRegion = cutForegroundRegion,
                    opacity = opacity
                )
            }
//            clampedBlurOpacity == 1.0f
            else -> { // blur is fully opaque, draw blur only
                blitForeground(foreground, cutForegroundRegion, renderTargetSize)
            }
        }
    }

    private fun blitForeground(
        foreground: Framebuffer,
        cutForegroundRegion: Rect,
        renderTargetSize: Size
    ) = trace("blitForeground") {
        foreground.bind(Bind.READ, updateViewport = false)
        RenderCommand.bindDefaultFramebuffer(bind = Bind.DRAW)
        RenderCommand.setViewPort(
            width = renderTargetSize.width.toInt(),
            height = renderTargetSize.height.toInt()
        )
        RenderCommand.blitFramebuffer(
            srcX0 = cutForegroundRegion.left.toInt(),
            srcY0 = (foreground.specification.size.height - cutForegroundRegion.bottom).toInt(),
            srcX1 = cutForegroundRegion.right.toInt(),
            srcY1 = cutForegroundRegion.bottom.toInt(),
            dstX0 = 0, dstY0 = 0,
            dstX1 = renderTargetSize.width.toInt(),
            dstY1 = renderTargetSize.height.toInt()
        )
    }

    private fun blitBackground(
        background: Framebuffer,
        cutBackgroundRegion: Rect,
        renderTargetSize: Size
    ) = trace("blitBackground") {
        background.bind(Bind.READ, updateViewport = false)
        RenderCommand.bindDefaultFramebuffer(bind = Bind.DRAW)
        RenderCommand.setViewPort(
            width = renderTargetSize.width.toInt(),
            height = renderTargetSize.height.toInt()
        )
        val cut = cutBackgroundRegion.translate(
            translateX = 0f,
            translateY = (background.specification.size.height - cutBackgroundRegion.height)
        )
        RenderCommand.blitFramebuffer(
            srcX0 = cut.left.toInt(),
            srcY0 = cut.top.toInt(),
            srcX1 = cut.right.toInt(),
            srcY1 = cut.bottom.toInt(),
            dstX0 = 0, dstY0 = 0,
            dstX1 = renderTargetSize.width.toInt(),
            dstY1 = renderTargetSize.height.toInt()
        )
    }

    private fun blendLayers(
        background: Framebuffer,
        cutBackgroundRegion: Rect,
        foreground: Framebuffer,
        cutForegroundRegion: Rect,
        opacity: Float
    ) = trace("blendLayers") {
        val backgroundSize = background.specification.size
        val backgroundBounds = trace("PostBlendEffect#computeBackgroundCropUv") {
            computeUvBoundsNoFlipFloat(
                left = cutBackgroundRegion.left,
                right = cutBackgroundRegion.right,
                top = cutBackgroundRegion.top,
                bottom = cutBackgroundRegion.bottom,
                targetWidth = backgroundSize.width,
                targetHeight = backgroundSize.height
            )
        }
        cropCoordinates[0] = Offset(
            x = backgroundBounds.u0,
            y = backgroundBounds.v0
        ) // BL
        cropCoordinates[1] = Offset(
            x = backgroundBounds.u1,
            y = backgroundBounds.v0
        ) // BR
        cropCoordinates[2] = Offset(
            x = backgroundBounds.u1,
            y = backgroundBounds.v1
        ) // TR
        cropCoordinates[3] = Offset(
            x = backgroundBounds.u0,
            y = backgroundBounds.v1
        ) // TL
        simpleRenderer.draw(
            texture = background.colorAttachmentTexture,
            textureCoordinates = cropCoordinates
        )

        val foregroundSize = foreground.specification.size
        val foregroundBounds = trace("PostBlendEffect#computeForegroundCropUv") {
            computeUvBoundsFlippedYFloat(
                left = cutForegroundRegion.left,
                right = cutForegroundRegion.right,
                top = cutForegroundRegion.top,
                bottom = cutForegroundRegion.bottom,
                targetWidth = foregroundSize.width,
                targetHeight = foregroundSize.height
            )
        }
        cropCoordinates[0] = Offset(
            x = foregroundBounds.u0,
            y = foregroundBounds.v0
        ) // BL
        cropCoordinates[1] = Offset(
            x = foregroundBounds.u1,
            y = foregroundBounds.v0
        ) // BR
        cropCoordinates[2] = Offset(
            x = foregroundBounds.u1,
            y = foregroundBounds.v1
        ) // TR
        cropCoordinates[3] = Offset(
            x = foregroundBounds.u0,
            y = foregroundBounds.v1
        ) // TL
        RenderCommand.withBlendingModeEnabled {
            simpleRenderer.draw(
                texture = foreground.colorAttachmentTexture,
                textureCoordinates = cropCoordinates,
                alpha = opacity
            )
        }
    }
}
