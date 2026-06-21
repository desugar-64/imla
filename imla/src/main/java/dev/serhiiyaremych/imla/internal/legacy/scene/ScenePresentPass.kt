/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.processing.SinglePassQuadExecutor

/**
 * Presents the selected texture into the currently attached default framebuffer.
 *
 * SceneGlRenderer invokes this on the GL renderer thread for root-only and composed scene output.
 * Command state comes from the owning GraphicsContext and stays local to that renderer. This pass
 * owns only default-framebuffer binding, viewport setup, and single-pass texture draw; it does
 * not own scene planning, captured resources, effect framebuffers, stencil state, or
 * renderer/session lifecycle.
 */
internal class ScenePresentPass(
    private val commandsProvider: () -> RenderCommands,
    private val singlePassQuadExecutor: SinglePassQuadExecutor
) {
    fun execute(texture: Texture2D, targetSize: IntSize, flipY: Boolean) = trace("ScenePresentPass#execute") {
        executeCommands(texture, targetSize, flipY)
    }

    internal fun executeCommands(texture: Texture2D, targetSize: IntSize, flipY: Boolean) {
        val commands = commandsProvider()
        commands.bindDefaultFramebuffer()
        commands.setViewPort(width = targetSize.width, height = targetSize.height)
        singlePassQuadExecutor.draw(
            texture = texture,
            flipY = flipY
        )
    }
}
