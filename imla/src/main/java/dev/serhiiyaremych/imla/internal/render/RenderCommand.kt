/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.internal.render

import androidx.compose.ui.graphics.Color
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.opengl.OpenGLRendererAPI

/**
 * Legacy global command adapter.
 *
 * Renderer 2 code should use [GraphicsContext.commands] so command state remains local to the
 * owning renderer instance. This singleton remains only for old utility callers that have not yet
 * been moved to an owned context.
 */
internal object RenderCommand {
    internal val commands: RenderCommands = RenderCommands(OpenGLRendererAPI())

    val rendererApi: RendererApi get() = commands.rendererApi

    val colorBufferBit: Int get() = commands.colorBufferBit
    val stencilBufferBit: Int get() = commands.stencilBufferBit
    val linearTextureFilter: Int get() = commands.linearTextureFilter

    val stencilAlways: Int get() = commands.stencilAlways
    val stencilEqual: Int get() = commands.stencilEqual
    val stencilKeep: Int get() = commands.stencilKeep
    val stencilReplace: Int get() = commands.stencilReplace

    fun init() {
        commands.init()
    }

    fun setClearColor(color: Color) {
        commands.setClearColor(color)
    }

    fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
        commands.colorMask(red, green, blue, alpha)
    }

    fun clear() {
        commands.clear()
    }

    fun clear(color: Color) {
        commands.clear(color)
    }

    fun drawIndexed(vertexArray: VertexArray, indexCount: Int = 0) {
        commands.drawIndexed(vertexArray, indexCount)
    }

    fun setViewPort(x: Int = 0, y: Int = 0, width: Int, height: Int) {
        commands.setViewPort(x, y, width, height)
    }

    fun disableDepthTest() {
        commands.disableDepthTest()
    }

    fun enableBlending() {
        commands.enableBlending()
    }

    fun disableBlending() {
        commands.disableBlending()
    }

    fun enableStencilTest() {
        commands.enableStencilTest()
    }

    fun disableStencilTest() {
        commands.disableStencilTest()
    }

    fun stencilFunc(func: Int, ref: Int, mask: Int) {
        commands.stencilFunc(func, ref, mask)
    }

    fun stencilOp(sfail: Int, dpfail: Int, dppass: Int) {
        commands.stencilOp(sfail, dpfail, dppass)
    }

    fun stencilMask(mask: Int) {
        commands.stencilMask(mask)
    }

    fun clearStencil(value: Int) {
        commands.clearStencil(value)
    }

    fun enableScissorTest() {
        commands.enableScissorTest()
    }

    fun disableScissorTest() {
        commands.disableScissorTest()
    }

    fun setScissor(x: Int, y: Int, width: Int, height: Int) {
        commands.setScissor(x, y, width, height)
    }

    fun clear(mask: Int) {
        commands.clear(mask)
    }

    fun bindDefaultFramebuffer(bind: Bind = Bind.BOTH) {
        commands.bindDefaultFramebuffer(bind)
    }

    fun useDefaultProgram() = commands.useDefaultProgram()

    fun blitFramebuffer(
        srcX0: Int,
        srcY0: Int,
        srcX1: Int,
        srcY1: Int,
        dstX0: Int,
        dstY0: Int,
        dstX1: Int,
        dstY1: Int,
        mask: Int = colorBufferBit,
        filter: Int = linearTextureFilter,
    ) {
        commands.blitFramebuffer(
            srcX0 = srcX0,
            srcY0 = srcY0,
            srcX1 = srcX1,
            srcY1 = srcY1,
            dstX0 = dstX0,
            dstY0 = dstY0,
            dstX1 = dstX1,
            dstY1 = dstY1,
            mask = mask,
            filter = filter
        )
    }

    inline fun withBlendingModeEnabled(block: () -> Unit) {
        commands.withBlendingModeEnabled(block)
    }

    fun copyImageSubData(
        srcName: Int,
        srcTarget: Int,
        srcLevel: Int,
        srcX: Int,
        srcY: Int,
        srcZ: Int,
        dstName: Int,
        dstTarget: Int,
        dstLevel: Int,
        dstX: Int,
        dstY: Int,
        dstZ: Int,
        srcWidth: Int,
        srcHeight: Int,
        srcDepth: Int
    ) {
        commands.copyImageSubData(
            srcName, srcTarget, srcLevel, srcX, srcY, srcZ,
            dstName, dstTarget, dstLevel, dstX, dstY, dstZ,
            srcWidth, srcHeight, srcDepth
        )
    }

    fun copyOrBlitFramebuffer(
        src: Framebuffer,
        dst: Framebuffer,
        srcX0: Int,
        srcY0: Int,
        srcX1: Int,
        srcY1: Int,
        dstX0: Int,
        dstY0: Int,
        dstX1: Int,
        dstY1: Int,
        useCopyImage: Boolean
    ) = commands.copyOrBlitFramebuffer(
        src = src,
        dst = dst,
        srcX0 = srcX0,
        srcY0 = srcY0,
        srcX1 = srcX1,
        srcY1 = srcY1,
        dstX0 = dstX0,
        dstY0 = dstY0,
        dstX1 = dstX1,
        dstY1 = dstY1,
        useCopyImage = useCopyImage
    )
}
