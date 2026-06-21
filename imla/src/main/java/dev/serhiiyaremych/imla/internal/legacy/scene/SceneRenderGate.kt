/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

internal interface SceneRenderGate {
    fun canRenderScene(): Boolean

    companion object {
        fun alwaysEnabled(): SceneRenderGate = object : SceneRenderGate {
            override fun canRenderScene(): Boolean = true
        }
    }
}

internal class MutableSceneRenderGate(
    initialEnabled: Boolean = true
) : SceneRenderGate {
    @Volatile
    var enabled: Boolean = initialEnabled

    override fun canRenderScene(): Boolean = enabled
}
