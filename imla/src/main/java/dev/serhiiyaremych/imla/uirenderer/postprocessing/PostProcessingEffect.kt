/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.uirenderer.postprocessing

import dev.serhiiyaremych.imla.renderer.Texture2D
import dev.serhiiyaremych.imla.uirenderer.RenderObject

internal interface PostProcessingEffect {
    fun applyEffect(renderObject: RenderObject): Texture2D
    fun dispose()
}