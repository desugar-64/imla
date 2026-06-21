package dev.serhiiyaremych.imla.internal.render.framebuffer

import androidx.compose.ui.graphics.Color
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.util.roundToMultipleOf

internal class FramebufferPool(
    private val commands: RenderCommands = RenderCommand.commands
) {
    private companion object {
        const val DEFAULT_BUCKET_SIZE_PX = 64
    }

    private val frameBuffers = mutableMapOf<FramebufferSpecification, BumpAllocatorPool<Framebuffer>>()

    fun acquire(spec: FramebufferSpecification): Framebuffer {
        val existing = frameBuffers.getOrPut(spec) { BumpAllocatorPool() }
        return existing.acquire { Framebuffer.create(spec, commands) }
    }

    /**
     * Bucketed allocation (default 64px step): size animations only allocate when a dimension
     * crosses a bucket boundary; extra area is at most (bucketSizePx - 1) per dimension.
     */
    fun acquireBucketed(spec: FramebufferSpecification, bucketSizePx: Int = DEFAULT_BUCKET_SIZE_PX): Framebuffer {
        val bucketedSize = spec.size.roundToMultipleOf(bucketSizePx)
        val bucketedSpec = if (bucketedSize == spec.size) spec else spec.copy(size = bucketedSize)
        val pool = frameBuffers.getOrPut(bucketedSpec) { BumpAllocatorPool() }
        val willAllocate = pool.length >= pool.items.size
        if (bucketedSize != spec.size || willAllocate) {
//            logd(
//                "BucketedFbo",
//                "bucketed acquire req=${spec.size} bucket=${bucketedSize} alloc=${willAllocate}"
//            )
        }
        return pool.acquire { Framebuffer.create(bucketedSpec, commands) }
    }

    fun resetPool() {
        frameBuffers.values.forEach { it.resetPool() }
    }

    fun eraseAll() {
        frameBuffers.values.forEach {
            it.onEach {
                it.bind(commands, updateViewport = false)
                commands.clear(Color.Transparent)
                it.unbind(commands)
            }
        }
    }

    fun clear() {
        frameBuffers.values.forEach { it.onEach { it.destroy() } }
        frameBuffers.clear()
    }
}
