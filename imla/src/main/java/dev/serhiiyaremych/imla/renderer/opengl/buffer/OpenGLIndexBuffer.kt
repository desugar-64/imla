/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.opengl.buffer

import android.opengl.GLES30
import dev.serhiiyaremych.imla.ext.checkGlError
import dev.serhiiyaremych.imla.renderer.IndexBuffer
import dev.serhiiyaremych.imla.renderer.toIntBuffer

internal class OpenGLIndexBuffer(indices: IntArray) : IndexBuffer {

    override val elements: Int = indices.size
    override val sizeBytes: Int = elements * Int.SIZE_BYTES
    private var rendererId: Int = 0
    private var isDestroyed: Boolean = false

    init {
        val ids = IntArray(1)
        checkGlError(GLES30.glGenBuffers(1, ids, 0))
        rendererId = ids[0]
        checkGlError(GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, rendererId))
        checkGlError(
            GLES30.glBufferData(
                /* target = */ GLES30.GL_ELEMENT_ARRAY_BUFFER,
                /* size = */ sizeBytes,
                /* data = */ indices.toIntBuffer(),
                /* usage = */ GLES30.GL_STATIC_DRAW
            )
        )
    }

    override fun bind() {
        if (isDestroyed) {
            error("Can't bind destroyed index buffer.")
        }
        checkGlError(GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, rendererId))
    }

    override fun unbind() {
        if (!isDestroyed) {
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        }
    }

    override fun destroy() {
        unbind()
        GLES30.glDeleteBuffers(1, intArrayOf(rendererId), 0)
        isDestroyed = true
    }
}