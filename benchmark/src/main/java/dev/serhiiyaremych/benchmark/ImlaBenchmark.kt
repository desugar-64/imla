/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.FrameTimingMetric
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
 * This is an example startup benchmark.
 *
 * It navigates to the device's home screen, and launches the default activity.
 *
 * Before running this benchmark:
 * 1) switch your app's active build variant in the Studio (affects Studio runs only)
 * 2) add `<profileable android:shell="true" />` to your app's manifest, within the `<application>` tag
 *
 * Run this benchmark from Studio to see startup measurements, and captured system traces
 * for investigating your app's performance.
 */
@RunWith(AndroidJUnit4::class)
class ImlaBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun measureRendering() = benchmarkRule.measureRepeated(
        packageName = "dev.serhiiyaremych.imla",
        compilationMode = CompilationMode.Full(),
        metrics = listOf(
            FrameTimingMetric(),
            FrameTimingGfxInfoMetric(),
            metricSection("RenderObject#onRender", TraceSectionMetric.Mode.Average),
            metricSection("copyExtTextureToFrameBuffer", TraceSectionMetric.Mode.Average),
            metricSection("fullSizeBuffer", TraceSectionMetric.Mode.Average),
//            metricSection("scaledSizeBuffer", TraceSectionMetric.Mode.Average),
        ),
        iterations = 1,
        startupMode = StartupMode.HOT
    ) {
        pressHome()
        startActivityAndWait()

        val uiDevice = this.device
        val width = uiDevice.displayWidth
        val height = uiDevice.displayHeight

        repeat(3) {
            uiDevice.awaitComposeIdle()
            uiDevice.swipe(
                width / 2,
                (height * 0.75).toInt(),
                width / 2,
                (height * 0.25).toInt(),
                8
            )
        }
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun metricSection(label: String, mode: TraceSectionMetric.Mode) = TraceSectionMetric(
        sectionName = label,
        mode = mode
    )

    private fun UiDevice.awaitComposeIdle(timeout: Long = 3000) {
        wait(Until.findObject(By.desc("COMPOSE-IDLE")), timeout)
    }
}