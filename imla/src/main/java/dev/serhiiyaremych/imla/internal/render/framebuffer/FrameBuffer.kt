/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.internal.render.framebuffer

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.opengl.buffer.OpenGLFramebuffer

internal enum class Bind {
    READ, DRAW, BOTH
}

internal enum class FramebufferTextureFormat {
    // bw masks, noise
    R8,

    // Color
    RGBA8,
    SRGB8_ALPHA8,  // sRGB with hardware gamma correction (for UI content only)
    RGB10_A2,

    // Depth/stencil
    DEPTH24STENCIL8,
}

internal data class FramebufferTextureSpecification(
    val format: FramebufferTextureFormat = FramebufferTextureFormat.RGBA8,
    val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT,
    val mipmapFiltering: Boolean = false
) {
    @Deprecated(
        "Use coordinateOrigin",
        ReplaceWith("coordinateOrigin == CoordinateOrigin.TOP_LEFT")
    )
    val flip: Boolean get() = coordinateOrigin == CoordinateOrigin.TOP_LEFT
}

internal data class FramebufferAttachmentSpecification(
    val attachments: List<FramebufferTextureSpecification> = listOf(
        FramebufferTextureSpecification(format = FramebufferTextureFormat.RGBA8),
    ),
    /**
     * When true, creates a GL_DEPTH24_STENCIL8 renderbuffer (not texture) and attaches it
     * to GL_DEPTH_STENCIL_ATTACHMENT for hardware stencil clipping.
     * Renderbuffer is preferred over texture for TBDR performance on mobile GPUs.
     */
    val useStencilRenderbuffer: Boolean = false
) {
    companion object {
        fun singleColor(
            format: FramebufferTextureFormat = FramebufferTextureFormat.RGBA8,
            mipmapFiltering: Boolean = false,
            coordinateOrigin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
        ): FramebufferAttachmentSpecification {
            return FramebufferAttachmentSpecification(
                attachments = listOf(
                    FramebufferTextureSpecification(
                        format = format,
                        mipmapFiltering = mipmapFiltering,
                        coordinateOrigin = coordinateOrigin
                    )
                )
            )
        }

        /**
         * Creates a color attachment with an attached depth-stencil renderbuffer.
         * Use this for FBOs that need hardware stencil clipping.
         */
        fun withStencil(
            colorFormat: FramebufferTextureFormat = FramebufferTextureFormat.RGBA8,
            coordinateOrigin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
        ): FramebufferAttachmentSpecification {
            return FramebufferAttachmentSpecification(
                attachments = listOf(
                    FramebufferTextureSpecification(
                        format = colorFormat,
                        coordinateOrigin = coordinateOrigin,
                        mipmapFiltering = false
                    )
                ),
                useStencilRenderbuffer = true
            )
        }

        /**
         * Creates a color attachment with stencil renderbuffer and mipmap filtering.
         * Use this for FBOs that need both stencil clipping and mipmap-based effects.
         */
        fun withStencilAndMipmaps(
            colorFormat: FramebufferTextureFormat = FramebufferTextureFormat.RGBA8,
            coordinateOrigin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
        ): FramebufferAttachmentSpecification {
            return FramebufferAttachmentSpecification(
                attachments = listOf(
                    FramebufferTextureSpecification(
                        format = colorFormat,
                        coordinateOrigin = coordinateOrigin,
                        mipmapFiltering = true
                    )
                ),
                useStencilRenderbuffer = true
            )
        }
    }
}

internal data class FramebufferSpecification(
    val size: IntSize,
    val attachmentsSpec: FramebufferAttachmentSpecification,
    val downSampleFactor: Int = 1,
)

internal interface Framebuffer {
    val rendererId: Int
    val specification: FramebufferSpecification
    val colorAttachmentTexture: Texture2D

    fun invalidate()
    fun bind(bind: Bind = Bind.BOTH, updateViewport: Boolean = true)
    fun bind(commands: RenderCommands, bind: Bind = Bind.BOTH, updateViewport: Boolean = true)
    fun bindForOverwrite(bind: Bind = Bind.DRAW)
    fun bindForOverwrite(commands: RenderCommands, bind: Bind = Bind.DRAW)
    fun bindForMipLevel(level: Int, size: IntSize, bind: Bind = Bind.DRAW)
    fun bindForMipLevel(commands: RenderCommands, level: Int, size: IntSize, bind: Bind = Bind.DRAW)
    fun unbind()
    fun unbind(commands: RenderCommands)
    fun resize(width: Int, height: Int)

    fun invalidateAttachments()
    fun clearAttachment(attachmentIndex: Int, value: Int)
    fun getColorAttachmentRendererID(index: Int = 0): Int

    fun destroy()
    fun setColorAttachmentAt(attachmentIndex: Int)

    companion object {
        fun create(
            spec: FramebufferSpecification,
            commands: RenderCommands = RenderCommand.commands
        ): Framebuffer {
            return OpenGLFramebuffer(spec, commands)
        }
    }
}
