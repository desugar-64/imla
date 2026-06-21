/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.collection.FloatFloatPair
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat3
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.RendererApi
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.VertexArray
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.processing.SinglePassQuadDrawSink
import dev.serhiiyaremych.imla.internal.render.processing.SinglePassQuadExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.Buffer

class SceneCommandPassContractTest {
    @Test
    fun rootSeedBindsSceneDisablesBlendAndHandsOffRootFlip() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)
        val drawSink = RecordingSinglePassDrawSink()
        val pass = SceneRootSeedPass(
            commandsProvider = { commands },
            singlePassQuadExecutor = SinglePassQuadExecutor(drawSink)
        )
        val rootTexture = FakeTexture(
            id = 7,
            size = IntSize(width = 64, height = 48),
            coordinateOrigin = CoordinateOrigin.TOP_LEFT
        )
        val scene = FakeFramebuffer(
            rendererId = 42,
            size = IntSize(width = 320, height = 240)
        )
        commands.enableBlending()
        api.events.clear()

        pass.executeCommands(rootTexture = rootTexture, scene = scene)

        assertEquals(
            listOf(
                "bindFramebuffer:DRAW:42",
                "viewport:0:0:320:240",
                "disableBlending"
            ),
            api.events
        )
        val draw = drawSink.onlyTextureDraw()
        assertSame(rootTexture, draw.texture)
        assertEquals(true, draw.flipY)
        assertEquals(1f, draw.alpha)
        assertEquals(Color.Transparent, draw.tint)
        assertNull(draw.textureCoordinatesFlat)
    }

    @Test
    fun presentBindsDefaultFramebufferSetsViewportAndUsesCallerFlip() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)
        val drawSink = RecordingSinglePassDrawSink()
        val pass = ScenePresentPass(
            commandsProvider = { commands },
            singlePassQuadExecutor = SinglePassQuadExecutor(drawSink)
        )
        val texture = FakeTexture(
            id = 8,
            size = IntSize(width = 128, height = 96),
            coordinateOrigin = CoordinateOrigin.TOP_LEFT
        )

        pass.executeCommands(
            texture = texture,
            targetSize = IntSize(width = 800, height = 600),
            flipY = false
        )

        assertEquals(
            listOf(
                "bindDefaultFramebuffer:BOTH",
                "viewport:0:0:800:600"
            ),
            api.events
        )
        val draw = drawSink.onlyTextureDraw()
        assertSame(texture, draw.texture)
        assertEquals(false, draw.flipY)
        assertNull(draw.textureCoordinatesFlat)
    }

    @Test
    fun singlePassTextureDrawKeepsNullUvForRendererFullTextureDefault() {
        val drawSink = RecordingSinglePassDrawSink()
        val texture = FakeTexture(
            id = 9,
            size = IntSize(width = 16, height = 16),
            coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
        )

        SinglePassQuadExecutor(drawSink).draw(texture = texture)

        val draw = drawSink.onlyTextureDraw()
        assertSame(texture, draw.texture)
        assertNull(draw.textureCoordinatesFlat)
        assertNull(draw.flipY)
        assertEquals(1f, draw.alpha)
        assertEquals(Color.Transparent, draw.tint)
    }

    @Test
    fun singlePassShaderDrawKeepsNullUvForRendererFullTextureDefault() {
        val drawSink = RecordingSinglePassDrawSink()
        val shader = FakeShader()
        val texture = FakeTexture(
            id = 10,
            size = IntSize(width = 16, height = 16),
            coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
        )

        SinglePassQuadExecutor(drawSink).draw(shader = shader, texture = texture)

        val draw = drawSink.onlyShaderDraw()
        assertSame(shader, draw.shader)
        assertSame(texture, draw.texture)
        assertNull(draw.textureCoordinatesFlat)
        assertNull(draw.flipY)
        assertEquals(1f, draw.alpha)
        assertEquals(Color.Transparent, draw.tint)
    }

    private data class TextureDraw(
        val texture: Texture2D,
        val textureCoordinatesFlat: FloatArray?,
        val alpha: Float,
        val flipY: Boolean?,
        val tint: Color
    )

    private data class ShaderDraw(
        val shader: Shader,
        val texture: Texture2D?,
        val textureCoordinatesFlat: FloatArray?,
        val alpha: Float,
        val flipY: Boolean?,
        val tint: Color
    )

    private class RecordingSinglePassDrawSink : SinglePassQuadDrawSink {
        private val textureDraws = mutableListOf<TextureDraw>()
        private val shaderDraws = mutableListOf<ShaderDraw>()

        override fun draw(
            shader: Shader,
            texture: Texture2D?,
            textureCoordinatesFlat: FloatArray?,
            alpha: Float,
            flipY: Boolean?,
            tint: Color
        ) {
            shaderDraws += ShaderDraw(shader, texture, textureCoordinatesFlat, alpha, flipY, tint)
        }

        override fun draw(
            texture: Texture2D,
            textureCoordinatesFlat: FloatArray?,
            alpha: Float,
            flipY: Boolean?,
            tint: Color
        ) {
            textureDraws += TextureDraw(texture, textureCoordinatesFlat, alpha, flipY, tint)
        }

        fun onlyTextureDraw(): TextureDraw {
            assertEquals(emptyList<ShaderDraw>(), shaderDraws)
            assertEquals(1, textureDraws.size)
            return textureDraws.single()
        }

        fun onlyShaderDraw(): ShaderDraw {
            assertEquals(emptyList<TextureDraw>(), textureDraws)
            assertEquals(1, shaderDraws.size)
            return shaderDraws.single()
        }
    }

    private class FakeFramebuffer(
        override val rendererId: Int,
        size: IntSize
    ) : Framebuffer {
        override val specification: FramebufferSpecification = FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification.singleColor()
        )
        override val colorAttachmentTexture: Texture2D = FakeTexture(rendererId + 1, size)

        override fun invalidate() = Unit
        override fun bind(bind: Bind, updateViewport: Boolean) = Unit

        override fun bind(commands: RenderCommands, bind: Bind, updateViewport: Boolean) {
            commands.bindFramebuffer(bind, rendererId)
            if (updateViewport) {
                commands.setViewPort(width = specification.size.width, height = specification.size.height)
            }
        }

        override fun bindForOverwrite(bind: Bind) = Unit
        override fun bindForOverwrite(commands: RenderCommands, bind: Bind) {
            bind(commands, bind, updateViewport = true)
        }

        override fun bindForMipLevel(level: Int, size: IntSize, bind: Bind) = Unit
        override fun bindForMipLevel(commands: RenderCommands, level: Int, size: IntSize, bind: Bind) = Unit
        override fun unbind() = Unit
        override fun unbind(commands: RenderCommands) = Unit
        override fun resize(width: Int, height: Int) = Unit
        override fun invalidateAttachments() = Unit
        override fun clearAttachment(attachmentIndex: Int, value: Int) = Unit
        override fun getColorAttachmentRendererID(index: Int): Int = colorAttachmentTexture.id
        override fun destroy() = Unit
        override fun setColorAttachmentAt(attachmentIndex: Int) = Unit
    }

    private class FakeTexture(
        override val id: Int,
        size: IntSize,
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
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

    private class FakeShader : Shader {
        override val name: String = "fake"
        override fun bind() = Unit
        override fun bind(shaderBinder: ShaderBinder) = Unit
        override fun unbind() = Unit
        override fun bindUniformBlock(blockName: String, bindingPoint: Int) = Unit
        override fun setInt(name: String, value: Int) = Unit
        override fun setIntArray(name: String, values: IntArray) = Unit
        override fun setFloatArray(name: String, values: FloatArray) = Unit
        override fun setFloat4Array(name: String, values: FloatArray, count: Int) = Unit
        override fun setFloat(name: String, value: Float) = Unit
        override fun setFloat2(name: String, value: FloatFloatPair) = Unit
        override fun setFloat3(name: String, value: Float3) = Unit
        override fun setFloat4(name: String, value: Float4) = Unit
        override fun setMat3(name: String, value: Mat3) = Unit
        override fun setMat4(name: String, value: Mat4) = Unit
        override fun resetUniformCache() = Unit
        override fun destroy() = Unit
    }

    private class RecordingRendererApi : RendererApi {
        override val colorBufferBit: Int = 1
        override val stencilBufferBit: Int = 2
        override val linearTextureFilter: Int = 3
        override val stencilAlways: Int = 4
        override val stencilEqual: Int = 5
        override val stencilKeep: Int = 6
        override val stencilReplace: Int = 7
        val events = mutableListOf<String>()

        override fun init() = Unit
        override fun setClearColor(color: Color) = Unit
        override fun clear() {
            events += "clear"
        }

        override fun enableStencilTest() {
            events += "enableStencilTest"
        }

        override fun disableStencilTest() {
            events += "disableStencilTest"
        }

        override fun stencilFunc(func: Int, ref: Int, mask: Int) = Unit
        override fun stencilOp(sfail: Int, dpfail: Int, dppass: Int) = Unit
        override fun stencilMask(mask: Int) = Unit
        override fun clearStencil(value: Int) = Unit
        override fun enableScissorTest() = Unit
        override fun disableScissorTest() = Unit
        override fun setScissor(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun clear(mask: Int) {
            events += "clear:$mask"
        }

        override fun drawIndexed(vertexArray: VertexArray, indexCount: Int) {
            events += "drawIndexed:$indexCount"
        }

        override fun setViewPort(x: Int, y: Int, width: Int, height: Int) {
            events += "viewport:$x:$y:$width:$height"
        }

        override fun disableDepthTest() = Unit
        override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = Unit

        override fun enableBlending() {
            events += "enableBlending"
        }

        override fun disableBlending() {
            events += "disableBlending"
        }

        override fun bindFramebuffer(bind: Bind, fboId: Int) {
            events += "bindFramebuffer:$bind:$fboId"
        }

        override fun bindDefaultFramebuffer(bind: Bind) {
            events += "bindDefaultFramebuffer:$bind"
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
