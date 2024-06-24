/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.renderer

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.util.trace
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.scale
import dev.romainguy.kotlin.math.translation
import dev.romainguy.kotlin.math.transpose
import dev.serhiiyaremych.imla.renderer.camera.OrthographicCamera
import dev.serhiiyaremych.imla.renderer.objects.QuadShaderProgram
import dev.serhiiyaremych.imla.renderer.primitive.QuadVertex

internal const val MAX_QUADS = 100
internal const val MAX_VERTICES = MAX_QUADS * 4
internal const val MAX_INDICES = MAX_QUADS * 6
internal const val MAX_TEXTURE_SLOTS = 8 // query from actual HW


internal class Renderer2D {
    private var _data: Renderer2DData? = null
    private val data: Renderer2DData get() = requireNotNull(_data) { "Renderer2D not initialized!" }

    private var isDrawingScene: Boolean = false

    fun init(assetManager: AssetManager) {
        val quadIndices = IntArray(MAX_INDICES)
        var offset = 0
        for (i in quadIndices.indices step 6) {
            quadIndices[i + 0] = offset + 0
            quadIndices[i + 1] = offset + 1
            quadIndices[i + 2] = offset + 2

            quadIndices[i + 3] = offset + 2
            quadIndices[i + 4] = offset + 3
            quadIndices[i + 5] = offset + 0

            offset += 4
        }

        val quadVertexArray: VertexArray = VertexArray.create()
        val defaultQuadShaderProgram = QuadShaderProgram(
            shader = Shader.create(
                assetManager = assetManager,
                vertexAsset = "shader/default_quad.vert",
                fragmentAsset = "shader/default_quad.frag"
            )
        )
        defaultQuadShaderProgram.shader.bind()
        val samplers = IntArray(MAX_TEXTURE_SLOTS) { index -> index }
        defaultQuadShaderProgram.shader.setIntArray("u_Textures", *samplers)

        val quadVertexBuffer: VertexBuffer =
            VertexBuffer.create(MAX_VERTICES * defaultQuadShaderProgram.componentsCount).apply {
                layout = defaultQuadShaderProgram.vertexBufferLayout
            }
        quadVertexArray.addVertexBuffer(quadVertexBuffer)
        quadVertexArray.indexBuffer = IndexBuffer.create(quadIndices)

        val externalQuadShaderProgram = QuadShaderProgram(
            shader = Shader.create(
                assetManager = assetManager,
                vertexAsset = "shader/default_quad.vert",
                fragmentAsset = "shader/external_quad.frag"
            )
        )
        externalQuadShaderProgram.shader.bind()
        externalQuadShaderProgram.shader.setIntArray("u_Textures", *samplers)

        val quadIndexCount = 0
        val quadVertexBufferBase: MutableList<QuadVertex> = ArrayList(MAX_VERTICES)

        val defaultQuadVertexPositions: Array<Float4> = Array(4) {
            when (it) {
                0 -> Float4(-0.5f, -0.5f, 0.0f, 1.0f) // BL
                1 -> Float4(0.5f, -0.5f, 0.0f, 1.0f)  // BR
                2 -> Float4(0.5f, 0.5f, 0.0f, 1.0f)   // TR
                3 -> Float4(-0.5f, 0.5f, 0.0f, 1.0f)  // TL
                else -> error("Wrong QuadVertex index: $it, max 3")
            }
        }

        val stats = RenderStatistics()

        val whiteTexture: Texture2D =
            Texture2D.create(Texture.Target.TEXTURE_2D, Texture.Specification())
        val textureSlots: Array<Texture2D?> = Array(MAX_TEXTURE_SLOTS) { null }
        val textureSlotIndex = 1 // 0 = white texture slot

        _data = Renderer2DData(
            cameraData = CameraData(Mat4.identity()),
            whiteTexture = whiteTexture,
            quadVertexArray = quadVertexArray,
            quadVertexBuffer = quadVertexBuffer,
            defaultQuadShaderProgram = defaultQuadShaderProgram,
            externalQuadShaderProgram = externalQuadShaderProgram,
            quadShaderProgram = externalQuadShaderProgram,
            quadIndexCount = quadIndexCount,
            quadVertexBufferBase = quadVertexBufferBase,
            textureSlots = textureSlots,
            textureSlotIndex = textureSlotIndex,
            defaultQuadVertexPositions = defaultQuadVertexPositions,
            stats = stats
        )

        whiteTexture.setData(intArrayOf(Color.White.toArgb()).toIntBuffer())
        externalQuadShaderProgram.shader.bind()

        textureSlots.fill(null)
        textureSlots[WHITE_TEXTURE_SLOT_INDEX] = whiteTexture

    }

    fun shutdown() {
        data.defaultQuadShaderProgram.shader.destroy()
        data.externalQuadShaderProgram.shader.destroy()
        data.quadShaderProgram.shader.destroy()
        data.quadVertexArray.destroy()
        data.textureSlots.fill(null)
        _data = null
    }

