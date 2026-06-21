/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.preprocess

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Float2
import dev.romainguy.kotlin.math.Float4
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.SimpleRenderer
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import dev.serhiiyaremych.imla.internal.render.util.roundToMultipleOf
import org.intellij.lang.annotations.Language
import kotlin.properties.Delegates
import kotlin.math.sqrt
import kotlin.math.max

@Language("GLSL")
private const val preProcessFragmentSource = """
            #version 300 es
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform vec2 u_TexelSize;
            uniform vec2 u_ContentSize;
            uniform vec4 u_UvBounds;
            
            in vec2 maskCoord;
            in vec2 texCoord;
            out vec4 color;
            
            const vec2 center = vec2(0.5);
            const vec3 luma = vec3(0.3, 0.59, 0.11);

            vec2 toLocal(vec2 uv) {
                vec2 size = u_UvBounds.zw - u_UvBounds.xy;
                return (uv - u_UvBounds.xy) / max(size, vec2(1e-6));
            }

            vec2 toAbsolute(vec2 uv) {
                return mix(u_UvBounds.xy, u_UvBounds.zw, uv);
            }

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
                float gray = dot(color, luma);
                return mix(vec3(gray), color, saturation);
            }
            
            vec2 calculateClampedTexCoord(vec2 coord) {
                vec2 halfSize = u_ContentSize * 0.5;
                vec2 rectMin = center - halfSize;
                vec2 rectMax = center + halfSize;
                vec2 rectSize = u_ContentSize;
            
                vec2 texCoord = clamp((coord - rectMin) / rectSize, 0.0, 1.0);
                return toAbsolute(texCoord);
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
            
            vec3 applyChromaticAberration(sampler2D tex, vec2 uv) {
                vec2 distFromCenter = (center-uv);
                float circleSDF = (1.-smoothstep(0.0, max(u_ContentSize.x, u_ContentSize.y), length(distFromCenter)));
                vec2 offset = circleSDF*(u_TexelSize*5.);
                vec3 color;
                vec2 redUV = calculateClampedTexCoord(uv + vec2(offset.x, 0.0));
                vec2 greenUV = calculateClampedTexCoord(uv + vec2(0.0, offset.y * 0.3));
                vec2 blueUV = calculateClampedTexCoord(uv - vec2(offset.x, 0.0));
                color.r = texture(tex, redUV).r;
                color.g = texture(tex, greenUV).g;
                color.b = texture(tex, blueUV).b;
                return color;
            }

            void main() {
                  vec2 localCoord = toLocal(texCoord);
                  float blendFactor = calculateBlendFactor(localCoord);
                  float alpha = 1.0 - smoothstep(-0.03, 0.0, blendFactor);

                  vec4 baseColor = vec4(0.0);
                  float totalWeight = 0.0;

                  for (int i = 0; i < 8; i++) {
                      vec2 sampleCoord = localCoord + OFFSETS[i] * u_TexelSize;
                      vec2 clampedUV = calculateClampedTexCoord(sampleCoord);
                      baseColor += WEIGHTS[i] * texture(u_Texture, clampedUV);
                      totalWeight += WEIGHTS[i];
                  }

                  baseColor /= max(totalWeight, 1e-5);

                  vec2 clampedCenter = calculateClampedTexCoord(localCoord);
                  vec3 caColor = applyChromaticAberration(u_Texture, localCoord);

                  baseColor.rgb = mix(baseColor.rgb, caColor, 0.5);

                  vec4 mipSample = textureLod(u_Texture, clampedCenter, 5.0);

                  float edgeBlend = mix(1.0, alpha, 0.5);
                  vec4 result = mix(vec4(desaturate(mipSample.rgb, alpha + 0.4), 1.0), baseColor, edgeBlend);
                  // Force alpha=1.0 to prevent semi-transparency during blur compositing
                  // The edge anti-aliasing (alpha variable) only affects color mixing, not output alpha
                  color = vec4(result.rgb, 1.0);
            }
        """

