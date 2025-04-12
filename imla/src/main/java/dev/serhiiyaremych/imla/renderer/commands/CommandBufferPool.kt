/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.renderer.commands

import dev.serhiiyaremych.imla.ext.logd
import java.util.Arrays // For potential array copy optimization

internal class CommandBufferPool(initialCapacity: Int = 1024) {

    private var buffer: Array<RenderCommand?>
    private var commandCount = 0
    private var currentCapacity: Int

    init {
        require(initialCapacity > 0) { "Initial capacity must be positive." }
        currentCapacity = initialCapacity
        buffer = arrayOfNulls(initialCapacity)
    }

    fun record(command: RenderCommand) {
        if (commandCount >= currentCapacity) {
            resizeBuffer()
        }
        buffer[commandCount++] = command
    }

    fun reset(clearReferences: Boolean = false) {
        if (clearReferences) {
            for (i in 0 until commandCount) {
                buffer[i] = null
            }
        }
        commandCount = 0
    }

    fun getCommands(): List<RenderCommand> {
        return buffer.take(commandCount).filterNotNull()
    }

    val count: Int
        get() = commandCount


    private fun resizeBuffer() {
        val oldCapacity = currentCapacity
        val newCapacity = oldCapacity * 2
        currentCapacity = newCapacity
        buffer = buffer.copyOf(newCapacity)
        logd("CommandBufferPool", "Resized from $oldCapacity to $newCapacity")
    }
}