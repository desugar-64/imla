/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.opengl

import android.hardware.HardwareBuffer
import android.opengl.EGL14
import android.opengl.GLES30
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import androidx.opengl.EGLExt
import androidx.opengl.EGLImageKHR
import androidx.tracing.trace
import dev.serhiiyaremych.imla.BuildConfig
import dev.serhiiyaremych.imla.internal.ext.checkGlError
import dev.serhiiyaremych.imla.internal.ext.logw
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.stats.HardwareBufferTextureSource
import dev.serhiiyaremych.imla.internal.render.stats.ShaderStats
import java.nio.Buffer
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "OpenGLHardwareBufferTex"
private const val MAX_IMPORT_WARNING_LOGS = 16
private val importWarningLogs = AtomicInteger()

/**
 * OpenGL texture backed by an EGLImage created from a HardwareBuffer.
 */
@RequiresApi(26)
internal class OpenGLHardwareBufferTexture2D private constructor(
    private val eglImage: EGLImageKHR,
    private val eglDisplay: android.opengl.EGLDisplay,
    private val textureId: Int,
    private val source: HardwareBufferTextureSource,
    override val specification: Texture.Specification
) : Texture2D() {
    override val target: Texture.Target = Texture.Target.TEXTURE_2D
    override val width: Int get() = specification.size.width
    override val height: Int get() = specification.size.height
    override var id: Int = textureId
        private set
    override val coordinateOrigin get() = specification.coordinateOrigin
    private var isReleased: Boolean = false

    init {
        ShaderStats.recordHardwareBufferTextureCreated(
            source = source,
            pixels = width.toLong() * height.toLong(),
            bytes = width.toLong() * height.toLong() * RGBA_8888_BYTES_PER_PIXEL
        )
    }

    override fun generateMipMaps() {
        if (specification.generateMips) {
            checkGlError(GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D))
        }
    }

    override fun setData(data: Buffer) {
        throw UnsupportedOperationException("Hardware buffer textures do not support setData")
    }

    override fun bind(slot: Int) {
        checkGlError(GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot))
        checkGlError(GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id))
    }

    override fun destroy() {
        if (isReleased) return
        isReleased = true
        trace("OpenGLHardwareBufferTexture2D#destroy") {
            checkGlError(GLES30.glDeleteTextures(1, intArrayOf(id), 0))
            EGLExt.eglDestroyImageKHR(eglDisplay, eglImage)
            ShaderStats.recordHardwareBufferTextureDestroyed(source)
            id = 0
        }
    }

    override fun isLoaded(): Boolean = true

    companion object {
        /**
         * Creates an OpenGL texture from a HardwareBuffer without taking ownership
         * of that buffer.
         */
        @RequiresApi(26)
        fun createFromBuffer(
            hardwareBuffer: HardwareBuffer,
            sizePx: IntSize,
            coordinateOrigin: dev.serhiiyaremych.imla.internal.render.CoordinateOrigin,
            source: HardwareBufferTextureSource = HardwareBufferTextureSource.Other
        ): OpenGLHardwareBufferTexture2D? {
            return create(
                hardwareBuffer = hardwareBuffer,
                sizePx = sizePx,
                coordinateOrigin = coordinateOrigin,
                source = source
            )
        }

        private fun create(
            hardwareBuffer: HardwareBuffer,
            sizePx: IntSize,
            coordinateOrigin: dev.serhiiyaremych.imla.internal.render.CoordinateOrigin,
            source: HardwareBufferTextureSource
        ): OpenGLHardwareBufferTexture2D? {
            if (hardwareBuffer.isClosed) {
                logw(TAG, "HardwareBuffer is closed, cannot create texture")
                return null
            }

            return trace("OpenGLHardwareBufferTexture2D#createFromBuffer") {
                val eglDisplay = EGL14.eglGetCurrentDisplay()
                val eglImage = trace("OpenGLHardwareBufferTexture2D#createImage") {
                    EGLExt.eglCreateImageFromHardwareBuffer(eglDisplay, hardwareBuffer)
                } ?: run {
                    logImportWarning("eglCreateImageFromHardwareBuffer failed eglError=${EGL14.eglGetError()}")
                    return@trace null
                }

                val textureIds = IntArray(1)
                checkGlError(GLES30.glGenTextures(1, textureIds, 0))
                val textureId = textureIds[0]
                checkGlError(GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId))
                checkGlError(GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE))
                checkGlError(GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE))
                checkGlError(GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR))
                checkGlError(GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR))
                trace("OpenGLHardwareBufferTexture2D#bindImage") {
                    EGLExt.glEGLImageTargetTexture2DOES(GLES30.GL_TEXTURE_2D, eglImage)
                }
                val bindError = GLES30.glGetError()
                if (bindError != GLES30.GL_NO_ERROR) {
                    logImportWarning("glEGLImageTargetTexture2DOES failed glError=$bindError textureId=$textureId")
                    GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                    EGLExt.eglDestroyImageKHR(eglDisplay, eglImage)
                    return@trace null
                }

                val specification = Texture.Specification(
                    size = sizePx,
                    format = Texture.ImageFormat.RGBA8,
                    generateMips = false,
                    coordinateOrigin = coordinateOrigin
                )

                OpenGLHardwareBufferTexture2D(
                    eglImage = eglImage,
                    eglDisplay = eglDisplay,
                    textureId = textureId,
                    source = source,
                    specification = specification
                )
            }
        }

        private const val RGBA_8888_BYTES_PER_PIXEL = 4L
    }
}

private fun logImportWarning(message: String) {
    if (BuildConfig.DEBUG && importWarningLogs.getAndIncrement() < MAX_IMPORT_WARNING_LOGS) {
        Log.w(TAG, message)
    }
}
