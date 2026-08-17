/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl.pipeline

import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import java.io.Closeable

// Two buffers so GL does not write the frame HWUI is still sampling.
private const val RING_CAPACITY = 2

@RequiresApi(26)
internal class SceneHwBufferRing(private val capacity: Int = RING_CAPACITY) : Closeable {
    private val slots = arrayOfNulls<SceneHwBuffer>(capacity)
    private var next = 0

    fun obtain(size: IntSize): SceneHwBuffer {
        val idx = Math.floorMod(next++, capacity)
        val existing = slots[idx]
        if (existing != null &&
            existing.texture.width == size.width &&
            existing.texture.height == size.height
        ) {
            return existing
        }
        existing?.close()
        return SceneHwBuffer.create(size).also { slots[idx] = it }
    }

    override fun close() {
        slots.forEachIndexed { idx, buffer ->
            buffer?.close()
            slots[idx] = null
        }
    }
}
