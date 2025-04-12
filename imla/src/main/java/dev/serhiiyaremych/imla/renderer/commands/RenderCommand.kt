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
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.renderer.framebuffer.Bind
import dev.serhiiyaremych.imla.renderer.primitive.FramebufferHandle
import dev.serhiiyaremych.imla.renderer.primitive.ShaderProgramHandle
import dev.serhiiyaremych.imla.renderer.primitive.TextureHandle
import dev.serhiiyaremych.imla.renderer.primitive.UniformHandle
import dev.serhiiyaremych.imla.renderer.primitive.VertexArrayHandle

internal sealed interface RenderCommand

internal data class SetRenderTargetCommand(
    val framebufferHandle: FramebufferHandle,
    val bind: Bind = Bind.BOTH,
    val viewportRect: Rect? = null
) : RenderCommand

internal data class ClearCommand(
    val color: Color = Color.Transparent,
    val depth: Float? = null,
    val stencil: Int? = null
) : RenderCommand

internal data class SetShaderCommand(
    val shaderProgramHandle: ShaderProgramHandle
) : RenderCommand

internal data class SetUniformFloatCommand(val location: UniformHandle, val value: Float) :
    RenderCommand

internal data class SetUniformVec2Command(val location: UniformHandle, val value: Offset) :
    RenderCommand

internal data class SetUniformVec4Command(val location: UniformHandle, val value: Color) :
    RenderCommand
// ... other uniform types (Mat4, Int, Sampler Index etc.)

internal data class SetTextureCommand(
    val textureUnit: Int,
    val textureHandle: TextureHandle,
    val target: Int = GLES30.GL_TEXTURE_2D
) : RenderCommand

internal class SetBlendStateCommand(
    val enabled: Boolean,
    // Common blend factors (using GLES constants)
    val srcFactor: Int = GLES30.GL_SRC_ALPHA,
    val dstFactor: Int = GLES30.GL_ONE_MINUS_SRC_ALPHA,
    val equation: Int = GLES30.GL_FUNC_ADD
) : RenderCommand

internal data class BlitFramebufferCommand(
    val srcFramebuffer: FramebufferHandle,
    val dstFramebuffer: FramebufferHandle,
    val srcRect: Rect,
    val dstRect: Rect,
    val mask: Int = GLES30.GL_COLOR_BUFFER_BIT,
    val filter: Int = GLES30.GL_LINEAR
) : RenderCommand

internal data class SetViewportCommand(
    val viewportRect: Rect
) : RenderCommand

internal data class GenerateMipmapCommand(
    val textureUnit: Int,
    val target: Int = GLES30.GL_TEXTURE_2D
) : RenderCommand

internal data class SetUniformIntCommand(
    val location: UniformHandle,
    val value: Int
) : RenderCommand


internal data class SetUniformIntArrayCommand(
    val location: UniformHandle,
    val values: IntArray
) : RenderCommand {
    // Override equals/hashCode for proper comparison of array content
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SetUniformIntArrayCommand
        if (location != other.location) return false
        if (!values.contentEquals(other.values)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = location.hashCode()
        result = 31 * result + values.contentHashCode()
        return result
    }
}

internal data class SetUniformMat4Command(
    val location: UniformHandle,
    val value: Mat4, // Assumes Mat4 type exists
    val transpose: Boolean = false // OpenGL expects column-major, transpose if matrix is row-major
) : RenderCommand

internal data class DrawCommand(
    val vertexArrayHandle: VertexArrayHandle,
    val indexCount: Int,
    val instanceCount: Int = 1, // For instanced drawing if needed later
    val indexOffset: Int = 0,
    val primitiveType: Int = GLES30.GL_TRIANGLES
) : RenderCommand
