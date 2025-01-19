package dev.serhiiyaremych.imla.renderer.shader

internal class ShaderBinder {
    private var currentShader: Shader? = null

    internal fun bind(shader: Shader) {
        if (shader != currentShader) {
            currentShader?.unbind()
            shader.bind()
            currentShader = shader
        }
    }

    internal fun destroyCurrent() {
        currentShader?.destroy()
        currentShader = null
    }
}