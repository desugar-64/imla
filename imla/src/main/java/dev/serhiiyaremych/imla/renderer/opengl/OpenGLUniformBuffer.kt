/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.opengl

import android.opengl.GLES30
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.UniformBuffer
import dev.serhiiyaremych.imla.renderer.toFloatBuffer
import java.nio.Buffer

internal class OpenGLUniformBuffer(
    count: Int,
    binding: Int
) : UniformBuffer {

    override val elements: Int = count

    override val sizeBytes: Int
        get() = elements * Float.SIZE_BYTES

    private var rendererId: Int = 0
    private var isBound: Boolean = false

    init {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        rendererId = ids[0]
        bind()
        GLES30.glBufferData(
            /* target = */ GLES30.GL_UNIFORM_BUFFER,
            /* size = */ sizeBytes,
            /* data = */ null,
            /* usage = */ GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glBindBufferBase(
            /* target = */ GLES30.GL_UNIFORM_BUFFER,
            /* index = */ binding,
            /* buffer = */ rendererId
        )
    }

    override fun setData(data: FloatArray) {
        bind()
        trace("uboSetData") {
            GLES30.glBufferSubData(
                /* target = */ GLES30.GL_UNIFORM_BUFFER,
                /* offset = */ 0,
                /* size = */ data.size * Float.SIZE_BYTES,
                /* data = */ data.toFloatBuffer()
            )
        }
    }

    override fun setData(data: Buffer) {
        bind()
        trace("uboSetData") {
            GLES30.glBufferSubData(
                /* target = */ GLES30.GL_UNIFORM_BUFFER,
                /* offset = */ 0,
                /* size = */ data.capacity() * Float.SIZE_BYTES,
                /* data = */ data
            )
        }
    }

    override fun bind() {
        if (isBound.not()) {
            trace("uboBind") {
                GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, rendererId)
            }
            isBound = true
        }
    }

    override fun unbind() {
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, 0)
        isBound = false
    }

    override fun destroy() {
        GLES30.glDeleteBuffers(1, intArrayOf(rendererId), 0)
        isBound = false
    }

}