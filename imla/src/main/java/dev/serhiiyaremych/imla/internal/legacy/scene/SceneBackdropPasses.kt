/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.Float2
import dev.serhiiyaremych.imla.internal.render.Renderer2D
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.objects.QuadShaderProgram
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.legacy.Style
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.RenderQuad
import dev.serhiiyaremych.imla.internal.render.processing.effects.EffectPipeline
import dev.serhiiyaremych.imla.internal.render.processing.effects.GaussianBlurEffect
import dev.serhiiyaremych.imla.internal.render.processing.effects.PreProcessEffect
import dev.serhiiyaremych.imla.internal.render.processing.noise.BackdropCompositeSamplingOrigins

/**
 * Extracts the backdrop sample region from the current scene framebuffer for blur processing.
 *
 * SceneGlRenderer invokes this after it has snapped the sample area. The pass must run on the GL
 * renderer thread and owns only the preprocess draw boundary; it does not own scene planning,
 * captured resources, effect framebuffer release, composition, or renderer/session lifecycle.
 */
internal class SceneBackdropPreprocessPass(
    private val effectPipeline: () -> EffectPipeline
) {
    fun execute(
        rootFbo: Framebuffer,
        sampleArea: Rect,
        contentSize: IntSize,
        sigma: Float
    ): PreProcessEffect.PreProcessOutput = trace("SceneBackdropPreprocessPass#execute") {
        effectPipeline().preProcess(
            rootFbo = rootFbo,
            sampleArea = sampleArea,
            contentSize = contentSize,
            sigma = sigma
        )
    }
}

/**
 * Runs the gaussian blur for one preprocessed backdrop sample.
 *
 * SceneGlRenderer invokes this immediately after preprocess and keeps ownership of composition and
 * transient effect-frame release. The pass must run on the GL renderer thread and does not own atlas
 * batching, mask capture, effect framebuffer release, composition, or renderer/session lifecycle.
 */
internal class SceneBlurPass(
    private val effectPipeline: () -> EffectPipeline
) {
    fun execute(
        input: PreProcessEffect.PreProcessOutput,
        sigma: Float,
        blurRadiusMask: Texture2D?
    ): GaussianBlurEffect.Output = trace("SceneBlurPass#execute") {
        effectPipeline().gaussianBlur(input, sigma, blurRadiusMask)
    }
}

/**
 * Composites one blurred backdrop sample back into the scene framebuffer.
 *
 * SceneGlRenderer invokes this after preprocess and blur, with the scene framebuffer already bound
 * for drawing. The pass must run on the GL renderer thread and owns only the composite draw boundary
 * and shader path selection; it does not own scene planning, captured resources, transient effect
 * frame release, or renderer/session lifecycle.
 */
internal class SceneBackdropCompositePass(
    private val quadBatchRenderer: QuadBatchRenderer,
    private val renderer2D: Renderer2D,
    private val shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder
) {
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

    fun execute(
        slot: SceneSlotPlan,
        transform: Mat4,
        sampleArea: Rect,
        style: Style,
        output: GaussianBlurEffect.Output,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        compositeCoverageMask: Texture2D?
    ) = trace("SceneBackdropCompositePass#execute") {
        val samplingOrigins = BackdropCompositeSamplingOrigins.fromTextures(
            blurTexture = output.fbo.texture,
            frameNoiseTexture = noiseTexture,
            compositeCoverageMask = compositeCoverageMask
        )
        val uv = output.fbo.toUvRect(output.sampleCrop)
        val quad = buildRenderQuad(
            slot = slot,
            transform = transform,
            sampleArea = sampleArea,
            style = style,
            output = output,
            uv = uv,
            samplingOrigins = samplingOrigins
        )

        if (noiseTexture != null || compositeCoverageMask != null) {
            compositeWithNoiseOrMask(
                quad = quad,
                sampleArea = sampleArea,
                sampleUv = uv,
                style = style,
                targetSize = targetSize,
                noiseTexture = noiseTexture,
                compositeCoverageMask = compositeCoverageMask,
                samplingOrigins = samplingOrigins
            )
        } else {
            compositeScreenSpaceQuad(quad, sampleArea, uv, targetSize)
        }
    }

    private fun buildRenderQuad(
        slot: SceneSlotPlan,
        transform: Mat4,
        sampleArea: Rect,
        style: Style,
        output: GaussianBlurEffect.Output,
        uv: Rect,
        samplingOrigins: BackdropCompositeSamplingOrigins
    ): RenderQuad {
        val texture = output.fbo.texture
        return RenderQuad(
            id = slot.id.value,
            center = Offset(sampleArea.left + sampleArea.width / 2f, sampleArea.top + sampleArea.height / 2f),
            size = sampleArea.size,
            uv = uv,
            texture = texture,
            alpha = style.blurOpacity,
            maskValue = 1f,
            flipTexture = samplingOrigins.blurTextureFlip,
            transform = transform,
            tint = style.tint
        )
    }

    private fun compositeWithNoiseOrMask(
        quad: RenderQuad,
        sampleArea: Rect,
        sampleUv: Rect,
        style: Style,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        compositeCoverageMask: Texture2D?,
        samplingOrigins: BackdropCompositeSamplingOrigins
    ) {
        backdropCompositeEffect.draw(
            quadBatchRenderer = quadBatchRenderer,
            input = BackdropCompositeInput(
                id = quad.id,
                blurTexture = requireNotNull(quad.texture),
                sampleRect = sampleArea,
                sampleUv = sampleUv,
                transform = requireNotNull(quad.transform),
                tint = style.tint,
                opacity = style.blurOpacity,
                targetSize = targetSize,
                noiseTexture = noiseTexture,
                noiseAlpha = style.noiseAlpha,
                compositeCoverageMask = compositeCoverageMask,
                blurSigma = style.sigma,
                samplingOrigins = samplingOrigins
            )
        )
    }

    private fun compositeScreenSpaceQuad(
        quad: RenderQuad,
        sampleArea: Rect,
        sampleUv: Rect,
        targetSize: IntSize
    ) {
        val shader = screenSpaceQuadShaderProgram.shader
        shader.bind(shaderBinder)
        shader.setFloat2("u_TargetSize", Float2(targetSize.width.toFloat(), targetSize.height.toFloat()))
        shader.setFloat("u_FragCoordFlipY", 1f)
        shader.setFloat4(
            "u_SampleRect",
            Float4(sampleArea.left, sampleArea.top, sampleArea.width, sampleArea.height)
        )
        shader.setFloat2("u_SampleUvMin", Float2(sampleUv.left, sampleUv.top))
        shader.setFloat2("u_SampleUvMax", Float2(sampleUv.right, sampleUv.bottom))

        quadBatchRenderer.begin(
            targetSize = targetSize,
            enableBlending = true,
            shaderProgram = screenSpaceQuadShaderProgram,
            traceLabel = "QuadBatchRenderer#sceneBackgroundComposite"
        )
        quadBatchRenderer.submit(quad)
        quadBatchRenderer.flush()
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
