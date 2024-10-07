/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.preprocess

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import androidx.tracing.trace
import dev.romainguy.kotlin.math.Float2
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import dev.serhiiyaremych.imla.renderer.util.SizeUtil
import dev.serhiiyaremych.imla.uirenderer.processing.SimpleQuadRenderer
import dev.serhiiyaremych.imla.uirenderer.processing.blur.BlurContext
import kotlin.properties.Delegates

internal class PreProcessFilter(
    assetManager: AssetManager,
    private val simpleQuadRenderer: SimpleQuadRenderer
) {

    private val preProcessShader = Shader.create(
        fragmentSrc = """
            #version 300 es
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform vec2 u_TexelSize;
            uniform vec2 u_ContentSize;
            
            in vec2 maskCoord;
            in vec2 texCoord;
            out vec4 color;
            
            const vec2 center = vec2(0.5);
            const vec3 luma = vec3(0.3, 0.59, 0.11);
            
            // Credits: Bart Wronski, https://bartwronski.com/2022/03/07/fast-gpu-friendly-antialiasing-downsampling-filter/
            const vec2 OFFSETS[8] = vec2[8](
                vec2(-0.95777, -0.95777),
                vec2(0.95777, -0.95777),
                vec2(0.95777, 0.95777),
                vec2(-0.95777, 0.95777),
                vec2(-3.907, 0.0),
                vec2(3.907, 0.0),
                vec2(0.0, -3.907),
                vec2(0.0, 3.907)
            );
            
//            const vec2 OFFSETS[8] = vec2[8](
//                vec2(-0.75777, -0.75777),
//                vec2(0.75777, -0.75777),
//                vec2(0.75777, 0.75777),
//                vec2(-0.75777, 0.75777),
//                vec2(-2.907, 0.0),
//                vec2(2.907, 0.0),
//                vec2(0.0, -2.907),
//                vec2(0.0, 2.907)
//            );

            const float WEIGHTS[8] = float[8](
                0.37487566,
                0.37487566,
                0.37487566,
                0.37487566,
                -0.12487566,
                -0.12487566,
                -0.12487566,
                -0.12487566
            );
            
            vec3 desaturate(vec3 color, float saturation) {
                // Convert color to grayscale (luminosity method)
                float gray = dot(color, luma);
                return mix(vec3(gray), color, saturation);
            }
            
            vec2 calculateClampedTexCoord(vec2 coord) {
                vec2 halfSize = u_ContentSize * 0.5;
                vec2 rectMin = center - halfSize;
                vec2 rectMax = center + halfSize;
                vec2 rectSize = u_ContentSize;
            
                vec2 texCoord = clamp((coord - rectMin) / rectSize, 0.0, 1.0);
                return texCoord;
            }
            
            float sdfRoundRect(vec2 point, vec2 rectSize, vec2 cornerRadius) {
                cornerRadius = min(cornerRadius, rectSize);
                vec2 dist = abs(point) - rectSize + cornerRadius;
                float outsideDistance = length(max(dist, 0.0));
                float insideDistance = min(max(dist.x, dist.y), 0.0);
                return outsideDistance + insideDistance - length(cornerRadius);
            }
            
            float calculateBlendFactor(vec2 coord) {
                vec2 uv = coord * 2.0 - 1.0;
                highp vec2 corners = vec2(0.01);
                return sdfRoundRect(uv, u_ContentSize - 0.01, corners);
            }
            
            vec3 applyChromaticAberration(sampler2D tex, vec2 uv, vec2 clampedUV) {
                vec2 distFromCenter = (center-uv);
                float circleSDF = (1.-smoothstep(0.0, max(u_ContentSize.x, u_ContentSize.y), length(distFromCenter)));
                vec2 offset = circleSDF*(u_TexelSize*5.);
                vec3 color;
                color.r = texture(tex, clampedUV + offset).r;
                color.g = texture(tex, clampedUV).g;
                color.b = texture(tex, clampedUV - offset).b;
                return color;
            }

            vec4 sampleTexture(sampler2D tex, vec2 uv) {
                float blendFactor = calculateBlendFactor(uv);

                vec2 clampledUV = calculateClampedTexCoord(uv);
                vec4 sharpSample = texture(tex, clampledUV);
                
                vec3 color = applyChromaticAberration(tex, uv, clampledUV);
                sharpSample = vec4(color, 1.0);
                

                float mipLevel = 5.0;
                vec4 mipSample = textureLod(tex, clampledUV, mipLevel);
                
                // debug draw sdf mask
                // vec3 rectColor = vec3(0.1, 0.4, 0.7); // Rectangle color
                // vec3 bgColor = vec3(1.0); // Background color
                // vec4 sdfMaskColor = vec4(mix(bgColor, rectColor, alpha), 1.0);
    
                // Edge smoothing
                float alpha = 1.0 - smoothstep(-0.03, 0.0, blendFactor);
                return mix(vec4(desaturate(mipSample.rgb, alpha+0.4), 1.0), sharpSample, alpha);
            }

            
            void main() {
                  vec4 baseColor = vec4(0.0);
                  
                  float totalWeight = 0.0;
                  for (int i = 0; i < 8; i++) {
                      vec2 sampleCoord = texCoord + OFFSETS[i] * u_TexelSize;
                      vec4 sampleColor = sampleTexture(u_Texture, sampleCoord);
                      baseColor += WEIGHTS[i] * sampleColor;
                      totalWeight += WEIGHTS[i];
                  }
  
                  totalWeight = max(totalWeight, 1e-5); // Prevent division by zero
                  baseColor /= totalWeight;
                  color = baseColor;
            }
        """.trimIndent(),
        assetManager = assetManager
    ).apply {
        bind()
        setInt("u_Texture", 0)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    var cut: Framebuffer by Delegates.notNull()
        private set

    private var target: Framebuffer by Delegates.notNull()

    private var isInitialized: Boolean = false

    var contentCrop = Rect.Zero
        private set

    var leftExtend: Float = 0f
    var topExtend: Float = 0f
    var rightExtend: Float = 0f
    var bottomExtend: Float = 0f

    private var extendedContentBounds: Rect = Rect.Zero

    fun preProcess(rootFbo: Framebuffer, area: Rect): Framebuffer = trace("preProcess") {

        init(rootFbo, area)
        cutArea(rootFbo, cut)

        val targetSize = target.specification.size / target.specification.downSampleFactor
        val textureSize = cut.specification.size

        trace("aaDownsampling") {
            target.bind(Bind.DRAW)
            RenderCommand.clear()
            preProcessShader.bind()
            preProcessShader.setFloat2(
                name = "u_TexelSize",
                value = Float2(x = 1.0f / targetSize.width, y = 1.0f / targetSize.height)
            )

            val scale = calculateFitScale(inner = textureSize, outer = targetSize)

            val scaledWidth = textureSize.width * scale
            val scaledHeight = textureSize.height * scale

            val normalizedContentBounds = Float2(
                x = scaledWidth / targetSize.width.toFloat(),
                y = scaledHeight / targetSize.height.toFloat(),
            )
            preProcessShader.setFloat2(
                name = "u_ContentSize",
                value = normalizedContentBounds
            )
            simpleQuadRenderer.draw(shader = preProcessShader, cut.colorAttachmentTexture)
            target.colorAttachmentTexture.bind()
            target.colorAttachmentTexture.generateMipMaps()
        }

        return target
    }

    private fun calculateFitScale(inner: IntSize, outer: IntSize): Float {
        val innerIsBigger = (inner.width * inner.height) > (outer.width * outer.height)
        val widthScale = outer.width / inner.width.toFloat()
        val heightScale = outer.height / inner.height.toFloat()
        val scale = minOf(widthScale, heightScale)
        return if (innerIsBigger || scale > 1f) scale else 1f
    }

    private fun cutArea(
        src: Framebuffer,
        dst: Framebuffer
    ) = trace("cutArea") {
        src.bind(Bind.READ, updateViewport = false)
        dst.bind(Bind.DRAW)

        RenderCommand.blitFramebuffer(
            srcX0 = extendedContentBounds.left.toInt(),
            srcX1 = extendedContentBounds.right.toInt(),
            srcY0 = src.specification.size.height - (extendedContentBounds.top).toInt(),
            srcY1 = src.specification.size.height - (extendedContentBounds.bottom).toInt(),
            dstX0 = 0, dstX1 = dst.specification.size.width.toInt(),
            dstY0 = 0, dstY1 = dst.specification.size.height.toInt()
        )

        trace("generateMipMaps") {
            dst.colorAttachmentTexture.bind()
            dst.colorAttachmentTexture.generateMipMaps()
        }
    }

    private fun init(rootFbo: Framebuffer, area: Rect) {
        if (isInitialized.not()) {
            leftExtend = minOf(EXPAND_CONTENT_PX, area.left)
            rightExtend =
                minOf(EXPAND_CONTENT_PX, rootFbo.specification.size.width - area.right)
            topExtend = minOf(EXPAND_CONTENT_PX, area.top)
            bottomExtend =
                minOf(EXPAND_CONTENT_PX, rootFbo.specification.size.height - area.bottom)

            extendedContentBounds = Rect(
                topLeft = Offset(
                    x = (area.left - leftExtend),
                    y = (area.top - topExtend)
                ),
                bottomRight = Offset(
                    x = (area.right + rightExtend),
                    y = (area.bottom + bottomExtend)
                )
            )
            val extendedSize = extendedContentBounds.size.toIntSize()
            val spec = FramebufferSpecification(
                size = extendedSize,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(flip = true)
            )
            cut = Framebuffer.create(
                spec.copy(
                    attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                        mipmapFiltering = true,
                        flip = true
                    )
                )
            )
            val potUpSize = SizeUtil.closestPOTUp(extendedSize)
            // render target if half size of pot input texture
            target = Framebuffer.create(spec.copy(size = potUpSize * BlurContext.PASS_SCALE))

            val targetSize = target.specification.size
            val fitScale = calculateFitScale(
                inner = extendedContentBounds.size.toIntSize(),
                outer = targetSize
            )
            val fitToScaleSize = IntSize(
                width = (extendedContentBounds.size.width * fitScale).toInt(),
                height = (extendedContentBounds.size.height * fitScale).toInt()
            )
            val centeredBounds = Rect(
                offset = Offset(
                    x = (targetSize.width - fitToScaleSize.width) / 2f,
                    y = (targetSize.height - fitToScaleSize.height) / 2f
                ),
                size = fitToScaleSize.toSize()
            )
            contentCrop = Rect(
                left = centeredBounds.left + (leftExtend / 2f) + 1f,
                top = centeredBounds.top + (topExtend / 2f) + 1f,
                right = centeredBounds.right - (rightExtend / 2f) - 1f,
                bottom = centeredBounds.bottom - (bottomExtend / 2f) - 1f,
            )

            isInitialized = true
        }
    }

    companion object {
        const val EXPAND_CONTENT_PX = 20f
    }
}

internal operator fun Rect.times(value: Float): Rect {
    return Rect(
        topLeft = Offset(
            x = left * value,
            y = top * value
        ),
        bottomRight = Offset(
            x = right * value,
            y = bottom * value
        )
    )
}

internal operator fun IntSize.times(value: Float): IntSize {
    return IntSize(
        width = (this.width * value).toInt(),
        height = (this.height * value).toInt()
    )
}
