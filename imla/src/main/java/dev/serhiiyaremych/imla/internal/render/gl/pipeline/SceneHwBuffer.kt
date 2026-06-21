/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl.pipeline

import android.annotation.SuppressLint
import android.hardware.HardwareBuffer
import android.opengl.GLES30
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.opengl.OpenGLHardwareBufferTexture2D
import dev.serhiiyaremych.imla.internal.render.stats.HardwareBufferTextureSource

@RequiresApi(26)
internal class SceneHwBuffer private constructor(
    val hardwareBuffer: HardwareBuffer,
    val texture: Texture2D,
    val fboId: Int,
    private val stencilRboId: Int
) {
    fun bindForDraw() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
    }

    fun close() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        if (stencilRboId != 0) {
            GLES30.glDeleteRenderbuffers(1, intArrayOf(stencilRboId), 0)
        }
        texture.destroy()
        hardwareBuffer.close()
    }

    companion object {
        // AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY is required for ASurfaceTransaction#setBuffer.
        // The SDK constant (HardwareBuffer.USAGE_COMPOSER_OVERLAY) is API 33+, so we pass the raw
        // value; it is valid from API 29. @SuppressLint("WrongConstant") because the literal is not
        // one of the recognised HardwareBuffer.USAGE_* @IntDef values at this compile SDK.
        private const val USAGE_COMPOSER_OVERLAY: Long = 2048L

        @SuppressLint("WrongConstant")
        fun create(size: IntSize): SceneHwBuffer {
            val hwBuffer = HardwareBuffer.create(
                size.width,
                size.height,
                HardwareBuffer.RGBA_8888,
                1,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    USAGE_COMPOSER_OVERLAY
            )
            val texture = checkNotNull(
                OpenGLHardwareBufferTexture2D.createFromBuffer(
                    hardwareBuffer = hwBuffer,
                    sizePx = size,
                    coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT,
                    source = HardwareBufferTextureSource.Other
                )
            ) { "Failed to import HardwareBuffer as GL texture (${size.width}x${size.height})" }

            val fbos = IntArray(1)
            GLES30.glGenFramebuffers(1, fbos, 0)
            val fboId = fbos[0]

            val rbos = IntArray(1)
            GLES30.glGenRenderbuffers(1, rbos, 0)
            val stencilRboId = rbos[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, stencilRboId)
            GLES30.glRenderbufferStorage(
                GLES30.GL_RENDERBUFFER,
                GLES30.GL_DEPTH24_STENCIL8,
                size.width,
                size.height
            )
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                texture.id,
                0
            )
            GLES30.glFramebufferRenderbuffer(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_DEPTH_STENCIL_ATTACHMENT,
                GLES30.GL_RENDERBUFFER,
                stencilRboId
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            return SceneHwBuffer(
                hardwareBuffer = hwBuffer,
                texture = texture,
                fboId = fboId,
                stencilRboId = stencilRboId
            )
        }
    }
}
