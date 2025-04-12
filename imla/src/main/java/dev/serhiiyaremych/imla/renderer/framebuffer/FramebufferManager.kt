/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.renderer.framebuffer

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.ext.logd
import dev.serhiiyaremych.imla.ext.logw
import dev.serhiiyaremych.imla.renderer.primitive.FramebufferHandle
import dev.serhiiyaremych.imla.renderer.primitive.TextureHandle

/**
 * Manages the lifecycle and acquisition of Framebuffer Objects (FBOs).
 * Uses a FramebufferPool internally to reuse FBOs with matching specifications.
 */
internal class FramebufferManager {

    private val pool = FramebufferPool()

    private val handleToSpec = mutableMapOf<FramebufferHandle, FramebufferSpecification>()

    // Maps handle to the *currently acquired* Framebuffer instance from the pool for this frame/cycle.
    private val handleToCurrentInstance = mutableMapOf<FramebufferHandle, Framebuffer>()

    private val specToHandle = mutableMapOf<FramebufferSpecification, FramebufferHandle>()

    private var nextHandleValue = 1 // Start handles from 1 (0 is Default FBO)

    /**
     * Acquires a Framebuffer instance from the pool that matches the specification.
     * If no matching FBO is available in the pool, a new one might be created.
     * Returns a handle to the acquired Framebuffer.
     *
     * @param spec The desired specification for the Framebuffer.
     * @return A FramebufferHandle identifying the acquired FBO.
     */
    fun acquireFramebuffer(spec: FramebufferSpecification): FramebufferHandle {
        val existingHandle = specToHandle[spec]
        if (existingHandle != null) {
            val currentInstance = pool.acquire(spec) // acquire might return existing or new
            handleToCurrentInstance[existingHandle] = currentInstance
            // logd(TAG, "Re-acquired FBO for spec $spec with handle $existingHandle")
            return existingHandle
        }

        val framebufferInstance = pool.acquire(spec) // Uses Framebuffer.create -> OpenGLFramebuffer internally

        val newHandle = FramebufferHandle(nextHandleValue++)
        handleToSpec[newHandle] = spec
        specToHandle[spec] = newHandle // Store spec -> handle mapping
        handleToCurrentInstance[newHandle] = framebufferInstance

        logd(TAG, "Acquired new FBO for spec $spec with handle $newHandle")
        return newHandle
    }

    /**
     * Gets the *currently acquired* Framebuffer instance associated with a handle for this cycle.
     * Returns null if the handle is invalid or no instance is currently acquired for it.
     */
    fun getFramebuffer(handle: FramebufferHandle): Framebuffer? {
        if (handle == FramebufferHandle.Default) {
            // Cannot return an instance for the default FBO this way
            logw(TAG, "Cannot get Framebuffer instance for Default handle (0). Bind directly.")
            return null
        }
        return handleToCurrentInstance[handle]
    }

    /**
     * Gets the TextureHandle of the primary color attachment of the specified Framebuffer.
     * Returns Invalid handle if the Framebuffer or its texture cannot be found.
     */
    fun getFramebufferTexture(handle: FramebufferHandle): TextureHandle {
        // Need the *instance* to get the texture ID
        val instance = getFramebuffer(handle)
        return instance?.colorAttachmentTexture?.id?.let { TextureHandle(it) } ?: TextureHandle.Invalid
    }

    /**
     * Gets the size specified for a given Framebuffer handle.
     */
    fun getFramebufferSize(handle: FramebufferHandle): IntSize? {
        if (handle == FramebufferHandle.Default) {
            // Need a different mechanism to get default FBO size
            return null
        }
        return handleToSpec[handle]?.size
    }

    /**
     * Gets the full specification associated with a Framebuffer handle.
     */
    fun getFramebufferSpec(handle: FramebufferHandle): FramebufferSpecification? {
        if (handle == FramebufferHandle.Default) return null
        return handleToSpec[handle]
    }

    /**
     * Resets the usage count of the internal pool, making all pooled FBOs available
     * for acquisition again in the next cycle. Also clears the handle-to-instance mapping.
     * Call this typically once per frame or rendering pass after FBOs are no longer needed.
     */
    fun resetPoolUsage() {
        pool.resetPool() // Resets the BumpAllocatorPool counts inside FramebufferPool
        handleToCurrentInstance.clear() // Handles no longer map to valid *current* instances
        // logd(TAG, "Reset FBO pool usage.")
    }


    fun eraseAllPooled() {
        pool.eraseAll() // Calls erase on each underlying BumpAllocatorPool
        logd(TAG, "Erased content of all pooled FBOs.")
    }

    /**
     * Destroys all Framebuffer objects managed by the internal pool permanently
     * and clears all tracking maps.
     */
    fun destroyAll() {
        logd(TAG, "Destroying all managed framebuffers...")
        pool.clear()
        handleToSpec.clear()
        specToHandle.clear()
        handleToCurrentInstance.clear()
        nextHandleValue = 1
        logd(TAG, "All framebuffers destroyed.")
    }

    companion object {
        private const val TAG = "FramebufferManager"
    }
}
