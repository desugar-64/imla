/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.opengl.buffer

import android.opengl.GLES30
import android.util.Log
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Bind.BOTH
import dev.serhiiyaremych.imla.renderer.Bind.DRAW
import dev.serhiiyaremych.imla.renderer.Bind.READ
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.renderer.opengl.OpenGLTexture2D
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

    override var rendererId: Int = 0
        private set
    private var depthAttachment: Int = 0

    private val colorAttachmentSpecifications: MutableList<FramebufferTextureSpecification> =
        mutableListOf()

    private var _colorAttachmentTexture: Texture2D = OpenGLTexture2D(
        target = Texture.Target.TEXTURE_2D,
        specification = Texture.Specification()
    )
    private var drawAttachments: IntArray = IntArray(0)
    private val colorAttachments: MutableList<Texture2D> = mutableListOf()
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
        checkGlError("bind fb")
        val attachments = specification.attachmentsSpec.attachments

        val colorAttachmentSpecs =
            attachments.filter { it.format != FramebufferTextureFormat.DEPTH24STENCIL8 }
        depthAttachmentSpecification =
            attachments.find { it.format == FramebufferTextureFormat.DEPTH24STENCIL8 }

        colorAttachmentSpecs.forEachIndexed { index, attachment ->
            createAttachment(
                width = sampledWidth,
                height = sampledHeight,
                format = attachment.format,
                flip = attachment.flip
            ).apply {
                colorAttachments.add(this)
                GLES30.glFramebufferTexture2D(
                    /* target = */ GLES30.GL_FRAMEBUFFER,
                    /* attachment = */ GLES30.GL_COLOR_ATTACHMENT0 + index,
                    /* textarget = */ this.target.toGlTextureTarget(),
                    /* texture = */ this.id,
                    /* level = */ 0
                )
                checkGlError("attach tex $index")
            }
        }

        if (_colorAttachmentTexture.id != colorAttachments.first().id) {
            _colorAttachmentTexture.destroy()
            _colorAttachmentTexture = colorAttachments.first()
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

                checkGlError("attach depth")
            }
        }

        if (colorAttachmentSpecs.isNotEmpty()) {
            val buffers: IntArray = IntArray(colorAttachmentSpecs.size) {
                GLES30.GL_COLOR_ATTACHMENT0 + it
            }
            GLES30.glDrawBuffers(colorAttachmentSpecs.size, buffers, 0)
            drawAttachments = buffers
            checkGlError("glDrawBuffers all")
        } else {
            // Only depth-pass
            GLES30.glDrawBuffers(0, intArrayOf(), 0)
        }

        require(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "OpenGL20Framebuffer is incomplete!"
        }

        // switchback to default framebuffer
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    override fun readBuffer(attachmentIndex: Int) {
        GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0 + attachmentIndex)
    }

    override fun bind(bind: Bind) = trace("glBindFramebuffer") {
        GLES30.glBindFramebuffer(bind.toGlTarget(), rendererId)
        GLES30.glViewport(0, 0, sampledWidth, sampledHeight)
    }

    override fun unbind() = trace("glUnBindFramebuffer") {
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
        require(index <= colorAttachments.lastIndex)
        return colorAttachments[index].id
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

    override fun setColorAttachmentAt(attachmentIndex: Int) {
        require(attachmentIndex <= colorAttachments.lastIndex)
        _colorAttachmentTexture = colorAttachments[attachmentIndex]
    }

    override fun destroy() {
        unbind()
        GLES30.glDeleteFramebuffers(1, intArrayOf(rendererId), 0)
        GLES30.glDeleteTextures(1, intArrayOf(depthAttachment), 0)
        GLES30.glDeleteTextures(
            colorAttachments.size,
            colorAttachments.map { it.id }.toIntArray(),
            0
        )
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
        ): Texture2D = Texture2D.create(
            target = Texture.Target.TEXTURE_2D,
            specification = Texture.Specification(
                size = IntSize(width = width, height = height),
                format = format.toTextureFormat(),
                flipTexture = flip
            )
        ).apply {
            // @formatter:off
            GLES30.glTexStorage2D(
                /* target = */ GLES30.GL_TEXTURE_2D,
                /* levels = */ 4,
                /* internalformat = */ specification.format.toGlInternalFormat(),
                /* width = */ width,
                /* height = */ height
            )
            // @formatter:on
            checkGlError("glTexImage2D")
        }

        fun checkGlError(operation: String) {
            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) {
//                throw RuntimeException("$operation: glError $error")
                Log.e(TAG, "checkGlError: glError $error")
            }
        }

    }
}

internal fun Bind.toGlTarget(): Int {
    return when (this) {
        READ -> GLES30.GL_READ_FRAMEBUFFER
        DRAW -> GLES30.GL_DRAW_FRAMEBUFFER
        BOTH -> GLES30.GL_FRAMEBUFFER
    }
}

private fun FramebufferTextureFormat.toTextureFormat(): Texture.ImageFormat {
    return when (this) {
        FramebufferTextureFormat.R8 -> Texture.ImageFormat.R8
        FramebufferTextureFormat.RGBA8 -> Texture.ImageFormat.RGBA8
        FramebufferTextureFormat.DEPTH24STENCIL8 -> Texture.ImageFormat.DEPTH24STENCIL8
    }
}
