/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.effects

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Float4
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.SimpleRenderer
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import dev.serhiiyaremych.imla.internal.render.Float2
import dev.serhiiyaremych.imla.internal.render.x
import dev.serhiiyaremych.imla.internal.render.y
import org.intellij.lang.annotations.Language
import kotlin.math.log2

/**
 * Pre-process effect for blur pipeline.
 * Performs mip-based downsampling with content centering.
 *
 * Two-pass operation:
 * 1. Cut: Extract extended area from root FBO to a sized FBO
 * 2. Target: Mip-based downsample from cut to target (scale derived from sigma)
 */
internal class PreProcessEffect(
    private val context: EffectContext
) : Effect {
    private val shader: Shader = context.shaderLibrary.loadShader(
        name = "preProcessEffect",
        vertexSrc = PRE_PROCESS_VERTEX_SHADER,
        fragmentSrc = PRE_PROCESS_SHADER
    ).apply {
        bind(context.shaderBinder)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
        setInt("u_Texture", 0)
    }

    /**
     * Apply pre-process to extract and downsample content from root FBO.
     *
     * @param rootFbo Source framebuffer to sample from
     * @param sampleArea Area in root FBO to extract (in pixels)
     * @param contentSize Actual content size (smaller than sampleArea if there's rotation padding)
     * @param sigma Blur sigma in content pixels
     * @return PreProcessOutput containing processed FBO and metadata
     */
    fun apply(
        rootFbo: Framebuffer,
        sampleArea: Rect,
        contentSize: IntSize,
        sigma: Float
    ): PreProcessOutput {
        val geometryRequest = planPreProcessGeometry(
            rootSize = rootFbo.specification.size,
            sampleArea = sampleArea,
            contentSize = contentSize,
            sigma = sigma
        )

        val cutSpec = FramebufferSpecification(
            size = geometryRequest.sourceContentSize,
            attachmentsSpec = FramebufferAttachmentSpecification.singleColor(mipmapFiltering = true, coordinateOrigin = CoordinateOrigin.TOP_LEFT)
        )
        val cutSized = context.pool.acquireBucketed(cutSpec).withSize(geometryRequest.sourceContentSize)

        blitArea(rootFbo, cutSized, geometryRequest.extendedArea)

        val targetSpec = FramebufferSpecification(
            size = geometryRequest.targetContentSize,
            attachmentsSpec = FramebufferAttachmentSpecification.singleColor(coordinateOrigin = CoordinateOrigin.TOP_LEFT)
        )
        val targetSized = context.pool.acquireBucketed(targetSpec).withSize(geometryRequest.targetContentSize)
        val geometry = geometryRequest.withContentOffsets(
            sourceContentOffset = cutSized.contentOffset,
            targetContentOffset = targetSized.contentOffset
        )

        renderMipDownsample(cutSized, targetSized, geometry.fitScale)

        return PreProcessOutput(
            fbo = targetSized,
            sourceFbo = cutSized,
            sourceSampleCrop = geometry.sourceSampleCrop,
            sampleCrop = geometry.sampleCrop,
            contentCrop = geometry.contentCrop,
            blurScale = geometryRequest.blurScale,
            noiseScale = geometryRequest.noiseScale,
            downsampleScale = geometry.fitScale
        )
    }

    private fun blitArea(
        src: Framebuffer,
        dst: SizedFramebuffer,
        area: Rect
    ) {
        val srcSize = src.specification.size

        // Calculate UV rect for the area in source FBO
        val left = area.left / srcSize.width
        val right = area.right / srcSize.width
        val topGl = srcSize.height - area.top
        val bottomGl = srcSize.height - area.bottom
        val top = topGl / srcSize.height
        val bottom = bottomGl / srcSize.height

        dst.fbo.bindForOverwrite(context.commands)
        context.commands.setViewPort(
            x = dst.contentOffset.x,
            y = dst.contentOffset.y,
            width = dst.contentSize.width,
            height = dst.contentSize.height
        )
        val srcTexture = src.colorAttachmentTexture
        srcTexture.bind()
        context.singlePassQuadExecutor.draw(
            texture = srcTexture,
            textureCoordinatesFlat = setCropCoords(left, bottom, right, top),
            flipY = false
        )
        dst.fbo.colorAttachmentTexture.bind()
        dst.fbo.colorAttachmentTexture.generateMipMaps()
    }

    private fun renderMipDownsample(
        src: SizedFramebuffer,
        dst: SizedFramebuffer,
        fitScale: Float
    ) {
        dst.fbo.bindForOverwrite(context.commands)
        // Set viewport to content area only (centered in allocated FBO)
        context.commands.setViewPort(
            x = dst.contentOffset.x,
            y = dst.contentOffset.y,
            width = dst.contentSize.width,
            height = dst.contentSize.height
        )

        src.texture.bind()
        shader.bind(context.shaderBinder)

        val targetSize = dst.contentSize
        shader.setFloat("u_Lod", calculateMipLod(fitScale))
        val scaledWidth = src.contentSize.width * fitScale
        val scaledHeight = src.contentSize.height * fitScale
        shader.setFloat2(
            "u_ContentSize",
            Float2(
                scaledWidth / targetSize.width.toFloat(),
                scaledHeight / targetSize.height.toFloat()
            )
        )

        val uvLeft = src.uvOffset.x
        val uvBottom = src.uvOffset.y
        val uvRight = uvLeft + src.uvScale.x
        val uvTop = uvBottom + src.uvScale.y
        val uvWidth = (uvRight - uvLeft).coerceAtLeast(1e-6f)
        val uvHeight = (uvTop - uvBottom).coerceAtLeast(1e-6f)
        shader.setFloat2("u_UvMin", Float2(uvLeft, uvBottom))
        shader.setFloat2("u_UvInvSize", Float2(1.0f / uvWidth, 1.0f / uvHeight))
        val uvBounds = reusableUvBounds
        uvBounds.x = uvLeft
        uvBounds.y = uvBottom
        uvBounds.z = uvRight
        uvBounds.w = uvTop
        shader.setFloat4("u_UvBounds", uvBounds)

        context.singlePassQuadExecutor.draw(
            shader = shader,
            texture = src.texture,
            textureCoordinatesFlat = setCropCoords(uvLeft, uvBottom, uvRight, uvTop),
            flipY = false
        )
    }

    /**
     * Output of pre-process effect.
     */
    data class PreProcessOutput(
        val fbo: SizedFramebuffer,
        val sourceFbo: SizedFramebuffer,
        val sourceSampleCrop: Rect,
        val sampleCrop: Rect,
        val contentCrop: Rect,
        val blurScale: Float,
        val noiseScale: Float2,
        val downsampleScale: Float
    )

    private val reusableTexCoordsFlat = FloatArray(8)
    private val reusableUvBounds = Float4(0f, 0f, 0f, 0f)

    private fun setCropCoords(left: Float, bottom: Float, right: Float, top: Float): FloatArray {
        reusableTexCoordsFlat[0] = left
        reusableTexCoordsFlat[1] = bottom
        reusableTexCoordsFlat[2] = right
        reusableTexCoordsFlat[3] = bottom
        reusableTexCoordsFlat[4] = right
        reusableTexCoordsFlat[5] = top
        reusableTexCoordsFlat[6] = left
        reusableTexCoordsFlat[7] = top
        return reusableTexCoordsFlat
    }

    private fun calculateFitScale(
        inner: IntSize,
        outer: IntSize,
        allowUpscale: Boolean
    ): Float {
        val safeInnerWidth = inner.width.coerceAtLeast(1)
        val safeInnerHeight = inner.height.coerceAtLeast(1)
        val widthScale = outer.width / safeInnerWidth.toFloat()
        val heightScale = outer.height / safeInnerHeight.toFloat()
        val scale = minOf(widthScale, heightScale)
        return if (!allowUpscale && scale > 1f) 1f else scale
    }

    private companion object {
        @Language("GLSL")
        private val PRE_PROCESS_VERTEX_SHADER = """
            #version 300 es
            precision mediump float;

            layout (std140) uniform TextureDataUBO {
                vec2 uv[4];
                float flipTexture;
                float alpha;
            } textureData;

            uniform vec2 u_UvMin;
            uniform vec2 u_UvInvSize;

            layout (location = 0) in vec2 aPosition;

            out vec2 localCoordIn;

            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);

                vec2 texCoord = textureData.uv[gl_VertexID % 4];
                texCoord.y = abs(textureData.flipTexture - texCoord.y);

                localCoordIn = (texCoord - u_UvMin) * u_UvInvSize;
            }
        """.trimIndent()

        @Language("GLSL")
        private val PRE_PROCESS_SHADER = """
            #version 300 es
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform float u_Lod;
            uniform vec2 u_ContentSize;
            uniform vec4 u_UvBounds;

            in vec2 localCoordIn;
            out vec4 color;

            const vec2 center = vec2(0.5);

            vec2 toAbsolute(vec2 uv) {
                return mix(u_UvBounds.xy, u_UvBounds.zw, uv);
            }

            vec2 calculateClampedTexCoord(vec2 coord) {
                vec2 halfSize = u_ContentSize * 0.5;
                vec2 rectMin = center - halfSize;
                vec2 rectSize = u_ContentSize;
                vec2 texCoord = clamp((coord - rectMin) / rectSize, 0.0, 1.0);
                return toAbsolute(texCoord);
            }

            void main() {
                vec2 localCoord = localCoordIn;
                vec2 clampedUV = calculateClampedTexCoord(localCoord);
                vec4 baseColor = textureLod(u_Texture, clampedUV, u_Lod);
                color = baseColor;
            }
        """.trimIndent()
    }

    private fun calculateMipLod(scale: Float): Float {
        if (scale >= 1f) return 0f
        val safeScale = scale.coerceAtLeast(1e-4f)
        return (-log2(safeScale)).coerceAtLeast(0f)
    }

}
