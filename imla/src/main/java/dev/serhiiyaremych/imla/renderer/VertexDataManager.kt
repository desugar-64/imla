/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.renderer

import android.opengl.GLES30
import dev.serhiiyaremych.imla.ext.logd
import dev.serhiiyaremych.imla.ext.loge
import dev.serhiiyaremych.imla.ext.logw
import dev.serhiiyaremych.imla.renderer.primitive.VertexArrayHandle

/**
 * Manages the lifecycle and lookup of OpenGL Vertex Array Objects (VAOs)
 * and their associated Vertex Buffer Objects (VBOs) and Index Buffer Objects (IBOs).
 */
internal class VertexDataManager {
    private val TAG = "VertexDataManager"

    // Internal storage for managed mesh data
    private data class ManagedMesh(
        val handle: VertexArrayHandle,
        val vaoWrapper: VertexArray,
        val vboWrappers: List<VertexBuffer>,
        val iboWrapper: IndexBuffer?,
        val layout: BufferLayout,
        val indexCount: Int
    )

    private val handleToMesh = mutableMapOf<VertexArrayHandle, ManagedMesh>()
    private var nextHandleValue = 1 // Start handles from 1 (0 is invalid)

    /**
     * Creates a standard static mesh (VAO, VBO, IBO) suitable for geometry that doesn't change often.
     *
     * @param vertices Vertex data.
     * @param indices Index data.
     * @param layout Description of the vertex data structure.
     * @return A handle to the created vertex array object.
     */
    fun createStaticMesh(
        vertices: FloatArray,
        indices: IntArray,
        layout: BufferLayout
    ): VertexArrayHandle {
        if (vertices.isEmpty() || indices.isEmpty()) {
            loge(TAG, "Attempted to create mesh with empty vertices or indices.")
            return VertexArrayHandle.Invalid
        }

        val vaoWrapper = VertexArray.create()
        val vboWrapper = VertexBuffer.create(vertices)
        val iboWrapper = IndexBuffer.create(indices)

        vaoWrapper.bind()
        vboWrapper.layout = layout
        vaoWrapper.addVertexBuffer(vboWrapper)
        vaoWrapper.indexBuffer = iboWrapper
        vaoWrapper.unbind()

        val handle = VertexArrayHandle(nextHandleValue++)
        val managedMesh = ManagedMesh(
            handle = handle,
            vaoWrapper = vaoWrapper,
            vboWrappers = listOf(vboWrapper),
            iboWrapper = iboWrapper,
            layout = layout,
            indexCount = indices.size
        )
        handleToMesh[handle] = managedMesh
        return handle
    }

    /**
     * Creates a mesh with a dynamic vertex buffer suitable for frequent updates (e.g., particle systems, sprites).
     * The VBO is allocated with a maximum size but no initial data.
     *
     * @param maxVertices The maximum number of vertices the VBO should be able to hold.
     * @param indices Index data (usually static).
     * @param layout Description of the vertex data structure.
     * @return A handle to the created vertex array object.
     */
    fun createDynamicMesh(
        maxVertices: Int,
        indices: IntArray,
        layout: BufferLayout
    ): VertexArrayHandle {
        if (maxVertices <= 0 || indices.isEmpty()) {
            loge(TAG, "Attempted to create dynamic mesh with invalid parameters.")
            return VertexArrayHandle.Invalid
        }
        val vertexSizeFloats = layout.stride / Float.SIZE_BYTES // Calculate floats per vertex
        if (vertexSizeFloats <= 0) {
            loge(TAG, "Invalid BufferLayout stride for dynamic mesh.")
            return VertexArrayHandle.Invalid
        }


        val vaoWrapper = VertexArray.create()
        val dynamicVboWrapper = VertexBuffer.create(count = maxVertices * vertexSizeFloats)
        val iboWrapper = IndexBuffer.create(indices)

        // Configure VAO
        vaoWrapper.bind()
        dynamicVboWrapper.layout = layout
        vaoWrapper.addVertexBuffer(dynamicVboWrapper)
        vaoWrapper.indexBuffer = iboWrapper
        vaoWrapper.unbind()

        val handle = VertexArrayHandle(nextHandleValue++)
        val managedMesh = ManagedMesh(
            handle = handle,
            vaoWrapper = vaoWrapper,
            vboWrappers = listOf(dynamicVboWrapper),
            iboWrapper = iboWrapper,
            layout = layout,
            indexCount = indices.size
        )
        handleToMesh[handle] = managedMesh
        return handle
    }

    /**
     * Updates the data in a dynamic vertex buffer associated with a mesh handle.
     * Assumes the mesh was created with createDynamicMesh or has a VBO suitable for dynamic updates.
     *
     * @param handle The handle of the mesh (VAO) to update.
     * @param bufferIndex The index of the VBO within the VAO to update (usually 0).
     * @param data The new vertex data as a FloatArray.
     * @param vertexCount The number of vertices represented by the data (used for glBufferSubData size).
     */
    fun updateDynamicVertexBuffer(
        handle: VertexArrayHandle,
        bufferIndex: Int = 0, // Default to the first VBO
        data: FloatArray,
        vertexCount: Int
    ) {
        val managedMesh = handleToMesh[handle]
        if (managedMesh == null) {
            logw(TAG, "Attempted to update VBO for unknown handle $handle")
            return
        }
        if (bufferIndex < 0 || bufferIndex >= managedMesh.vboWrappers.size) {
            logw(TAG, "Invalid buffer index $bufferIndex for handle $handle")
            return
        }

        val vboWrapper = managedMesh.vboWrappers[bufferIndex]
        val layout = managedMesh.layout
        val expectedFloats = vertexCount * (layout.stride / Float.SIZE_BYTES)

        if (data.size < expectedFloats) {
            logw(TAG, "Insufficient data size (${data.size} floats) provided for $vertexCount vertices and layout stride ${layout.stride}. Expected at least $expectedFloats floats.")
            // Decide whether to proceed with partial data or return
            // Proceeding might be okay if only updating a part, but needs careful size calculation for glBufferSubData
            return // Safer to return here
        }

         vboWrapper.bind()
         val updateSizeInBytes = vertexCount * layout.stride
         GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, updateSizeInBytes, data.toFloatBuffer())
         vboWrapper.unbind()

        // No need to update handleToMesh unless the wrapper itself changes internal state significantly
    }

    /**
     * Gets the number of indices associated with a mesh handle.
     */
    fun getIndexCount(handle: VertexArrayHandle): Int {
        return handleToMesh[handle]?.indexCount ?: 0
    }

    /**
     * Destroys the OpenGL objects (VAO, VBOs, IBO) associated with a handle
     * and removes it from management.
     */
    fun destroyMesh(handle: VertexArrayHandle) {
        handleToMesh.remove(handle)?.let { mesh ->
            // Use the destroy methods of the wrapper classes
            mesh.vboWrappers.forEach { it.destroy() }
            mesh.iboWrapper?.destroy()
            mesh.vaoWrapper.destroy() // Destroys VAO last
        }
    }

    /**
     * Destroys all managed meshes and their associated OpenGL objects.
     */
    fun destroyAll() {
        logd(TAG, "Destroying all managed meshes...")
        handleToMesh.values.forEach { mesh ->
            mesh.vboWrappers.forEach { it.destroy() }
            mesh.iboWrapper?.destroy()
            mesh.vaoWrapper.destroy()
        }
        handleToMesh.clear()
        nextHandleValue = 1
        logd(TAG, "All meshes destroyed.")
    }
}