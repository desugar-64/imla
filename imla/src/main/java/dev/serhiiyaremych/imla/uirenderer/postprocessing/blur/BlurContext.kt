/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.uirenderer.postprocessing.blur

import android.content.res.AssetManager
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import dev.serhiiyaremych.imla.renderer.Framebuffer
import dev.serhiiyaremych.imla.renderer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferSpecification
import dev.serhiiyaremych.imla.renderer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.renderer.FramebufferTextureSpecification
import kotlin.math.pow

internal data class BlurContext(
    val framebuffers: List<Framebuffer>,
    val shaderProgram: DualBlurFilterShaderProgram
) {
    companion object {
        private const val MAX_PASSES = 5
        private const val PASS_SCALE = 0.6f

        // Minimum and maximum sampling offsets for each pass count, determined empirically.
        // Too low: bilinear downsampling artifacts
        // Too high: diagonal sampling artifacts
        private val offsetRanges = listOf(
            1.00f..2.50f, // pass 1
            1.25f..4.25f, // pass 2
            1.50f..11.25f, // pass 3
            1.75f..18.00f, // pass 4
            2.00f..20.00f  // pass 5
            /* limited by MAX_PASSES */
        )

        fun create(assetManager: AssetManager, textureSize: IntSize): BlurContext {
            val fboSpec = FramebufferSpecification(
                size = textureSize,
                attachmentsSpec = FramebufferAttachmentSpecification(
                    attachments = listOf(FramebufferTextureSpecification(format = FramebufferTextureFormat.RGB10_A2))
                )
            )
            val baseLayerSize = (textureSize.toSize() * PASS_SCALE).toIntSize()
            val fbos = buildList {
                for (i in 0..MAX_PASSES) {
                    add(
                        Framebuffer.create(
                            spec = fboSpec.copy(
                                size = baseLayerSize shr i
                            )
                        )
                    )
                }
            }

            return BlurContext(
                framebuffers = fbos,
                shaderProgram = DualBlurFilterShaderProgram(assetManager)
            )
        }

        fun convertGaussianRadius(radius: Float): Pair<Int, Float> {
            for (i in 0 until MAX_PASSES) {
                val offsetRange = offsetRanges[i]
                val offset = (radius * PASS_SCALE / (2.0).pow(i + 1)).toFloat()
                if (offset in offsetRange) {
                    return (i + 1) to offset
                }
            }

            return 1 to (radius * PASS_SCALE / (2.0).pow(1)).toFloat()
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