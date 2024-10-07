/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.processing.blur

import android.content.res.AssetManager
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.uirenderer.processing.preprocess.times

internal data class BlurContext(
    val framebuffers: List<Framebuffer>,
    val shaderProgram: DualBlurFilterShaderProgram
) {
    companion object {
        const val PASS_SCALE = 0.5f
        const val MAX_PASSES = 4

        fun create(assetManager: AssetManager, textureSize: IntSize): BlurContext {
            val fboSpec = FramebufferSpecification(
                size = textureSize,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(format = FramebufferTextureFormat.RGB10_A2)
            )
            val baseLayerSize = textureSize * PASS_SCALE

            val fbos = buildList {
                for (i in 0..MAX_PASSES) {
                    add(Framebuffer.create(fboSpec.copy(size = baseLayerSize shr i)))
                }
            }

            return BlurContext(
                framebuffers = fbos,
                shaderProgram = DualBlurFilterShaderProgram(assetManager)
            )
        }


    }
}


/**
 * Performs a bitwise right shift operation on both the width and height of an IntSize.
 * This is equivalent to dividing both dimensions by 2^i.
 *
 * @param i The number of positions to shift right. Must be non-negative.
 * @return A new IntSize with both width and height shifted right by i positions.
 *
 * Example:
 *   val originalSize = IntSize(1024, 768)
 *   val halfSize = originalSize shr 1  // Results in IntSize(512, 384)
 *   val quarterSize = originalSize shr 2  // Results in IntSize(256, 192)
 *
 */
private infix fun IntSize.shr(i: Int): IntSize {
    return IntSize(
        width = width shr i,
        height = height shr i
    )
}