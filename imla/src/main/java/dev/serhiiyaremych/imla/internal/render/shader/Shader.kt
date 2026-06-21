/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.internal.render.shader

import android.content.res.AssetManager
import androidx.collection.FloatFloatPair
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat3
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.opengl.OpenGLShader
import org.intellij.lang.annotations.Language
import java.io.InputStream

internal interface Shader {
    val name: String
    @Deprecated("")
    fun bind()
    fun bind(shaderBinder: ShaderBinder)
    fun unbind()

    fun bindUniformBlock(blockName: String, bindingPoint: Int)

    fun setInt(name: String, value: Int)
    fun setIntArray(name: String, values: IntArray)
    fun setFloatArray(name: String, values: FloatArray)
    fun setFloat4Array(name: String, values: FloatArray, count: Int)
    fun setFloat(name: String, value: Float)
    fun setFloat2(name: String, value: FloatFloatPair)
    fun setFloat3(name: String, value: Float3)
    fun setFloat4(name: String, value: Float4)
    fun setMat3(name: String, value: Mat3)
    fun setMat4(name: String, value: Mat4)
    fun resetUniformCache()

    fun destroy()

    companion object {
        private const val TAG = "Shader"

        private fun dropExtension(fileName: String): String {
            val lastIndexOfDot = fileName.lastIndexOf(".")
            return if (lastIndexOfDot != -1) fileName.substring(0, lastIndexOfDot) else fileName
        }

        private fun readWithCloseStream(inputStream: InputStream): String {
            return inputStream.bufferedReader().readText().also { inputStream.close() }
        }

        fun create(assetManager: AssetManager, vertexAsset: String, fragmentAsset: String): Shader {
            return OpenGLShader(
                name = dropExtension(vertexAsset),
                vertexSrc = readWithCloseStream(assetManager.open(vertexAsset)),
                fragmentSrc = readWithCloseStream(assetManager.open(fragmentAsset))
            )
        }

        fun create(
            assetManager: AssetManager,
            vertexAsset: String,
            fragmentAsset: String,
            preprocessorDefines: Map<String, String>
        ): Shader {
            var vertexSrc = readWithCloseStream(assetManager.open(vertexAsset))
            var fragmentSrc = readWithCloseStream(assetManager.open(fragmentAsset))

            preprocessorDefines.forEach { (key, value) ->
                vertexSrc = vertexSrc.replace("\${$key}", value)
                fragmentSrc = fragmentSrc.replace("\${$key}", value)
            }

            return OpenGLShader(
                name = dropExtension(vertexAsset),
                vertexSrc = vertexSrc,
                fragmentSrc = fragmentSrc
            )
        }

        fun create(
            name: String,
            @Language("GLSL") vertexSrc: String,
            @Language("GLSL") fragmentSrc: String
        ): Shader {
            return OpenGLShader(name, vertexSrc, fragmentSrc)
        }

        fun create(
            assetManager: AssetManager,
            @Language("GLSL") fragmentSrc: String
        ): Shader {
            return OpenGLShader(
                name = "simple_quad",
                vertexSrc = readWithCloseStream(assetManager.open("shader/simple_quad.vert")),
                fragmentSrc = fragmentSrc
            )
        }
    }
}
