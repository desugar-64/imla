/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui

import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.imla.EffectLayerScope
import dev.serhiiyaremych.imla.effectGroup
import dev.serhiiyaremych.imla.effectLayer

/**
 * Parametrized blur-cost benchmark scene.
 *
 * A high-contrast scrolling list (the effect-group root) with one centered
 * backdrop-blur slot. The macrobenchmark scrolls the list, so fresh content
 * moves behind the slot every frame and the whole backdrop pipeline
 * (prepare -> separable blur -> composite) re-runs each frame. This isolates
 * per-frame blur GPU cost as a function of sigma (kernel radius / downsample
 * tier), slot size (punch area), and the filter stack.
 *
 * Everything is driven by [BlurBenchmarkParams] read from the launch Intent, so
 * the scenario matrix lives in the benchmark, not in a combinatorial enum.
 */
internal data class BlurBenchmarkParams(
    val sigmaPx: Float,
    val slotWidthDp: Float,
    val slotHeightDp: Float,
    val slotCount: Int,
    val animateSigma: Boolean,
    val tint: Boolean,
    val noise: Boolean,
    val clip: Boolean,
    val progressiveMask: Boolean,
) {
    companion object {
        const val EXTRA_ENABLED: String = "imla.blurbench.enabled"
        private const val EXTRA_SIGMA = "imla.blurbench.sigmaPx"
        private const val EXTRA_SLOT_W = "imla.blurbench.slotWidthDp"
        private const val EXTRA_SLOT_H = "imla.blurbench.slotHeightDp"
        private const val EXTRA_SLOT_COUNT = "imla.blurbench.slotCount"
        private const val EXTRA_ANIMATE_SIGMA = "imla.blurbench.animateSigma"
        private const val EXTRA_TINT = "imla.blurbench.tint"
        private const val EXTRA_NOISE = "imla.blurbench.noise"
        private const val EXTRA_CLIP = "imla.blurbench.clip"
        private const val EXTRA_PROGRESSIVE = "imla.blurbench.progressiveMask"

        fun fromIntent(intent: Intent?): BlurBenchmarkParams? {
            if (intent == null || !intent.getBooleanExtra(EXTRA_ENABLED, false)) return null
            return BlurBenchmarkParams(
                sigmaPx = intent.getFloatExtra(EXTRA_SIGMA, 24f),
                slotWidthDp = intent.getFloatExtra(EXTRA_SLOT_W, 220f),
                slotHeightDp = intent.getFloatExtra(EXTRA_SLOT_H, 136f),
                slotCount = intent.getIntExtra(EXTRA_SLOT_COUNT, 1).coerceAtLeast(1),
                animateSigma = intent.getBooleanExtra(EXTRA_ANIMATE_SIGMA, false),
                tint = intent.getBooleanExtra(EXTRA_TINT, false),
                noise = intent.getBooleanExtra(EXTRA_NOISE, false),
                clip = intent.getBooleanExtra(EXTRA_CLIP, false),
                progressiveMask = intent.getBooleanExtra(EXTRA_PROGRESSIVE, false),
            )
        }
    }
}

@Composable
internal fun BlurBenchmarkScene(
    params: BlurBenchmarkParams,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .effectGroup()
                .background(Color(0xFF101010)),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(BLUR_BENCH_ITEM_COUNT) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .height(72.dp)
                        .background(
                            color = BLUR_BENCH_COLORS[index % BLUR_BENCH_COLORS.size],
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "item ${index + 1}",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = Color.White,
                        fontSize = 18.sp
                    )
                }
            }
        }

        if (params.slotCount <= 1) {
            BlurBenchSlot(params, shape, Modifier.align(Alignment.Center))
        } else {
            FlowRow(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                repeat(params.slotCount) { BlurBenchSlot(params, shape) }
            }
        }

        Text(
            text = params.label(),
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun BlurBenchSlot(
    params: BlurBenchmarkParams,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
) {
    // When animating, slowly sweep sigma min<->max so it crosses the
    // power-of-two downsample boundaries (sigma ~22.6 and ~45.3) repeatedly;
    // a visible pop there is the tier-flip artifact Slice 4 is checking for.
    val sigmaPx = if (params.animateSigma) {
        val transition = rememberInfiniteTransition(label = "blurSigma")
        transition.animateFloat(
            initialValue = ANIMATE_SIGMA_MIN,
            targetValue = params.sigmaPx.coerceAtLeast(ANIMATE_SIGMA_MIN + 1f),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "sigma"
        ).value
    } else {
        params.sigmaPx
    }

    Box(
        modifier = modifier
            .size(width = params.slotWidthDp.dp, height = params.slotHeightDp.dp)
            .effectLayer { applyBlurBenchEffects(params, shape, sigmaPx) },
        contentAlignment = Alignment.Center
    ) {
        val label = if (params.animateSigma) "σ ${sigmaPx.toInt()}" else "blur"
        Text(text = label, color = Color.White, fontSize = 20.sp)
    }
}

private fun BlurBenchmarkParams.label(): String {
    val filters = buildList {
        if (tint) add("tint")
        if (noise) add("noise")
        if (clip) add("clip")
        if (progressiveMask) add("progressive")
    }.ifEmpty { listOf("plain") }.joinToString("+")
    val count = if (slotCount > 1) "  ×$slotCount" else ""
    return "σ=${sigmaPx.toInt()}  ${slotWidthDp.toInt()}×${slotHeightDp.toInt()}$count  $filters"
}

private fun EffectLayerScope.applyBlurBenchEffects(
    params: BlurBenchmarkParams,
    shape: RoundedCornerShape,
    sigmaPx: Float,
) {
    if (params.progressiveMask) {
        backdropBlur(sigmaPx = sigmaPx, progressiveMask = BlurBenchProgressiveMask)
    } else {
        backdropBlur(sigmaPx = sigmaPx)
    }
    if (params.tint) tint(Color(0xFFE0F7FA).copy(alpha = 0.22f))
    if (params.noise) noise(alpha = 0.16f)
    if (params.clip) clip(shape, inset = PaddingValues(1.dp))
}

private const val ANIMATE_SIGMA_MIN = 2f

private val BlurBenchProgressiveMask = Brush.verticalGradient(
    0f to Color.Transparent,
    0.42f to Color.White,
    1f to Color.White
)

private const val BLUR_BENCH_ITEM_COUNT = 60
private val BLUR_BENCH_COLORS = listOf(
    Color(0xFFE53935),
    Color(0xFF1E88E5),
    Color(0xFF43A047),
    Color(0xFFFB8C00),
    Color(0xFF8E24AA),
    Color(0xFF00ACC1),
    Color(0xFFFDD835),
    Color(0xFF6D4C41),
)
