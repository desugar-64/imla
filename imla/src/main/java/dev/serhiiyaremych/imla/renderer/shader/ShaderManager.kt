/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.renderer.shader

import android.content.res.AssetManager
import android.opengl.GLES30
import dev.serhiiyaremych.imla.ext.logd
import dev.serhiiyaremych.imla.ext.loge
import dev.serhiiyaremych.imla.ext.logw
import dev.serhiiyaremych.imla.renderer.primitive.ShaderProgramHandle
import dev.serhiiyaremych.imla.renderer.primitive.UniformHandle
import java.io.IOException

internal class ShaderManager(private val assetManager: AssetManager) {
    private val TAG = "ShaderManager"

    private val handleToShader = mutableMapOf<ShaderProgramHandle, Shader>()
    private val nameToHandle = mutableMapOf<String, ShaderProgramHandle>()

    // Uniform Location Cache: Key is (ShaderProgramHandle, UniformName)
    private val uniformLocations = mutableMapOf<Pair<ShaderProgramHandle, String>, UniformHandle>()

    private var nextHandleValue = 1 // Start handles from 1 (0 is invalid)

    /**
     * Loads, compiles, and links a shader program from vertex and fragment shader files
     * located in the assets/shader directory. Uses the filename (without extension) as the shader name.
     * Returns a handle to the managed shader program. If already loaded, returns the existing handle.
     */
    fun loadShaderFromAssets(
        vertFileName: String,
        fragFileName: String
    ): ShaderProgramHandle {
        val shaderName = "${vertFileName}_${fragFileName}" // Unique name based on files
        nameToHandle[shaderName]?.let { return it } // Return cached handle

        val vertPath = "shader/$vertFileName.vert"
        val fragPath = "shader/$fragFileName.frag"

        try {
            val shader = Shader.create(assetManager, vertPath, fragPath)

            val handle = ShaderProgramHandle(nextHandleValue++)
            handleToShader[handle] = shader
            nameToHandle[shaderName] = handle

            logd(TAG, "Loaded shader '$shaderName' with handle $handle")
            // Optional: Pre-cache common uniforms (like u_ViewProjection) here
            // getUniformHandle(handle, "u_ViewProjection")

            return handle

        } catch (e: IOException) {
            loge(TAG, "Error loading shader files: $vertPath, $fragPath", e)
            error("Failed to load shader: $shaderName. Check logs.") // Or return Invalid handle
        } catch (e: RuntimeException) {
            loge(TAG, "Error compiling/linking shader '$shaderName'", e)
            error("Failed to compile/link shader: $shaderName. Check logs.") // Or return Invalid handle
        }
    }

    /**
     * Retrieves the actual Shader object associated with a handle.
     * Returns null if the handle is invalid or not found.
     */
    fun getShader(handle: ShaderProgramHandle): Shader? {
        return handleToShader[handle]
    }

    /**
     * Gets a handle for a uniform location within a specific shader program.
     * Caches the result for subsequent lookups.
     *
     * @param shaderHandle The handle of the shader program.
     * @param uniformName The name of the uniform variable in the shader source.
     * @return UniformHandle containing the location, or UniformHandle.Invalid if not found.
     */
    fun getUniformHandle(shaderHandle: ShaderProgramHandle, uniformName: String): UniformHandle {
        val key = shaderHandle to uniformName
        uniformLocations[key]?.let { return it }

        val programId = shaderHandle.id

        if (programId == ShaderProgramHandle.Invalid.id) {
            logw(TAG, "Attempted to get uniform '$uniformName' for invalid shader handle $shaderHandle")
            return UniformHandle.Invalid
        }

        // Get uniform location from OpenGL
        val location = GLES30.glGetUniformLocation(programId, uniformName)
        // Check for GL errors after the call if needed

        val handle = if (location != -1) {
            UniformHandle(location)
        } else {
            logw(TAG, "Uniform '$uniformName' not found or inactive in shader program $programId (handle $shaderHandle)")
            UniformHandle.Invalid
        }

        uniformLocations[key] = handle
        return handle
    }

    /**
     * Destroys a specific shader program and removes it from management.
     */
    fun destroyShader(handle: ShaderProgramHandle) {
        handleToShader.remove(handle)?.let { shader ->
            // Find the name associated with the handle to remove from nameToHandle map
            val nameToRemove = nameToHandle.entries.find { it.value == handle }?.key
            if (nameToRemove != null) {
                nameToHandle.remove(nameToRemove)
            }
            uniformLocations.keys.removeAll { it.first == handle }
            shader.destroy()
            logd(TAG, "Destroyed shader '${shader.name}' (handle $handle)")
        }
    }

    /**
     * Destroys all managed shader programs.
     */
    fun destroyAll() {
        logd(TAG, "Destroying all managed shaders...")
        handleToShader.values.forEach { it.destroy() }
        handleToShader.clear()
        nameToHandle.clear()
        uniformLocations.clear()
        nextHandleValue = 1
        logd(TAG, "All shaders destroyed.")
    }

    // Helper to read shader source from assets
    private fun readAsset(assetPath: String): String {
        var inputStream: InputStream? = null
        try {
            inputStream = assetManager.open(assetPath)
            return inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read asset: $assetPath", e)
            throw e // Re-throw to be caught by the caller
        } finally {
            inputStream?.close() // Ensure stream is closed
        }
    }
}