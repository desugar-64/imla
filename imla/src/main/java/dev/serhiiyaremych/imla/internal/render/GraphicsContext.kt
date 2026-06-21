/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render

import android.util.Log
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary

/**
 * Central hub for GPU-related resources and capabilities.
 * Manages capabilities detection, resource pools, binders, and feature-level queries.
 * Must be initialized on the GL rendering thread.
 */
internal class GraphicsContext(
    rendererApi: RendererApi,
    val shaderLibrary: ShaderLibrary,
    val shaderBinder: ShaderBinder,
    val fboPool: FramebufferLendingPool,
    val commands: RenderCommands = RenderCommands(rendererApi)
) {
    private lateinit var capabilities: GLCapabilities

    fun initialize() {
        capabilities = GLCapabilities.detect()
        commands.init()
        logCapabilities()
    }

    fun destroy() {
        fboPool.destroy()
        shaderLibrary.destroyAll()
    }

    fun getCapabilities(): GLCapabilities = capabilities

    fun supportsHardwareStencilClipping(): Boolean =
        capabilities.majorVersion >= 3 && capabilities.supportsDepth24Stencil8

    fun supportsHighDynamicRangeRendering(): Boolean =
        capabilities.supportsColorBufferHalfFloat &&
        capabilities.supportsHalfFloatTexture &&
        capabilities.supportsHalfFloatTextureLinear

    fun supportsLinearColorSpaceBlending(): Boolean =
        capabilities.supportsSRGBWriteControl

    fun supportsOptimizedBlurRendering(): Boolean =
        capabilities.supportsMultisampledRenderToTexture

    fun supportsHighQualityGradients(): Boolean =
        capabilities.supportsNorm16Texture

    fun supportsFastTextureOps(): Boolean =
        capabilities.supportsCopyImage

    /**
     * GL_EXT_shader_framebuffer_fetch and GL_ARM_shader_framebuffer_fetch are NOT used.
     *
     * Reason: "Context Poisoning" - compiling a shader with framebuffer fetch permanently
     * sets global driver flags that break glBlitFramebuffer on both Adreno and Mali GPUs.
     * The conflict is architectural: fetch locks tile memory (TBDR), blit requires resolved
     * DRAM. Chromium disables this extension entirely on Adreno (Bug 1010338).
     *
     * The bandwidth savings from avoiding accumulator ping-pong are marginal compared to
     * the driver instability. copyOrBlitFramebuffer with glCopyImageSubData provides stable
     * optimization without the complexity.
     */

    fun isPremiumRenderingSupported(): Boolean =
        supportsHighDynamicRangeRendering() &&
        supportsLinearColorSpaceBlending() &&
        supportsOptimizedBlurRendering()

    private fun logCapabilities() {
        Log.d(TAG, "=== GPU Hardware Info ===")
        Log.d(TAG, "GPU: ${capabilities.rendererName}")
        Log.d(TAG, "Vendor: ${capabilities.vendorName}")
        Log.d(TAG, "GL Version: ${capabilities.glVersion}")
        Log.d(TAG, "GLSL Version: ${capabilities.glslVersion}")
        Log.d(TAG, "OpenGL ES: ${capabilities.majorVersion}.${capabilities.minorVersion}")

        Log.d(TAG, "=== Hardware Limits ===")
        Log.d(TAG, "Max Texture Units: ${capabilities.maxTextureImageUnits}")
        Log.d(TAG, "Max Texture Size: ${capabilities.maxTextureSize}")
        Log.d(TAG, "Max Renderbuffer Size: ${capabilities.maxRenderBufferSize}")
        Log.d(TAG, "Max Fragment Uniform Vectors: ${capabilities.maxUniformVectors}")
        Log.d(TAG, "Max Vertex Uniform Vectors: ${capabilities.maxVertexUniformVectors}")

        Log.d(TAG, "=== Performance Extensions ===")
        Log.d(TAG, "MSAA Render-to-Texture: ${capabilities.supportsMultisampledRenderToTexture}")
        Log.d(TAG, "Copy Image: ${capabilities.supportsCopyImage}")

        Log.d(TAG, "=== Dynamic Range Support ===")
        Log.d(TAG, "Half-Float Color Buffer: ${capabilities.supportsColorBufferHalfFloat}")
        Log.d(TAG, "Half-Float Texture: ${capabilities.supportsHalfFloatTexture}")
        Log.d(TAG, "Half-Float Linear: ${capabilities.supportsHalfFloatTextureLinear}")

        Log.d(TAG, "=== Precision Formats ===")
        Log.d(TAG, "Norm16 Texture: ${capabilities.supportsNorm16Texture}")

        Log.d(TAG, "=== Color Space ===")
        Log.d(TAG, "sRGB Write Control: ${capabilities.supportsSRGBWriteControl}")

        Log.d(TAG, "=== Stencil Support ===")
        Log.d(TAG, "Stencil Buffer: ${capabilities.supportsStencilBuffer}")
        Log.d(TAG, "Depth24 Stencil8: ${capabilities.supportsDepth24Stencil8}")

        Log.d(TAG, "=== Feature Tiers ===")
        Log.d(TAG, "HDR Rendering: ${supportsHighDynamicRangeRendering()}")
        Log.d(TAG, "Linear Color Space: ${supportsLinearColorSpaceBlending()}")
        Log.d(TAG, "Optimized Blur: ${supportsOptimizedBlurRendering()}")
        Log.d(TAG, "Premium Rendering: ${isPremiumRenderingSupported()}")
    }

    companion object {
        private const val TAG = "GraphicsContext"
    }
}
