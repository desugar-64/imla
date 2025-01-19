/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.stats

import android.util.Log

internal object ShaderStats {
    private const val TAG = "ShaderStats"

    var shaderInstances: Int = 0
    var shaderBinds: Int = 0
    var shaderBindUniformBlock: Int = 0
    var shaderUploads: Int = 0

    fun printStats() {
        Log.d(TAG, "--------ShaderStats--------")
        Log.d(
            TAG, """
            shaderInstances        = $shaderInstances
            shaderBinds            = $shaderBinds
            shaderUploads          = $shaderUploads
            shaderBindUniformBlock = $shaderBindUniformBlock
        """.trimIndent()
        )
        Log.d(TAG, "---------------------------")
    }

    fun reset() {
        shaderBinds = 0
        shaderUploads = 0
    }
}