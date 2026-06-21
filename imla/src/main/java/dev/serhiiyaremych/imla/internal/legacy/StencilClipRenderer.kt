/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.ext.logw
import dev.serhiiyaremych.imla.internal.render.BufferLayout
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.GraphicsContext
import dev.serhiiyaremych.imla.internal.render.IndexBuffer
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.ShaderDataType
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.VertexArray
import dev.serhiiyaremych.imla.internal.render.VertexBuffer
import dev.serhiiyaremych.imla.internal.render.addElement
import dev.serhiiyaremych.imla.internal.render.camera.OrthographicCamera
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary

internal enum class StencilClipWriteResult {
    Written,
    Unsupported
}

internal interface StencilClipWriter {
    fun writeTextureToStencil(
        clipTexture: Texture2D,
        transform: Mat4,
        targetSize: IntSize,
        stencilRef: Int
    ): StencilClipWriteResult

    fun enableStencilTest(stencilRef: Int = 1)

    fun disableStencilTest()
}

/**
 * Renders clip shape textures to the stencil buffer for hardware clipping.
 *
 * This uses a hybrid approach:
 * 1. Shape is rendered to a slot-local, top-left R8 texture
 * 2. This renderer draws a quad sampling that texture with the same transform
 *    as the blur quad
 * 3. Fragments with mask value < 0.5 are discarded
 * 4. Surviving fragments write to stencil buffer via glStencilOp
 *
 * Benefits:
 * - Reuses existing Canvas-based shape rendering
 * - Supports arbitrary shapes without geometry generation
 * - Stencil test is synchronous (no frame lag)
 * - Perfect alignment with blur quad (same transform)
 */
internal class StencilClipRenderer(
    private val graphicsContext: GraphicsContext,
    private val shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder
) : StencilClipWriter {
    private val commands = graphicsContext.commands
    private val stateWriter = StencilClipStateWriter(commands)

    private companion object {
        private const val TAG = "StencilClipRenderer"
        private val QUAD_VERTICES = floatArrayOf(
            0f, 0f, -0.5f, -0.5f,
            1f, 0f, 0.5f, -0.5f,
            1f, 1f, 0.5f, 0.5f,
            0f, 1f, -0.5f, 0.5f
        )
        private val QUAD_INDICES = intArrayOf(0, 1, 2, 2, 3, 0)
    }
    private val stencilShader by lazy(LazyThreadSafetyMode.NONE) {
        shaderLibrary.loadShaderFromFile(
            vertFileName = "stencil_clip_quad",
            fragFileName = "stencil_clip_quad"
        ).apply {
            bind(shaderBinder)
            setInt("u_Texture", 0)
        }
    }
    private val quadVertexArray: VertexArray by lazy(LazyThreadSafetyMode.NONE) {
        VertexArray.create().apply {
            val vertexBuffer = VertexBuffer.create(QUAD_VERTICES).apply {
                layout = BufferLayout {
                    addElement("a_TexCoord", ShaderDataType.Float2)
                    addElement("a_Position", ShaderDataType.Float2)
                }
            }
            addVertexBuffer(vertexBuffer)
            indexBuffer = IndexBuffer.create(QUAD_INDICES)
        }
    }

    private val camera: OrthographicCamera = OrthographicCamera(
        left = 0f,
        right = 1f,
        bottom = 1f,
        top = 0f
    )

    /**
     * Writes clip shape from R8 texture to stencil buffer.
     * Must be called with accumulator FBO bound (which has stencil attachment).
     *
     * The quad is drawn at the SAME position/size/rotation as the blur quad
     * to ensure perfect alignment.
     *
     * @param clipTexture R8 texture with [CoordinateOrigin.TOP_LEFT] shape mask
     * (white = inside, black = outside)
     * @param transform Full transform matrix applied to the blur quad
     * @param targetSize Target FBO size for camera projection
     * @param stencilRef Value to write to stencil where texture > 0.5
     */
    override fun writeTextureToStencil(
        clipTexture: Texture2D,
        transform: Mat4,
        targetSize: IntSize,
        stencilRef: Int
    ): StencilClipWriteResult = trace("StencilClipRenderer#writeTextureToStencil") {
        if (!graphicsContext.supportsHardwareStencilClipping()) {
            logw(TAG, "Hardware stencil clipping not supported on this device, clipping disabled")
            return@trace StencilClipWriteResult.Unsupported
        }

        stateWriter.writeTextureToStencil(stencilRef) {
            camera.setProjection(
                left = 0f,
                right = targetSize.width.toFloat(),
                bottom = targetSize.height.toFloat(),
                top = 0f
            )

            stencilShader.bind(shaderBinder)
            stencilShader.setMat4("u_ViewProjection", camera.viewProjectionMatrix)
            stencilShader.setMat4("u_Transform", transform)
            clipTexture.bind(slot = 0)
            quadVertexArray.bind()
            commands.drawIndexed(quadVertexArray, QUAD_INDICES.size)
        }
    }

    /**
     * Enable stencil testing for subsequent draw calls.
     * Call after writeTextureToStencil() to enable clipping.
     *
     * @param stencilRef Reference value that was used in writeTextureToStencil()
     */
    override fun enableStencilTest(stencilRef: Int) {
        commands.enableStencilTest()
        commands.stencilFunc(commands.stencilEqual, stencilRef, 0xFF)
        commands.stencilOp(
            commands.stencilKeep,
            commands.stencilKeep,
            commands.stencilKeep
        )
        commands.stencilMask(0x00)  // Don't modify stencil during blur render
    }

    /**
     * Disable stencil testing.
     * Call after rendering content that should be clipped.
     */
    override fun disableStencilTest() {
        commands.disableStencilTest()
    }
}

internal class StencilClipStateWriter(
    private val commands: RenderCommands
) {
    fun writeTextureToStencil(
        stencilRef: Int,
        drawClipQuad: () -> Unit
    ): StencilClipWriteResult {
        commands.colorMask(red = false, green = false, blue = false, alpha = false)
        try {
            commands.enableStencilTest()
            commands.stencilFunc(commands.stencilAlways, stencilRef, 0xFF)
            commands.stencilOp(
                commands.stencilKeep,
                commands.stencilKeep,
                commands.stencilReplace
            )
            commands.stencilMask(0xFF)
            drawClipQuad()
            return StencilClipWriteResult.Written
        } finally {
            commands.colorMask(red = true, green = true, blue = true, alpha = true)
        }
    }
}
