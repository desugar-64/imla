/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.Renderer2D
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.camera.OrthographicCamera
import dev.serhiiyaremych.imla.internal.render.objects.QuadShaderProgram
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.shader.ShaderProgram

/** Shared full-coverage UV rect, used as the default for [RenderQuad.uv]. */
internal val FullQuadUv: Rect = Rect(0f, 0f, 1f, 1f)

/**
 * Quad draw description.
 *
 * When [transform] is provided, it is used directly and [center]/[size] act as metadata only.
 */
internal data class RenderQuad(
    val id: String,
    val center: Offset,
    val size: Size,
    val uv: Rect = FullQuadUv,
    val textureCoords: FloatArray? = null,
    val texture: Texture2D? = null,
    val maskValue: Float = 0f,
    val alpha: Float = 1f,
    /** When null, uses the texture's flip state; otherwise overrides it. */
    val flipTexture: Boolean? = null,
    val tint: Color = Color.Transparent,
    val transform: Mat4? = null
)

internal class QuadBatchRenderer(
    private val renderer2D: Renderer2D,
    private val shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder,
    private val commandsProvider: () -> RenderCommands = { RenderCommand.commands }
) {
    private var targetSize: IntSize = IntSize.Zero
    private val camera: OrthographicCamera = OrthographicCamera(
        left = 0f,
        right = 1f,
        bottom = 1f,
        top = 0f
    )

    // Flat float array: [u0, v0, u1, v1, u2, v2, u3, v3] - avoids Offset boxing
    private val reusableTexCoordsFlat = FloatArray(8)

    // Reused translate+scale matrix; only the four non-identity cells are rewritten.
    private val reusableTransform: Mat4 = Mat4.identity()

    private val debugShaderProgram: QuadShaderProgram by lazy(LazyThreadSafetyMode.NONE) {
        QuadShaderProgram(
            shaderBinder = shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "debug_quad"
            )
        )
    }
    private val defaultShaderProgram: ShaderProgram
        get() = renderer2D.data.defaultQuadShaderProgram

    private var isBeginCalled: Boolean = false
    private var useDebugShader: Boolean = false
    private var enableBlending: Boolean = false
    private var activeTraceLabel: String? = null

    fun begin(
        targetSize: IntSize,
        debug: Boolean = false,
        enableBlending: Boolean = false,
        shaderProgram: ShaderProgram? = null,
        reservedTextures: List<Texture2D> = emptyList(),
        configureShader: ((ShaderProgram, Map<Texture2D, Int>) -> Unit)? = null,
        traceLabel: String? = null
    ) {
        require(targetSize != IntSize.Zero) { "Call begin() with a non-zero target size" }
        this.targetSize = targetSize
        this.useDebugShader = debug
        this.enableBlending = enableBlending
        this.activeTraceLabel = traceLabel
        camera.setProjection(
            left = 0f,
            right = targetSize.width.toFloat(),
            bottom = targetSize.height.toFloat(),
            top = 0f
        )
        val program = when {
            debug -> debugShaderProgram
            shaderProgram != null -> shaderProgram
            else -> defaultShaderProgram
        }
        val reservedTextureSlots = mutableMapOf<Texture2D, Int>()
        val configureReservedShader = configureShader?.let { configure ->
            { shader: ShaderProgram ->
                configure(shader, reservedTextureSlots)
            }
        }
        renderer2D.beginScene(camera, program, configureReservedShader)
        isBeginCalled = true
        reserveTextureSlots(
            textures = reservedTextures,
            slots = reservedTextureSlots
        )
    }

    fun submitDebug(quad: RenderQuad) = trace("QuadBatchRenderer#submitDebug") {
        if (!isBeginCalled || quad.size.width <= 0f || quad.size.height <= 0f || useDebugShader.not()) return@trace

        val tintPacked = packTint(quad.tint)

        renderer2D.drawQuad(
            transform = quad.transform ?: return@trace,
            texture2D = quad.texture,
            textureCoords = quad.textureCoords ?: uvToTexCoords(quad.uv),
            alpha = quad.alpha,
            maskValue = quad.maskValue,
            flipTextureOverride = quad.flipTexture,
            tintPacked = tintPacked
        )
    }

    fun submit(quad: RenderQuad) = trace("QuadBatchRenderer#submit") {
        if (!isBeginCalled || quad.size.width <= 0f || quad.size.height <= 0f || useDebugShader) {
            return@trace
        }

        val tintPacked = packTint(quad.tint)

        renderer2D.drawQuad(
            transform = quad.transform ?: return@trace,
            texture2D = quad.texture,
            textureCoords = quad.textureCoords ?: uvToTexCoords(quad.uv),
            alpha = quad.alpha,
            maskValue = quad.maskValue,
            flipTextureOverride = quad.flipTexture,
            tintPacked = tintPacked
        )
    }

    fun reserveTextureSlot(texture: Texture2D): Int {
        require(isBeginCalled) { "Call begin() before reserving quad texture slots" }
        val data = renderer2D.data
        var textureIndex = -1
        for (index in 1 until data.textureSlotIndex) {
            if (data.textureSlots[index] == texture) {
                textureIndex = index
            }
        }
        if (textureIndex < 0) {
            check(data.textureSlotIndex < data.maxTextureSlots) {
                "QuadBatchRenderer exceeded available texture slots: requested ${data.textureSlotIndex + 1}, " +
                        "available ${data.maxTextureSlots}."
            }
            textureIndex = data.textureSlotIndex
            data.textureSlots[textureIndex] = texture
            data.textureSlotIndex++
        }
        return textureIndex
    }

    private fun reserveTextureSlots(
        textures: List<Texture2D>,
        slots: MutableMap<Texture2D, Int>
    ) {
        textures.forEach { texture ->
            slots[texture] = reserveTextureSlot(texture)
        }
    }

    fun flush() {
        if (!isBeginCalled) return
        val traceLabel = activeTraceLabel
        val commands = commandsProvider()
        if (enableBlending) {
            commands.withBlendingModeEnabled {
                if (traceLabel != null) {
                    trace(traceLabel) {
                        renderer2D.endScene()
                    }
                } else {
                    renderer2D.endScene()
                }
            }
        } else {
            if (traceLabel != null) {
                trace(traceLabel) {
                    renderer2D.endScene()
                }
            } else {
                renderer2D.endScene()
            }
        }
        isBeginCalled = false
        activeTraceLabel = null
    }

    /**
     * Fills a reused [Mat4] with an axis-aligned translate+scale (no rotation) transform
     * and returns it, avoiding the ~17 short-lived `Mat4`/`Float4`/`Float3` allocations of
     * `translation(...) * scale(...)`.
     *
     * The returned matrix is shared and rewritten on each call (same contract as
     * [uvToTexCoords]). Hand it straight to [submit]; do not retain it past the next call.
     */
    fun translateScale(center: Offset, size: Size): Mat4 {
        reusableTransform.x.x = size.width
        reusableTransform.y.y = size.height
        reusableTransform.w.x = center.x
        reusableTransform.w.y = center.y
        return reusableTransform
    }

    private fun uvToTexCoords(uv: Rect): FloatArray {
        reusableTexCoordsFlat[0] = uv.left
        reusableTexCoordsFlat[1] = uv.top
        reusableTexCoordsFlat[2] = uv.right
        reusableTexCoordsFlat[3] = uv.top
        reusableTexCoordsFlat[4] = uv.right
        reusableTexCoordsFlat[5] = uv.bottom
        reusableTexCoordsFlat[6] = uv.left
        reusableTexCoordsFlat[7] = uv.bottom
        return reusableTexCoordsFlat
    }

    private fun packTint(color: Color): Float {
        val argb = color.toArgb()
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val abgr = (a shl 24) or (b shl 16) or (g shl 8) or r
        return Float.fromBits(abgr)
    }

    companion object
}

/**
 * Scopes a single [QuadBatchRenderer.begin]/[QuadBatchRenderer.flush] pass and
 * runs [block] with the renderer as receiver, so `submit(...)` reads bare.
 *
 * [QuadBatchRenderer.flush] is guaranteed to run even if [block] throws, so a
 * failed submit cannot leave the shared renderer scene open for the next pass.
 */
internal inline fun QuadBatchRenderer.draw(
    targetSize: IntSize,
    debug: Boolean = false,
    enableBlending: Boolean = false,
    shaderProgram: ShaderProgram? = null,
    reservedTextures: List<Texture2D> = emptyList(),
    noinline configureShader: ((ShaderProgram, Map<Texture2D, Int>) -> Unit)? = null,
    traceLabel: String? = null,
    block: QuadBatchRenderer.() -> Unit
) {
    begin(
        targetSize = targetSize,
        debug = debug,
        enableBlending = enableBlending,
        shaderProgram = shaderProgram,
        reservedTextures = reservedTextures,
        configureShader = configureShader,
        traceLabel = traceLabel
    )
    try {
        block()
    } finally {
        flush()
    }
}
