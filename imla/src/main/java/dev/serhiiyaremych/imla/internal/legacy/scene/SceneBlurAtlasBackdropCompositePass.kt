/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import android.opengl.GLES30
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.ext.checkGlError
import dev.serhiiyaremych.imla.internal.render.Float2
import dev.serhiiyaremych.imla.internal.render.Renderer2D
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.objects.QuadShaderProgram
import dev.serhiiyaremych.imla.internal.render.opengl.toGlTextureTarget
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.legacy.Style
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.RenderQuad
import dev.serhiiyaremych.imla.internal.render.processing.noise.BackdropCompositeSamplingOrigins
import java.nio.Buffer

internal interface SceneBlurAtlasBackdropCompositeExecutor {
    fun execute(
        lookup: SceneBlurAtlasCompositeLookupEntry,
        slot: SceneSlotPlan,
        transform: Mat4,
        style: Style,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        maskTexture: Texture2D?
    )
}

/**
 * Isolated atlas-backed backdrop composite pass for future renderer-2 atlas delivery.
 *
 * The pass consumes one [SceneBlurAtlasCompositeLookupEntry] plus the slot plan, transform, style,
 * target size, and optional noise or mask textures that the live per-slot backdrop composite path
 * already uses. It draws the lookup's blurred atlas sample crop back into the scene-space sample
 * rect recorded by atlas planning. It owns only shader-path selection and draw submission.
 *
 * Atlas texture metadata is borrowed from [SceneBlurAtlasTextureHandle]. The pass does not release,
 * destroy, cache, allocate, or otherwise manage atlas resources; callers must keep the producing
 * atlas output alive until drawing finishes. Crops are converted directly from blurred atlas pixel
 * coordinates to UVs using the borrowed texture size. UVs are not flipped or counter-rotated here:
 * texture origin metadata is carried by the borrowed texture wrapper and clip/stencil work remains
 * outside this pass. The pass assumes it is called on the GL thread after the caller has bound the
 * scene target and installed any slot-local stencil window.
 */
internal class SceneBlurAtlasBackdropCompositePass(
    private val drawSink: SceneBlurAtlasBackdropCompositeDrawSink
) : SceneBlurAtlasBackdropCompositeExecutor {
    constructor(
        quadBatchRenderer: QuadBatchRenderer,
        renderer2D: Renderer2D,
        shaderLibrary: ShaderLibrary,
        shaderBinder: ShaderBinder
    ) : this(
        drawSink = DefaultSceneBlurAtlasBackdropCompositeDrawSink(
            quadBatchRenderer = quadBatchRenderer,
            renderer2D = renderer2D,
            shaderLibrary = shaderLibrary,
            shaderBinder = shaderBinder
        )
    )

    override fun execute(
        lookup: SceneBlurAtlasCompositeLookupEntry,
        slot: SceneSlotPlan,
        transform: Mat4,
        style: Style,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        maskTexture: Texture2D?
    ) {
        require(lookup.slotId == slot.id) {
            "Atlas composite lookup entry ${lookup.slotId.value} does not match slot ${slot.id.value}"
        }

        val draw = SceneBlurAtlasBackdropCompositeDraw.from(
            lookup = lookup,
            slot = slot,
            transform = transform,
            style = style
        )

        if (noiseTexture != null || maskTexture != null) {
            drawSink.drawWithBackdropCompositeEffect(
                draw = draw,
                style = style,
                targetSize = targetSize,
                noiseTexture = noiseTexture,
                maskTexture = maskTexture
            )
        } else {
            drawSink.drawScreenSpace(draw = draw, targetSize = targetSize)
        }
    }
}

internal data class SceneBlurAtlasBackdropCompositeDraw(
    val quad: RenderQuad,
    val sampleArea: Rect,
    val sampleUv: Rect,
    val samplingOrigins: BackdropCompositeSamplingOrigins
) {
    companion object {
        fun from(
            lookup: SceneBlurAtlasCompositeLookupEntry,
            slot: SceneSlotPlan,
            transform: Mat4,
            style: Style
        ): SceneBlurAtlasBackdropCompositeDraw {
            val texture = lookup.blurredAtlasTexture.toBorrowedTexture()
            val sampleArea = lookup.sourceSampleRect.toRect()
            val sampleUv = lookup.blurredAtlasTexture.toUvRect(lookup.blurredAtlasSampleCrop)
            val samplingOrigins = BackdropCompositeSamplingOrigins.fromTextures(
                blurTexture = texture,
                frameNoiseTexture = null,
                compositeCoverageMask = null
            )
            val quad = RenderQuad(
                id = slot.id.value,
                center = Offset(
                    x = sampleArea.left + sampleArea.width / 2f,
                    y = sampleArea.top + sampleArea.height / 2f
                ),
                size = sampleArea.size,
                uv = sampleUv,
                texture = texture,
                alpha = style.blurOpacity,
                maskValue = 1f,
                flipTexture = samplingOrigins.blurTextureFlip,
                transform = transform,
                tint = style.tint
            )
            return SceneBlurAtlasBackdropCompositeDraw(
                quad = quad,
                sampleArea = sampleArea,
                sampleUv = sampleUv,
                samplingOrigins = samplingOrigins
            )
        }
    }
}

internal interface SceneBlurAtlasBackdropCompositeDrawSink {
    fun drawScreenSpace(
        draw: SceneBlurAtlasBackdropCompositeDraw,
        targetSize: IntSize
    )