    fun beginScene(camera: OrthographicCamera) {
        beginScene(camera, data.defaultQuadShaderProgram)
    }

    fun beginScene(camera: OrthographicCamera, shaderProgram: ShaderProgram) {
        require(isDrawingScene.not()) { "Please complete the current scene before starting a new one." }
        isDrawingScene = true
        data.cameraData.viewProjection = camera.viewProjectionMatrix

        if (shaderProgram != data.quadShaderProgram) {
            data.quadShaderProgram = shaderProgram
        }
        val mat4 = data.cameraData.viewProjection

        trace("Renderer2D#beginBatch") {
            data.defaultQuadShaderProgram.shader.bind()
            data.defaultQuadShaderProgram.shader.setMat4("u_ViewProjection", mat4)

            data.externalQuadShaderProgram.shader.bind()
            data.externalQuadShaderProgram.shader.setMat4("u_ViewProjection", mat4)

            data.quadShaderProgram.shader.bind()
            data.quadShaderProgram.shader.setMat4("u_ViewProjection", mat4)
        }

        data.quadIndexCount = 0
        data.quadVertexBufferBase.clear()

        data.textureSlotIndex = 1
        data.textureSlots.fill(null, 1)
    }

    fun endScene() {
        data.quadVertexBuffer.setData(
            data.quadShaderProgram.mapVertexData(data.quadVertexBufferBase)
        )
        flush()
        isDrawingScene = false
    }

    private fun flush() {
        if (data.quadIndexCount > 0) {
            // Bind textures
            for (i in 0 until data.textureSlotIndex) {
                data.textureSlots[i]?.bind(slot = i)
            }
            val isCustomShader = data.quadShaderProgram != data.defaultQuadShaderProgram &&
                    data.quadShaderProgram != data.externalQuadShaderProgram
            when {
                isCustomShader -> {
                    data.quadShaderProgram.shader.bind()
                }

                else -> {
                    if (data.quadVertexBufferBase.any { it.isExternalTexture > 0f }) {
                        data.externalQuadShaderProgram.shader.bind()
                    } else {
                        data.defaultQuadShaderProgram.shader.bind()
                    }
                }
            }
            RenderCommand.drawIndexed(data.quadVertexArray, data.quadIndexCount)
            data.stats.drawCalls++
        }
    }

    fun drawQuad(
        position: Float3,
        size: Float2,
        rotated: Float3 = zero3,
        cameraDistance: Float = 3f,
        texture2D: Texture2D? = null,
        alpha: Float = 1.0f,
        withMask: Boolean = false
    ) {
        if (data.quadIndexCount >= MAX_INDICES) {
            flushAndReset()
        }
        val transform: Mat4
        if (rotated != zero3) {
            val depth = cameraDistance * 72f
            var cameraDepth = matId
            if (rotated.x != 0f || rotated.y != 0f) { // faking perspective mapping
                cameraDepth = Mat4.identity()
                cameraDepth.set(row = 2, column = 3, v = -1f / depth)
                cameraDepth.set(row = 2, column = 2, v = 0f)
                cameraDepth = transpose(cameraDepth)
            }

            val rotX = if (rotated.x != 0.0f) rotation(X_AXIS, rotated.x) else matId
            val rotY = if (rotated.y != 0.0f) rotation(Y_AXIS, rotated.y) else matId
            val rotZ = if (rotated.z != 0.0f) rotation(Z_AXIS, rotated.z) else matId

            transform = translation(position) *
                    cameraDepth *
                    rotX * rotY * rotZ *
                    scale(Float3(size, 1.0f))
        } else {
            transform = translation(position) * scale(Float3(size, 1.0f))
        }

        var texIndex = 0.0f
        var flipTexture = false
        var isExternalTexture = false
        if (texture2D != null) {
            flipTexture = texture2D.flipTexture
            isExternalTexture = texture2D.target == Texture.Target.TEXTURE_EXTERNAL_OES
            var textureIndex = findTextureSlotIndexFor(texture2D)
            if (textureIndex == -1) {
                textureIndex = data.textureSlotIndex++
            } else {
                data.textureSlotIndex = textureIndex + 1
            }
            data.textureSlots[textureIndex] = texture2D
            texIndex = textureIndex.toFloat()
        }

        submitQuad(
            transform = transform,
            texIndex = texIndex,
            flipTexture = if (flipTexture) 1.0f else 0.0f,
            isExternalTexture = if (isExternalTexture) 1.0f else 0.0f,
            alpha = alpha,
            mask = if (withMask) 1.0f else 0.0f
        )
    }

    fun drawQuad(
        position: Float3,
        size: Float2,
        texture: Texture,
        alpha: Float = 1.0f,
        withMask: Boolean = false
    ) {
        when (texture) {
            is Texture2D -> drawQuad(
                position = position,
                size = size,
                texture2D = texture,
                alpha = alpha,
                withMask = withMask
            )

            is SubTexture2D -> drawQuad(
                position = position,
                size = size,
                subTexture = texture,
                alpha = alpha,
                withMask = withMask
            )
        }
    }

