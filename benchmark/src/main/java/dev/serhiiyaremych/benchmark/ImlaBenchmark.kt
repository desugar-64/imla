/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.benchmark

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.benchmark.macro.ArtMetric
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.PowerCategory
import androidx.benchmark.macro.PowerCategoryDisplayLevel
import androidx.benchmark.macro.PowerMetric
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
 * IMLA Rendering Performance Benchmark
 * ====================================
 *
 * PURPOSE:
 * Measures worst-case performance of OpenGL ES rendering pipeline using TraceSectionMetric.Mode.Max.
 * Captures maximum execution times for critical rendering operations to identify performance bottlenecks.
 *
 * WHAT IT PROVIDES:
 * - 56 worst-case execution time metrics (ending in "MaxMs")
 * - Automatic Perfetto trace generation for detailed analysis
 * - Frame timing and GPU power consumption metrics
 *
 * HOW TO RUN:
 * ```bash
 * ./gradlew :benchmark:connectedBenchmarkAndroidTest
 *
 * # Detailed analysis from Perfetto trace:
 * source perfetto-env/bin/activate
 * python scripts/trace_analyzer.py --latest "*Effect*" "*Renderer*"
 * ```
 *
 * PREREQUISITES:
 * - Android device connected via ADB with app installed
 * - App manifest includes `<profileable android:shell="true" />`
 * - Device unlocked during testing
 *
 * INTERPRETING RESULTS:
 *
 * 📊 Benchmark Metrics:
 * - All values are **MAXIMUM execution times** in milliseconds (MaxMs suffix)
 * - Lower = better performance
 * - Focus on worst-case scenarios for optimization
 *
 * 🎯 Performance Targets (60fps = 16.67ms budget):
 * - Excellent: < 5ms
 * - Good: 5-10ms
 * - Acceptable: 10-16ms
 * - Needs Optimization: > 16ms
 *
 * 📈 Key Metrics:
 * - RenderingPipeline#renderAllMaxMs: Overall pipeline (⚠️ > 20ms = critical)
 * - GraphicsLayerRenderer*MaxMs: UI layer conversion (⚠️ > 5ms = bottleneck)
 * - GaussianBlurEffect*MaxMs: Blur performance (⚠️ > 10ms = slow)
 * - gl*MaxMs: OpenGL operations (⚠️ > 2ms = inefficient)
 *
 * 📋 Perfetto Trace Analysis:
 * For min/avg/max/percentile statistics beyond the max values:
 * ```bash
 * python scripts/trace_analyzer.py --latest "RenderingPipeline*" --store baseline_name
 * ```
 *
 * 📁 Output:
 * - Benchmark Report: benchmark/build/reports/androidTests/connected/benchmark/
 * - Metrics JSON: benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/
 * - Perfetto Trace: Same directory as JSON file
 * - Detailed Analysis: benchmark_results/ (when using --store)
 *
 * Why Mode.Max: Provides reliable worst-case measurements for optimization focus.
 */
