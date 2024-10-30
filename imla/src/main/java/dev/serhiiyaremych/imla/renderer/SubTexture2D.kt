/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import dev.serhiiyaremych.imla.uirenderer.processing.SimpleQuadRenderer

internal class SubTexture2D(
    val texture: Texture2D
) : Texture by texture {
    val texCoords: Array<Offset> = Array(4) { index ->
        SimpleQuadRenderer.defaultTextureCoords[index]
    }

    var subTextureSize: IntSize = IntSize.Zero
        private set

    constructor(texture: Texture2D, min: Offset, max: Offset) : this(texture) {
        texCoords[0] = min
        texCoords[1] = Offset(max.x, min.y)
        texCoords[2] = Offset(max.x, max.y)
        texCoords[3] = Offset(min.x, max.y)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SubTexture2D

        if (texture != other.texture) return false
        if (!texCoords.contentEquals(other.texCoords)) return false
        if (subTextureSize != other.subTextureSize) return false

        return true
    }

    override fun hashCode(): Int {
        var result = texture.hashCode()
        result = 31 * result + texCoords.contentHashCode()
        result = 31 * result + subTextureSize.hashCode()
        return result
    }

    companion object {
        fun createFromCoords(
            texture: Texture2D,
            rect: Rect
        ): SubTexture2D {
            val texLeft: Float = rect.left / texture.width
            val texRight: Float = (rect.right) / texture.width
            val texTop: Float = 1.0f - (rect.top / texture.height)
            val texBottom: Float = 1.0f - ((rect.bottom) / texture.height)

            val min = Offset(x = texLeft, y = texTop)
            val max = Offset(x = texRight, y = texBottom)

            return SubTexture2D(texture = texture, min = min, max = max).apply {
                subTextureSize = rect.size.toIntSize()
            }
        }
    }
}