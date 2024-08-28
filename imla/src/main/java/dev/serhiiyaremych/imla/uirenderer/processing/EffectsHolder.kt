/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing

import dev.serhiiyaremych.imla.uirenderer.processing.blur.DualBlurEffect
import dev.serhiiyaremych.imla.uirenderer.processing.mask.MaskEffect
import dev.serhiiyaremych.imla.uirenderer.processing.noise.NoiseEffect
import dev.serhiiyaremych.imla.uirenderer.processing.preprocess.PreProcess

internal data class EffectsHolder(
    val preProcess: PreProcess,
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