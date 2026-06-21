/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.IndexBuffer
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.RendererApi
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.VertexArray
import dev.serhiiyaremych.imla.internal.render.VertexBuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.legacy.StencilClipStateWriter
import dev.serhiiyaremych.imla.internal.legacy.StencilClipWriteResult
import dev.serhiiyaremych.imla.internal.legacy.StencilClipWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.nio.Buffer

class SceneStencilClipPassTest {
    @Test
    fun successfulClippedSetupClearsWritesAndArmsStencilInOrder() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)
        commands.enableStencilTest()
        api.events.clear()
        val pass = stencilPass(commands)

        val result = pass.executeSetup(
            clipTexture = FakeTexture(id = 1, size = IntSize(width = 16, height = 16)),
            transform = Mat4.identity(),
            targetSize = IntSize(width = 320, height = 240)
        )

        assertEquals(SceneStencilClipSetupResult.Applied, result)
        assertEquals(
            listOf(
                "disableStencilTest",
                "clearStencil:0",
                "stencilMask:255",
                "clear:2",
                "colorMask:false:false:false:false",
                "enableStencilTest",
                "stencilFunc:4:1:255",
                "stencilOp:6:6:7",
                "stencilMask:255",
                "drawClipQuad",
                "colorMask:true:true:true:true",
                "stencilFunc:5:1:255",
                "stencilOp:6:6:6",
                "stencilMask:0"
            ),
            api.events
        )
    }

    @Test
    fun unsupportedStencilReturnsUnclippedFallbackWithoutArmingStencil() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)
        val pass = SceneStencilClipPass(
            commandsProvider = { commands },
            stencilClipRendererFactory = {
                CommandStencilClipWriter(
                    commands = commands,
                    writeResult = StencilClipWriteResult.Unsupported
                )
            }
        )

        val result = pass.executeSetup(
            clipTexture = FakeTexture(id = 2, size = IntSize(width = 16, height = 16)),
            transform = Mat4.identity(),
            targetSize = IntSize(width = 320, height = 240)
        )

        assertEquals(SceneStencilClipSetupResult.FallbackUnclipped, result)
        assertEquals(
            listOf(
                "clearStencil:0",
                "stencilMask:255",
                "clear:2"
            ),
            api.events
        )
    }

    @Test
    fun exceptionDuringStencilWriteRestoresColorMask() {
        val api = RecordingRendererApi()
        val writer = StencilClipStateWriter(RenderCommands(api))
        val failure = StencilFailure("draw failed")

        try {
            writer.writeTextureToStencil(stencilRef = 1) {
                api.events += "drawClipQuad"
                throw failure
            }
            fail("Expected stencil write failure")
        } catch (caught: StencilFailure) {
            assertSame(failure, caught)
        }

        assertEquals(
            listOf(
                "colorMask:false:false:false:false",
                "enableStencilTest",
                "stencilFunc:4:1:255",
                "stencilOp:6:6:7",
                "stencilMask:255",
                "drawClipQuad",
                "colorMask:true:true:true:true"
            ),
            api.events
        )
    }

    @Test
    fun exceptionAfterStencilEnableDisablesStencil() {
        val api = RecordingRendererApi(throwWhenStencilFunc = 5)
        val commands = RenderCommands(api)
        val pass = stencilPass(commands)

        try {
            pass.executeSetup(
                clipTexture = FakeTexture(id = 3, size = IntSize(width = 16, height = 16)),
                transform = Mat4.identity(),
                targetSize = IntSize(width = 320, height = 240)
            )
            fail("Expected stencil setup failure")
        } catch (caught: StencilFailure) {
            assertEquals("stencilFunc 5", caught.message)
        }

        assertEquals(
            listOf(
                "clearStencil:0",
                "stencilMask:255",
                "clear:2",
                "colorMask:false:false:false:false",
                "enableStencilTest",
                "stencilFunc:4:1:255",
                "stencilOp:6:6:7",
                "stencilMask:255",
                "drawClipQuad",
                "colorMask:true:true:true:true",
                "stencilFunc:5:1:255",
                "disableStencilTest"
            ),
            api.events
        )
    }

    @Test
    fun exceptionDuringDisableInCatchPreservesOriginalFailure() {
        val api = RecordingRendererApi(
            throwWhenStencilFunc = 5,
            throwWhenDisableStencil = true
        )
        val commands = RenderCommands(api)
        val pass = stencilPass(commands)

        try {
            pass.executeSetup(
                clipTexture = FakeTexture(id = 7, size = IntSize(width = 16, height = 16)),
                transform = Mat4.identity(),
                targetSize = IntSize(width = 320, height = 240)
            )
            fail("Expected stencil setup failure")
        } catch (caught: StencilFailure) {
            assertEquals("stencilFunc 5", caught.message)
            assertEquals(1, caught.suppressed.size)
            assertSame(DisableStencilFailure, caught.suppressed[0])
        }

        assertEquals(
            listOf(
                "clearStencil:0",
                "stencilMask:255",
                "clear:2",
                "colorMask:false:false:false:false",
                "enableStencilTest",
                "stencilFunc:4:1:255",
                "stencilOp:6:6:7",
                "stencilMask:255",
                "drawClipQuad",
                "colorMask:true:true:true:true",
                "stencilFunc:5:1:255",
                "disableStencilTest"
            ),
            api.events
        )
    }

    @Test
    fun bottomLeftClipTextureFailsBeforeStencilStateChanges() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)
        val pass = stencilPass(commands)

        try {
            pass.executeSetup(
                clipTexture = FakeTexture(
                    id = 4,
                    size = IntSize(width = 16, height = 16),
                    coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
                ),
                transform = Mat4.identity(),
                targetSize = IntSize(width = 320, height = 240)
            )
            fail("Expected bottom-left clip texture to be rejected")
        } catch (caught: IllegalArgumentException) {
            assertEquals(
                "Stencil clip shader expects slot-local TOP_LEFT clip textures; got BOTTOM_LEFT",
                caught.message
            )
        }

        assertEquals(emptyList<String>(), api.events)
    }

    private fun stencilPass(commands: RenderCommands): SceneStencilClipPass {
        return SceneStencilClipPass(
            commandsProvider = { commands },
            stencilClipRendererFactory = { CommandStencilClipWriter(commands) }
        )
    }

    private class CommandStencilClipWriter(
        private val commands: RenderCommands,
        private val writeResult: StencilClipWriteResult = StencilClipWriteResult.Written
    ) : StencilClipWriter {
        private val stateWriter by lazy(LazyThreadSafetyMode.NONE) {
            StencilClipStateWriter(commands)
        }

        override fun writeTextureToStencil(
            clipTexture: Texture2D,
            transform: Mat4,
            targetSize: IntSize,
            stencilRef: Int
        ): StencilClipWriteResult {
            if (writeResult == StencilClipWriteResult.Unsupported) {
                return StencilClipWriteResult.Unsupported
            }
            return stateWriter.writeTextureToStencil(stencilRef) {
                commands.drawIndexed(FakeVertexArray, indexCount = 6)
            }
        }

        override fun enableStencilTest(stencilRef: Int) {
            commands.enableStencilTest()
            commands.stencilFunc(commands.stencilEqual, stencilRef, 0xFF)
            commands.stencilOp(commands.stencilKeep, commands.stencilKeep, commands.stencilKeep)
            commands.stencilMask(0x00)
        }

        override fun disableStencilTest() {
            commands.disableStencilTest()
        }
    }

    private object FakeVertexArray : VertexArray {
        override fun bind() = Unit
        override fun unbind() = Unit
        override fun destroy() = Unit
        override fun addVertexBuffer(vertexBuffer: VertexBuffer) = Unit

        override var indexBuffer: IndexBuffer? = null
    }

    private class FakeTexture(
        override val id: Int,
        size: IntSize,
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.TOP_LEFT
    ) : Texture2D() {
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = size.width
        override val height: Int = size.height
        override val specification: Texture.Specification = Texture.Specification(
            size = size,
            coordinateOrigin = coordinateOrigin
        )

        override fun bind(slot: Int) = Unit
        override fun setData(data: Buffer) = Unit
        override fun isLoaded(): Boolean = true
        override fun destroy() = Unit
        override fun generateMipMaps() = Unit
    }

    private class RecordingRendererApi(
        private val throwWhenStencilFunc: Int? = null,
        private val throwWhenDisableStencil: Boolean = false
    ) : RendererApi {
        val events = mutableListOf<String>()
        override val colorBufferBit: Int = 1
        override val stencilBufferBit: Int = 2
        override val linearTextureFilter: Int = 3
        override val stencilAlways: Int = 4
        override val stencilEqual: Int = 5
        override val stencilKeep: Int = 6
        override val stencilReplace: Int = 7

        override fun init() = Unit
        override fun setClearColor(color: Color) = Unit
        override fun clear() = Unit
        override fun enableStencilTest() {
            events += "enableStencilTest"
        }

        override fun disableStencilTest() {
            events += "disableStencilTest"
            if (throwWhenDisableStencil) {
                throw DisableStencilFailure
            }
        }

        override fun stencilFunc(func: Int, ref: Int, mask: Int) {
            events += "stencilFunc:$func:$ref:$mask"
            if (func == throwWhenStencilFunc) {
                throw StencilFailure("stencilFunc $func")
            }
        }

        override fun stencilOp(sfail: Int, dpfail: Int, dppass: Int) {
            events += "stencilOp:$sfail:$dpfail:$dppass"
        }

        override fun stencilMask(mask: Int) {
            events += "stencilMask:$mask"
        }

        override fun clearStencil(value: Int) {
            events += "clearStencil:$value"
        }

        override fun enableScissorTest() = Unit
        override fun disableScissorTest() = Unit
        override fun setScissor(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun clear(mask: Int) {
            events += "clear:$mask"
        }

        override fun drawIndexed(vertexArray: VertexArray, indexCount: Int) {
            events += "drawClipQuad"
        }

        override fun setViewPort(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun disableDepthTest() = Unit
        override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
            events += "colorMask:$red:$green:$blue:$alpha"
        }

        override fun enableBlending() = Unit
        override fun disableBlending() = Unit
        override fun bindFramebuffer(bind: Bind, fboId: Int) = Unit
        override fun bindDefaultFramebuffer(bind: Bind) = Unit
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

    private class StencilFailure(message: String) : RuntimeException(message)

    private object DisableStencilFailure : RuntimeException("disable stencil test")
}
