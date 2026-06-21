/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render

import androidx.compose.ui.graphics.Color
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RenderCommandsTest {
    @Test
    fun framebufferBindsShareOnePerContextCache() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)

        commands.bindFramebuffer(Bind.DRAW, 8)
        commands.bindFramebuffer(Bind.DRAW, 8)
        commands.bindDefaultFramebuffer(Bind.DRAW)
        commands.bindDefaultFramebuffer(Bind.DRAW)
        commands.bindFramebuffer(Bind.READ, 9)
        commands.bindFramebuffer(Bind.BOTH, 9)
        commands.bindFramebuffer(Bind.BOTH, 9)

        assertEquals(
            listOf(
                FramebufferBind(Bind.DRAW, 8),
                FramebufferBind(Bind.DRAW, 0),
                FramebufferBind(Bind.READ, 9),
                FramebufferBind(Bind.BOTH, 9)
            ),
            api.framebufferBinds
        )
    }

    @Test
    fun forcedFramebufferBindsKeepTheSameCacheCoherent() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)

        commands.bindFramebuffer(Bind.DRAW, 3)
        commands.bindFramebuffer(Bind.DRAW, 3, force = true)
        commands.bindFramebuffer(Bind.DRAW, 3)
        commands.bindDefaultFramebuffer(Bind.DRAW, force = true)
        commands.bindDefaultFramebuffer(Bind.DRAW)

        assertEquals(
            listOf(
                FramebufferBind(Bind.DRAW, 3),
                FramebufferBind(Bind.DRAW, 3),
                FramebufferBind(Bind.DRAW, 0)
            ),
            api.framebufferBinds
        )
    }

    @Test
    fun commandStateCacheIsOwnedByOneRenderCommandsInstance() {
        val firstApi = RecordingRendererApi()
        val secondApi = RecordingRendererApi()
        val first = RenderCommands(firstApi)
        val second = RenderCommands(secondApi)

        first.setViewPort(width = 100, height = 200)
        first.setViewPort(width = 100, height = 200)
        second.setViewPort(width = 100, height = 200)
        first.init()
        first.setViewPort(width = 100, height = 200)

        first.enableBlending()
        first.enableBlending()
        first.disableBlending()
        first.disableBlending()

        first.enableStencilTest()
        first.enableStencilTest()
        first.disableStencilTest()
        first.disableStencilTest()

        first.enableScissorTest()
        first.enableScissorTest()
        first.setScissor(1, 2, 3, 4)
        first.setScissor(1, 2, 3, 4)
        first.disableScissorTest()
        first.disableScissorTest()

        assertEquals(
            listOf(Viewport(0, 0, 100, 200), Viewport(0, 0, 100, 200)),
            firstApi.viewports
        )
        assertEquals(listOf(Viewport(0, 0, 100, 200)), secondApi.viewports)
        assertEquals(1, firstApi.initCalls)
        assertEquals(1, firstApi.enableBlendCalls)
        assertEquals(1, firstApi.disableBlendCalls)
        assertEquals(1, firstApi.enableStencilCalls)
        assertEquals(1, firstApi.disableStencilCalls)
        assertEquals(1, firstApi.enableScissorCalls)
        assertEquals(1, firstApi.disableScissorCalls)
        assertEquals(listOf(Scissor(1, 2, 3, 4)), firstApi.scissors)
    }

    @Test
    fun withBlendingModeEnabledDisablesBlendingAfterFailure() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)

        try {
            commands.withBlendingModeEnabled {
                error("draw failed")
            }
            fail("Expected draw failure")
        } catch (failure: IllegalStateException) {
            assertEquals("draw failed", failure.message)
        }

        assertEquals(1, api.enableBlendCalls)
        assertEquals(1, api.disableBlendCalls)
    }

    private data class FramebufferBind(val bind: Bind, val fboId: Int)
    private data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)
    private data class Scissor(val x: Int, val y: Int, val width: Int, val height: Int)

    private class RecordingRendererApi : RendererApi {
        override val colorBufferBit: Int = 1
        override val stencilBufferBit: Int = 2
        override val linearTextureFilter: Int = 3
        override val stencilAlways: Int = 4
        override val stencilEqual: Int = 5
        override val stencilKeep: Int = 6
        override val stencilReplace: Int = 7

        val framebufferBinds = mutableListOf<FramebufferBind>()
        val viewports = mutableListOf<Viewport>()
        val scissors = mutableListOf<Scissor>()
        var initCalls: Int = 0
        var enableBlendCalls: Int = 0
        var disableBlendCalls: Int = 0
        var enableStencilCalls: Int = 0
        var disableStencilCalls: Int = 0
        var enableScissorCalls: Int = 0
        var disableScissorCalls: Int = 0

        override fun init() {
            initCalls++
        }

        override fun setClearColor(color: Color) = Unit
        override fun clear() = Unit
        override fun enableStencilTest() {
            enableStencilCalls++
        }
        override fun disableStencilTest() {
            disableStencilCalls++
        }
        override fun stencilFunc(func: Int, ref: Int, mask: Int) = Unit
        override fun stencilOp(sfail: Int, dpfail: Int, dppass: Int) = Unit
        override fun stencilMask(mask: Int) = Unit
        override fun clearStencil(value: Int) = Unit
        override fun enableScissorTest() {
            enableScissorCalls++
        }
        override fun disableScissorTest() {
            disableScissorCalls++
        }
        override fun setScissor(x: Int, y: Int, width: Int, height: Int) {
            scissors += Scissor(x, y, width, height)
        }
        override fun clear(mask: Int) = Unit
        override fun drawIndexed(vertexArray: VertexArray, indexCount: Int) = Unit
        override fun setViewPort(x: Int, y: Int, width: Int, height: Int) {
            viewports += Viewport(x, y, width, height)
        }
        override fun disableDepthTest() = Unit
        override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = Unit
        override fun enableBlending() {
            enableBlendCalls++
        }
        override fun disableBlending() {
            disableBlendCalls++
        }
        override fun bindFramebuffer(bind: Bind, fboId: Int) {
            framebufferBinds += FramebufferBind(bind, fboId)
        }
        override fun bindDefaultFramebuffer(bind: Bind) {
            framebufferBinds += FramebufferBind(bind, 0)
        }
        override fun bindDefaultProgram() = Unit
        override fun blitFramebuffer(
            srcX0: Int,
            srcY0: Int,
            srcX1: Int,
            srcY1: Int,
            dstX0: Int,
            dstY0: Int,
            dstX1: Int,
            dstY1: Int,
            mask: Int,
            filter: Int
        ) = Unit
        override fun copyImageSubData(
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
        ) = Unit
    }
}
