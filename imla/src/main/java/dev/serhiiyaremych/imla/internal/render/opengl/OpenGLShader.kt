/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.opengl

import android.opengl.GLES30
import android.util.Log
import androidx.collection.FloatFloatPair
import androidx.collection.MutableObjectFloatMap
import androidx.collection.MutableObjectIntMap
import androidx.compose.ui.util.trace
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat3
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.ext.checkGlError
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import kotlin.math.abs
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.stats.ShaderStats
import org.intellij.lang.annotations.Language

internal class OpenGLShader(
    name: String,
    @Language("GLSL")
    vertexSrc: String,
    @Language("GLSL")
    fragmentSrc: String
) : Shader {
    private var _name: String = name
    override val name: String get() = _name
    private var rendererId: Int = 0

    private val locationMap: MutableObjectIntMap<String> = MutableObjectIntMap()
    private val intValueCache: MutableObjectIntMap<String> = MutableObjectIntMap()
    private val intArrayValueCache: MutableMap<String, IntArray> = mutableMapOf()
    private val floatValueCache: MutableObjectFloatMap<String> = MutableObjectFloatMap()
    private val floatArrayValueCache: MutableMap<String, FloatArray> = mutableMapOf()
    private val float4ArrayValueCache: MutableMap<String, FloatArray> = mutableMapOf()
    private val float4ArrayCountCache: MutableObjectIntMap<String> = MutableObjectIntMap()
    private val float2Cache: MutableMap<String, FloatFloatPair> = mutableMapOf()
    private val float3Cache: MutableMap<String, Float3> = mutableMapOf()
    private val float4Cache: MutableMap<String, Float4> = mutableMapOf()
    private val mat3Cache: MutableMap<String, FloatArray> = mutableMapOf()
    private val mat4Cache: MutableMap<String, FloatArray> = mutableMapOf()
    private var traceName = "shaderBind"
    private var vertexShaderId: Int = 0
    private var fragmentShaderId: Int = 0
    private var isDestroyed: Boolean = false

    init {
        compile(vertexSrc, fragmentSrc)
        ShaderStats.recordShaderCreated()
    }

    @Deprecated("")
    override fun bind() {
        trace(traceName) {
            if (rendererId == 0) {
                // Skip binding if shader is not valid
                android.util.Log.w("OpenGLShader", "Skipping bind - invalid shader ID")
                return
            }

            // Check for existing GL error
            val existingError = GLES30.glGetError()
            if (existingError != GLES30.GL_NO_ERROR) {
                // Clear existing error and skip
                android.util.Log.w("OpenGLShader", "Clearing existing GL error: $existingError")
            }

            GLES30.glUseProgram(rendererId)
            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) {
                // Don't throw, just log and skip
                android.util.Log.e("OpenGLShader", "Failed to use shader $rendererId, error: $error")
            }
        }
        ShaderStats.recordShaderBind()
    }

    override fun bind(shaderBinder: ShaderBinder) {
        shaderBinder.bind(this)
    }

    override fun unbind() = trace(traceName) {
        GLES30.glUseProgram(0)
        intValueCache.clear()
        intArrayValueCache.clear()
        floatValueCache.clear()
        floatArrayValueCache.clear()
        float4ArrayValueCache.clear()
        float4ArrayCountCache.clear()
    }

    override fun bindUniformBlock(blockName: String, bindingPoint: Int) {
        trace("bindUniformBlock") {
            val blockIndex =
                locationMap.getOrPut(blockName) {
                    GLES30.glGetUniformBlockIndex(
                        rendererId,
                        blockName
                    ).also { checkGlError() }
                }
            checkGlError(GLES30.glUniformBlockBinding(rendererId, blockIndex, bindingPoint))
        }
        ShaderStats.recordShaderBindUniformBlock()
    }

    override fun setInt(name: String, value: Int) = trace("setInt") {
        uploadUniformInt(name, value)
    }

    override fun setIntArray(name: String, values: IntArray) = trace("setIntArray") {
        uploadUniformIntArray(name, values)
    }

    override fun setFloatArray(name: String, values: FloatArray) = trace("setFloatArray") {
        uploadFloatArray(name, values)
    }

    override fun setFloat4Array(name: String, values: FloatArray, count: Int) = trace("setFloat4Array") {
        uploadFloat4Array(name, values, count)
    }

    override fun setFloat(name: String, value: Float) = trace("setFloat") {
        uploadUniformFloat(name, value)
    }

    override fun setFloat2(name: String, value: FloatFloatPair) = trace("setFloat2") {
        uploadUniformFloat2(name, value)
    }

    override fun setFloat3(name: String, value: Float3) = trace("setFloat3") {
        uploadUniformFloat3(name, value)
    }

    override fun setFloat4(name: String, value: Float4) = trace("setFloat4") {
        uploadUniformFloat4(name, value)
    }

    override fun setMat3(name: String, value: Mat3) = trace("setMat3") {
        uploadUniformMat3(name, value)
    }

    override fun setMat4(name: String, value: Mat4) = trace("setMat4") {
        uploadUniformMat4(name, value)
    }

    override fun resetUniformCache() {
        intValueCache.clear()
        intArrayValueCache.clear()
        floatValueCache.clear()
        floatArrayValueCache.clear()
        float4ArrayValueCache.clear()
        float4ArrayCountCache.clear()
        float2Cache.clear()
        float3Cache.clear()
        float4Cache.clear()
        mat3Cache.clear()
        mat4Cache.clear()
    }

    private fun uploadUniformInt(name: String, value: Int) {
        if (intValueCache.getOrDefault(name, Int.MIN_VALUE) != value) {
            val location = uniformLocation(rendererId, name)
            checkGlError(GLES30.glUniform1i(location, value))
            ShaderStats.recordShaderUpload()
            intValueCache[name] = value
        }
    }

    private fun uploadUniformIntArray(name: String, values: IntArray) {
        if (!values.contentEquals(intArrayValueCache[name])) {
            val location = uniformLocation(rendererId, name)
            checkGlError(GLES30.glUniform1iv(location, values.size, values, 0))
            ShaderStats.recordShaderUpload()
            intArrayValueCache[name] = values
        }
    }

    private fun uploadFloatArray(name: String, values: FloatArray) {
        if (!floatArrayEquals(values, floatArrayValueCache[name])) {
            val location = uniformLocation(rendererId, name)
            checkGlError(GLES30.glUniform1fv(location, values.size, values, 0))
            ShaderStats.recordShaderUpload()
            floatArrayValueCache[name] = values
        }
    }

    private fun uploadFloat4Array(name: String, values: FloatArray, count: Int) {
        val expectedSize = count * 4
        require(values.size >= expectedSize) {
            "Uniform $name requires $expectedSize floats, got ${values.size}"
        }
        val cached = float4ArrayValueCache[name]
        val cachedCount = float4ArrayCountCache.getOrDefault(name, Int.MIN_VALUE)
        if (cached == null ||
            cachedCount != count ||
            !floatArrayPrefixEquals(values, cached, expectedSize)
        ) {
            val location = uniformLocation(rendererId, name)
            checkGlError(GLES30.glUniform4fv(location, count, values, 0))
            ShaderStats.recordShaderUpload()
            if (cached == null || cached.size != expectedSize) {
                float4ArrayValueCache[name] = values.copyOf(expectedSize)
            } else {
                System.arraycopy(values, 0, cached, 0, expectedSize)
            }
            float4ArrayCountCache[name] = count
        }
    }

    private fun uploadUniformFloat(name: String, value: Float) {
        val cached = floatValueCache.getOrDefault(name, Float.MAX_VALUE)
        if (!floatEquals(cached, value)) {
            val location = uniformLocation(rendererId, name)
            checkGlError(GLES30.glUniform1f(location, value))
            ShaderStats.recordShaderUpload()
            floatValueCache[name] = value
        }
    }

    private fun uploadUniformFloat2(name: String, value: FloatFloatPair) {
        val cached = float2Cache[name]
        if (cached == null || !float2Equals(cached, value)) {
            val location = uniformLocation(rendererId, name)
            checkGlError(GLES30.glUniform2f(location, value.first, value.second))
            ShaderStats.recordShaderUpload()
            float2Cache[name] = FloatFloatPair(value.first, value.second)
        }
    }

    private fun uploadUniformFloat3(name: String, value: Float3) {
        val cached = float3Cache[name]
        if (cached == null || !float3Equals(cached, value)) {
            val location = uniformLocation(rendererId, name)
            checkGlError(GLES30.glUniform3f(location, value.x, value.y, value.z))
            ShaderStats.recordShaderUpload()
            if (cached == null) {
                float3Cache[name] = Float3(value.x, value.y, value.z)
            } else {
                cached.x = value.x
                cached.y = value.y
                cached.z = value.z
            }
        }
    }

    private fun uploadUniformFloat4(name: String, value: Float4) {
        val cached = float4Cache[name]
        if (cached == null || !float4Equals(cached, value)) {
            val location = uniformLocation(rendererId, name)
            checkGlError(GLES30.glUniform4f(location, value.x, value.y, value.z, value.w))
            ShaderStats.recordShaderUpload()
            if (cached == null) {
                float4Cache[name] = Float4(value.x, value.y, value.z, value.w)
            } else {
                cached.x = value.x
                cached.y = value.y
                cached.z = value.z
                cached.w = value.w
            }
        }
    }

    private fun uploadUniformMat3(name: String, value: Mat3) {
        val newArray = value.toFloatArray()
        if (!floatArrayEquals(newArray, mat3Cache[name])) {
            val location = uniformLocation(rendererId, name)
            checkGlError(GLES30.glUniformMatrix3fv(location, 1, true, newArray, 0))
            ShaderStats.recordShaderUpload()
            mat3Cache[name] = newArray
        }
    }

    private fun uploadUniformMat4(name: String, value: Mat4) {
        val newArray = value.toFloatArray()
        if (!floatArrayEquals(newArray, mat4Cache[name])) {
            val location = uniformLocation(rendererId, name)
            checkGlError(GLES30.glUniformMatrix4fv(location, 1, true, newArray, 0))
            ShaderStats.recordShaderUpload()
            mat4Cache[name] = newArray
        }
    }

    override fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        unbind()
        if (rendererId != 0) {
            GLES30.glDeleteProgram(rendererId)
            rendererId = 0
        }
        if (vertexShaderId != 0) {
            GLES30.glDeleteShader(vertexShaderId)
            vertexShaderId = 0
        }
        if (fragmentShaderId != 0) {
            GLES30.glDeleteShader(fragmentShaderId)
            fragmentShaderId = 0
        }
        ShaderStats.recordShaderDestroyed()
    }

    private fun compile(vertexSrc: String, fragmentSrc: String) = trace("shaderCompile") {
        val vertexShader = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also {
            vertexShaderId = it
        }
        checkGlError(GLES30.glShaderSource(vertexShader, vertexSrc))
        checkGlError(GLES30.glCompileShader(vertexShader))

        val status = IntArray(1)
        GLES30.glGetShaderiv(vertexShader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val errorMessage = GLES30.glGetShaderInfoLog(vertexShader)
            GLES30.glDeleteShader(vertexShader)
            Log.e(TAG, "=== VERTEX SHADER COMPILATION ERROR ===")
            Log.e(TAG, "Error: $errorMessage")
            Log.e(TAG, "=== VERTEX SHADER SOURCE ===")
            vertexSrc.lines().forEachIndexed { index, line ->
                Log.e(TAG, "${index + 1}: $line")
            }
            error(errorMessage)
        }

        val fragmentShader = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also {
            fragmentShaderId = it
        }
        checkGlError(GLES30.glShaderSource(fragmentShader, fragmentSrc))
        checkGlError(GLES30.glCompileShader(fragmentShader))

        GLES30.glGetShaderiv(fragmentShader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val errorMessage = GLES30.glGetShaderInfoLog(fragmentShader)
            GLES30.glDeleteShader(fragmentShader)
            Log.e(TAG, "=== FRAGMENT SHADER COMPILATION ERROR ===")
            Log.e(TAG, "Error: $errorMessage")
            Log.e(TAG, "=== FRAGMENT SHADER SOURCE ===")
            fragmentSrc.lines().forEachIndexed { index, line ->
                Log.e(TAG, "${index + 1}: $line")
            }
            error(errorMessage)
        }

        rendererId = GLES30.glCreateProgram()
        traceName = "shaderBind[$rendererId]"
        val program = rendererId
        checkGlError(GLES30.glAttachShader(program, vertexShader))
        checkGlError(GLES30.glAttachShader(program, fragmentShader))

        GLES30.glLinkProgram(program)

        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val errorMessage = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            Log.e(TAG, "=== SHADER PROGRAM LINK ERROR ===")
            Log.e(TAG, "Error: $errorMessage")
            Log.e(TAG, "=== VERTEX SHADER SOURCE ===")
            vertexSrc.lines().forEachIndexed { index, line ->
                Log.e(TAG, "${index + 1}: $line")
            }
            Log.e(TAG, "=== FRAGMENT SHADER SOURCE ===")
            fragmentSrc.lines().forEachIndexed { index, line ->
                Log.e(TAG, "${index + 1}: $line")
            }
            error(errorMessage)
        }

        checkGlError(GLES30.glDetachShader(program, vertexShader))
        checkGlError(GLES30.glDetachShader(program, fragmentShader))
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        vertexShaderId = 0
        fragmentShaderId = 0
    }

    private fun uniformLocation(rendererId: Int, uniformName: String): Int {
        return locationMap.getOrPut(uniformName) {
            GLES30.glGetUniformLocation(rendererId, uniformName).also { checkGlError() }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OpenGLShader

        return rendererId == other.rendererId
    }

    override fun hashCode(): Int {
        return rendererId.hashCode()
    }

    override fun toString(): String {
        return "OpenGLShader('$_name:$rendererId')"
    }


    private companion object {
        private const val TAG = "OpenGLShader"
        private const val EPSILON = 1e-6f

        private fun floatEquals(a: Float, b: Float): Boolean = abs(a - b) < EPSILON

        private fun float2Equals(a: FloatFloatPair, b: FloatFloatPair): Boolean =
            floatEquals(a.first, b.first) && floatEquals(a.second, b.second)

        private fun float3Equals(a: Float3, b: Float3): Boolean =
            floatEquals(a.x, b.x) && floatEquals(a.y, b.y) && floatEquals(a.z, b.z)

        private fun float4Equals(a: Float4, b: Float4): Boolean =
            floatEquals(a.x, b.x) && floatEquals(a.y, b.y) &&
                floatEquals(a.z, b.z) && floatEquals(a.w, b.w)

        private fun floatArrayEquals(a: FloatArray, b: FloatArray?): Boolean {
            if (b == null || a.size != b.size) return false
            for (i in a.indices) {
                if (!floatEquals(a[i], b[i])) return false
            }
            return true
        }

        private fun floatArrayPrefixEquals(a: FloatArray, b: FloatArray, size: Int): Boolean {
            if (size > a.size || size > b.size) return false
            for (i in 0 until size) {
                if (!floatEquals(a[i], b[i])) return false
            }
            return true
        }
    }
}