    fun drawWithBackdropCompositeEffect(
        draw: SceneBlurAtlasBackdropCompositeDraw,
        style: Style,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        maskTexture: Texture2D?
    )
}

private class DefaultSceneBlurAtlasBackdropCompositeDrawSink(
    private val quadBatchRenderer: QuadBatchRenderer,
    renderer2D: Renderer2D,
    shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder
) : SceneBlurAtlasBackdropCompositeDrawSink {
    private val backdropCompositeEffect = BackdropCompositeEffect(shaderLibrary, shaderBinder, renderer2D)
    private val screenSpaceQuadShaderProgram: QuadShaderProgram by lazy(LazyThreadSafetyMode.NONE) {
        val maxSlots = renderer2D.data.maxTextureSlots
        QuadShaderProgram(
            shaderBinder = shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "screen_space_quad",
                preprocessorDefines = mapOf(
                    "MAX_TEXTURE_SLOTS" to maxSlots.toString(),
                    "TEXTURE_SWITCH_CASES" to generateTextureSwitchCases(maxSlots, "texCoordAdjusted")
                )
            )
        )
    }

    override fun drawScreenSpace(
        draw: SceneBlurAtlasBackdropCompositeDraw,
        targetSize: IntSize
    ) {
        val shader = screenSpaceQuadShaderProgram.shader
        shader.bind(shaderBinder)
        shader.setFloat2("u_TargetSize", Float2(targetSize.width.toFloat(), targetSize.height.toFloat()))
        shader.setFloat("u_FragCoordFlipY", 1f)
        shader.setFloat4(
            "u_SampleRect",
            Float4(draw.sampleArea.left, draw.sampleArea.top, draw.sampleArea.width, draw.sampleArea.height)
        )
        shader.setFloat2("u_SampleUvMin", Float2(draw.sampleUv.left, draw.sampleUv.top))
        shader.setFloat2("u_SampleUvMax", Float2(draw.sampleUv.right, draw.sampleUv.bottom))

        quadBatchRenderer.begin(
            targetSize = targetSize,
            enableBlending = true,
            shaderProgram = screenSpaceQuadShaderProgram,
            traceLabel = "QuadBatchRenderer#sceneBlurAtlasBackdropComposite"
        )
        quadBatchRenderer.submit(draw.quad)
        quadBatchRenderer.flush()
    }

    override fun drawWithBackdropCompositeEffect(
        draw: SceneBlurAtlasBackdropCompositeDraw,
        style: Style,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        maskTexture: Texture2D?
    ) {
        backdropCompositeEffect.draw(
            quadBatchRenderer = quadBatchRenderer,
            input = BackdropCompositeInput(
                id = draw.quad.id,
                blurTexture = requireNotNull(draw.quad.texture),
                sampleRect = draw.sampleArea,
                sampleUv = draw.sampleUv,
                transform = requireNotNull(draw.quad.transform),
                tint = style.tint,
                opacity = style.blurOpacity,
                targetSize = targetSize,
                noiseTexture = noiseTexture,
                noiseAlpha = style.noiseAlpha,
                compositeCoverageMask = maskTexture,
                blurSigma = style.sigma,
                samplingOrigins = draw.samplingOrigins.withFrameInputs(
                    frameNoiseTexture = noiseTexture,
                    compositeCoverageMask = maskTexture
                )
            )
        )
    }

    private fun generateTextureSwitchCases(maxTextureSlots: Int, coordinateVariable: String): String {
        return buildString {
            for (i in 0 until maxTextureSlots) {
                appendLine("        case $i:")
                appendLine("            baseColor = texture(u_Textures[$i], $coordinateVariable);break;")
            }
        }
    }
}

private fun SceneBlurAtlasTextureHandle.toBorrowedTexture(): Texture2D {
    return BorrowedSceneBlurAtlasTexture(this)
}

private fun SceneBlurAtlasTextureHandle.toUvRect(pixelRect: SceneBlurAtlasPixelRect): Rect {
    return Rect(
        left = pixelRect.left.toFloat() / size.width.coerceAtLeast(1),
        top = pixelRect.top.toFloat() / size.height.coerceAtLeast(1),
        right = pixelRect.right.toFloat() / size.width.coerceAtLeast(1),
        bottom = pixelRect.bottom.toFloat() / size.height.coerceAtLeast(1)
    )
}

private fun SceneBlurAtlasPixelRect.toRect(): Rect {
    return Rect(
        left = left.toFloat(),
        top = top.toFloat(),
        right = right.toFloat(),
        bottom = bottom.toFloat()
    )
}

private class BorrowedSceneBlurAtlasTexture(
    handle: SceneBlurAtlasTextureHandle
) : Texture2D() {
    override val id: Int = handle.textureId
    override val target: Texture.Target = Texture.Target.TEXTURE_2D
    override val width: Int = handle.size.width
    override val height: Int = handle.size.height
    override val specification: Texture.Specification = Texture.Specification(
        size = handle.size,
        coordinateOrigin = handle.coordinateOrigin
    )
    override val coordinateOrigin = handle.coordinateOrigin

    override fun bind(slot: Int) {
        checkGlError(GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + slot))
        checkGlError(GLES30.glBindTexture(target.toGlTextureTarget(), id))
    }

    override fun setData(data: Buffer) = Unit

    override fun isLoaded(): Boolean = true

    override fun destroy() = Unit

    override fun generateMipMaps() = Unit
}
