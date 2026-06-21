/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.effects

import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.processing.SinglePassQuadExecutor

/**
 * Shared context for active effects. It owns no GL state; effects bind their targets, configure
 * their shaders, and submit single offscreen draws through [singlePassQuadExecutor].
 */
internal class EffectContext(
    val pool: FramebufferLendingPool,
    val commands: RenderCommands,
    val shaderLibrary: ShaderLibrary,
    val shaderBinder: ShaderBinder,
    val singlePassQuadExecutor: SinglePassQuadExecutor
)