    fun drawQuad(
        position: Float3,
        size: Float2,
        subTexture: SubTexture2D,
        alpha: Float = 1.0f,
        withMask: Boolean = false
    ) {
        if (data.quadIndexCount >= MAX_INDICES) {
            flushAndReset()
        }

        var textureIndex = findTextureSlotIndexFor(subTexture.texture)
        if (textureIndex == -1) {
            textureIndex = data.textureSlotIndex
            data.textureSlots[textureIndex] = subTexture.texture
            data.textureSlotIndex++
        } else {
            data.textureSlotIndex = textureIndex + 1
        }
        val isExternalTexture = subTexture.texture.target == Texture.Target.TEXTURE_EXTERNAL_OES
        submitQuad(
            transform = translation(position) * scale(Float3(size, 1.0f)),
            texIndex = textureIndex.toFloat(),
            textureCoords = subTexture.texCoords,
            flipTexture = if (subTexture.flipTexture) 1.0f else 0.0f,
            isExternalTexture = if (isExternalTexture) 1.0f else 0.0f,
            alpha = alpha,
            mask = if (withMask) 1.0f else 0.0f
        )
    }

    fun resetRenderStats() {
        data.stats.reset()
    }

    fun renderStats(): RenderStatistics {
        return if (_data != null) data.stats else RenderStatistics()
    }

    private fun flushAndReset() {
        endScene()

        data.quadIndexCount = 0
        data.quadVertexBufferBase.clear()
        data.textureSlotIndex = 1
        data.textureSlots.fill(null, 1)
        data.textureSlots[WHITE_TEXTURE_SLOT_INDEX] = data.whiteTexture

    }

    private fun findTextureSlotIndexFor(texture: Texture2D): Int {
        var textureIndex = -1
        for (i in 1..data.textureSlotIndex) {
            if (data.textureSlots[i] == texture) {
                textureIndex = i
            }
        }
        return textureIndex
    }


    private fun submitQuad(
        transform: Mat4,
        textureCoords: Array<Offset> = defaultTextureCoords,
        texIndex: Float = 0.0f, // 0 = white texture
        flipTexture: Float = 1.0f,
        isExternalTexture: Float = 0.0f,
        alpha: Float = 1.0f,
        mask: Float
    ) {

        for (i in 0 until 4) {
            data.quadVertexBufferBase += QuadVertex(
                position = (transform * data.defaultQuadVertexPositions[i]),
                texCoord = textureCoords[i],
                texIndex = texIndex,
                flipTexture = flipTexture,
                isExternalTexture = isExternalTexture,
                alpha = alpha,
                mask = mask,
                maskCoord = textureCoords[i]
            )
        }

        data.quadIndexCount += 6
        data.stats.quadCount++
    }

}


private data class CameraData(var viewProjection: Mat4)

@Suppress("ArrayInDataClass")
private data class Renderer2DData(
    val cameraData: CameraData,
    var whiteTexture: Texture2D,

    val defaultQuadShaderProgram: ShaderProgram,
    val externalQuadShaderProgram: ShaderProgram,

    var quadVertexArray: VertexArray,
    var quadVertexBuffer: VertexBuffer,
    var quadShaderProgram: ShaderProgram,
    var quadIndexCount: Int = 0,
    val quadVertexBufferBase: MutableList<QuadVertex> = ArrayList(MAX_VERTICES),


    val textureSlots: Array<Texture2D?> = Array(MAX_TEXTURE_SLOTS) { null },
    var textureSlotIndex: Int = 1, // 0 = white texture slot
    val defaultQuadVertexPositions: Array<Float4> = Array(4) { Float4(0.0f) },
    val stats: RenderStatistics = RenderStatistics(),
)

public data class RenderStatistics(
    var drawCalls: Int = 0,
    var quadCount: Int = 0,
    var lineCount: Int = 0,
    var frameTime: Float = 0f,
) {
    val vertexCount: Int get() = (quadCount * 4) + (lineCount * 4)
    val indexCount: Int get() = quadCount * 6 + (lineCount * 6)

    public fun reset() {
        drawCalls = 0
        quadCount = 0
        lineCount = 0
        frameTime = 0f
    }
}

// Texture coordinates
private val bottomLeft = Offset(0.0f, 0.0f)
private val bottomRight = Offset(1.0f, 0.0f)
private val topRight = Offset(1.0f, 1.0f)
private val topLeft = Offset(0.0f, 1.0f)
private val defaultTextureCoords = arrayOf(bottomLeft, bottomRight, topRight, topLeft) // CCW

private val whiteColor = Float4(1.0f)
private val zero3 = Float3(0.0f)
private val zero4 = Float4(0.0f)
private val X_AXIS = Float3(1.0f, 0.0f, 0.0f)
private val Y_AXIS = Float3(0.0f, 1.0f, 0.0f)
private val Z_AXIS = Float3(0.0f, 0.0f, 1.0f)
private val matId = Mat4.identity()

private const val WHITE_TEXTURE_SLOT_INDEX = 0
