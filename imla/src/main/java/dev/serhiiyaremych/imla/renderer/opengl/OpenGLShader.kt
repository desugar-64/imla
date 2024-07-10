/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.opengl

import android.opengl.GLES30
import android.util.Log
import androidx.collection.MutableObjectIntMap
import androidx.compose.ui.util.trace
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat3
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.renderer.Shader
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

    init {
        compile(vertexSrc, fragmentSrc)
    }

    override fun bind() = trace("shaderBind") {
        GLES30.glUseProgram(rendererId)
    }

    override fun unbind() = trace("shaderUnBind") {
        GLES30.glUseProgram(0)
    }

    override fun setInt(name: String, value: Int) {
        uploadUniformInt(name, value)
    }

    override fun setIntArray(name: String, vararg values: Int) {
        uploadUniformIntArray(name, *values)
    }

    override fun setFloat(name: String, value: Float) {
        uploadUniformFloat(name, value)
    }

    override fun setFloat2(name: String, value: Float2) {
        uploadUniformFloat2(name, value)
    }

    override fun setFloat3(name: String, value: Float3) {
        uploadUniformFloat3(name, value)
    }

    override fun setFloat4(name: String, value: Float4) {
        uploadUniformFloat4(name, value)
    }

    override fun setMat3(name: String, value: Mat3) {
        uploadUniformMat3(name, value)
    }

    override fun setMat4(name: String, value: Mat4) {
        uploadUniformMat4(name, value)
    }

    override fun uploadUniformInt(name: String, value: Int) {
        val location = uniformLocation(rendererId, name)
        GLES30.glUniform1i(location, value)
    }

    override fun uploadUniformIntArray(name: String, vararg values: Int) {
        val location = uniformLocation(rendererId, name)
        GLES30.glUniform1iv(location, values.size, values, 0)
    }

    override fun uploadUniformFloat(name: String, value: Float) {
        val location = uniformLocation(rendererId, name)
        GLES30.glUniform1f(location, value)
    }

    override fun uploadUniformFloat2(name: String, value: Float2) {
        val location = uniformLocation(rendererId, name)
        GLES30.glUniform2f(location, value.x, value.y)
    }

    override fun uploadUniformFloat3(name: String, value: Float3) {
        val location = uniformLocation(rendererId, name)
        GLES30.glUniform3f(location, value.x, value.y, value.z)
    }

    override fun uploadUniformFloat4(name: String, value: Float4) {
        val location = uniformLocation(rendererId, name)
        GLES30.glUniform4f(location, value.x, value.y, value.z, value.w)
    }

    override fun uploadUniformMat3(name: String, value: Mat3) {
        val location = uniformLocation(rendererId, name)
        GLES30.glUniformMatrix3fv(location, 1, true, value.toFloatArray(), 0)
    }

    override fun uploadUniformMat4(name: String, value: Mat4) {
        val location = uniformLocation(rendererId, name)
        GLES30.glUniformMatrix4fv(location, 1, true, value.toFloatArray(), 0)
    }

    override fun destroy() {
        unbind()
        GLES30.glDetachShader(rendererId, GLES30.GL_VERTEX_SHADER)
        GLES30.glDetachShader(rendererId, GLES30.GL_FRAGMENT_SHADER)
        GLES30.glDeleteProgram(rendererId)
    }

    private fun compile(vertexSrc: String, fragmentSrc: String) = trace("shaderCompile") {
        val vertexShader = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
        GLES30.glShaderSource(vertexShader, vertexSrc)
        GLES30.glCompileShader(vertexShader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(vertexShader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val errorMessage = GLES30.glGetShaderInfoLog(vertexShader)
            GLES30.glDeleteShader(vertexShader)
            error(errorMessage)
        }

        val fragmentShader = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
        GLES30.glShaderSource(fragmentShader, fragmentSrc)
        GLES30.glCompileShader(fragmentShader)

        GLES30.glGetShaderiv(fragmentShader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val errorMessage = GLES30.glGetShaderInfoLog(fragmentShader)
            GLES30.glDeleteShader(fragmentShader)
            Log.e(TAG, errorMessage)
            error(errorMessage)
        }

        rendererId = GLES30.glCreateProgram()
        val program = rendererId
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)

        GLES30.glLinkProgram(program)

        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val errorMessage = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            Log.e(TAG, errorMessage)
            error(errorMessage)
        }

        GLES30.glDetachShader(program, vertexShader)
        GLES30.glDetachShader(program, fragmentShader)
    }

    private fun uniformLocation(rendererId: Int, uniformName: String): Int {
        return locationMap.getOrPut(uniformName) {
            GLES30.glGetUniformLocation(rendererId, uniformName)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OpenGLShader

        return rendererId == other.rendererId
    }

    override fun hashCode(): Int {
        return rendererId
    }

    override fun toString(): String {
        return "OpenGLShader('$_name:$rendererId')"
    }


    private companion object {
        private const val TAG = "OpenGLShader"
    }
}
