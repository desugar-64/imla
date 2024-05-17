/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer.postprocessing

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.renderer.Texture
import dev.serhiiyaremych.imla.uirenderer.RenderableScope

internal interface PostProcessingEffect {
    fun shouldResize(size: IntSize): Boolean
    fun setup(size: IntSize)
    context(RenderableScope)
    fun applyEffect(texture: Texture): Texture

    fun dispose()
}