/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing

import dev.serhiiyaremych.imla.uirenderer.processing.blend.PostBlendEffect
import dev.serhiiyaremych.imla.uirenderer.processing.blur.DualBlurEffect
import dev.serhiiyaremych.imla.uirenderer.processing.mask.MaskEffect
import dev.serhiiyaremych.imla.uirenderer.processing.noise.NoiseEffect
import dev.serhiiyaremych.imla.uirenderer.processing.preprocess.PreProcessFilter

internal data class EffectsHolder(
    val preProcess: PreProcessFilter,
    val blurEffect: DualBlurEffect,
    val noiseEffect: NoiseEffect,
    val maskEffect: MaskEffect,
    val blendEffect: PostBlendEffect
) {
    fun dispose() {
        blurEffect.dispose()
        noiseEffect.dispose()
        maskEffect.dispose()
    }
}