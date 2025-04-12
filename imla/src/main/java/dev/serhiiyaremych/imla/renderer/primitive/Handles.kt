/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.renderer.primitive

@JvmInline
internal value class CommandBufferHandle(val id: Int)
@JvmInline
internal value class TextureHandle(val id: Int) {
    companion object {
        val Invalid = TextureHandle(-1)
    }
}

@JvmInline
internal value class FramebufferHandle(val id: Int) {
    companion object {
        val Default = FramebufferHandle(0)
        val Invalid = FramebufferHandle(-1)
    }
}

@JvmInline
internal value class ShaderProgramHandle(val id: Int) {
    companion object {
        val Invalid = ShaderProgramHandle(-1)
    }
}

@JvmInline
internal value class VertexArrayHandle(val id: Int) {
    companion object {
        val Invalid = VertexArrayHandle(-1)
    }
}

@JvmInline
internal value class UniformHandle(val location: Int) {
    companion object {
        val Invalid = UniformHandle(-1)
    }
}