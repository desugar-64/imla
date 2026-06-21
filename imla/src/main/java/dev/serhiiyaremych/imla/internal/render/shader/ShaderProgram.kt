/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.shader

import dev.serhiiyaremych.imla.internal.render.BufferLayout
import dev.serhiiyaremych.imla.internal.render.primitive.QuadVertex

internal interface ShaderProgram {
    val shader: Shader
    val vertexBufferLayout: BufferLayout
    val componentsCount: Int
    fun mapVertexData(quadVertexBufferBase: List<QuadVertex>, buffer: FloatArray): Int

    fun destroy()
}