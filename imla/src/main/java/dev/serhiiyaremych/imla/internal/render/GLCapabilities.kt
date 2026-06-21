/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render

import android.opengl.GLES30
import android.util.Log
import androidx.collection.IntIntPair
import androidx.opengl.EGLExt

/**
 * GL capabilities and version info detected at runtime.
 * Detects OpenGL version, supported extensions, and hardware limits.
 * All queries are cached (performed once at initialization).
 */
internal data class GLCapabilities(
    val glVersion: String,
    val glslVersion: String,
    val vendorName: String,
    val rendererName: String,
    val majorVersion: Int,
    val minorVersion: Int,
    val supportedExtensions: Set<String>,
    val maxTextureImageUnits: Int,
    val maxUniformVectors: Int,
    val maxVertexUniformVectors: Int,
    val maxRenderBufferSize: Int,
    val maxTextureSize: Int,
    val supportsMultisampledRenderToTexture: Boolean,
    val supportsCopyImage: Boolean,
    val supportsColorBufferHalfFloat: Boolean,
    val supportsHalfFloatTexture: Boolean,
    val supportsHalfFloatTextureLinear: Boolean,
    val supportsNorm16Texture: Boolean,
    val supportsSRGBWriteControl: Boolean,
    val supportsStencilBuffer: Boolean,
    val supportsDepth24Stencil8: Boolean
) {

    fun isExtensionSupported(name: String): Boolean = name in supportedExtensions

    companion object {
        private const val TAG = "GLCapabilities"

        fun detect(): GLCapabilities {
            // Version strings
            val glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
            val glslVersion = GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION) ?: ""
            val vendorName = GLES30.glGetString(GLES30.GL_VENDOR) ?: ""
            val rendererName = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""

            // Parse version: "OpenGL ES 3.2 (build 10.0.0.2000)"
            val (majorVersion, minorVersion) = parseVersion(glVersion)

            // Extensions
            val supportedExtensions = queryExtensions()

            // Hardware limits
            val maxTextureImageUnits = queryIntParameter(GLES30.GL_MAX_TEXTURE_IMAGE_UNITS)
            val maxUniformVectors = queryIntParameter(GLES30.GL_MAX_FRAGMENT_UNIFORM_VECTORS)
            val maxVertexUniformVectors = queryIntParameter(GLES30.GL_MAX_VERTEX_UNIFORM_VECTORS)
            val maxRenderBufferSize = queryIntParameter(GLES30.GL_MAX_RENDERBUFFER_SIZE)
            val maxTextureSize = queryIntParameter(GLES30.GL_MAX_TEXTURE_SIZE)

            // Extension checks
            val supportsMultisampledRenderToTexture = "GL_EXT_multisampled_render_to_texture" in supportedExtensions
            val supportsCopyImage = "GL_EXT_copy_image" in supportedExtensions

            val supportsColorBufferHalfFloat = "GL_EXT_color_buffer_half_float" in supportedExtensions
            val supportsHalfFloatTexture = "GL_OES_texture_half_float" in supportedExtensions
            val supportsHalfFloatTextureLinear = "GL_OES_texture_half_float_linear" in supportedExtensions

            val supportsNorm16Texture = "GL_EXT_texture_norm16" in supportedExtensions

            val supportsSRGBWriteControl = "GL_EXT_sRGB_write_control" in supportedExtensions

            // Stencil support
            val supportsStencilBuffer = majorVersion >= 3  // GLES 3.0+
            val supportsDepth24Stencil8 = "GL_OES_depth24" in supportedExtensions ||
                "GL_OES_depth_stencil" in supportedExtensions ||
                majorVersion >= 3  // Core in GLES 3.0

            return GLCapabilities(
                glVersion = glVersion,
                glslVersion = glslVersion,
                vendorName = vendorName,
                rendererName = rendererName,
                majorVersion = majorVersion,
                minorVersion = minorVersion,
                supportedExtensions = supportedExtensions,
                maxTextureImageUnits = maxTextureImageUnits,
                maxUniformVectors = maxUniformVectors,
                maxVertexUniformVectors = maxVertexUniformVectors,
                maxRenderBufferSize = maxRenderBufferSize,
                maxTextureSize = maxTextureSize,
                supportsMultisampledRenderToTexture = supportsMultisampledRenderToTexture,
                supportsCopyImage = supportsCopyImage,
                supportsColorBufferHalfFloat = supportsColorBufferHalfFloat,
                supportsHalfFloatTexture = supportsHalfFloatTexture,
                supportsHalfFloatTextureLinear = supportsHalfFloatTextureLinear,
                supportsNorm16Texture = supportsNorm16Texture,
                supportsSRGBWriteControl = supportsSRGBWriteControl,
                supportsStencilBuffer = supportsStencilBuffer,
                supportsDepth24Stencil8 = supportsDepth24Stencil8
            )
        }

        private fun parseVersion(versionString: String): IntIntPair {
            return try {
                // Extract numbers from version string (e.g., "3.2")
                val regex = Regex("""(\d+)\.(\d+)""")
                val match = regex.find(versionString)
                if (match != null) {
                    val major = match.groupValues[1].toIntOrNull() ?: 3
                    val minor = match.groupValues[2].toIntOrNull() ?: 0
                    IntIntPair(major, minor)
                } else {
                    IntIntPair(3, 0)  // Default to GLES 3.0
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse GL version: $versionString", e)
                IntIntPair(3, 0)
            }
        }

        private fun queryExtensions(): Set<String> {
            return try {
                val extensions = mutableSetOf<String>()

                // GLES 3.0+: Use glGetStringi for safer string handling
                val numExtensions = queryIntParameter(GLES30.GL_NUM_EXTENSIONS)
                for (i in 0 until numExtensions) {
                    val ext = GLES30.glGetStringi(GLES30.GL_EXTENSIONS, i)
                    if (ext != null) {
                        extensions.add(ext)
                    }
                }

                if (extensions.isEmpty()) {
                    val extString = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
                    if (extString.isNotEmpty()) {
                        extensions.addAll(EGLExt.parseExtensions(extString))
                    }
                }

                extensions
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query GL extensions", e)
                emptySet()
            }
        }

        private fun queryIntParameter(paramName: Int): Int {
            return try {
                val params = IntArray(1)
                GLES30.glGetIntegerv(paramName, params, 0)
                params[0]
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query GL parameter $paramName", e)
                -1
            }
        }
    }
}