/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.processing.SinglePassQuadExecutor

/**
 * Seeds the scene framebuffer with the committed root texture before slot passes run.
 *
 * SceneGlRenderer invokes this on the GL renderer thread after it has resolved a frame and scene
 * framebuffer. Command state comes from the owning GraphicsContext and stays local to that
 * renderer. This pass owns only the root clean-plate FBO bind, blend-state setup, and
 * single-pass texture draw; it does not own scene planning, captured resources, effect
 * framebuffers, stencil state, or renderer/session lifecycle.
 */
internal class SceneRootSeedPass(
    private val commandsProvider: () -> RenderCommands,
    private val singlePassQuadExecutor: SinglePassQuadExecutor
) {
    fun execute(rootTexture: Texture2D, scene: Framebuffer) = trace("SceneRootSeedPass#execute") {
        executeCommands(rootTexture, scene)
    }

    internal fun executeCommands(rootTexture: Texture2D, scene: Framebuffer) {
        val commands = commandsProvider()
        scene.bindForOverwrite(commands, Bind.DRAW)
        commands.disableBlending()
        singlePassQuadExecutor.draw(
            texture = rootTexture,
            flipY = rootTexture.flipTexture
        )
    }
}
