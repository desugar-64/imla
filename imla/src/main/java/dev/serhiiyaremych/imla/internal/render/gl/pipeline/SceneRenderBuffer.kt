/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl.pipeline

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D

/**
 * Owned scene draw target between the captured root texture and hardware-bitmap present.
 */
internal class SceneRenderBuffer(
    val size: IntSize,
    internal val hwBuffer: SceneHwBuffer
) {
    val texture: Texture2D
        get() = hwBuffer.texture

    fun bindForFrameStart(commands: RenderCommands) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hwBuffer.bindForDraw()
            commands.clear(Color.Transparent)
            commands.setViewPort(width = size.width, height = size.height)
        }
    }

    fun bindForDraw(commands: RenderCommands) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hwBuffer.bindForDraw()
            commands.setViewPort(width = size.width, height = size.height)
        }
    }
}
