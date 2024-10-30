/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.renderer

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.renderer.opengl.OpenGLTexture2D
import java.nio.Buffer

internal interface Texture {
    enum class Target {
        TEXTURE_2D,
        TEXTURE_EXTERNAL_OES,
        //TEXTURE_2D_ARRAY // do I need it?
    }

    enum class ImageFormat {
        None,
        A8,
        R8,
        R16F,
        RGB8,
        RGBA8,
        RGB10_A2,
        DEPTH24STENCIL8
    }

    data class Specification(
        val size: IntSize = IntSize(1, 1),
        val format: ImageFormat = ImageFormat.RGBA8,
        val generateMips: Boolean = false,
        var flipTexture: Boolean = false,
        val mipmapFiltering: Boolean = false
    )


    val id: Int
    val target: Target
    val width: Int
    val height: Int
    val flipTexture: Boolean
    val specification: Specification

    fun bind(slot: Int = 0)
    fun setData(data: Buffer)
    fun isLoaded(): Boolean
    fun destroy()
}

internal abstract class Texture2D : Texture {

    companion object {
        fun create(target: Texture.Target, specification: Texture.Specification): Texture2D {
            return OpenGLTexture2D(target, specification)
        }

        fun create(
            target: Texture.Target,
            textureId: Int,
            specification: Texture.Specification
        ): Texture2D {
            return OpenGLTexture2D(textureId, target, specification)
        }
    }

    abstract fun generateMipMaps()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Texture2D
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}