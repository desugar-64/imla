/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.opengl.buffer

import android.opengl.GLES30
import androidx.tracing.trace
import dev.serhiiyaremych.imla.renderer.BufferLayout
import dev.serhiiyaremych.imla.renderer.VertexBuffer
import dev.serhiiyaremych.imla.renderer.toFloatBuffer

internal class OpenGLVertexBuffer : VertexBuffer {

    override var count: Int
    override var sizeBytes: Int
    override var layout: BufferLayout? = null
    private var bufferId: Int = 0
    private var isDestroyed: Boolean = false

    constructor(count: Int) {
        this.count = count
        this.sizeBytes = this.count * Float.SIZE_BYTES
        createVertexBuffer(null, GLES30.GL_DYNAMIC_DRAW)
    }

    constructor(vertices: FloatArray) {
        this.count = vertices.size
        this.sizeBytes = count * Float.SIZE_BYTES
        createVertexBuffer(vertices, GLES30.GL_STATIC_DRAW)
    }

    private fun createVertexBuffer(vertices: FloatArray?, usage: Int) {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        bufferId = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        GLES30.glBufferData(
            /* target = */ GLES30.GL_ARRAY_BUFFER,
            /* size = */ sizeBytes,
            /* data = */ vertices?.toFloatBuffer(),
            /* usage = */ usage
        )
    }

    override fun bind() = trace("vboBind") {
        if (isDestroyed) {
            error("Can't bind destroyed buffer.")
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
    }

    override fun unbind() {
        if (!isDestroyed) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
    }

    override fun setData(data: FloatArray) = trace("vboSetData") {
        bind()
        this.sizeBytes = data.size * Float.SIZE_BYTES
        this.count = data.size

        trace("glBufferSubData[${count}, ${sizeBytes}bytes]") {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, sizeBytes, data.toFloatBuffer())
        }
    }

    override fun destroy() {
        unbind()
        GLES30.glDeleteBuffers(1, intArrayOf(bufferId), 0)
        isDestroyed = true
    }
}