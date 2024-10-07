/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.renderer.opengl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import androidx.tracing.trace
import dev.serhiiyaremych.imla.ext.checkGlError
import dev.serhiiyaremych.imla.ext.logd
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import java.nio.Buffer
import kotlin.math.ln

internal class OpenGLTexture2D : Texture2D {
    override val target: Texture.Target
    override val specification: Texture.Specification

    override val width: Int get() = specification.size.width

    override val height: Int get() = specification.size.height
    override var id: Int = 0
        private set
    override val flipTexture: Boolean get() = specification.flipTexture
    private var _isDataLoaded: Boolean = false

    constructor(target: Texture.Target, specification: Texture.Specification) : super() {
        this.target = target
        this.specification = specification
        val glTarget = target.toGlTextureTarget()
        createGLTexture(glTarget)

        if (target != Texture.Target.TEXTURE_EXTERNAL_OES) {
            val maxSize: Int = width.coerceAtLeast(height)
            val maxLevels =
                if (specification.generateMips) (1 + (ln(maxSize.toDouble()) / ln(2.0)).toInt()).coerceAtMost(
                    6
                ) else 1
            logd("OpenGLTexture2D", "create texture: $target, $specification, mipmaps $maxLevels")
            checkGlError(
                GLES30.glTexStorage2D(
                    /* target = */ GLES30.GL_TEXTURE_2D,
                    /* levels = */ maxLevels,
                    /* internalformat = */ specification.format.toGlInternalFormat(),
                    /* width = */ width,
                    /* height = */ height
                )
            )
        }

        checkGlError(
            GLES30.glTexParameteri(
                glTarget,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE
            )
        )
        checkGlError(
            GLES30.glTexParameteri(
                glTarget,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE
            )
        )
        if (target != Texture.Target.TEXTURE_EXTERNAL_OES && specification.mipmapFiltering) {
            checkGlError(
                GLES30.glTexParameteri(
                    glTarget,
                    GLES30.GL_TEXTURE_MIN_FILTER,
                    GLES30.GL_LINEAR_MIPMAP_LINEAR
                )
            )
        } else {
            checkGlError(
                GLES30.glTexParameteri(
                    glTarget,
                    GLES30.GL_TEXTURE_MIN_FILTER,
                    GLES30.GL_LINEAR
                )
            )
        }
        checkGlError(
            GLES30.glTexParameteri(
                glTarget,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
            )
        )
    }

    constructor(
        textureId: Int,
        target: Texture.Target,
        specification: Texture.Specification
    ) : this(target, specification) {
        this.id = textureId
        _isDataLoaded = true
    }

    override fun generateMipMaps() = trace("glGenerateMipmap") {
        if (specification.generateMips && (specification.size.width > 1 || specification.size.height > 1)) {
            checkGlError(GLES30.glGenerateMipmap(/* target = */ target.toGlTextureTarget()))
        }
    }

    private fun createGLTexture(glTarget: Int) {
        val ids = IntArray(1)
        checkGlError(GLES30.glGenTextures(1, ids, 0))
        id = ids[0]
        checkGlError(GLES30.glBindTexture(glTarget, id))
    }

    override fun setData(data: Buffer) {
        val glTextureTarget = target.toGlTextureTarget()
        checkGlError(
            GLES30.glTexSubImage2D(
                /* target = */ glTextureTarget,
                /* level = */ 0,
                /* xoffset = */ 0,
                /* yoffset = */ 0,
                /* width = */ width,
                /* height = */ height,
                /* format = */ specification.format.toGlImageFormat(),
                /* type = */ specification.format.getDataType(),
                /* pixels = */ data
            )
        )
        generateMipMaps()
        _isDataLoaded = true
    }

    override fun bind(slot: Int) = trace("textureBind") {
        checkGlError(GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot))
        checkGlError(GLES30.glBindTexture(target.toGlTextureTarget(), id))
    }

    override fun destroy() {
        GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    override fun isLoaded(): Boolean {
        return _isDataLoaded
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as OpenGLTexture2D

        if (target != other.target) return false
        if (specification != other.specification) return false
        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + specification.hashCode()
        result = 31 * result + id
        return result
    }

    override fun toString(): String {
        return "OpenGLTexture2D(target=$target, specification=$specification, width=$width, height=$height, id=$id)"
    }

    companion object {
        private const val TAG = "OpenGLTexture2D"
    }

}

internal fun Texture.Target.toGlTextureTarget(): Int {
    return when (this) {
        Texture.Target.TEXTURE_2D -> GLES30.GL_TEXTURE_2D
        Texture.Target.TEXTURE_EXTERNAL_OES -> GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    }
}

internal fun Texture.ImageFormat.toGlInternalFormat(): Int {
    return when (this) {
        Texture.ImageFormat.None -> 0
        Texture.ImageFormat.A8,
        Texture.ImageFormat.R8 -> GLES30.GL_R8

        Texture.ImageFormat.R16F -> GLES30.GL_R16F
        Texture.ImageFormat.RGB8 -> GLES30.GL_RGB8
        Texture.ImageFormat.RGBA8 -> GLES30.GL_SRGB8_ALPHA8
        Texture.ImageFormat.RGB10_A2 -> GLES30.GL_RGB10_A2
        Texture.ImageFormat.DEPTH24STENCIL8 -> GLES30.GL_DEPTH24_STENCIL8
    }
}

internal fun Texture.ImageFormat.getDataType(): Int {
    // Use a when expression to return the corresponding OpenGL type constant
    return when (this) {
        Texture.ImageFormat.None -> 0 // No type
        Texture.ImageFormat.A8 -> GLES30.GL_UNSIGNED_BYTE
        Texture.ImageFormat.R8 -> GLES30.GL_UNSIGNED_BYTE
        Texture.ImageFormat.R16F -> GLES30.GL_FLOAT
        Texture.ImageFormat.RGB8 -> GLES30.GL_UNSIGNED_BYTE
        Texture.ImageFormat.RGBA8 -> GLES30.GL_UNSIGNED_BYTE
        Texture.ImageFormat.RGB10_A2 -> GLES30.GL_UNSIGNED_INT_2_10_10_10_REV
        Texture.ImageFormat.DEPTH24STENCIL8 -> GLES30.GL_UNSIGNED_INT_24_8
    }
}

internal fun Texture.ImageFormat.toGlImageFormat(): Int {
    return when (this) {
        Texture.ImageFormat.None -> 0
        Texture.ImageFormat.A8 -> GLES30.GL_ALPHA
        Texture.ImageFormat.R8, Texture.ImageFormat.R16F -> GLES30.GL_RED
        Texture.ImageFormat.RGB8 -> GLES30.GL_RGB
        Texture.ImageFormat.RGBA8, Texture.ImageFormat.RGB10_A2 -> GLES30.GL_RGBA
        Texture.ImageFormat.DEPTH24STENCIL8 -> GLES30.GL_DEPTH_STENCIL
    }
}
