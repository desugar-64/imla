package dev.serhiiyaremych.imla.internal.render.shader

internal class ShaderBinder {
    private var currentShader: Shader? = null

    internal fun bind(shader: Shader) {
        if (shader != currentShader) {
            currentShader?.unbind()
        }
        // Always bind, even if it's the same shader object; GL state might have been reset.
        shader.bind()
        currentShader = shader
    }

    internal fun destroyCurrent() {
        currentShader?.destroy()
        currentShader = null
    }
}
