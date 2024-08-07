/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing

import dev.serhiiyaremych.imla.uirenderer.postprocessing.blur.DualBlurEffect
import dev.serhiiyaremych.imla.uirenderer.postprocessing.mask.MaskEffect
import dev.serhiiyaremych.imla.uirenderer.postprocessing.noise.NoiseEffect

internal data class EffectsHolder(
    val blurEffect: DualBlurEffect,
    val noiseEffect: NoiseEffect,
    val maskEffect: MaskEffect
) {
    fun dispose() {
        blurEffect.dispose()
        noiseEffect.dispose()
        maskEffect.dispose()
    }
}