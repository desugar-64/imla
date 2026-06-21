/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.framebuffer

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.ext.logd
import dev.serhiiyaremych.imla.internal.render.RenderCommand
import dev.serhiiyaremych.imla.internal.render.RenderCommands

/**
 * A lending pool for Framebuffer objects that enables FBO reuse across effects and passes.
 *
 * Effects acquire FBOs from this pool at initialization and release them on dispose/resize,
 * reducing GPU memory allocation churn. FBOs are grouped by exact [FramebufferSpecification].
 */
internal open class FramebufferLendingPool(
    private val commands: RenderCommands = RenderCommand.commands
) {
    private companion object {
        const val DEFAULT_BUCKET_SIZE_PX = 64
        private const val TAG = "FboPool"
    }

    // Available FBOs grouped by exact specification
    private val available = mutableMapOf<FramebufferSpecification, ArrayDeque<Framebuffer>>()

    // Track checked-out FBOs for debugging/stats
    private val inUse = mutableSetOf<Framebuffer>()

    /**
     * Acquire an FBO matching the spec. Returns pooled FBO if available, creates new otherwise.
     */
    open fun acquire(spec: FramebufferSpecification): Framebuffer {
        val queue = available[spec]
        val reused = queue?.removeFirstOrNull()
        val fb = reused ?: Framebuffer.create(spec, commands).also {
            logd(
                TAG,
                "create spec=${spec.size} downsample=${spec.downSampleFactor} " +
                    "pooled=${pooledCount} inUse=${inUse.size + 1}"
            )
        }
        inUse.add(fb)
        return fb
    }

    /**
     * Bucketed allocation (adaptive 64/128/256px steps by dimension): size animations only
     * allocate when a dimension crosses a bucket boundary; extra area is at most (step - 1)
     * per dimension.
     */
    fun acquireBucketed(spec: FramebufferSpecification, bucketSizePx: Int = DEFAULT_BUCKET_SIZE_PX): Framebuffer {
        val widthStep = resolveBucketSize(spec.size.width, bucketSizePx)
        val heightStep = resolveBucketSize(spec.size.height, bucketSizePx)
        val bucketedSize = IntSize(
            width = roundUpToMultiple(spec.size.width, widthStep),
            height = roundUpToMultiple(spec.size.height, heightStep)
        )
        val bucketedSpec = if (bucketedSize == spec.size) spec else spec.copy(size = bucketedSize)
        val reuse = available[bucketedSpec]?.isNotEmpty() == true
        if (!reuse) {
            logd(
                TAG,
                "bucketed create req=${spec.size} bucket=${bucketedSize} step=${widthStep}x${heightStep}"
            )
        }
        return acquire(bucketedSpec)
    }

    /**
     * Release an FBO back to the pool for reuse.
     * Does nothing if the FBO was not acquired from this pool.
     */
    open fun release(fb: Framebuffer) {
        if (inUse.remove(fb)) {
            val spec = fb.specification
            available.getOrPut(spec) { ArrayDeque() }.addLast(fb)
        }
    }

    /**
     * Release multiple FBOs at once.
     */
    fun releaseAll(fbos: Collection<Framebuffer>) {
        fbos.forEach { release(it) }
    }

    /**
     * Destroy all pooled FBOs. Call on GL context teardown.
     * Note: In-use FBOs are tracked but not destroyed here - effects handle their own cleanup.
     */
    fun destroy() {
        available.values.forEach { queue ->
            queue.forEach { it.destroy() }
        }
        available.clear()
        inUse.clear()
    }

    /** Number of FBOs currently available in the pool */
    val pooledCount: Int get() = available.values.sumOf { it.size }

    /** Number of FBOs currently checked out */
    val inUseCount: Int get() = inUse.size

    private fun resolveBucketSize(sizePx: Int, bucketSizePx: Int): Int {
        if (bucketSizePx != DEFAULT_BUCKET_SIZE_PX) return bucketSizePx
        return when {
            sizePx >= 2048 -> 256
            sizePx >= 1024 -> 128
            else -> DEFAULT_BUCKET_SIZE_PX
        }
    }

    private fun roundUpToMultiple(value: Int, multiple: Int): Int {
        if (multiple <= 0) return value
        return ((value + multiple - 1) / multiple) * multiple
    }
}