internal class PreProcessFilter(
    shaderLibrary: ShaderLibrary,
    private val simpleQuadRenderer: SimpleQuadRenderer,
    private val shaderBinder: ShaderBinder,
    private val fboPool: FramebufferLendingPool
) {

    private val preProcessShader = shaderLibrary.loadShader(
        name = "preProcess",
        fragmentSrc = preProcessFragmentSource.trimIndent(),
    ).apply {
        bind(shaderBinder)
        setInt("u_Texture", 0)
        bindUniformBlock(
            SimpleRenderer.TEXTURE_DATA_UBO_BLOCK,
            SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT
        )
    }

    private var cutFboSpec: FramebufferSpecification by Delegates.notNull()
    private var targetFboSpec: FramebufferSpecification by Delegates.notNull()
    private var cutFramebuffer: Framebuffer? = null
    private var targetFramebuffer: Framebuffer? = null

    private var isInitialized: Boolean = false
    private var lastArea: Rect? = null
    private var lastContentSize: IntSize? = null
    private var extendedSize: IntSize = IntSize.Zero
    private var referenceSize: IntSize = IntSize.Zero
    private var cutRequestedSize: IntSize = IntSize.Zero
    private var targetRequestedSize: IntSize = IntSize.Zero

    var contentCrop = Rect.Zero
        private set
    var blurScale: Float = 1f
        private set
    var noiseScale: Float2 = Float2(1f, 1f)
        private set

    var leftExtend: Float = 0f
    var topExtend: Float = 0f
    var rightExtend: Float = 0f
    var bottomExtend: Float = 0f

    private var extendedContentBounds: Rect = Rect.Zero

    // Flat float array: [u0, v0, u1, v1, u2, v2, u3, v3] - avoids Offset boxing
    private val reusableCropCoordsFlat = FloatArray(8)
    private val reusableFullCoordsFlat = FloatArray(8)
    private val reusableUvBounds = Float4()
    private val reusableUvBoundsFlipped = Float4()

    internal val requestedSize: IntSize get() = targetRequestedSize

    fun dispose() {
        cutFramebuffer?.let { fboPool.release(it) }
        targetFramebuffer?.let { fboPool.release(it) }
        cutFramebuffer = null
        targetFramebuffer = null
        isInitialized = false
        lastArea = null
        cutRequestedSize = IntSize.Zero
        targetRequestedSize = IntSize.Zero
    }

    fun preProcess(
        rootFbo: Framebuffer,
        sampleArea: Rect,
        contentSize: IntSize
    ): Framebuffer = trace("preProcess") {
        init(rootFbo, sampleArea, contentSize)

        val target = targetFramebuffer ?: error("target framebuffer not initialized")
        val cut = cutFramebuffer ?: error("cut framebuffer not initialized")

        cutArea(rootFbo, cut)

        val targetSize = targetRequestedSize / target.specification.downSampleFactor

        trace("aaDownsampling") {
            // bindForOverwrite invalidates FBO; skip clear since full-screen quad overwrites all pixels
            target.bindForOverwrite()
            RenderCommand.setViewPort(width = targetRequestedSize.width, height = targetRequestedSize.height)
            preProcessShader.bind(shaderBinder)
            preProcessShader.setFloat2(
                name = "u_TexelSize",
                value = Float2(1.0f / targetSize.width, 1.0f / targetSize.height)
            )

            val scale = calculateFitScale(
                inner = extendedSize,
                outer = targetSize,
                allowUpscale = false
            )

            val scaledWidth = extendedSize.width * scale
            val scaledHeight = extendedSize.height * scale

            val normalizedContentBounds = Float2(
                scaledWidth / targetSize.width.toFloat(),
                scaledHeight / targetSize.height.toFloat()
            )
            preProcessShader.setFloat2(
                name = "u_ContentSize",
                value = normalizedContentBounds
            )
            val uvBounds = uvBounds(cutRequestedSize, cut.specification.size)
            val uMin = uvBounds.x
            val vMin = uvBounds.y
            val uMax = uvBounds.z
            val vMax = uvBounds.w
            val cutTexture = cut.colorAttachmentTexture as Texture2D
            if (cutTexture.flipTexture) {
                reusableUvBoundsFlipped.x = uMin
                reusableUvBoundsFlipped.y = 1f - vMax
                reusableUvBoundsFlipped.z = uMax
                reusableUvBoundsFlipped.w = 1f - vMin
                preProcessShader.setFloat4(
                    name = "u_UvBounds",
                    value = reusableUvBoundsFlipped
                )
            } else {
                preProcessShader.setFloat4(
                    name = "u_UvBounds",
                    value = uvBounds
                )
            }
            simpleQuadRenderer.draw(
                shader = preProcessShader,
                texture = cut.colorAttachmentTexture as Texture2D,
                textureCoordinatesFlat = fullCoordsFlat(cutRequestedSize, cut.specification.size)
            )
            val leftBorder = 0
            val rightBorder = target.specification.size.width - targetRequestedSize.width
            val bottomBorder = 0
            val topBorder = target.specification.size.height - targetRequestedSize.height
            val padBottom = if (cutTexture.flipTexture) vMax else vMin
            val padTop = if (cutTexture.flipTexture) vMin else vMax
            if (bottomBorder > 0) {
                RenderCommand.setViewPort(
                    x = 0,
                    y = 0,
                    width = targetRequestedSize.width,
                    height = bottomBorder
                )
                simpleQuadRenderer.draw(
                    shader = preProcessShader,
                    texture = cut.colorAttachmentTexture as Texture2D,
                    textureCoordinatesFlat = setCropCoords(uMin, padBottom, uMax, padBottom)
                )
            }
            if (rightBorder > 0) {
                RenderCommand.setViewPort(
                    x = targetRequestedSize.width,
                    y = 0,
                    width = rightBorder,
                    height = targetRequestedSize.height
                )
                simpleQuadRenderer.draw(
                    shader = preProcessShader,
                    texture = cut.colorAttachmentTexture as Texture2D,
                    textureCoordinatesFlat = setCropCoords(uMax, padBottom, uMax, padTop)
                )
            }
            if (leftBorder > 0) {
                RenderCommand.setViewPort(
                    x = 0,
                    y = 0,
                    width = leftBorder,
                    height = targetRequestedSize.height
                )
                simpleQuadRenderer.draw(
                    shader = preProcessShader,
                    texture = cut.colorAttachmentTexture as Texture2D,
                    textureCoordinatesFlat = setCropCoords(uMin, padBottom, uMin, padTop)
                )
            }
            if (topBorder > 0) {
                RenderCommand.setViewPort(
                    x = 0,
                    y = targetRequestedSize.height,
                    width = targetRequestedSize.width,
                    height = topBorder
                )
                simpleQuadRenderer.draw(
                    shader = preProcessShader,
                    texture = cut.colorAttachmentTexture as Texture2D,
                    textureCoordinatesFlat = setCropCoords(uMin, padTop, uMax, padTop)
                )
            }
            if (rightBorder > 0 && bottomBorder > 0) {
                RenderCommand.setViewPort(
                    x = targetRequestedSize.width,
                    y = 0,
                    width = rightBorder,
                    height = bottomBorder
                )
                simpleQuadRenderer.draw(
                    shader = preProcessShader,
                    texture = cut.colorAttachmentTexture as Texture2D,
                    textureCoordinatesFlat = setCropCoords(uMax, padBottom, uMax, padBottom)
                )
            }
            if (leftBorder > 0 && bottomBorder > 0) {
                RenderCommand.setViewPort(
                    x = 0,
                    y = 0,
                    width = leftBorder,
                    height = bottomBorder
                )
                simpleQuadRenderer.draw(
                    shader = preProcessShader,
                    texture = cut.colorAttachmentTexture as Texture2D,
                    textureCoordinatesFlat = setCropCoords(uMin, padBottom, uMin, padBottom)
                )
            }
            if (leftBorder > 0 && topBorder > 0) {
                RenderCommand.setViewPort(
                    x = 0,
                    y = targetRequestedSize.height,
                    width = leftBorder,
                    height = topBorder
                )
                simpleQuadRenderer.draw(
                    shader = preProcessShader,
                    texture = cut.colorAttachmentTexture as Texture2D,
                    textureCoordinatesFlat = setCropCoords(uMin, padTop, uMin, padTop)
                )
            }
            if (rightBorder > 0 && topBorder > 0) {
                RenderCommand.setViewPort(
                    x = targetRequestedSize.width,
                    y = targetRequestedSize.height,
                    width = rightBorder,
                    height = topBorder
                )
                simpleQuadRenderer.draw(
                    shader = preProcessShader,
                    texture = cut.colorAttachmentTexture as Texture2D,
                    textureCoordinatesFlat = setCropCoords(uMax, padTop, uMax, padTop)
                )
            }
            target.colorAttachmentTexture.bind()
            target.colorAttachmentTexture.generateMipMaps()
        }

        return target
    }

    private fun calculateFitScale(
        inner: IntSize,
        outer: IntSize,
        allowUpscale: Boolean
    ): Float {
        val widthScale = outer.width / inner.width.toFloat()
        val heightScale = outer.height / inner.height.toFloat()
        val scale = minOf(widthScale, heightScale)
        if (!allowUpscale && scale > 1f) return 1f
        return scale
    }

    private fun cutArea(
        src: Framebuffer,
        dst: Framebuffer
    ) = trace("cutArea") {
        val srcSize = src.specification.size

        val left = extendedContentBounds.left / srcSize.width.toFloat()
        val right = extendedContentBounds.right / srcSize.width.toFloat()

        val bottomGlPx = srcSize.height.toFloat() - extendedContentBounds.bottom
        val topGlPx = srcSize.height.toFloat() - extendedContentBounds.top

        val bottom = bottomGlPx / srcSize.height.toFloat()
        val top = topGlPx / srcSize.height.toFloat()

        // bindForOverwrite invalidates FBO; skip clear since full-screen quad overwrites all pixels
        dst.bindForOverwrite()
        val offsetX = ((dst.specification.size.width - cutRequestedSize.width) / 2).coerceAtLeast(0)
        val offsetY = ((dst.specification.size.height - cutRequestedSize.height) / 2).coerceAtLeast(0)
        RenderCommand.setViewPort(
            x = offsetX,
            y = offsetY,
            width = cutRequestedSize.width,
            height = cutRequestedSize.height
        )
        simpleQuadRenderer.draw(
            texture = src.colorAttachmentTexture,
            textureCoordinatesFlat = setCropCoords(left, bottom, right, top),
            flipY = false
        )

        val leftBorder = offsetX
        val rightBorder = dst.specification.size.width - offsetX - cutRequestedSize.width
        val bottomBorder = offsetY
        val topBorder = dst.specification.size.height - offsetY - cutRequestedSize.height

        if (bottomBorder > 0) {
            RenderCommand.setViewPort(
                x = 0,
                y = 0,
                width = dst.specification.size.width,
                height = bottomBorder
            )
            simpleQuadRenderer.draw(
                texture = src.colorAttachmentTexture,
                textureCoordinatesFlat = setCropCoords(left, bottom, right, bottom),
                flipY = false
            )
        }
        if (topBorder > 0) {
            RenderCommand.setViewPort(
                x = 0,
                y = offsetY + cutRequestedSize.height,
                width = dst.specification.size.width,
                height = topBorder
            )
            simpleQuadRenderer.draw(
                texture = src.colorAttachmentTexture,
                textureCoordinatesFlat = setCropCoords(left, top, right, top),
                flipY = false
            )
        }
        if (leftBorder > 0) {
            RenderCommand.setViewPort(
                x = 0,
                y = offsetY,
                width = leftBorder,
                height = cutRequestedSize.height
            )
            simpleQuadRenderer.draw(
                texture = src.colorAttachmentTexture,
                textureCoordinatesFlat = setCropCoords(left, bottom, left, top),
                flipY = false
            )
        }
        if (rightBorder > 0) {
            RenderCommand.setViewPort(
                x = offsetX + cutRequestedSize.width,
                y = offsetY,
                width = rightBorder,
                height = cutRequestedSize.height
            )
            simpleQuadRenderer.draw(
                texture = src.colorAttachmentTexture,
                textureCoordinatesFlat = setCropCoords(right, bottom, right, top),
                flipY = false
            )
        }

        trace("generateMipMaps") {
            dst.colorAttachmentTexture.bind()
            dst.colorAttachmentTexture.generateMipMaps()
        }
    }

    private fun init(rootFbo: Framebuffer, area: Rect, contentSize: IntSize) {
        if (isInitialized && lastArea == area && lastContentSize == contentSize) return

        leftExtend = minOf(EXPAND_CONTENT_PX, area.left)
        rightExtend = minOf(EXPAND_CONTENT_PX, rootFbo.specification.size.width - area.right)
        topExtend = minOf(EXPAND_CONTENT_PX, area.top)
        bottomExtend = minOf(EXPAND_CONTENT_PX, rootFbo.specification.size.height - area.bottom)

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
        extendedSize = extendedContentBounds.size.toIntSize()
        cutRequestedSize = extendedSize
        // Use fixed reference size based on intrinsic content size + max extension for consistent blur
        // This ensures blur FBO size is independent of edge proximity
        referenceSize = IntSize(
            width = (contentSize.width.toFloat() + EXPAND_CONTENT_PX * 2).toInt(),
            height = (contentSize.height.toFloat() + EXPAND_CONTENT_PX * 2).toInt()
        )
        targetRequestedSize = (referenceSize * 0.5f).roundToMultipleOfFour()
        val cutBucketedSize = cutRequestedSize.roundToMultipleOf(FBO_BUCKET_SIZE)
        val targetBucketedSize = targetRequestedSize.roundToMultipleOf(FBO_BUCKET_SIZE)
        if (!isInitialized || cutFboSpec.size != cutBucketedSize) {
            cutFboSpec = FramebufferSpecification(
                size = cutBucketedSize,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                    mipmapFiltering = true,
                    coordinateOrigin = CoordinateOrigin.TOP_LEFT
                )
            )
        }
        if (!isInitialized || targetFboSpec.size != targetBucketedSize) {
            targetFboSpec = FramebufferSpecification(
                size = targetBucketedSize,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(coordinateOrigin = CoordinateOrigin.TOP_LEFT)
            )
        }

        val targetSize = targetRequestedSize
        val fitScale = calculateFitScale(
            inner = extendedSize,
            outer = targetSize,
            allowUpscale = false
        )
        val fitToScaleSize = IntSize(
            width = (extendedSize.width * fitScale).toInt(),
            height = (extendedSize.height * fitScale).toInt()
        )
        val centeredBounds = Rect(
            offset = Offset(
                x = (targetSize.width - fitToScaleSize.width) / 2f,
                y = (targetSize.height - fitToScaleSize.height) / 2f
            ),
            size = fitToScaleSize.toSize()
        )
        val rotationPadX = ((area.width - contentSize.width.toFloat()).coerceAtLeast(0f)) / 2f
        val rotationPadY = ((area.height - contentSize.height.toFloat()).coerceAtLeast(0f)) / 2f
        val scaledContentWidth = contentSize.width.toFloat() * fitScale
        val scaledContentHeight = contentSize.height.toFloat() * fitScale
        val contentLeft = centeredBounds.left + ((leftExtend + rotationPadX) * fitScale)
        val contentTop = centeredBounds.top + ((topExtend + rotationPadY) * fitScale)
        contentCrop = Rect(
            left = contentLeft,
            top = contentTop,
            right = contentLeft + scaledContentWidth,
            bottom = contentTop + scaledContentHeight,
        )
        val blurScaleX = referenceSize.width.toFloat() / extendedSize.width.toFloat()
        val blurScaleY = referenceSize.height.toFloat() / extendedSize.height.toFloat()
        val blurScaleAvg = sqrt(blurScaleX * blurScaleY)
        blurScale = blurScaleAvg
        val safeContentWidth = max(contentSize.width, 1).toFloat()
        val safeContentHeight = max(contentSize.height, 1).toFloat()
        noiseScale = Float2(
            extendedSize.width.toFloat() / safeContentWidth,
            extendedSize.height.toFloat() / safeContentHeight
        )

        // Only release/acquire if spec actually changed
        if (cutFramebuffer?.specification != cutFboSpec) {
            cutFramebuffer?.let { fboPool.release(it) }
            cutFramebuffer = fboPool.acquire(cutFboSpec)
        }
        if (targetFramebuffer?.specification != targetFboSpec) {
            targetFramebuffer?.let { fboPool.release(it) }
            targetFramebuffer = fboPool.acquire(targetFboSpec)
        }

        lastArea = area
        lastContentSize = contentSize
        isInitialized = true
    }

    private companion object {
        const val EXPAND_CONTENT_PX = 20f
        const val FBO_BUCKET_SIZE = 64
    }

    private fun fullCoordsFlat(requestedSize: IntSize, allocatedSize: IntSize): FloatArray {
        val safeWidth = allocatedSize.width.coerceAtLeast(1)
        val safeHeight = allocatedSize.height.coerceAtLeast(1)
        val offsetX = ((allocatedSize.width - requestedSize.width) / 2).coerceAtLeast(0)
        val offsetY = ((allocatedSize.height - requestedSize.height) / 2).coerceAtLeast(0)
        val uMin = (offsetX.toFloat() / safeWidth).coerceIn(0f, 1f)
        val vMin = (offsetY.toFloat() / safeHeight).coerceIn(0f, 1f)
        val uMaxCoord = ((offsetX + requestedSize.width).toFloat() / safeWidth).coerceIn(0f, 1f)
        val vMaxCoord = ((offsetY + requestedSize.height).toFloat() / safeHeight).coerceIn(0f, 1f)
        // BL
        reusableFullCoordsFlat[0] = uMin
        reusableFullCoordsFlat[1] = vMin
        // BR
        reusableFullCoordsFlat[2] = uMaxCoord
        reusableFullCoordsFlat[3] = vMin
        // TR
        reusableFullCoordsFlat[4] = uMaxCoord
        reusableFullCoordsFlat[5] = vMaxCoord
        // TL
        reusableFullCoordsFlat[6] = uMin
        reusableFullCoordsFlat[7] = vMaxCoord
        return reusableFullCoordsFlat
    }

    private fun uvBounds(requestedSize: IntSize, allocatedSize: IntSize): Float4 {
        val safeWidth = allocatedSize.width.coerceAtLeast(1)
        val safeHeight = allocatedSize.height.coerceAtLeast(1)
        val offsetX = ((allocatedSize.width - requestedSize.width) / 2).coerceAtLeast(0)
        val offsetY = ((allocatedSize.height - requestedSize.height) / 2).coerceAtLeast(0)
        val uMin = (offsetX.toFloat() / safeWidth).coerceIn(0f, 1f)
        val vMin = (offsetY.toFloat() / safeHeight).coerceIn(0f, 1f)
        val uMaxCoord = ((offsetX + requestedSize.width).toFloat() / safeWidth).coerceIn(0f, 1f)
        val vMaxCoord = ((offsetY + requestedSize.height).toFloat() / safeHeight).coerceIn(0f, 1f)
        reusableUvBounds.x = uMin
        reusableUvBounds.y = vMin
        reusableUvBounds.z = uMaxCoord
        reusableUvBounds.w = vMaxCoord
        return reusableUvBounds
    }

    private fun setCropCoords(left: Float, bottom: Float, right: Float, top: Float): FloatArray {
        // BL
        reusableCropCoordsFlat[0] = left
        reusableCropCoordsFlat[1] = bottom
        // BR
        reusableCropCoordsFlat[2] = right
        reusableCropCoordsFlat[3] = bottom
        // TR
        reusableCropCoordsFlat[4] = right
        reusableCropCoordsFlat[5] = top
        // TL
        reusableCropCoordsFlat[6] = left
        reusableCropCoordsFlat[7] = top
        return reusableCropCoordsFlat
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

private fun roundUpToMultipleOfFour(value: Int): Int = (value + 3) / 4 * 4

private fun IntSize.roundToMultipleOfFour(): IntSize =
    IntSize(
        width = roundUpToMultipleOfFour(this.width),
        height = roundUpToMultipleOfFour(this.height)
    )
