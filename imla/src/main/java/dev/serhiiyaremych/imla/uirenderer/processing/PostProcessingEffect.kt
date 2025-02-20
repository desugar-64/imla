/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer.processing

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.renderer.Texture

internal interface PostProcessingEffect {
    fun shouldResize(size: IntSize): Boolean
    fun setup(size: IntSize)
    fun applyEffect(texture: Texture): Texture

    fun dispose()
}