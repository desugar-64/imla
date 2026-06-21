/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.internal.render

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.tracing.trace
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.scale
import dev.romainguy.kotlin.math.translation
import dev.romainguy.kotlin.math.transpose
import dev.serhiiyaremych.imla.internal.render.camera.OrthographicCamera
import dev.serhiiyaremych.imla.internal.render.objects.QuadShaderProgram
import dev.serhiiyaremych.imla.internal.render.primitive.QuadVertex
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.shader.ShaderProgram
import dev.serhiiyaremych.imla.internal.ext.logd

internal const val MAX_QUADS = 50
internal const val MAX_VERTICES = MAX_QUADS * 4
internal const val MAX_INDICES = MAX_QUADS * 6
internal const val DEFAULT_TEXTURE_SLOTS = 8


internal class Renderer2D {
    private var _data: Renderer2DData? = null
    internal val data: Renderer2DData get() = _data ?: error("Renderer2D not initialized!")

    private var isDrawingScene: Boolean = false
    private var graphicsContext: GraphicsContext? = null
    private var activeShaderConfigurer: ((ShaderProgram) -> Unit)? = null

    private fun generateTextureSwitchCases(maxTextureSlots: Int): String {
        return buildString {
            for (i in 0 until maxTextureSlots) {
                appendLine("        case $i:")
                appendLine("            baseColor = texture(u_Textures[$i], texCoord);break;")
            }
        }
    }

