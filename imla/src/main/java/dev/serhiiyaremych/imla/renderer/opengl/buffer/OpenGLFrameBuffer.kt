/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.opengl.buffer

import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.renderer.opengl.OpenGLTexture2D
import dev.serhiiyaremych.imla.renderer.opengl.toGlImageFormat
import dev.serhiiyaremych.imla.renderer.opengl.toGlInternalFormat
import dev.serhiiyaremych.imla.renderer.toIntBuffer

internal class OpenGLFramebuffer(
    spec: FramebufferSpecification
) : Framebuffer {
    override var specification: FramebufferSpecification = spec
        private set
    override val colorAttachmentTexture: Texture2D
        get() = _colorAttachmentTexture

    private var rendererId: Int = 0

    private var _colorAttachmentTexture: Texture2D = OpenGLTexture2D(
        target = Texture.Target.TEXTURE_2D,
        specification = Texture.Specification()
    )

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


        _colorAttachmentTexture = Texture2D.create(
            target = Texture.Target.TEXTURE_2D,
            specification = Texture.Specification(
                size = IntSize(width = sampledWidth, height = sampledHeight),
                format = specification.attachmentsSpec.attachments.first().format.toTextureFormat(), // todo: remove and migrate for OpenGL ES 2 FBs?
                flipTexture = specification.attachmentsSpec.attachments.first().flip
            )
        )
        _colorAttachmentTexture.bind()
        // @formatter:off
        GLES30.glTexImage2D(
            /* target = */ GLES30.GL_TEXTURE_2D,
            /* level = */ 0,
            /* internalformat = */ _colorAttachmentTexture.specification.format.toGlInternalFormat(),
            /* width = */ sampledWidth,
            /* height = */ sampledHeight,
            /* border = */ 0,
            /* format = */ _colorAttachmentTexture.specification.format.toGlImageFormat(),
            /* type = */ GLES30.GL_UNSIGNED_BYTE,
            /* pixels = */ null
        )
        // @formatter:on
        GLES30.glFramebufferTexture2D(
            /* target = */ GLES30.GL_FRAMEBUFFER,
            /* attachment = */ GLES30.GL_COLOR_ATTACHMENT0,
            /* textarget = */ GLES30.GL_TEXTURE_2D,
            /* texture = */ _colorAttachmentTexture.id,
            /* level = */ 0
        )

        val buffers: IntArray = IntArray(1) {
            GLES31.GL_COLOR_ATTACHMENT0
        }
        GLES31.glDrawBuffers(1, buffers, 0);

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
        // no-op
        return 0
    }

    override fun clearAttachment(attachmentIndex: Int, value: Int) {
        val textureFormat = specification.attachmentsSpec.attachments.first().format
        val type = when (textureFormat) {
            FramebufferTextureFormat.None -> 0
            FramebufferTextureFormat.RGBA8, FramebufferTextureFormat.R8 -> GLES30.GL_UNSIGNED_BYTE
            else -> error("OpenGL20Framebuffer: Unsupported attachment format: $textureFormat")
        }
        val components = when (textureFormat) {
            FramebufferTextureFormat.None -> 0
            FramebufferTextureFormat.R8 -> 1
            FramebufferTextureFormat.RGBA8 -> 4
            else -> error("OpenGL20Framebuffer: Unsupported attachment format: $textureFormat")
        }
        val emptyPixels = IntArray(
            size = sampledWidth * sampledHeight * components,
            init = { value }
        ).toIntBuffer()
        _colorAttachmentTexture.bind()

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
        _colorAttachmentTexture.destroy()
    }

    private companion object {
        private const val TAG = "OpenGLFramebuffer"
        const val MAX_FRAMEBUFFER_SIZE = 8192

        fun fbTextureFormatToGL(format: FramebufferTextureFormat): Int {
            return when (format) {
                FramebufferTextureFormat.RGBA8 -> GLES30.GL_RGBA8
                FramebufferTextureFormat.None -> 0
                else -> error("OpenGL20Framebuffer: Unsupported attachment format: $format")
            }
        }

    }
}

private fun FramebufferTextureFormat.toTextureFormat(): Texture.ImageFormat {
    return when (this) {
        FramebufferTextureFormat.None -> Texture.ImageFormat.None
        FramebufferTextureFormat.R8 -> Texture.ImageFormat.R8
        FramebufferTextureFormat.RGBA8 -> Texture.ImageFormat.RGBA8
        FramebufferTextureFormat.DEPTH24STENCIL8 -> TODO()
    }
}
