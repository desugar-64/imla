/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.preprocess

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.tracing.trace
import dev.romainguy.kotlin.math.Float2
import dev.serhiiyaremych.imla.renderer.Bind
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import dev.serhiiyaremych.imla.renderer.RenderCommand
import dev.serhiiyaremych.imla.renderer.Shader
import dev.serhiiyaremych.imla.renderer.SimpleRenderer
import dev.serhiiyaremych.imla.renderer.util.SizeUtil
import dev.serhiiyaremych.imla.uirenderer.processing.SimpleQuadRenderer
import dev.serhiiyaremych.imla.uirenderer.processing.blur.BlurContext
import kotlin.properties.Delegates

internal class PreProcess(
    assetManager: AssetManager,
    private val simpleQuadRenderer: SimpleQuadRenderer
) {

    private val preProcessShader = Shader.create(
        assetManager = assetManager,
        fragmentSrc = """
            #version 300 es
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform vec2 u_TexelSize;
            in vec2 maskCoord;
            in vec2 texCoord;
            out vec4 color;
            
            // Credits: Bart Wronski, https://bartwronski.com/2022/03/07/fast-gpu-friendly-antialiasing-downsampling-filter/
            const vec2 offsets[8] = vec2[8](
                vec2(-0.75777, -0.75777),
                vec2(0.75777, -0.75777),
                vec2(0.75777, 0.75777),
                vec2(-0.75777, 0.75777),
                vec2(-2.907, 0.0),
                vec2(2.907, 0.0),
                vec2(0.0, -2.907),
                vec2(0.0, 2.907)
            );

            const float weights[8] = float[8](
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
                float gray = dot(color, vec3(0.3, 0.59, 0.11));

                return mix(vec3(gray), color, saturation);
            }
            
            bool isInBounds(vec2 texCoord) {
                return texCoord.x >= 0.0 && texCoord.x <= 1.0 &&
                       texCoord.y >= 0.0 && texCoord.y <= 1.0;
            }

            vec3 sampleTexture(sampler2D tex, vec2 coord) {
                return isInBounds(coord) ? texture(tex, coord).rgb : vec3(0.0);
            }
            
            void main() {
                  vec3 baseColor = vec3(0.0);

                  float totalWeight = 0.0;
                  for (int i = 0; i < 8; i++) {
                      vec2 sampleCoord = texCoord + offsets[i] * u_TexelSize;
                      vec3 sampleColor = sampleTexture(u_Texture, sampleCoord);
                      baseColor += weights[i] * sampleColor;
                      totalWeight += weights[i] * float(any(greaterThan(sampleColor, vec3(0.0))));
                  }
  
                  totalWeight = max(totalWeight, 1e-5); // Prevent division by zero
                  baseColor /= totalWeight;
                  
                  // Desaturate borders
                  vec2 borderWidth = vec2(70)*u_TexelSize;
                  vec2 bl = smoothstep(vec2(0), borderWidth, texCoord);
                  float pct = bl.x * bl.y;
                  vec2 tr = smoothstep(vec2(0), borderWidth, 1.0-texCoord);
                  pct *= tr.x * tr.y;
                  vec3 finalColor = desaturate(baseColor, pct + 0.5);
                  color = vec4(finalColor, 1.0);

            }
        """.trimIndent()
    ).apply {
        bind()
        setInt("u_Texture", 0)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    private var cut: Framebuffer by Delegates.notNull()
    private var processed: Framebuffer by Delegates.notNull()
    private var isInitialized: Boolean = false

    fun preProcess(rootFbo: Framebuffer, area: Rect): Framebuffer = trace("preProcess") {
        init(area.size)
        cutArea(rootFbo, area, cut)

        val size = cut.specification.size / cut.specification.downSampleFactor

        trace("aaDownsampling") {
            processed.bind(Bind.DRAW)
            RenderCommand.clear()
            preProcessShader.bind()
            preProcessShader.setFloat2(
                "u_TexelSize",
                Float2(x = 1.0f / size.width, y = 1.0f / size.height)
            )
            simpleQuadRenderer.draw(shader = preProcessShader, cut.colorAttachmentTexture)
        }

        return processed
    }

    private fun cutArea(
        src: Framebuffer,
        srcArea: Rect,
        dst: Framebuffer
    ) = trace("cutArea") {
        src.bind(Bind.READ, updateViewport = false)
        dst.bind(Bind.DRAW)
        RenderCommand.blitFramebuffer(
            srcX0 = srcArea.left.toInt(),
            srcX1 = srcArea.right.toInt(),
            srcY0 = src.specification.size.height - srcArea.top.toInt(),
            srcY1 = src.specification.size.height - srcArea.bottom.toInt(),
            dstX0 = 0, dstX1 = srcArea.width.toInt(),
            dstY0 = 0, dstY1 = srcArea.height.toInt()
        )
        trace("generateMipMaps") {
            dst.colorAttachmentTexture.bind()
            dst.colorAttachmentTexture.generateMipMaps()
        }
    }

    private fun init(size: Size) {
        if (isInitialized.not()) {
            val spec = FramebufferSpecification(
                size = size.toIntSize(),
                attachmentsSpec = FramebufferAttachmentSpecification(
                    attachments = listOf(
                        FramebufferTextureSpecification(
                            format = FramebufferTextureFormat.RGBA8,
                            flip = true
                        )
                    )
                )
            )

            val powerOfTwoSize =
                SizeUtil.closetPOTUp(size.toIntSize()) / 10 //* BlurContext.PASS_SCALE

            processed = Framebuffer.create(
                spec.copy(size = powerOfTwoSize)
            )
            cut = Framebuffer.create(
                spec.copy(
                    attachmentsSpec = FramebufferAttachmentSpecification(
                        attachments = listOf(
                            FramebufferTextureSpecification(
                                FramebufferTextureFormat.RGBA8,
                                mipmapFiltering = true,
                                flip = true
                            )
                        )
                    )
                )
            )
            isInitialized = true
        }
    }
}

internal operator fun IntSize.times(value: Float): IntSize {
    return IntSize(
        width = (this.width * value).toInt(),
        height = (this.height * value).toInt()
    )
}