@RunWith(AndroidJUnit4::class)
class ImlaBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    private fun traceSectionMaxMetrics(vararg sectionNames: String) =
        sectionNames.map { metricSection(it, TraceSectionMetric.Mode.Max) }

    /** Zero-copy HardwareBuffer + SurfaceControl present path (default). */
    @RequiresApi(Build.VERSION_CODES.Q)
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun measureRendering_hwPresent() = measureRendering(hwPresent = true)

    /** Blit control arm: same binary + SurfaceView, only the present mechanism differs. */
    @RequiresApi(Build.VERSION_CODES.Q)
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun measureRendering_blit() = measureRendering(hwPresent = false)

    @RequiresApi(Build.VERSION_CODES.Q)
    @OptIn(ExperimentalMetricApi::class)
    private fun measureRendering(hwPresent: Boolean) = benchmarkRule.measureRepeated(
        packageName = "dev.serhiiyaremych.imla",
        compilationMode = CompilationMode.Full(),
        // Basic control + the pacing probe. traceSectionMaxMetrics stay out until
        // the pacing baseline is confirmed; gfxinfo metric stays out (1.4.1 bug).
        metrics = listOf(
            FrameTimingMetric(),
            CapturePacingMetric(),
        ),
        iterations = 3,
        startupMode = StartupMode.WARM,
        setupBlock = {
            // Select the present path for this arm, then force a fresh process so the
            // renderer snapshots the new value (SceneRenderFlags reads it at attach).
            device.executeShellCommand(
                "setprop log.tag.ImlaBlitPresent " + if (hwPresent) "ASSERT" else "DEBUG"
            )
            killProcess()
            pressHome()
            startActivityAndWait()
        }
    ) {
        val uiDevice = this.device
        val width = uiDevice.displayWidth
        val height = uiDevice.displayHeight
        val cx = width / 2
        val top = (height * 0.25).toInt()
        val bottom = (height * 0.75).toInt()

        // Continuous-motion interaction for pacing measurement: settle once, then
        // run back-to-back controlled drags with NO awaitComposeIdle between them.
        // Every measured interval is real scroll cadence, not a settle pause; 40
        // steps keeps each drag a ~200ms constant-velocity motion (not a fling that
        // decelerates), so interval spread reflects pipeline jitter, not the gesture.
        uiDevice.awaitComposeIdle()
        repeat(8) {
            uiDevice.swipe(cx, bottom, cx, top, 40)
        }
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun metricSection(label: String, mode: TraceSectionMetric.Mode) = TraceSectionMetric(
        sectionName = label,
        mode = mode
    )

    @RequiresApi(Build.VERSION_CODES.Q)
    @OptIn(ExperimentalMetricApi::class)
    private fun baseMetrics() = listOf(
        PowerMetric(
            PowerMetric.Type.Power(
                mapOf(
                    PowerCategory.GPU to PowerCategoryDisplayLevel.TOTAL,
                    PowerCategory.CPU to PowerCategoryDisplayLevel.TOTAL,
                )
            )
        ),
        FrameTimingMetric(),
        FrameTimingGfxInfoMetric(),
    )

    private fun UiDevice.awaitComposeIdle(timeout: Long = 1000) {
        wait(Until.findObject(By.desc("COMPOSE-IDLE")), timeout)
    }

    private companion object {
        // All static trace sections in the project (dynamic traces with string interpolation are tracked via wildcard in scripts).
        // Full trace inventory (used by scripts and trace analyzer)
        private val TRACE_SECTIONS = listOf(
            "GaussianBlurEffect*",
            "GraphicsLayerRenderer#copyTextureToFramebuffer",
            "GraphicsLayerRenderer#renderGraphicsLayer",
            "GraphicsLayerRenderer#updateGraphicsLayer",
            "MaskTextureRenderer#renderMask",
            "QuadBatchRenderer#submit",
            "RenderableRootLayer#updateTex",
            "Renderer2D#beginScene",
            "Renderer2D#endScene",
            "RenderingPipeline#renderAll",
            "ShaderLibrary#loadShader", // Previously problematic - testing with Mode.First
            "SimpleQuadRenderer#draw",
            "SimpleRenderer#flush",

            "aaDownsampling",
            "bindDefaultFBO",
            "bindUniformBlock", // Previously problematic - testing with Mode.First
            "blendLayers", // Previously problematic - testing with Mode.First
            "blendToDefaultBuffer", // Previously problematic - testing with Mode.First
            "blitBackground", // Previously problematic - testing with Mode.First
            "blitFirstFBO", // Previously problematic - testing with Mode.First
            "blitForeground", // Previously problematic - testing with Mode.First
            "blitFramebuffers",
            "blitResult",
            "cutArea",
            "cutBackgroundRegion",
            "defaultQuadVertexMapper",
            "disableBlending",
            "downsample",
            "drawFullQuadStatic", // Previously problematic - testing with Mode.First
            "drawGraphicsLayer", // Previously problematic - testing with Mode.First
            "drawIndexed", // Previously problematic - testing with Mode.First
            "drawMask", // Previously problematic - testing with Mode.First
            "drawNoiseTextureOnce", // Previously problematic - testing with Mode.First
            "drawQuad",
            "enableBlending",
            "flush",
            "fullSizeBuffer",
            "generateMipMaps",
            "glBlitFramebuffer",
            "glClear",
            "glGenerateMipmap", // Previously problematic - testing with Mode.First
            "glUnBindFramebuffer", // Previously problematic - testing with Mode.First
            "glViewport", // Previously problematic - testing with Mode.First
            "init", // Previously problematic - testing with Mode.First
            "invalidateAttachments", // Previously problematic - testing with Mode.First
            "preProcess", // Previously problematic - testing with Mode.First
            "recordCanvas", // Previously problematic - testing with Mode.First
            "setClearColor", // Previously problematic - testing with Mode.First
            "setFloat", // Previously problematic - testing with Mode.First
            "setFloat2", // Previously problematic - testing with Mode.First
            "setFloat3", // Previously problematic - testing with Mode.First
            "setFloat4", // Previously problematic - testing with Mode.First
            "setFloatArray", // Previously problematic - testing with Mode.First
            "setInt", // Previously problematic - testing with Mode.First
            "setIntArray", // Previously problematic - testing with Mode.First
            "setMaskProp", // Previously problematic - testing with Mode.First
            "setMat3", // Previously problematic - testing with Mode.First
            "setMat4", // Previously problematic - testing with Mode.First
            "setup", // Previously problematic - testing with Mode.First
            "shaderCompile", // Previously problematic - testing with Mode.First
            "submitQuad", // Previously problematic - testing with Mode.First
            "surfaceTexture#updateTexImage", // Previously problematic - testing with Mode.First
            "textureBind",
            "uboBind", // Previously problematic - testing with Mode.First
            "uboSetData", // Previously problematic - testing with Mode.First
            "uploadDataIfNeed", // Previously problematic - testing with Mode.First
            "upsample", // Previously problematic - testing with Mode.First
            "useDefaultProgram", // Previously problematic - testing with Mode.First
            "vaoBind", // Previously problematic - testing with Mode.First
            "vboBind",
            "vboCreateDynamic", // Previously problematic - testing with Mode.First
            "vboCreateStatic", // Previously problematic - testing with Mode.First
            "vboSetData", // Previously problematic - testing with Mode.First
        )
    }
}
