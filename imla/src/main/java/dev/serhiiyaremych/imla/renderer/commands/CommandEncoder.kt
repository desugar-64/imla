/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.renderer.commands

import android.opengl.GLES30
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.renderer.VertexDataManager
import dev.serhiiyaremych.imla.renderer.framebuffer.FramebufferManager
import dev.serhiiyaremych.imla.renderer.primitive.FramebufferHandle
import dev.serhiiyaremych.imla.renderer.primitive.ShaderProgramHandle
import dev.serhiiyaremych.imla.renderer.primitive.TextureHandle
import dev.serhiiyaremych.imla.renderer.primitive.UniformHandle
import dev.serhiiyaremych.imla.renderer.primitive.VertexArrayHandle
import dev.serhiiyaremych.imla.renderer.shader.ShaderManager


/**
 * Provides a high-level API for recording rendering commands into a CommandBufferPool.
 * Translates rendering intentions into sequences of fundamental RenderCommand objects.
 *
 * @param commandBufferPool The pool where recorded commands will be stored.
 * @param shaderManager Optional manager to look up shader/uniform handles by name/path.
 * @param framebufferManager Optional manager to query FBO properties (like size).
 * @param vertexDataManager Optional manager to query mesh properties (like index count).
 */
internal class CommandEncoder(
    private val commandBufferPool: CommandBufferPool,
    private val shaderManager: ShaderManager? = null,
    private val framebufferManager: FramebufferManager? = null,
    private val vertexDataManager: VertexDataManager? = null
) {

    private fun record(command: RenderCommand) {
        commandBufferPool.record(command)
    }

    fun setRenderTarget(handle: FramebufferHandle, setViewport: Boolean = true) {
        record(SetRenderTargetCommand(handle))
        if (setViewport) {
            val size: IntSize? = if (handle == FramebufferHandle.Default) {
                // Need a way to get default framebuffer size (e.g., from context/surface)
                // Placeholder: Assume a known size or pass it in.
                null // Or get from GLRenderer/Context
            } else {
                framebufferManager?.getFramebufferSize(handle)
            }
            size?.let {
                record(SetViewportCommand(Rect(Offset.Zero, it.toSize())))
            }
        }
    }

    fun setViewport(rect: Rect) {
        record(SetViewportCommand(rect))
    }

    fun clear(color: Color, depth: Float? = null, stencil: Int? = null) {
        record(ClearCommand(color, depth, stencil))
    }

    fun setShader(handle: ShaderProgramHandle) {
        record(SetShaderCommand(handle))
    }

    fun setTexture(unit: Int, handle: TextureHandle) {
        record(SetTextureCommand(unit, handle))
    }

    fun generateMipmaps(textureUnit: Int) {
        record(GenerateMipmapCommand(textureUnit))
    }

    // --- Uniform Setters ---
    fun setUniform(location: UniformHandle, value: Float) = record(SetUniformFloatCommand(location, value))
    fun setUniform(location: UniformHandle, value: Offset) = record(SetUniformVec2Command(location, value))
    fun setUniform(location: UniformHandle, value: Color) = record(SetUniformVec4Command(location, value))
    fun setUniform(location: UniformHandle, value: Int) = record(SetUniformIntCommand(location, value))
    fun setUniform(location: UniformHandle, value: Mat4) = record(SetUniformMat4Command(location, value))
    // Add other uniform types (vec3, mat4, arrays) as needed

    // --- State Setters ---
    fun setBlendState(
        enabled: Boolean,
        srcFactor: Int = GLES30.GL_SRC_ALPHA,
        dstFactor: Int = GLES30.GL_ONE_MINUS_SRC_ALPHA,
        equation: Int = GLES30.GL_FUNC_ADD
    ) {
        record(SetBlendStateCommand(enabled, srcFactor, dstFactor, equation))
    }

    // --- Drawing ---
    fun draw(vaoHandle: VertexArrayHandle, indexCount: Int, instanceCount: Int = 1, indexOffset: Int = 0) {
        if (indexCount > 0) {
            record(DrawCommand(vertexArrayHandle = vaoHandle, indexCount = indexCount, instanceCount = instanceCount))
        } else {
            // Optionally query index count from VertexDataManager if not provided
            vertexDataManager?.getIndexCount(vaoHandle)?.let { count ->
                if (count > 0) {
                    record(DrawCommand(vaoHandle, indexCount = count, instanceCount = instanceCount, indexOffset = indexOffset))
                }
            }
        }
    }

    // Draw a fullscreen quad (assumes a specific VAO handle is known/provided)
    fun drawFullscreenQuad(vaoHandle: VertexArrayHandle) {
        // Assuming the VAO contains 6 indices for a quad
        draw(vaoHandle, 6)
    }

    // --- Blitting ---
    fun blit(src: FramebufferHandle, dst: FramebufferHandle, srcRect: Rect, dstRect: Rect, filter: Int = GLES30.GL_LINEAR) {
        record(BlitFramebufferCommand(src, dst, srcRect, dstRect, GLES30.GL_COLOR_BUFFER_BIT, filter))
    }

    // --- Higher-Level Effect Encoding (Example using previously defined blur logic) ---

    /**
     * Encodes commands for the Dual Kawase Blur effect.
     * Requires necessary shader and uniform handles to be known/passed or looked up.
     */
    fun encodeDualBlur(
        inputFbo: FramebufferHandle,
        outputFbo: FramebufferHandle,
        tempFboHandles: List<FramebufferHandle>,
        passes: Int,
        offsetScale: Float,
        tint: Color,
        // Assumed handles (replace with actual lookup/constants)
        downsampleShaderHandle: ShaderProgramHandle,
        upsampleShaderHandle: ShaderProgramHandle,
        texelUniform: UniformHandle,
        tintUniform: UniformHandle,
        quadVaoHandle: VertexArrayHandle
    ) {
        if (passes <= 0 || tempFboHandles.size < passes + 1) return
        val inputSize = framebufferManager?.getFramebufferSize(inputFbo) ?: return // Need size for blit
        val firstPassSize = framebufferManager?.getFramebufferSize(tempFboHandles[0]) ?: return // Need size for blit

        // 1. Initial Blit
        blit(inputFbo, tempFboHandles[0], Rect(Offset.Zero, inputSize.toSize()), Rect(Offset.Zero, firstPassSize.toSize()), GLES30.GL_LINEAR)

        // 2. Downsample Passes
        setShader(downsampleShaderHandle)
        for (i in 0 until passes) {
            val readFboHandle = tempFboHandles[i]
            val writeFboHandle = tempFboHandles[i + 1]
            val readTextureHandle = framebufferManager?.getFramebufferTexture(readFboHandle) ?: TextureHandle.Invalid
            val writeSize = framebufferManager?.getFramebufferSize(writeFboHandle) ?: return

            setRenderTarget(writeFboHandle, true) // Set RT & viewport
            clear(Color.Transparent)

            val texel = Offset((1f / writeSize.width) * offsetScale, (1f / writeSize.height) * offsetScale)
            setUniform(texelUniform, texel)
            setTexture(0, readTextureHandle)
            drawFullscreenQuad(quadVaoHandle)
        }

        // 3. Upsample Passes
        setShader(upsampleShaderHandle)
        for (i in passes downTo 1) {
            val readFboHandle = tempFboHandles[i]
            val writeFboHandle = if (i == 1) outputFbo else tempFboHandles[i - 1]
            val readTextureHandle = framebufferManager?.getFramebufferTexture(readFboHandle) ?: TextureHandle.Invalid
            val writeSize = framebufferManager?.getFramebufferSize(writeFboHandle) ?: return

            setRenderTarget(writeFboHandle, true) // Set RT & viewport
            clear(Color.Transparent)

            val texel = Offset((1f / writeSize.width) * offsetScale, (1f / writeSize.height) * offsetScale)
            setUniform(texelUniform, texel)
            val currentTint = if (writeFboHandle == outputFbo) tint else Color.Transparent
            setUniform(tintUniform, currentTint)
            setTexture(0, readTextureHandle)
            drawFullscreenQuad(quadVaoHandle)
        }
    }

    // --- Add other encode* methods for PreProcess, Noise, Mask, PostBlend ---
    // (Following the logic outlined in the previous response)
}