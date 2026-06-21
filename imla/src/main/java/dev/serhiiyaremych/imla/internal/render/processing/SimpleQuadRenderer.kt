/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.SimpleRenderer
import dev.serhiiyaremych.imla.internal.render.SubTexture2D
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.VertexBuffer
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.toFloatBuffer
import java.nio.FloatBuffer

internal class SimpleQuadRenderer(
    shaderLibrary: ShaderLibrary,
    val renderer: SimpleRenderer,
    val shaderBinder: ShaderBinder
) {
    private var vbo: VertexBuffer? = null
    internal val simpleQuadShader by lazy(LazyThreadSafetyMode.NONE) {
        shaderLibrary
            .loadShaderFromFile(vertFileName = "simple_quad", fragFileName = "simple_quad")
            .apply {
                bindUniformBlock(
                    SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
                    SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
                )
            }
    }
    private val simpleDataCache: FloatBuffer by lazy(LazyThreadSafetyMode.NONE) {
        FloatArray(renderer.data.textureDataUBO.elements).toFloatBuffer()
    }

    private val texCoord: Array<Offset> = Array(4) { Offset.Unspecified }
    // Flat float array: [u0, v0, u1, v1, u2, v2, u3, v3] - avoids Offset boxing.
    // IMPORTANT: do not store caller-provided array references here; many call sites reuse and mutate
    // a single FloatArray for performance, so we must keep our own copy for change detection.
    private val texCoordFlat = FloatArray(8) { -1.0f }  // Sentinel to detect first upload
    private var flipY: Boolean = false
    private var alpha: Float = -1.0f

    fun draw(
        shader: Shader = simpleQuadShader,
        texture: Texture? = null,
        alpha: Float = 1.0f,
        flipY: Boolean? = null,
        tint: Color = Color.Transparent
    ) =
        trace("SimpleQuadRenderer#draw") {
            renderer.data.vao.bind()
            vbo?.bind()
            shader.bind(shaderBinder)
            renderer.updateTint(packTint(tint))
            if (texture != null) {
                texture.bind()
                uploadTextureDataIfNeed(
                    alpha = alpha,
                    flipTexture = flipY ?: texture.flipTexture,
                    textureCoordinates = getTextureCoordinates(texture)
                )
            }
            renderer.flush()
        }

    fun draw(
        shader: Shader = simpleQuadShader,
        texture: Texture2D? = null,
        textureCoordinates: Array<Offset>? = null,
        alpha: Float = 1.0f,
        flipY: Boolean? = null,
        tint: Color = Color.Transparent
    ) =
        trace("SimpleQuadRenderer#draw") {
            renderer.data.vao.bind()
            vbo?.bind()
            shader.bind(shaderBinder)
            renderer.updateTint(packTint(tint))
            if (texture != null) {
                texture.bind()
                uploadTextureDataIfNeed(
                    alpha,
                    flipY ?: texture.flipTexture,
                    textureCoordinates ?: getTextureCoordinates(texture)
                )
            }
            renderer.flush()
        }

    fun draw(
        shader: Shader = simpleQuadShader,
        texture: Texture2D? = null,
        textureCoordinatesFlat: FloatArray? = null,
        alpha: Float = 1.0f,
        flipY: Boolean? = null,
        tint: Color = Color.Transparent
    ) =
        trace("SimpleQuadRenderer#draw") {
            renderer.data.vao.bind()
            vbo?.bind()
            shader.bind(shaderBinder)
            renderer.updateTint(packTint(tint))
            if (texture != null) {
                texture.bind()
                uploadTextureDataIfNeed(
                    alpha,
                    flipY ?: texture.flipTexture,
                    textureCoordinatesFlat ?: defaultTextureCoordsFlat
                )
            }
            renderer.flush()
        }


    private fun uploadTextureDataIfNeed(
        alpha: Float,
        flipTexture: Boolean,
        textureCoordinates: Array<Offset>
    ) {
        val texCoord: Array<Offset> = textureCoordinates
        val flipY: Boolean = flipTexture
        val texCoordinatesChanged = isTexCoordinatesChanged(texCoord)
        val flipChanged = isFlipChanged(flipY)
        val alphaChanged = isAlphaChanged(alpha)
        val isDataChanged =
            texCoordinatesChanged || flipChanged || alphaChanged

        if (isDataChanged) {
            trace("uploadDataIfNeed") {
                simpleDataCache.rewind()

                // Bottom Left
                simpleDataCache.put(texCoord[0].x)
                simpleDataCache.put(texCoord[0].y)
                simpleDataCache.put(0.0f)       // padding
                simpleDataCache.put(0.0f)       // padding
                // Bottom Right
                simpleDataCache.put(texCoord[1].x)
                simpleDataCache.put(texCoord[1].y)
                simpleDataCache.put(0.0f)       // padding
                simpleDataCache.put(0.0f)       // padding
                // Top Right
                simpleDataCache.put(texCoord[2].x)
                simpleDataCache.put(texCoord[2].y)
                simpleDataCache.put(0.0f)       // padding
                simpleDataCache.put(0.0f)       // padding
                // Top Left
                simpleDataCache.put(texCoord[3].x)
                simpleDataCache.put(texCoord[3].y)
                simpleDataCache.put(0.0f)       // padding
                simpleDataCache.put(0.0f)       // padding
                // Flip Y & Alpha
                simpleDataCache.put(if (flipY) 1.0f else 0.0f)
                simpleDataCache.put(alpha)
                simpleDataCache.put(0.0f)       // padding
                simpleDataCache.put(0.0f)       // padding

                renderer.data.textureDataUBO.setData(simpleDataCache.position(0))

                this.texCoord[0] = texCoord[0]
                this.texCoord[1] = texCoord[1]
                this.texCoord[2] = texCoord[2]
                this.texCoord[3] = texCoord[3]
                this.flipY = flipY
                this.alpha = alpha
            }
        }
    }

    private fun isAlphaChanged(alpha: Float): Boolean {
        return this.alpha != alpha
    }

    private fun isFlipChanged(flipY: Boolean): Boolean {
        return this.flipY != flipY
    }

    private fun isTexCoordinatesChanged(texCoord: Array<Offset>): Boolean {
        return this.texCoord[0] != texCoord[0] ||
                this.texCoord[1] != texCoord[1] ||
                this.texCoord[2] != texCoord[2] ||
                this.texCoord[3] != texCoord[3]
    }

    private fun getTextureCoordinates(texture: Texture): Array<Offset> {
        return when (texture) {
            is Texture2D -> defaultTextureCoords
            is SubTexture2D -> texture.texCoords
            else -> defaultTextureCoords
        }
    }

    private fun uploadTextureDataIfNeed(
        alpha: Float,
        flipTexture: Boolean,
        textureCoordinatesFlat: FloatArray
    ) {
        val flipY: Boolean = flipTexture
        val texCoordinatesChanged = isTexCoordinatesChanged(textureCoordinatesFlat)
        val flipChanged = isFlipChanged(flipY)
        val alphaChanged = isAlphaChanged(alpha)
        val isDataChanged =
            texCoordinatesChanged || flipChanged || alphaChanged

        if (isDataChanged) {
            trace("uploadDataIfNeed") {
                simpleDataCache.rewind()

                // Bottom Left
                simpleDataCache.put(textureCoordinatesFlat[0])
                simpleDataCache.put(textureCoordinatesFlat[1])
                simpleDataCache.put(0.0f)       // padding
                simpleDataCache.put(0.0f)       // padding
                // Bottom Right
                simpleDataCache.put(textureCoordinatesFlat[2])
                simpleDataCache.put(textureCoordinatesFlat[3])
                simpleDataCache.put(0.0f)       // padding
                simpleDataCache.put(0.0f)       // padding
                // Top Right
                simpleDataCache.put(textureCoordinatesFlat[4])
                simpleDataCache.put(textureCoordinatesFlat[5])
                simpleDataCache.put(0.0f)       // padding
                simpleDataCache.put(0.0f)       // padding
                // Top Left
                simpleDataCache.put(textureCoordinatesFlat[6])
                simpleDataCache.put(textureCoordinatesFlat[7])
                simpleDataCache.put(0.0f)       // padding
                simpleDataCache.put(0.0f)       // padding
                // Flip Y & Alpha
                simpleDataCache.put(if (flipY) 1.0f else 0.0f)
                simpleDataCache.put(alpha)
                simpleDataCache.put(0.0f)       // padding
                simpleDataCache.put(0.0f)       // padding

                renderer.data.textureDataUBO.setData(simpleDataCache.position(0))

                textureCoordinatesFlat.copyInto(this.texCoordFlat, destinationOffset = 0, startIndex = 0, endIndex = 8)
                this.flipY = flipY
                this.alpha = alpha
            }
        }
    }

    private fun isTexCoordinatesChanged(texCoordFlat: FloatArray): Boolean {
        return texCoordFlat[0] != this.texCoordFlat[0] ||
                texCoordFlat[1] != this.texCoordFlat[1] ||
                texCoordFlat[2] != this.texCoordFlat[2] ||
                texCoordFlat[3] != this.texCoordFlat[3] ||
                texCoordFlat[4] != this.texCoordFlat[4] ||
                texCoordFlat[5] != this.texCoordFlat[5] ||
                texCoordFlat[6] != this.texCoordFlat[6] ||
                texCoordFlat[7] != this.texCoordFlat[7]
    }

    companion object {
        private val bottomLeft = Offset(0.0f, 0.0f)
        private val bottomRight = Offset(1.0f, 0.0f)
        private val topRight = Offset(1.0f, 1.0f)
        private val topLeft = Offset(0.0f, 1.0f)
        val defaultTextureCoords = arrayOf(bottomLeft, bottomRight, topRight, topLeft) // CCW
        // Flat float array: [u0, v0, u1, v1, u2, v2, u3, v3] - avoids Offset boxing
        val defaultTextureCoordsFlat = floatArrayOf(
            0.0f, 0.0f,  // BL
            1.0f, 0.0f,  // BR
            1.0f, 1.0f,  // TR
            0.0f, 1.0f   // TL
        )

        /**
         * Pack Color into a single float as ABGR bytes for GPU unpacking.
         */
        fun packTint(color: Color): Float {
            val argb = color.toArgb()
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val abgr = (a shl 24) or (b shl 16) or (g shl 8) or r
            return Float.fromBits(abgr)
        }
    }
}
