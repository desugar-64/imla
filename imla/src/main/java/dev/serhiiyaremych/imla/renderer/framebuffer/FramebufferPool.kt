package dev.serhiiyaremych.imla.renderer.framebuffer

import androidx.compose.ui.graphics.Color
import dev.serhiiyaremych.imla.renderer.RenderCommand

internal class FramebufferPool {
    private val frameBuffers = mutableMapOf<FramebufferSpecification, BumpAllocatorPool<Framebuffer>>()

    fun acquire(spec: FramebufferSpecification): Framebuffer {
        val existing = frameBuffers.getOrPut(spec) { BumpAllocatorPool() }
        return existing.acquire { Framebuffer.create(spec) }
    }

    fun resetPool() {
        frameBuffers.values.forEach { it.resetPool() }
    }

    fun eraseAll() {
        frameBuffers.values.forEach {
            it.onEach {
                it.bind(updateViewport = false)
                RenderCommand.clear(Color.Transparent)
                it.unbind()
            }
        }
    }

    fun clear() {
        frameBuffers.values.forEach { it.onEach { it.destroy() } }
        frameBuffers.clear()
    }
}