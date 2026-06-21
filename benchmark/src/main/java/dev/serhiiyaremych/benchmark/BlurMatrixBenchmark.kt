/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Blur-cost baseline matrix.
 *
 * Each test drives the parametrized [BlurBenchmarkScene] in the app (selected via
 * launch-Intent extras) and scrolls a high-contrast list behind one backdrop-blur
 * slot, so the full backdrop pipeline (prepare -> separable blur -> composite)
 * re-runs every frame. The matrix separates the two cost drivers and their
 * interaction:
 *
 *   - sigma {4, 24, 96}  -> kernel radius + downsample tier (1.0 / 0.5 / 0.25)
 *   - slot size {small, large} -> punch area (pixels the pipeline touches)
 *   - filter stack at the anchor (sigma=24, large): plain / +tint+noise+clip /
 *     +progressive mask (the separate, heavier masked shader path)
 *
 * Ground truth is [FrameTimingMetric] (on a GPU-bound scene the frame duration is
 * the GPU cost). The per-pass [TraceSectionMetric]s are *attribution*: they are
 * GL-thread CPU wall time, not GPU timer-query measurements. True per-pass GPU
 * isolation needs GL_EXT_disjoint_timer_query (separate follow-up).
 */
@RunWith(AndroidJUnit4::class)
class BlurMatrixBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test fun blur_s4_small() = measureBlur(sigmaPx = 4f, w = SMALL_W, h = SMALL_H)
    @Test fun blur_s4_large() = measureBlur(sigmaPx = 4f, w = LARGE_W, h = LARGE_H)
    @Test fun blur_s24_small() = measureBlur(sigmaPx = 24f, w = SMALL_W, h = SMALL_H)
    @Test fun blur_s24_large() = measureBlur(sigmaPx = 24f, w = LARGE_W, h = LARGE_H)
    @Test fun blur_s96_small() = measureBlur(sigmaPx = 96f, w = SMALL_W, h = SMALL_H)
    @Test fun blur_s96_large() = measureBlur(sigmaPx = 96f, w = LARGE_W, h = LARGE_H)

    @Test fun blur_s24_large_fullstack() =
        measureBlur(sigmaPx = 24f, w = LARGE_W, h = LARGE_H, tint = true, noise = true, clip = true)

    @Test fun blur_s24_large_progressive() =
        measureBlur(sigmaPx = 24f, w = LARGE_W, h = LARGE_H, progressiveMask = true)

    // N-slot scenarios: the heavy "real" scene (top bar + nav + FAB + cards) is
    // N simultaneous backdrop-blur slots, not one. Per-slot cost (4 passes + FBO
    // switches) scales with N; these reproduce the budget break single-slot cannot.
    // Slot size held at MEDIUM so FlowRow tiles them without overlap on the device.
    @Test fun blur_s24_n2_medium() = measureBlur(sigmaPx = 24f, w = MEDIUM_W, h = MEDIUM_H, slots = 2)
    @Test fun blur_s24_n4_medium() = measureBlur(sigmaPx = 24f, w = MEDIUM_W, h = MEDIUM_H, slots = 4)
    @Test fun blur_s24_n6_medium() = measureBlur(sigmaPx = 24f, w = MEDIUM_W, h = MEDIUM_H, slots = 6)
    @Test fun blur_s24_n8_medium() = measureBlur(sigmaPx = 24f, w = MEDIUM_W, h = MEDIUM_H, slots = 8)

    // ~72dp blur (sigma 64 -> 1/16 downsample) in the heavy scene: confirms the
    // deep-downsample range is affordable at N slots, not just single-slot.
    @Test fun blur_s64_n4_medium() = measureBlur(sigmaPx = 64f, w = MEDIUM_W, h = MEDIUM_H, slots = 4)
    @Test fun blur_s64_n8_medium() = measureBlur(sigmaPx = 64f, w = MEDIUM_W, h = MEDIUM_H, slots = 8)

    @OptIn(ExperimentalMetricApi::class)
    private fun measureBlur(
        sigmaPx: Float,
        w: Float,
        h: Float,
        slots: Int = 1,
        tint: Boolean = false,
        noise: Boolean = false,
        clip: Boolean = false,
        progressiveMask: Boolean = false,
    ) = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        compilationMode = CompilationMode.Full(),
        metrics = blurMetrics(),
        iterations = 3,
        startupMode = StartupMode.WARM,
        setupBlock = {
            killProcess()
            pressHome()
            startActivityAndWait(
                Intent().apply {
                    setClassName(PACKAGE, "$PACKAGE.MainActivity")
                    putExtra(EXTRA_ENABLED, true)
                    putExtra(EXTRA_SIGMA, sigmaPx)
                    putExtra(EXTRA_SLOT_W, w)
                    putExtra(EXTRA_SLOT_H, h)
                    putExtra(EXTRA_SLOT_COUNT, slots)
                    putExtra(EXTRA_TINT, tint)
                    putExtra(EXTRA_NOISE, noise)
                    putExtra(EXTRA_CLIP, clip)
                    putExtra(EXTRA_PROGRESSIVE, progressiveMask)
                }
            )
        }
    ) {
        val uiDevice = this.device
        val cx = uiDevice.displayWidth / 2
        val top = (uiDevice.displayHeight * 0.25).toInt()
        val bottom = (uiDevice.displayHeight * 0.75).toInt()

        uiDevice.awaitComposeIdle()
        // Continuous-motion scroll: fresh content moves behind the slot each frame
        // so the backdrop pipeline re-runs at full cost. 40 steps = ~constant
        // velocity, not a decelerating fling.
        repeat(8) {
            uiDevice.swipe(cx, bottom, cx, top, 40)
        }
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun blurMetrics(): List<Metric> = listOf(
        FrameTimingMetric(),
        CapturePacingMetric(),
        traceSection("SceneRenderPipeline#render", TraceSectionMetric.Mode.Average),
        traceSection("SceneRenderPipeline#render", TraceSectionMetric.Mode.Max),
        traceSection("SceneBackdropPreparePass#prepare", TraceSectionMetric.Mode.Average),
        traceSection("SceneBackdropBlurPass#process", TraceSectionMetric.Mode.Average),
        traceSection("SceneBackdropBlurPass#process", TraceSectionMetric.Mode.Max),
        traceSection("SceneBackdropCompositePass#draw", TraceSectionMetric.Mode.Average),
        traceSection("SceneGlPresent", TraceSectionMetric.Mode.Average),
    )

    @OptIn(ExperimentalMetricApi::class)
    private fun traceSection(label: String, mode: TraceSectionMetric.Mode) =
        TraceSectionMetric(sectionName = label, mode = mode)

    private fun UiDevice.awaitComposeIdle(timeout: Long = 1000) {
        wait(Until.findObject(By.desc("COMPOSE-IDLE")), timeout)
    }

    private companion object {
        const val PACKAGE = "dev.serhiiyaremych.imla"

        // Mirrors app-module BlurBenchmarkParams keys (no source dependency across modules).
        const val EXTRA_ENABLED = "imla.blurbench.enabled"
        const val EXTRA_SIGMA = "imla.blurbench.sigmaPx"
        const val EXTRA_SLOT_W = "imla.blurbench.slotWidthDp"
        const val EXTRA_SLOT_H = "imla.blurbench.slotHeightDp"
        const val EXTRA_SLOT_COUNT = "imla.blurbench.slotCount"
        const val EXTRA_TINT = "imla.blurbench.tint"
        const val EXTRA_NOISE = "imla.blurbench.noise"
        const val EXTRA_CLIP = "imla.blurbench.clip"
        const val EXTRA_PROGRESSIVE = "imla.blurbench.progressiveMask"

        const val SMALL_W = 110f
        const val SMALL_H = 68f
        const val MEDIUM_W = 170f
        const val MEDIUM_H = 110f
        const val LARGE_W = 520f
        const val LARGE_H = 360f
    }
}
