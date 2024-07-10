/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.renderer.opengl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.renderer.Texture2D
import java.nio.Buffer

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
        GLES30.glTexParameteri(glTarget, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(glTarget, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(glTarget, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(glTarget, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
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
        if (specification.generateMips) {
            GLES30.glGenerateMipmap(/* target = */ target.toGlTextureTarget())
        }
    }

    private fun createGLTexture(glTarget: Int) {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        id = ids[0]
        GLES30.glBindTexture(glTarget, id)
    }

    override fun setData(data: Buffer) {
        val glTextureTarget = target.toGlTextureTarget()
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
        generateMipMaps()
        _isDataLoaded = true
    }

    override fun bind(slot: Int) = trace("textureBind") {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot)
        GLES30.glBindTexture(target.toGlTextureTarget(), id)
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
        Texture.ImageFormat.RGB8 -> GLES30.GL_RGB8
        Texture.ImageFormat.RGBA8 -> GLES30.GL_RGBA8
        Texture.ImageFormat.DEPTH24STENCIL8 -> GLES30.GL_DEPTH24_STENCIL8
    }
}

internal fun Texture.ImageFormat.getDataType(): Int {
    // Use a when expression to return the corresponding OpenGL type constant
    return when (this) {
        Texture.ImageFormat.None -> 0 // No type
        Texture.ImageFormat.A8 -> GLES30.GL_UNSIGNED_BYTE
        Texture.ImageFormat.R8 -> GLES30.GL_UNSIGNED_BYTE
        Texture.ImageFormat.RGB8 -> GLES30.GL_UNSIGNED_BYTE
        Texture.ImageFormat.RGBA8 -> GLES30.GL_UNSIGNED_BYTE
        Texture.ImageFormat.DEPTH24STENCIL8 -> GLES30.GL_UNSIGNED_INT_24_8
    }
}

internal fun Texture.ImageFormat.toGlImageFormat(): Int {
    return when (this) {
        Texture.ImageFormat.None -> 0
        Texture.ImageFormat.A8 -> GLES30.GL_ALPHA
        Texture.ImageFormat.R8 -> GLES30.GL_RED
        Texture.ImageFormat.RGB8 -> GLES30.GL_RGB
        Texture.ImageFormat.RGBA8 -> GLES30.GL_RGBA
        Texture.ImageFormat.DEPTH24STENCIL8 -> GLES30.GL_DEPTH_STENCIL
    }
}