    fun init(graphicsContext: GraphicsContext, shaderLibrary: ShaderLibrary, shaderBinder: ShaderBinder) {
        this.graphicsContext = graphicsContext
        val capabilities = graphicsContext.getCapabilities()
        val maxTextureSlots = capabilities.maxTextureImageUnits
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
        val shaderPreprocessor = mapOf(
            "MAX_TEXTURE_SLOTS" to maxTextureSlots.toString(),
            "TEXTURE_SWITCH_CASES" to generateTextureSwitchCases(maxTextureSlots)
        )

        val quadVertexArray: VertexArray = VertexArray.create()
        val defaultQuadShaderProgram = QuadShaderProgram(
            shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "default_quad",
                preprocessorDefines = shaderPreprocessor
            )
        )
        val quadVertexBuffer: VertexBuffer =
            VertexBuffer.create(MAX_VERTICES * defaultQuadShaderProgram.componentsCount).apply {
                layout = defaultQuadShaderProgram.vertexBufferLayout
            }
        quadVertexArray.addVertexBuffer(quadVertexBuffer)
        quadVertexArray.indexBuffer = IndexBuffer.create(quadIndices)
        val externalQuadShaderProgram = QuadShaderProgram(
            shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "external_quad",
                preprocessorDefines = shaderPreprocessor
            )
        )
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
        val textureSlots: Array<Texture2D?> = Array(maxTextureSlots) { null }
        val textureSlotIndex = 1 // 0 = white texture slot

        _data = Renderer2DData(
            cameraData = CameraData(Mat4.identity()),
            whiteTexture = whiteTexture,
            setStaticQuadData = false,
            quadVertexArray = quadVertexArray,
            quadVertexBuffer = quadVertexBuffer,
            defaultQuadShaderProgram = defaultQuadShaderProgram,
            externalQuadShaderProgram = externalQuadShaderProgram,
            quadShaderProgram = externalQuadShaderProgram,
            quadIndexCount = quadIndexCount,
            quadVertexBufferBase = quadVertexBufferBase,
            textureSlots = textureSlots,
            quadVertexBufferStatic = null,
            textureSlotIndex = textureSlotIndex,
            defaultQuadVertexPositions = defaultQuadVertexPositions,
            stats = stats,
            shaderBinder = shaderBinder,
            maxTextureSlots = maxTextureSlots
        )

        whiteTexture.setData(intArrayOf(Color.White.toArgb()).toIntBuffer())
        externalQuadShaderProgram.shader.bind(shaderBinder)

        textureSlots.fill(null)
        textureSlots[WHITE_TEXTURE_SLOT_INDEX] = whiteTexture

    }

    fun shutdown() {
        data.defaultQuadShaderProgram.destroy()
        data.externalQuadShaderProgram.destroy()
        data.quadShaderProgram.destroy()
        data.quadVertexArray.destroy()
        data.textureSlots.fill(null)
        _data = null
        graphicsContext = null
    }

    fun beginScene(camera: OrthographicCamera) {
        beginScene(camera, data.defaultQuadShaderProgram)
    }

    fun beginScene(
        camera: OrthographicCamera,
        shaderProgram: ShaderProgram,
        configureShader: ((ShaderProgram) -> Unit)? = null
    ) =
        trace("Renderer2D#beginScene") {
            require(isDrawingScene.not()) { "Please complete the current scene before starting a new one." }
            isDrawingScene = true
            activeShaderConfigurer = configureShader
            data.cameraData.viewProjection = camera.viewProjectionMatrix

            if (shaderProgram != data.quadShaderProgram) {
                data.quadShaderProgram = shaderProgram
            }
            // View-projection matrix will be set lazily in flush() on the shader that's actually used

            data.quadIndexCount = 0
            data.quadVertexBufferBase.clear()
            data.vertexDataIndex = 0

            data.textureSlotIndex = 1
            data.textureSlots.fill(null, 1)
        }

    fun endScene() = trace("Renderer2D#endScene") {
        trace("Renderer2D#uploadVertices") {
            data.quadVertexBuffer.setData(data.vertexDataBuffer, data.vertexDataIndex)
        }
        flush()
        isDrawingScene = false
        activeShaderConfigurer = null
    }

    private fun flush() = trace("flush") {
        if (data.quadIndexCount > 0) {
            // Bind textures
            for (i in 0 until data.textureSlotIndex) {
                data.textureSlots[i]?.bind(slot = i)
            }
            val isCustomShader = data.quadShaderProgram != data.defaultQuadShaderProgram &&
                    data.quadShaderProgram != data.externalQuadShaderProgram
            // Determine and bind the shader that will actually be used
            val activeShader = when {
                isCustomShader -> data.quadShaderProgram
                data.quadVertexBufferBase.any { ((it.flags.toInt() and 2) != 0) } -> data.externalQuadShaderProgram
                else -> data.defaultQuadShaderProgram
            }
            activeShader.shader.bind(data.shaderBinder)
            // Set view-projection lazily on the shader that's actually used
            activeShader.shader.setMat4("u_ViewProjection", data.cameraData.viewProjection)
            activeShaderConfigurer?.invoke(activeShader)

            trace("Renderer2D#drawIndexed") {
                requireNotNull(graphicsContext).commands.drawIndexed(data.quadVertexArray, data.quadIndexCount)
            }
            data.stats.drawCalls++
        }
    }

    fun drawFullQuadStatic(texture: Texture, alpha: Float) = trace("drawFullQuadStatic") {
        if (data.setStaticQuadData.not()) {
            val texCoordsFlat = if (texture is SubTexture2D) {
                val coords = texture.texCoords
                floatArrayOf(
                    coords[0].x, coords[0].y,
                    coords[1].x, coords[1].y,
                    coords[2].x, coords[2].y,
                    coords[3].x, coords[3].y
                )
            } else {
                defaultTextureCoordsFlat
            }
            submitQuad(
                transform = matId,
                textureCoordsFlat = texCoordsFlat,
                alpha = alpha
            )
            data.quadVertexBufferStatic = VertexBuffer.create(
                vertices = data.vertexDataBuffer.copyOf(data.vertexDataIndex)
            )
            data.quadVertexArray.addVertexBuffer(data.quadVertexBufferStatic!!)
            data.setStaticQuadData = true
            data.quadShaderProgram.shader.bind(data.shaderBinder)
            data.quadShaderProgram.shader.setFloat("staticRenderer", 1.0f)
        }
        flush()
        isDrawingScene = false
    }

    fun drawQuad(
        position: Float3,
        size: Float2,
        rotated: Float3 = zero3,
        cameraDistance: Float = 3f,
        texture2D: Texture2D? = null,
        alpha: Float = 1.0f,
        withMask: Boolean = false,
        textureCoords: FloatArray? = null,
        maskValue: Float? = null,
        flipTextureOverride: Boolean? = null,
        tintPacked: Float = 0f
    ) = trace("drawQuad") {
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
                    scale(Float3(size.first, size.second, 1.0f))
        } else {
            transform = translation(position) * scale(Float3(size.first, size.second, 1.0f))
        }

        var texIndex = 0.0f
        var flipTexture = flipTextureOverride ?: false
        var isExternalTexture = false
        if (texture2D != null) {
            flipTexture = flipTextureOverride ?: texture2D.flipTexture
            isExternalTexture = texture2D.target == Texture.Target.TEXTURE_EXTERNAL_OES
            val textureIndex = textureSlotIndexFor(texture2D)
            texIndex = textureIndex.toFloat()
        }

        val maskEnabled = (maskValue ?: if (withMask) 1.0f else 0.0f) > 0.5f
        var flags = 0
        if (flipTexture) flags = flags or 1
        if (isExternalTexture) flags = flags or 2
        if (maskEnabled) flags = flags or 4

        submitQuad(
            transform = transform,
            textureCoordsFlat = textureCoords ?: defaultTextureCoordsFlat,
            texIndex = texIndex,
            flags = flags,
            alpha = alpha,
            tintPacked = tintPacked
        )
    }

    fun drawQuad(
        transform: Mat4,
        texture2D: Texture2D? = null,
        alpha: Float = 1.0f,
        withMask: Boolean = false,
        textureCoords: FloatArray? = null,
        maskValue: Float? = null,
        flipTextureOverride: Boolean? = null,
        tintPacked: Float = 0f
    ) = trace("drawQuad") {
        if (data.quadIndexCount >= MAX_INDICES) {
            flushAndReset()
        }

        var texIndex = 0.0f
        var flipTexture = flipTextureOverride ?: false
        var isExternalTexture = false
        if (texture2D != null) {
            flipTexture = flipTextureOverride ?: texture2D.flipTexture
            isExternalTexture = texture2D.target == Texture.Target.TEXTURE_EXTERNAL_OES
            val textureIndex = textureSlotIndexFor(texture2D)
            texIndex = textureIndex.toFloat()
        }

        val maskEnabled = (maskValue ?: if (withMask) 1.0f else 0.0f) > 0.5f
        var flags = 0
        if (flipTexture) flags = flags or 1
        if (isExternalTexture) flags = flags or 2
        if (maskEnabled) flags = flags or 4

        submitQuad(
            transform = transform,
            textureCoordsFlat = textureCoords ?: defaultTextureCoordsFlat,
            texIndex = texIndex,
            flags = flags,
            alpha = alpha,
            tintPacked = tintPacked
        )
    }

    @Deprecated("Use FloatArray version for better performance", ReplaceWith("drawQuad(position, size, rotated, cameraDistance, texture2D, alpha, withMask, textureCoords?.let { floatArrayOf(it[0].x, it[0].y, it[1].x, it[1].y, it[2].x, it[2].y, it[3].x, it[3].y) }, maskValue, flipTextureOverride)"))
    fun drawQuad(
        position: Float3,
        size: Float2,
        rotated: Float3 = zero3,
        cameraDistance: Float = 3f,
        texture2D: Texture2D? = null,
        alpha: Float = 1.0f,
        withMask: Boolean = false,
        textureCoords: Array<Offset>? = null,
        maskValue: Float? = null,
        flipTextureOverride: Boolean? = null,
        tintPacked: Float = 0f
    ) {
        // Convert Array<Offset> to FloatArray to avoid boxing
        val texCoordsFlat = textureCoords?.let { coords ->
            floatArrayOf(
                coords[0].x, coords[0].y,
                coords[1].x, coords[1].y,
                coords[2].x, coords[2].y,
                coords[3].x, coords[3].y
            )
        }
        drawQuad(
            position,
            size,
            rotated,
            cameraDistance,
            texture2D,
            alpha,
            withMask,
            texCoordsFlat,
            maskValue,
            flipTextureOverride,
            tintPacked
        )
    }

    fun drawQuad(
        position: Float3,
        size: Float2,
        texture: Texture,
        alpha: Float = 1.0f,
        withMask: Boolean = false,
        tintPacked: Float = 0f
    ) = trace("drawQuad[${size.first.toInt()} x ${size.second.toInt()}]") {
        when (texture) {
            is Texture2D -> drawQuad(
                position = position,
                size = size,
                texture2D = texture,
                alpha = alpha,
                withMask = withMask,
                textureCoords = null as FloatArray?,
                tintPacked = tintPacked
            )

            is SubTexture2D -> drawQuad(
                position = position,
                size = size,
                subTexture = texture,
                alpha = alpha,
                withMask = withMask,
                tintPacked = tintPacked
            )
        }
        Unit
    }

    fun drawQuad(
        position: Float3,
        size: Float2,
        subTexture: SubTexture2D,
        alpha: Float = 1.0f,
        withMask: Boolean = false,
        tintPacked: Float = 0f
    ) {
        if (data.quadIndexCount >= MAX_INDICES) {
            flushAndReset()
        }

        val textureIndex = textureSlotIndexFor(subTexture.texture)
        val isExternalTexture = subTexture.texture.target == Texture.Target.TEXTURE_EXTERNAL_OES
        // Convert Array<Offset> to FloatArray to avoid boxing
        val coords = subTexture.texCoords
        val texCoordsFlat = floatArrayOf(
            coords[0].x, coords[0].y,
            coords[1].x, coords[1].y,
            coords[2].x, coords[2].y,
            coords[3].x, coords[3].y
        )
        val maskEnabled = withMask
        var flags = 0
        if (subTexture.flipTexture) flags = flags or 1
        if (isExternalTexture) flags = flags or 2
        if (maskEnabled) flags = flags or 4

        submitQuad(
            transform = translation(position) * scale(Float3(size.first, size.second, 1.0f)),
            texIndex = textureIndex.toFloat(),
            textureCoordsFlat = texCoordsFlat,
            flags = flags,
            alpha = alpha,
            tintPacked = tintPacked
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
        activeShaderConfigurer = null

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

    private fun textureSlotIndexFor(texture: Texture2D): Int {
        val existingIndex = findTextureSlotIndexFor(texture)
        if (existingIndex != -1) {
            data.textureSlotIndex = maxOf(data.textureSlotIndex, existingIndex + 1)
            return existingIndex
        }

        check(data.textureSlotIndex < data.maxTextureSlots) {
            "Renderer2D exceeded available texture slots: requested ${data.textureSlotIndex + 1}, " +
                    "available ${data.maxTextureSlots}. Add mid-batch texture flushing before drawing " +
                    "more unique textures in one scene."
        }

        val textureIndex = data.textureSlotIndex
        data.textureSlots[textureIndex] = texture
        data.textureSlotIndex++
        return textureIndex
    }


    private fun submitQuad(
        transform: Mat4,
        textureCoordsFlat: FloatArray = defaultTextureCoordsFlat,
        texIndex: Float = 0.0f, // 0 = white texture
        flags: Int = 0,
        alpha: Float = 1.0f,
        tintPacked: Float = 0f
    ) = trace("submitQuad") {
        val buffer = data.vertexDataBuffer
        var idx = data.vertexDataIndex
        val pos = data.transformedPosition

        for (i in 0 until 4) {
            val vertexPos = data.defaultQuadVertexPositions[i]
            // Inline matrix-vector multiplication to avoid Float4 allocation
            pos.x = transform[0, 0] * vertexPos.x + transform[1, 0] * vertexPos.y +
                    transform[2, 0] * vertexPos.z + transform[3, 0] * vertexPos.w
            pos.y = transform[0, 1] * vertexPos.x + transform[1, 1] * vertexPos.y +
                    transform[2, 1] * vertexPos.z + transform[3, 1] * vertexPos.w
            pos.z = transform[0, 2] * vertexPos.x + transform[1, 2] * vertexPos.y +
                    transform[2, 2] * vertexPos.z + transform[3, 2] * vertexPos.w
            pos.w = transform[0, 3] * vertexPos.x + transform[1, 3] * vertexPos.y +
                    transform[2, 3] * vertexPos.z + transform[3, 3] * vertexPos.w

            val uvIdx = i * 2
            val texCoordU = textureCoordsFlat[uvIdx]
            val texCoordV = textureCoordsFlat[uvIdx + 1]
            val maskCoord = defaultTextureCoords[i]

            // a_TexCoord
            buffer[idx++] = texCoordU
            buffer[idx++] = texCoordV
            // a_Position
            buffer[idx++] = pos.x
            buffer[idx++] = pos.y
            buffer[idx++] = pos.z
            buffer[idx++] = pos.w
            // a_TexIndex
            buffer[idx++] = texIndex
            // a_Flags
            buffer[idx++] = flags.toFloat()
            // a_Alpha
            buffer[idx++] = alpha
            // a_MaskCoord
            buffer[idx++] = maskCoord.x
            buffer[idx++] = maskCoord.y
            // a_TintPacked
            buffer[idx++] = tintPacked
        }

        data.vertexDataIndex = idx
        data.quadIndexCount += 6
        data.stats.quadCount++
    }

}


internal data class CameraData(var viewProjection: Mat4)

@Suppress("ArrayInDataClass")
internal data class Renderer2DData(
    val cameraData: CameraData,
    var whiteTexture: Texture2D,
    var setStaticQuadData: Boolean,

    val defaultQuadShaderProgram: ShaderProgram,
    val externalQuadShaderProgram: ShaderProgram,

    var quadVertexArray: VertexArray,
    var quadVertexBuffer: VertexBuffer,
    var quadShaderProgram: ShaderProgram,
    var quadIndexCount: Int = 0,
    val quadVertexBufferBase: MutableList<QuadVertex> = ArrayList(MAX_VERTICES),
    val textureSlots: Array<Texture2D?>,

    var quadVertexBufferStatic: VertexBuffer?,

    var textureSlotIndex: Int = 1, // 0 = white texture slot
    val defaultQuadVertexPositions: Array<Float4> = Array(4) { Float4(0.0f) },
    val stats: RenderStatistics = RenderStatistics(),
    val shaderBinder: ShaderBinder,
    val vertexDataBuffer: FloatArray = FloatArray(MAX_VERTICES * QuadVertex.NUMBER_OF_COMPONENTS),
    var vertexDataIndex: Int = 0,
    val transformedPosition: Float4 = Float4(0.0f),
    val maxTextureSlots: Int
) {
}

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
// Flat float array version to avoid Offset boxing: [u0, v0, u1, v1, u2, v2, u3, v3]
private val defaultTextureCoordsFlat = floatArrayOf(
    0.0f, 0.0f,  // BL
    1.0f, 0.0f,  // BR
    1.0f, 1.0f,  // TR
    0.0f, 1.0f   // TL
)

//private val defaultTextureCoords = arrayOf(
//    Offset(0.0f, 0.0f), // Bottom Left
//    Offset(1.0f, 0.0f), // Bottom Right
//    Offset(0.0f, 1.0f), // Top Left
//    Offset(1.0f, 1.0f)  // Top Right
//)

private val whiteColor = Float4(1.0f)
private val zero3 = Float3(0.0f)
private val zero4 = Float4(0.0f)
private val X_AXIS = Float3(1.0f, 0.0f, 0.0f)
private val Y_AXIS = Float3(0.0f, 1.0f, 0.0f)
private val Z_AXIS = Float3(0.0f, 0.0f, 1.0f)
private val matId = Mat4.identity()

private const val WHITE_TEXTURE_SLOT_INDEX = 0
