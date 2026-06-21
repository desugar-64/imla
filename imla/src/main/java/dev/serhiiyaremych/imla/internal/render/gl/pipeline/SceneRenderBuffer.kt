/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl.pipeline

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer

/**
 * Owned scene draw target between the captured root texture and the default surface.
 *
 * Non-rectangle clip shapes use a stencil-capable attachment. Future cumulative blur should expand
 * this into read/write accumulation buffers so each slot can sample the scene produced by previous
 * slots.
 */
internal class SceneRenderBuffer(
    val size: IntSize,
    val hasStencil: Boolean,
    internal val backing: Backing
) {
    internal sealed interface Backing {
        class Plain(val framebuffer: Framebuffer) : Backing

        @RequiresApi(26)
        class Hw(val hwBuffer: SceneHwBuffer) : Backing
    }

    val texture: Texture2D
        get() = when (val b = backing) {
            is Backing.Plain -> b.framebuffer.colorAttachmentTexture
            is Backing.Hw -> b.hwBuffer.texture
        }

    fun bindForFrameStart(commands: RenderCommands) {
        when (val b = backing) {
            is Backing.Plain -> {
                b.framebuffer.bindForOverwrite(commands, Bind.DRAW)
                commands.clear(Color.Transparent)
                commands.setViewPort(width = size.width, height = size.height)
            }

            is Backing.Hw -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b.hwBuffer.bindForDraw()
                commands.clear(Color.Transparent)
                commands.setViewPort(width = size.width, height = size.height)
            }
        }
    }

    fun bindForDraw(commands: RenderCommands) {
        when (val b = backing) {
            is Backing.Plain -> {
                b.framebuffer.bind(commands, Bind.DRAW)
                commands.setViewPort(width = size.width, height = size.height)
            }

            is Backing.Hw -> {
                b.hwBuffer.bindForDraw()
                commands.setViewPort(width = size.width, height = size.height)
            }
        }
    }
}
