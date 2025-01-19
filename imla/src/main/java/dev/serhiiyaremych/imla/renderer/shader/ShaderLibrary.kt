package dev.serhiiyaremych.imla.renderer.shader

import android.content.res.AssetManager
import androidx.tracing.trace
import org.intellij.lang.annotations.Language

internal class ShaderLibrary(private val assetManager: AssetManager) {
    private val shaders: MutableMap<String, Shader> = mutableMapOf()

    fun loadShaderFromFile(vertFileName: String, fragFileName: String): Shader =
        trace("ShaderLibrary#loadShader") {
            return shaders.getOrPut("${vertFileName}_$fragFileName") {
                Shader.create(
                    assetManager,
                    "shader/$vertFileName.vert",
                    "shader/$fragFileName.frag"
                )
            }
        }

    fun loadShader(
        name: String,
        @Language("GLSL") vertexSrc: String,
        @Language("GLSL") fragmentSrc: String
    ): Shader {
        return shaders.getOrPut(name) {
            Shader.create(name, vertexSrc, fragmentSrc)
        }
    }

    fun loadShader(
        name: String,
        @Language("GLSL") fragmentSrc: String
    ): Shader {
        return shaders.getOrPut(name) {
            Shader.create(assetManager, fragmentSrc)
        }
    }


    fun destroy(shader: Shader) {
        shader.destroy()
        shaders.remove(shader.name)
    }

    fun destroyAll() {
        shaders.forEach { (_, shader) -> shader.destroy() }
        shaders.clear()
    }
}