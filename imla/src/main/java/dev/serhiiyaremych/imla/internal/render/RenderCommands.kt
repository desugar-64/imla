/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.internal.render

import android.opengl.GLES30
import androidx.compose.ui.graphics.Color
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer

/**
 * Context-owned command facade for GL state changes.
 *
 * A [GraphicsContext] owns one instance and uses it on its GL renderer thread. The facade caches
 * viewport, blending, stencil, scissor, and framebuffer bindings for that context only. Immutable
 * frame/pass data should receive this dependency from the owning context instead of reaching for
 * [RenderCommand].
 */
internal class RenderCommands(
    val rendererApi: RendererApi
) {
    val colorBufferBit: Int = rendererApi.colorBufferBit
    val stencilBufferBit: Int = rendererApi.stencilBufferBit
    val linearTextureFilter: Int = rendererApi.linearTextureFilter

    val stencilAlways: Int = rendererApi.stencilAlways
    val stencilEqual: Int = rendererApi.stencilEqual
    val stencilKeep: Int = rendererApi.stencilKeep
    val stencilReplace: Int = rendererApi.stencilReplace

    private var cachedViewportX: Int = -1
    private var cachedViewportY: Int = -1
    private var cachedViewportWidth: Int = -1
    private var cachedViewportHeight: Int = -1
    private var blendingEnabled: Boolean = false
    private var stencilTestEnabled: Boolean = false
    private var scissorTestEnabled: Boolean = false
    private var cachedScissorX: Int = -1
    private var cachedScissorY: Int = -1
    private var cachedScissorWidth: Int = -1
    private var cachedScissorHeight: Int = -1
    private var cachedDrawFbo: Int = -1
    private var cachedReadFbo: Int = -1

    fun init() {
        clearCachedState()
        rendererApi.init()
    }

    fun setClearColor(color: Color) {
        rendererApi.setClearColor(color)
    }

    fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
        rendererApi.colorMask(red, green, blue, alpha)
    }

    fun clear() {
        rendererApi.clear()
    }

    fun clear(color: Color) {
        setClearColor(color)
        rendererApi.clear()
    }

    fun drawIndexed(vertexArray: VertexArray, indexCount: Int = 0) {
        rendererApi.drawIndexed(vertexArray, indexCount)
    }

    fun setViewPort(x: Int = 0, y: Int = 0, width: Int, height: Int) {
        if (x == cachedViewportX &&
            y == cachedViewportY &&
            width == cachedViewportWidth &&
            height == cachedViewportHeight
        ) {
            return
        }
        rendererApi.setViewPort(x, y, width, height)
        cachedViewportX = x
        cachedViewportY = y
        cachedViewportWidth = width
        cachedViewportHeight = height
    }

    fun disableDepthTest() {
        rendererApi.disableDepthTest()
    }

    fun enableBlending() {
        if (blendingEnabled) return
        rendererApi.enableBlending()
        blendingEnabled = true
    }

    fun disableBlending() {
        if (!blendingEnabled) return
        rendererApi.disableBlending()
        blendingEnabled = false
    }

    fun enableStencilTest() {
        if (stencilTestEnabled) return
        rendererApi.enableStencilTest()
        stencilTestEnabled = true
    }

    fun disableStencilTest() {
        if (!stencilTestEnabled) return
        rendererApi.disableStencilTest()
        stencilTestEnabled = false
    }

    fun stencilFunc(func: Int, ref: Int, mask: Int) {
        rendererApi.stencilFunc(func, ref, mask)
    }

    fun stencilOp(sfail: Int, dpfail: Int, dppass: Int) {
        rendererApi.stencilOp(sfail, dpfail, dppass)
    }

    fun stencilMask(mask: Int) {
        rendererApi.stencilMask(mask)
    }

    fun clearStencil(value: Int) {
        rendererApi.clearStencil(value)
    }

    fun enableScissorTest() {
        if (scissorTestEnabled) return
        rendererApi.enableScissorTest()
        scissorTestEnabled = true
    }

    fun disableScissorTest() {
        if (!scissorTestEnabled) return
        rendererApi.disableScissorTest()
        scissorTestEnabled = false
    }

    fun setScissor(x: Int, y: Int, width: Int, height: Int) {
        if (x == cachedScissorX &&
            y == cachedScissorY &&
            width == cachedScissorWidth &&
            height == cachedScissorHeight
        ) {
            return
        }
        rendererApi.setScissor(x, y, width, height)
        cachedScissorX = x
        cachedScissorY = y
        cachedScissorWidth = width
        cachedScissorHeight = height
    }

    fun clear(mask: Int) {
        rendererApi.clear(mask)
    }

    fun bindDefaultFramebuffer(bind: Bind = Bind.BOTH, force: Boolean = false) {
        if (cacheFramebufferBinding(bind, fboId = 0) && !force) return
        rendererApi.bindDefaultFramebuffer(bind)
    }

    fun bindFramebuffer(bind: Bind, fboId: Int, force: Boolean = false): Boolean {
        val wasCached = cacheFramebufferBinding(bind, fboId)
        if (wasCached && !force) return true
        rendererApi.bindFramebuffer(bind, fboId)
        return false
    }

    fun useDefaultProgram() = rendererApi.bindDefaultProgram()

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
        rendererApi.blitFramebuffer(
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
        enableBlending()
        try {
            block()
        } finally {
            disableBlending()
        }
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
        rendererApi.copyImageSubData(
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
    ) = trace("copyOrBlitFramebuffer") {
        val srcWidth = srcX1 - srcX0
        val srcHeight = srcY1 - srcY0
        val dstWidth = dstX1 - dstX0
        val dstHeight = dstY1 - dstY0

        val canUseCopyImage = useCopyImage &&
            srcWidth == dstWidth &&
            srcHeight == dstHeight

        if (canUseCopyImage) {
            copyImageSubData(
                srcName = src.colorAttachmentTexture.id,
                srcTarget = GLES30.GL_TEXTURE_2D,
                srcLevel = 0,
                srcX = srcX0,
                srcY = srcY0,
                srcZ = 0,
                dstName = dst.colorAttachmentTexture.id,
                dstTarget = GLES30.GL_TEXTURE_2D,
                dstLevel = 0,
                dstX = dstX0,
                dstY = dstY0,
                dstZ = 0,
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                srcDepth = 1
            )
        } else {
            src.bind(this, Bind.READ, updateViewport = false)
            dst.bind(this, Bind.DRAW, updateViewport = false)
            blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1)
        }
    }

    private fun cacheFramebufferBinding(bind: Bind, fboId: Int): Boolean {
        return when (bind) {
            Bind.DRAW -> {
                if (cachedDrawFbo == fboId) {
                    true
                } else {
                    cachedDrawFbo = fboId
                    false
                }
            }
            Bind.READ -> {
                if (cachedReadFbo == fboId) {
                    true
                } else {
                    cachedReadFbo = fboId
                    false
                }
            }
            Bind.BOTH -> {
                if (cachedDrawFbo == fboId && cachedReadFbo == fboId) {
                    true
                } else {
                    cachedDrawFbo = fboId
                    cachedReadFbo = fboId
                    false
                }
            }
        }
    }

    private fun clearCachedState() {
        cachedViewportX = -1
        cachedViewportY = -1
        cachedViewportWidth = -1
        cachedViewportHeight = -1
        blendingEnabled = false
        stencilTestEnabled = false
        scissorTestEnabled = false
        cachedScissorX = -1
        cachedScissorY = -1
        cachedScissorWidth = -1
        cachedScissorHeight = -1
        cachedDrawFbo = -1
        cachedReadFbo = -1
    }
}
