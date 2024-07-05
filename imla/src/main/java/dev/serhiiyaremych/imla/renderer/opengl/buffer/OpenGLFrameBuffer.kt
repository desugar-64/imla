/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.opengl.buffer

import android.opengl.GLES30
import android.util.Log
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.renderer.opengl.OpenGLTexture2D
import dev.serhiiyaremych.imla.renderer.opengl.getDataType
import dev.serhiiyaremych.imla.renderer.opengl.toGlImageFormat
import dev.serhiiyaremych.imla.renderer.opengl.toGlInternalFormat
import dev.serhiiyaremych.imla.renderer.opengl.toGlTextureTarget
import dev.serhiiyaremych.imla.renderer.toIntBuffer

internal class OpenGLFramebuffer(
    spec: FramebufferSpecification
) : Framebuffer {
    override var specification: FramebufferSpecification = spec
        private set
    override val colorAttachmentTexture: Texture2D
        get() = _colorAttachmentTexture

    private var rendererId: Int = 0
    private var depthAttachment: Int = 0

    private val colorAttachmentSpecifications: MutableList<FramebufferTextureSpecification> =
        mutableListOf()

    private var _colorAttachmentTexture: Texture2D = OpenGLTexture2D(
        target = Texture.Target.TEXTURE_2D,
        specification = Texture.Specification()
    )
    private var colorAttachmentIds: IntArray = IntArray(0)
    private var depthAttachmentSpecification: FramebufferTextureSpecification? = null

    private val sampledWidth get() = specification.size.width / specification.downSampleFactor
    private val sampledHeight get() = specification.size.height / specification.downSampleFactor

    init {
        invalidate()
    }

    override fun invalidate() {
        if (rendererId != 0) {
            destroy()
        }

        val id = IntArray(1)
        GLES30.glGenFramebuffers(1, id, 0)
        rendererId = id[0]

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rendererId)

        val attachments = specification.attachmentsSpec.attachments

        colorAttachmentIds = IntArray(
            attachments.count { it.format != FramebufferTextureFormat.DEPTH24STENCIL8 }
        )
        val colorAttachments =
            attachments.filter { it.format != FramebufferTextureFormat.DEPTH24STENCIL8 }
        depthAttachmentSpecification =
            attachments.find { it.format == FramebufferTextureFormat.DEPTH24STENCIL8 }
        colorAttachments.forEachIndexed { index, attachment ->
            createAttachment(
                width = sampledWidth,
                height = sampledHeight,
                format = attachment.format,
                flip = attachment.flip
            ).apply {
                colorAttachmentIds[index] = this.id
                GLES30.glFramebufferTexture2D(
                    /* target = */ GLES30.GL_FRAMEBUFFER,
                    /* attachment = */ GLES30.GL_COLOR_ATTACHMENT0 + index,
                    /* textarget = */ this.target.toGlTextureTarget(),
                    /* texture = */ this.id,
                    /* level = */ 0
                )
            }
        }

        if (_colorAttachmentTexture.id != colorAttachmentIds[0]) {
            _colorAttachmentTexture.destroy()
            _colorAttachmentTexture = Texture2D.create(
                target = Texture.Target.TEXTURE_2D,
                textureId = colorAttachmentIds[0],
                specification = Texture.Specification(
                    size = IntSize(sampledWidth, sampledHeight),
                    flipTexture = colorAttachments[0].flip
                )
            )

        }

        depthAttachmentSpecification?.let {
            createAttachment(
                width = sampledWidth,
                height = sampledHeight,
                format = FramebufferTextureFormat.DEPTH24STENCIL8,
                flip = it.flip
            ).apply {
                depthAttachment = this.id
                GLES30.glFramebufferTexture2D(
                    /* target = */ GLES30.GL_FRAMEBUFFER,
                    /* attachment = */ GLES30.GL_DEPTH_ATTACHMENT,
                    /* textarget = */ GLES30.GL_TEXTURE_2D,
                    /* texture = */ depthAttachment,
                    /* level = */ 0
                )
            }
        }

        if (colorAttachments.isNotEmpty()) {
            val buffers: IntArray = IntArray(colorAttachments.size) {
                GLES30.GL_COLOR_ATTACHMENT0 + it
            }
            GLES30.glDrawBuffers(colorAttachments.size, buffers, 0);
        } else {
            // Only depth-pass
            GLES30.glDrawBuffers(0, intArrayOf(), 0)
        }

        GLES30.glFramebufferTexture2D(
            /* target = */ GLES30.GL_FRAMEBUFFER,
            /* attachment = */ GLES30.GL_COLOR_ATTACHMENT0,
            /* textarget = */ GLES30.GL_TEXTURE_2D,
            /* texture = */ _colorAttachmentTexture.id,
            /* level = */ 0
        )

        val buffers: IntArray = IntArray(1) {
            GLES30.GL_COLOR_ATTACHMENT0
        }
        GLES30.glDrawBuffers(1, buffers, 0);

        require(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "OpenGL20Framebuffer is incomplete!"
        }

        // switchback to default framebuffer
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    override fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rendererId)
        GLES30.glViewport(0, 0, sampledWidth, sampledHeight)
    }

    override fun unbind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    override fun resize(width: Int, height: Int) {
        if (width == 0 || height == 0 || width > MAX_FRAMEBUFFER_SIZE || height > MAX_FRAMEBUFFER_SIZE) {
            Log.w(TAG, "Attempt to resize framebuffer to $width, $height failed")
            return
        }
        if (specification.size.width != width || specification.size.height != height) {
            specification = specification.copy(
                size = IntSize(width, height)
            )
            invalidate()
        }
    }

    override fun getColorAttachmentRendererID(index: Int): Int {
        require(index <= colorAttachmentIds.lastIndex)
        return colorAttachmentIds[index]
    }

    override fun clearAttachment(attachmentIndex: Int, value: Int) {
        val attachmentHandle = getColorAttachmentRendererID(attachmentIndex)
        val spec = colorAttachmentSpecifications[attachmentIndex]
        val textureFormat = spec.format
        val type = when (textureFormat) {
            FramebufferTextureFormat.RGBA8, FramebufferTextureFormat.R8 -> GLES30.GL_UNSIGNED_BYTE
            FramebufferTextureFormat.DEPTH24STENCIL8 -> GLES30.GL_UNSIGNED_INT_24_8
        }
        val components = when (textureFormat) {
            FramebufferTextureFormat.R8 -> 1
            FramebufferTextureFormat.RGBA8 -> 4
            FramebufferTextureFormat.DEPTH24STENCIL8 -> 1
        }
        val emptyPixels = IntArray(
            size = sampledWidth * sampledHeight * components,
            init = { value }
        ).toIntBuffer()

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, attachmentHandle)

        GLES30.glTexSubImage2D(
            /* target = */ GLES30.GL_TEXTURE_2D,
            /* level = */ 0,
            /* xoffset = */ 0,
            /* yoffset = */ 0,
            /* width = */ sampledWidth,
            /* height = */ sampledHeight,
            /* format = */ fbTextureFormatToGL(textureFormat),
            /* type = */ type,
            /* pixels = */ emptyPixels
        )
    }


    override fun destroy() {
        unbind()
        GLES30.glDeleteFramebuffers(1, intArrayOf(rendererId), 0)
        GLES30.glDeleteTextures(1, intArrayOf(depthAttachment), 0)
        GLES30.glDeleteTextures(colorAttachmentIds.size, colorAttachmentIds, 0)
    }

    private companion object {
        private const val TAG = "OpenGLFramebuffer"
        const val MAX_FRAMEBUFFER_SIZE = 8192

        fun fbTextureFormatToGL(format: FramebufferTextureFormat): Int {
            return when (format) {
                FramebufferTextureFormat.R8 -> GLES30.GL_R8
                FramebufferTextureFormat.RGBA8 -> GLES30.GL_RGBA8
                FramebufferTextureFormat.DEPTH24STENCIL8 -> GLES30.GL_DEPTH24_STENCIL8
            }
        }

        fun createAttachment(
            width: Int,
            height: Int,
            format: FramebufferTextureFormat,
            flip: Boolean
        ): Texture = Texture2D.create(
            target = Texture.Target.TEXTURE_2D,
            specification = Texture.Specification(
                size = IntSize(width = width, height = height),
                format = format.toTextureFormat(),
                flipTexture = flip
            )
        ).apply {
            bind()

            // @formatter:off
            GLES30.glTexImage2D(
                /* target = */ GLES30.GL_TEXTURE_2D,
                /* level = */ 0,
                /* internalformat = */ specification.format.toGlInternalFormat(),
                /* width = */ width,
                /* height = */ height,
                /* border = */ 0,
                /* format = */ specification.format.toGlImageFormat(),
                /* type = */ specification.format.getDataType(),
                /* pixels = */ null
            )
        }
    }
}

private fun FramebufferTextureFormat.toTextureFormat(): Texture.ImageFormat {
    return when (this) {
        FramebufferTextureFormat.R8 -> Texture.ImageFormat.R8
        FramebufferTextureFormat.RGBA8 -> Texture.ImageFormat.RGBA8
        FramebufferTextureFormat.DEPTH24STENCIL8 -> Texture.ImageFormat.DEPTH24STENCIL8
    }
}
