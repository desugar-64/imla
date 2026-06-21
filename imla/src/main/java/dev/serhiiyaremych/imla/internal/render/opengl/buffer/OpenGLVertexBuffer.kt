/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.opengl.buffer

import android.opengl.GLES30
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.ext.checkGlError
import dev.serhiiyaremych.imla.internal.render.BufferLayout
import dev.serhiiyaremych.imla.internal.render.VertexBuffer
import dev.serhiiyaremych.imla.internal.render.toFloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class OpenGLVertexBuffer : VertexBuffer {

    override var elements: Int
    override var sizeBytes: Int
    override var layout: BufferLayout? = null
    private var bufferId: Int = 0
    private var isDestroyed: Boolean = false
    private var reusableBuffer: FloatBuffer? = null
    private var maxCapacity: Int = 0
    private var bufferUsage: Int = GLES30.GL_STATIC_DRAW

    constructor(count: Int) {
        this.elements = count
        this.sizeBytes = this.elements * Float.SIZE_BYTES
        this.maxCapacity = count
        trace("vboCreateDynamic") {
            createVertexBuffer(null, GLES30.GL_DYNAMIC_DRAW)
            allocateReusableBuffer(count)
        }
    }

    constructor(vertices: FloatArray) {
        this.elements = vertices.size
        this.sizeBytes = elements * Float.SIZE_BYTES
        this.maxCapacity = vertices.size
        trace("vboCreateStatic") {
            createVertexBuffer(vertices, GLES30.GL_STATIC_DRAW)
        }
    }

    private fun allocateReusableBuffer(capacity: Int) {
        reusableBuffer = ByteBuffer
            .allocateDirect(capacity * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        maxCapacity = capacity
    }

    private fun createVertexBuffer(vertices: FloatArray?, usage: Int) {
        val ids = IntArray(1)
        checkGlError(GLES30.glGenBuffers(1, ids, 0))
        bufferId = ids[0]
        bufferUsage = usage
        checkGlError(GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId))
        checkGlError(
            GLES30.glBufferData(
                /* target = */ GLES30.GL_ARRAY_BUFFER,
                /* size = */ sizeBytes,
                /* data = */ vertices?.toFloatBuffer(),
                /* usage = */ usage
            )
        )
    }

    override fun bind() = trace("vboBind") {
        if (isDestroyed) {
            error("Can't bind destroyed buffer.")
        }
        checkGlError(GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId))
    }

    override fun unbind() {
        if (!isDestroyed) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
    }

    override fun setData(data: FloatArray) = setData(data, data.size)

    override fun setData(data: FloatArray, count: Int) = trace("vboSetData") {
        bind()
        this.sizeBytes = count * Float.SIZE_BYTES
        this.elements = count
        if (count > maxCapacity) {
            val capacityBytes = count * Float.SIZE_BYTES
            trace("glBufferData[resize, ${capacityBytes}bytes]") {
                checkGlError(
                    GLES30.glBufferData(
                        GLES30.GL_ARRAY_BUFFER,
                        capacityBytes,
                        null,
                        bufferUsage
                    )
                )
            }
            allocateReusableBuffer(count)
        }

        val buffer = reusableBuffer
        if (buffer != null && count <= maxCapacity) {
            buffer.clear()
            buffer.put(data, 0, count)
            buffer.position(0)
            buffer.limit(count)

            trace("glBufferSubData[${elements}, ${sizeBytes}bytes]") {
                checkGlError(
                    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, sizeBytes, buffer)
                )
            }
        } else {
            trace("glBufferSubData[${elements}, ${sizeBytes}bytes]") {
                checkGlError(
                    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, sizeBytes, data.toFloatBuffer())
                )
            }
        }
    }

    override fun destroy() {
        unbind()
        GLES30.glDeleteBuffers(1, intArrayOf(bufferId), 0)
        isDestroyed = true
    }
}
