/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.internal.render.processing

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.Texture

internal interface PostProcessingEffect {
    fun shouldResize(size: IntSize): Boolean
    fun setup(size: IntSize)
    fun applyEffect(texture: Texture): Texture

    fun dispose()
}