/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.effects

import androidx.compose.ui.geometry.Rect
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import dev.serhiiyaremych.imla.internal.render.x
import dev.serhiiyaremych.imla.internal.render.y

internal class GaussianBlurEffect(
    private val context: EffectContext
) : Effect {
    data class Output(
        val fbo: SizedFramebuffer,
        val sampleCrop: Rect,
        val contentCrop: Rect
    )

    private var kernelShaderProgram: GaussianBlurKernelShaderProgram? = null
    private var outputSpec: FramebufferSpecification? = null
    private var outputAttachmentsSpec: FramebufferAttachmentSpecification? = null
    private var outputDownSampleFactor: Int = 1
    private val reusableTextureCoordinates = FloatArray(8)

    fun reset() {
        kernelShaderProgram?.destroy()
        kernelShaderProgram = null
        outputSpec = null
        outputAttachmentsSpec = null
        outputDownSampleFactor = 1
        GaussianKernel.clearCache()
    }

    fun applyEffect(
        input: PreProcessEffect.PreProcessOutput,
        sigma: Float,
        maskTexture: Texture2D? = null
    ): Output = trace("GaussianBlurEffect#apply") {
        if (sigma < MIN_SIGMA) {
            return@trace Output(input.fbo, input.sampleCrop, input.contentCrop)
        }
        applyKernelBlur(input, sigma, maskTexture)
    }

    fun applyAtlas(
        input: SizedFramebuffer,
        sampleCrop: Rect,
        sigmaTexels: Float,
        maskTexture: Texture2D? = null,
        releaseInput: Boolean = true
    ): SizedFramebuffer = trace("GaussianBlurEffect#applyAtlas") {
        applyKernelAtlas(input, sampleCrop, sigmaTexels, maskTexture, releaseInput)
    }

    private fun applyKernelBlur(
        input: PreProcessEffect.PreProcessOutput,
        sigma: Float,
        maskTexture: Texture2D?
    ): Output = trace("GaussianBlurEffect#applyKernel") {
        val sigmaTexels = sigma * input.downsampleScale
        val kernel = GaussianKernel.getKernel(sigmaTexels)

        val ping = acquireOutput(input.fbo)
        val pong = acquireOutput(input.fbo)
        val maskUv = input.fbo.toUvRect(input.sampleCrop)

        trace("GaussianBlurEffect#kernelPassH") {
            runKernelBlurPass(
                input = input.fbo,
                output = ping,
                kernel = kernel,
                directionX = 1f,
                directionY = 0f,
                maskUv = maskUv,
                maskTexture = maskTexture
            )
        }

        trace("GaussianBlurEffect#kernelPassV") {
            runKernelBlurPass(
                input = ping,
                output = pong,
                kernel = kernel,
                directionX = 0f,
                directionY = 1f,
                maskUv = maskUv,
                maskTexture = maskTexture
            )
        }

        context.pool.release(input.fbo.fbo)
        context.pool.release(ping.fbo)

        return@trace Output(pong, input.sampleCrop, input.contentCrop)
    }

    private fun applyKernelAtlas(
        input: SizedFramebuffer,
        sampleCrop: Rect,
        sigmaTexels: Float,
        maskTexture: Texture2D?,
        releaseInput: Boolean
    ): SizedFramebuffer = trace("GaussianBlurEffect#applyKernelAtlas") {
        val kernel = GaussianKernel.getKernel(sigmaTexels)
        val ping = acquireOutput(input)
        val pong = acquireOutput(input)

        trace("GaussianBlurEffect#kernelPassH") {
            runKernelBlurPass(
                input = input,
                output = ping,
                kernel = kernel,
                directionX = 1f,
                directionY = 0f,
                maskUv = input.toContentUvRect(sampleCrop),
                maskTexture = maskTexture,
                drawIntoContentViewport = true
            )
        }

        trace("GaussianBlurEffect#kernelPassV") {
            runKernelBlurPass(
                input = ping,
                output = pong,
                kernel = kernel,
                directionX = 0f,
                directionY = 1f,
                maskUv = ping.toContentUvRect(sampleCrop),
                maskTexture = maskTexture,
                drawIntoContentViewport = true
            )
        }

        if (releaseInput) {
            context.pool.release(input.fbo)
        }
        context.pool.release(ping.fbo)

        return@trace pong
    }

    private fun runKernelBlurPass(
        input: SizedFramebuffer,
        output: SizedFramebuffer,
        kernel: GaussianKernel.KernelSamples,
        directionX: Float,
        directionY: Float,
        maskUv: Rect,
        maskTexture: Texture2D?,
        drawIntoContentViewport: Boolean = false
    ) {
        runKernelBlurPass(
            input = input,
            output = output,
            kernel = kernel,
            directionX = directionX,
            directionY = directionY,
            uvBounds = calculateUvBounds(input),
            maskUv = maskUv,
            maskTexture = maskTexture,
            textureCoordinates = null,
            drawIntoContentViewport = drawIntoContentViewport
        )
    }

    private fun runKernelBlurPass(
        input: SizedFramebuffer,
        output: SizedFramebuffer,
        kernel: GaussianKernel.KernelSamples,
        directionX: Float,
        directionY: Float,
        uvBounds: Rect,
        maskUv: Rect,
        maskTexture: Texture2D?,
        textureCoordinates: FloatArray?,
        drawIntoContentViewport: Boolean = false
    ) {
        val program = ensureKernelShaderProgram()
        program.shader.bind(context.shaderBinder)

        val inputSize = input.allocatedSize
        val texelScaleX = (1f / inputSize.width.toFloat()) * directionX
        val texelScaleY = (1f / inputSize.height.toFloat()) * directionY
        program.setKernelSamples(kernel, texelScaleX, texelScaleY)

        program.setUvBounds(uvBounds.left, uvBounds.top, uvBounds.right, uvBounds.bottom)
        program.setFlipY(false)
        program.setMaskUvBounds(
            minX = maskUv.left,
            minY = maskUv.top,
            maxX = maskUv.right,
            maxY = maskUv.bottom
        )
        if (maskTexture != null) {
            program.setMaskEnabled(true)
            program.setMaskFlip(maskTexture.flipTexture)
            maskTexture.bind(slot = 1)
        } else {
            program.setMaskEnabled(false)
        }

        drawBlurOutput(
            input = input,
            output = output,
            shader = program.shader,
            uvBounds = uvBounds,
            textureCoordinates = textureCoordinates,
            drawIntoContentViewport = drawIntoContentViewport
        )
    }

    private fun ensureKernelShaderProgram(): GaussianBlurKernelShaderProgram {
        return kernelShaderProgram ?: GaussianBlurKernelShaderProgram(
            context.shaderLibrary,
            context.shaderBinder
        ).also { kernelShaderProgram = it }
    }

    private fun acquireOutput(input: SizedFramebuffer): SizedFramebuffer {
        val requestedSize = input.contentSize
        val allocatedSize = input.allocatedSize
        val attachmentsSpec = input.fbo.specification.attachmentsSpec
        val downSampleFactor = input.fbo.specification.downSampleFactor
        val cachedSpec = outputSpec
        if (cachedSpec == null ||
            cachedSpec.size != allocatedSize ||
            outputAttachmentsSpec != attachmentsSpec ||
            outputDownSampleFactor != downSampleFactor
        ) {
            outputSpec = FramebufferSpecification(
                size = allocatedSize,
                attachmentsSpec = attachmentsSpec,
                downSampleFactor = downSampleFactor
            )
            outputAttachmentsSpec = attachmentsSpec
            outputDownSampleFactor = downSampleFactor
        }
        val spec = requireNotNull(outputSpec)
        val outputFbo = context.pool.acquireBucketed(spec)
        return outputFbo.withSize(requestedSize)
    }

    private fun drawBlurOutput(
        input: SizedFramebuffer,
        output: SizedFramebuffer,
        shader: Shader,
        uvBounds: Rect,
        textureCoordinates: FloatArray?,
        drawIntoContentViewport: Boolean
    ) {
        val outputSize = output.contentSize
        val outputOffset = output.contentOffset
        output.fbo.bindForOverwrite(context.commands)
        if (drawIntoContentViewport) {
            context.commands.setViewPort(
                x = outputOffset.x,
                y = outputOffset.y,
                width = outputSize.width,
                height = outputSize.height
            )
        } else {
            context.commands.setViewPort(
                width = output.allocatedSize.width,
                height = output.allocatedSize.height
            )
        }
        if (!drawIntoContentViewport && outputSize != output.allocatedSize) {
            context.commands.enableScissorTest()
            context.commands.setScissor(
                x = outputOffset.x,
                y = outputOffset.y,
                width = outputSize.width,
                height = outputSize.height
            )
        }

        context.singlePassQuadExecutor.draw(
            shader = shader,
            texture = input.texture,
            textureCoordinatesFlat = textureCoordinates ?: if (drawIntoContentViewport) {
                writeTextureCoordinates(uvBounds)
            } else {
                null
            },
            flipY = false
        )
        if (!drawIntoContentViewport && outputSize != output.allocatedSize) {
            context.commands.disableScissorTest()
        }
    }

    private fun SizedFramebuffer.toContentUvRect(contentRect: Rect): Rect {
        val offset = contentOffset
        return toUvRect(
            Rect(
                left = contentRect.left + offset.x,
                top = contentRect.top + offset.y,
                right = contentRect.right + offset.x,
                bottom = contentRect.bottom + offset.y
            )
        )
    }

    private fun calculateUvBounds(input: SizedFramebuffer): Rect {
        val uvOffset = input.uvOffset
        val uvScale = input.uvScale
        val minX = uvOffset.x
        val minY = uvOffset.y
        val maxX = minX + uvScale.x
        val maxY = minY + uvScale.y

        return Rect(
            left = minX.coerceIn(0f, 1f),
            top = minY.coerceIn(0f, 1f),
            right = maxX.coerceIn(0f, 1f),
            bottom = maxY.coerceIn(0f, 1f)
        )
    }

    private fun writeTextureCoordinates(uvBounds: Rect): FloatArray {
        reusableTextureCoordinates[0] = uvBounds.left
        reusableTextureCoordinates[1] = uvBounds.top
        reusableTextureCoordinates[2] = uvBounds.right
        reusableTextureCoordinates[3] = uvBounds.top
        reusableTextureCoordinates[4] = uvBounds.right
        reusableTextureCoordinates[5] = uvBounds.bottom
        reusableTextureCoordinates[6] = uvBounds.left
        reusableTextureCoordinates[7] = uvBounds.bottom
        return reusableTextureCoordinates
    }

    internal companion object {
        const val MIN_SIGMA = 0.5f
    }
}
