/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.blend

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toIntSize
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.uirenderer.processing.SimpleQuadRenderer
import kotlin.properties.Delegates

internal class BlendEffect(
    private val assetManager: AssetManager,
    private val simpleRenderer: SimpleQuadRenderer
) {

    // TODO: Optimize. Use single shader operation
    private var background: Framebuffer by Delegates.notNull()
    private var foreground: Framebuffer by Delegates.notNull()
    private var isInitialised: Boolean = false

    fun blendToDefaultBuffer(
        background: Framebuffer,
        cutBackgroundRegion: Rect,
        foreground: Framebuffer,
        cutForegroundRegion: Rect,
        opacity: Float,
    ) {
        init(cutBackgroundRegion, cutForegroundRegion)
        cutRegion(
            input = background,
            output = this.background,
            cutRegion = cutBackgroundRegion.translate(
                0f,
                background.specification.size.height - cutBackgroundRegion.height
            )
        )
        RenderCommand.bindDefaultFramebuffer(Bind.DRAW)
        RenderCommand.setViewPort(
            width = cutBackgroundRegion.width.toInt(),
            height = cutBackgroundRegion.height.toInt()
        )
        simpleRenderer.draw(texture = this.background.colorAttachmentTexture)

        cutRegion(foreground, this.foreground, cutForegroundRegion)
        RenderCommand.bindDefaultFramebuffer(Bind.DRAW)
        RenderCommand.enableBlending()
        RenderCommand.setViewPort(
            width = cutBackgroundRegion.width.toInt(),
            height = cutBackgroundRegion.height.toInt()
        )
        simpleRenderer.draw(texture = this.foreground.colorAttachmentTexture, alpha = opacity)
        RenderCommand.disableBlending()

    }

    private fun cutRegion(input: Framebuffer, output: Framebuffer, cutRegion: Rect) {
        input.bind(bind = Bind.READ, updateViewport = false)
        output.bind(bind = Bind.DRAW)
        RenderCommand.blitFramebuffer(
            srcX0 = cutRegion.left.toInt(),
            srcY0 = cutRegion.top.toInt(),
            srcX1 = cutRegion.right.toInt(),
            srcY1 = cutRegion.bottom.toInt(),
            dstX0 = 0,
            dstY0 = 0,
            dstX1 = cutRegion.width.toInt(),
            dstY1 = cutRegion.height.toInt()
        )

    }

    private fun init(cutBackgroundRegion: Rect, cutForegroundRegion: Rect) {
        if (!isInitialised) {
            background = Framebuffer.create(
                spec = FramebufferSpecification(
                    size = cutBackgroundRegion.size.toIntSize(),
                    attachmentsSpec = FramebufferAttachmentSpecification.singleColor()
                )
            )
            foreground = Framebuffer.create(
                spec = FramebufferSpecification(
                    size = cutForegroundRegion.size.toIntSize(),
                    attachmentsSpec = FramebufferAttachmentSpecification.singleColor()
                )
            )
            isInitialised = true
        }
    }
}