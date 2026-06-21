package dev.serhiiyaremych.imla

import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.imla.internal.render.stats.ResourceLifetimeSnapshot
import dev.serhiiyaremych.imla.internal.render.LocalSceneRenderer
import dev.serhiiyaremych.imla.internal.render.SceneRenderer
import dev.serhiiyaremych.imla.internal.metrics.SceneMetricsSnapshot
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderPassMetric
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderPassSnapshot
import dev.serhiiyaremych.imla.internal.layer.registry.LocalSceneRegistry
import dev.serhiiyaremych.imla.internal.layer.registry.SceneRegistry
import dev.serhiiyaremych.imla.internal.render.rememberSceneRenderer
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
public fun ImlaHost(
    modifier: Modifier = Modifier,
    showMetricsOverlay: Boolean = true,
    content: @Composable () -> Unit
) {
    val renderer = rememberSceneRenderer()
    val registry = remember { SceneRegistry() }
    val view = LocalView.current

    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }

    LaunchedEffect(renderer, view) {
        renderer.setFrameBudgetNanos(
            view.currentRefreshRate().frameBudgetNanos() ?: DEFAULT_FRAME_BUDGET_NANOS
        )
    }

    CompositionLocalProvider(
        LocalSceneRenderer provides renderer,
        LocalSceneRegistry provides registry
    ) {
        Box(modifier = modifier) {
            // API 29+ presents through a SurfaceView so the renderer can hand HardwareBuffers
            // straight to SurfaceFlinger (zero-copy). Older APIs blit into an external surface.
            if (Build.VERSION.SDK_INT >= 29) {
                SurfaceViewOutput(renderer = renderer, modifier = Modifier.matchParentSize())
            } else {
                ExternalSurfaceOutput(renderer = renderer, modifier = Modifier.matchParentSize())
            }

            Box(modifier = Modifier.matchParentSize()) {
                content()
            }

            if (showMetricsOverlay) {
                SceneMetricsOverlay(
                    renderer = renderer,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .statusBarsPadding()
                )
            }
        }
    }
}

/** API 29+ zero-copy output: a SurfaceView the renderer presents into via SurfaceControl. */
@RequiresApi(29)
@Composable
private fun SurfaceViewOutput(renderer: SceneRenderer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also { sv ->
                sv.holder.addCallback(SceneSurfaceViewCallback(sv, renderer))
            }
        },
        modifier = modifier
    )
}

/** Pre-API-29 output: the renderer blits the scene into a Compose external surface. */
@Composable
private fun ExternalSurfaceOutput(renderer: SceneRenderer, modifier: Modifier = Modifier) {
    AndroidExternalSurface(modifier = modifier) {
        onSurface { surface, width, height ->
            if (width > 0 && height > 0) {
                renderer.setOutputSurface(surface, IntSize(width, height))
            }
            surface.onChanged { newWidth, newHeight ->
                if (newWidth > 0 && newHeight > 0) {
                    renderer.resizeOutputSurface(IntSize(newWidth, newHeight))
                }
            }
            surface.onDestroyed { renderer.clearOutputSurface(surface) }
        }
    }
}

@RequiresApi(29)
private class SceneSurfaceViewCallback(
    private val surfaceView: SurfaceView,
    private val renderer: SceneRenderer
) : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            renderer.setOutputSurfaceView(surfaceView, IntSize(width, height))
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer.clearOutputSurface()
    }
}

@Composable
private fun SceneMetricsOverlay(
    renderer: SceneRenderer,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var expanded by remember { mutableStateOf(false) }
    var overlayState by remember(renderer, view) {
        mutableStateOf(
            SceneMetricsOverlayState(
                snapshot = renderer.metricsSnapshot(),
                refreshRate = view.currentRefreshRate()
            )
        )
    }

    LaunchedEffect(renderer, view) {
        while (isActive) {
            overlayState = SceneMetricsOverlayState(
                snapshot = renderer.metricsSnapshot(),
                refreshRate = view.currentRefreshRate()
            )
            delay(METRICS_OVERLAY_REFRESH_MS)
        }
    }

    BasicText(
        text = overlayState.formatOverlayText(expanded),
        modifier = modifier
            .clickable { expanded = !expanded }
            .background(overlayState.backgroundColor())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        style = TextStyle(
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp
        )
    )
}

private data class SceneMetricsOverlayState(
    val snapshot: SceneMetricsSnapshot,
    val refreshRate: Float?
) {
    val frameBudgetMs: Float?
        get() = refreshRate
            ?.takeIf { it > 0f }
            ?.let { MILLIS_PER_SECOND / it }
}

private enum class SceneMetricsOverlayStatus {
    Healthy,
    Tight,
    Dropping
}

private fun SceneMetricsOverlayState.formatOverlayText(expanded: Boolean): String {
    return if (expanded) {
        formatExpandedOverlayText()
    } else {
        formatCollapsedOverlayText()
    }
}

private fun SceneMetricsOverlayState.formatCollapsedOverlayText(): String {
    val pressureSkips = if (snapshot.sceneWorkCoalescedFrames > 0L) {
        "  Skip ${snapshot.sceneWorkCoalescedFrames}"
    } else {
        ""
    }
    return String.format(
        Locale.US,
        "Scene %.1ffps  CPU %.1fms  GL %.1fms  Lat %.1fms%s",
        snapshot.fps,
        snapshot.mainSubmitMs,
        snapshot.glFrameMs,
        snapshot.drawLatencyMs,
        pressureSkips
    )
}

private fun SceneMetricsOverlayState.formatExpandedOverlayText(): String {
    val mainRoomMs = frameBudgetMs?.minus(snapshot.mainSubmitMs)
    val glRoomMs = frameBudgetMs?.minus(snapshot.glFrameMs)
    val rootPass = snapshot.renderPass(SceneRenderPassMetric.RootPresent)
    val noisePass = snapshot.renderPass(SceneRenderPassMetric.NoiseGenerate)
    val preparePass = snapshot.renderPass(SceneRenderPassMetric.BackdropPrepare)
    val blurPass = snapshot.renderPass(SceneRenderPassMetric.BackdropBlur)
    val compositePass = snapshot.renderPass(SceneRenderPassMetric.BackdropComposite)
    val stencilPass = snapshot.renderPass(SceneRenderPassMetric.StencilClip)
    val contentPass = snapshot.renderPass(SceneRenderPassMetric.SlotContent)
    val finalPresentPass = snapshot.renderPass(SceneRenderPassMetric.FinalPresent)
    val featureStats = snapshot.featureStats
    val resourceStats = snapshot.resourceStats
    return String.format(
        Locale.US,
        "Scene FPS %.1f  Frame budget %s  %s\n\n" +
                "CPU / COMPOSE\n" +
                "Frame submit        %.2f ms   room %s\n" +
                "Root capture        %.2f ms\n" +
                "Root after render   %.2f ms   latest %.2f ms\n" +
                "Content captures    %.2f ms   slots %d/%d\n" +
                "  slot scan         %.2f ms\n" +
                "  renderer lookup   %.2f ms\n" +
                "  layer capture     %.2f ms\n" +
                "  frame store       %.2f ms\n" +
                "Mask captures       %.2f ms\n" +
                "Clip captures       %.2f ms\n" +
                "UI data delta       %.2f ms draw  latest %.2f ms\n" +
                "                    %.2f ms build latest %.2f ms\n" +
                "Capture -> submit   %.2f ms\n\n" +
                "-----------------------------\n" +
                "GL THREAD / RENDER PIPELINE\n" +
                "Root texture import     %.2f ms\n" +
                "Content texture imports %.2f ms\n" +
                "Mask texture imports    %.2f ms\n" +
                "Clip texture imports    %.2f ms\n\n" +
                "Scene features\n" +
                "Slots %d   backdrops %d   cumulative %d\n" +
                "Clips backdrop/content %d/%d   tint %d   noise %d\n\n" +
                "Scene passes\n" +
                "Root draw               %.2f ms\n" +
                "Noise generate          %.2f ms   calls %d   %s pixels\n" +
                "Backdrop prefilter      %.2f ms   calls %d   %s pixels   %s texture samples\n" +
                "Backdrop blur stage     %.2f ms   calls %d   %s pixels   %s texture samples\n" +
                "Backdrop composite      %.2f ms   calls %d   %s pixels   %s texture samples\n" +
                "Stencil clip            %.2f ms   calls %d   %s pixels   %s texture samples\n" +
                "Slot content            %.2f ms   calls %d   %s pixels   %s texture samples\n" +
                "Final present           %.2f ms\n\n" +
                "GL thread frame        %.2f ms   room %s\n" +
                "Scene render total      %.2f ms\n\n" +
                "GL resources\n" +
                "FBO       %s\n" +
                "Texture   %s\n" +
                "HB tex    %s\n" +
                "  root    %s\n" +
                "  root import recreate/reuse %d/%d\n" +
                "  content %s\n" +
                "  mask    %s\n" +
                "  clip    %s\n" +
                "  other   %s\n" +
                "  imported %s px / %s bytes\n" +
                "Shader    %s\n" +
                "Shader binds/uploads %d/%d\n\n" +
                "END-TO-END\n" +
                "Input -> rendered       %.2f ms\n" +
                "Submit -> GL start      %.2f ms\n" +
                "Frames                  %d rendered / %d submitted\n" +
                "Drops                   %d\n" +
                "Pacing                  capture %d   render %d   root %d\n" +
                "Pressure skips          dirty ticks %d\n" +
                "Capture failures        %d",
        snapshot.fps,
        frameBudgetMs.formatMetricMs(),
        snapshot.targetSize.formatResolution(),
        snapshot.mainSubmitMs,
        mainRoomMs.formatMetricMs(),
        snapshot.mainRootCaptureMs,
        snapshot.rootCaptureAfterRenderCompleteMs,
        snapshot.latestRootCaptureAfterRenderCompleteMs,
        snapshot.mainSlotCaptureMs,
        snapshot.latestSlotCaptureCaptured,
        snapshot.latestSlotCaptureAttempted,
        snapshot.mainSlotDiscoveryMs,
        snapshot.mainSlotRendererMs,
        snapshot.mainSlotLayerCaptureMs,
        snapshot.mainSlotBookkeepingMs,
        snapshot.mainProgressiveMaskCaptureMs,
        snapshot.mainClipShapeCaptureMs,
        snapshot.uiDataPreDrawAgeMs,
        snapshot.latestUiDataPreDrawAgeMs,
        snapshot.uiDataFrameBuildAgeMs,
        snapshot.latestUiDataFrameBuildAgeMs,
        snapshot.mainPostCaptureWaitMs,
        snapshot.glRootImportMs,
        snapshot.glSlotImportMs,
        snapshot.glMaskImportMs,
        snapshot.glClipImportMs,
        featureStats.slots,
        featureStats.backdropSlots,
        featureStats.cumulativeBackdropSlots,
        featureStats.backdropClipSlots,
        featureStats.contentClipSlots,
        featureStats.tintSlots,
        featureStats.noiseSlots,
        rootPass.issueMs,
        noisePass.issueMs,
        noisePass.latestCalls,
        noisePass.latestOutputPixels.formatMetricCount(),
        preparePass.issueMs,
        preparePass.latestCalls,
        preparePass.latestOutputPixels.formatMetricCount(),
        preparePass.latestTextureSamples.formatMetricCount(),
        blurPass.issueMs,
        blurPass.latestCalls,
        blurPass.latestOutputPixels.formatMetricCount(),
        blurPass.latestTextureSamples.formatMetricCount(),
        compositePass.issueMs,
        compositePass.latestCalls,
        compositePass.latestOutputPixels.formatMetricCount(),
        compositePass.latestTextureSamples.formatMetricCount(),
        stencilPass.issueMs,
        stencilPass.latestCalls,
        stencilPass.latestOutputPixels.formatMetricCount(),
        stencilPass.latestTextureSamples.formatMetricCount(),
        contentPass.issueMs,
        contentPass.latestCalls,
        contentPass.latestOutputPixels.formatMetricCount(),
        contentPass.latestTextureSamples.formatMetricCount(),
        finalPresentPass.issueMs,
        snapshot.glFrameMs,
        glRoomMs.formatMetricMs(),
        snapshot.glRenderMs,
        resourceStats.fbo.formatResourceLifetime(),
        resourceStats.texture.formatResourceLifetime(),
        resourceStats.hardwareBufferTexture.formatResourceLifetime(),
        resourceStats.hardwareBufferRootTexture.formatResourceLifetime(),
        resourceStats.hardwareBufferRootTextureRecreates,
        resourceStats.hardwareBufferRootTextureReuses,
        resourceStats.hardwareBufferContentTexture.formatResourceLifetime(),
        resourceStats.hardwareBufferMaskTexture.formatResourceLifetime(),
        resourceStats.hardwareBufferClipTexture.formatResourceLifetime(),
        resourceStats.hardwareBufferOtherTexture.formatResourceLifetime(),
        resourceStats.hardwareBufferImportedPixels.formatMetricCount(),
        resourceStats.hardwareBufferImportedBytes.formatMetricCount(),
        resourceStats.shader.formatResourceLifetime(),
        resourceStats.shaderBinds,
        resourceStats.shaderUploads,
        snapshot.drawLatencyMs,
        snapshot.glQueueDelayMs,
        snapshot.renderedFrames,
        snapshot.submittedFrames,
        snapshot.droppedFrames,
        snapshot.capturePacedFrames,
        snapshot.renderRequestPacedFrames,
        snapshot.rootCaptureDeferredFrames,
        snapshot.sceneWorkCoalescedFrames,
        snapshot.captureFailures
    )
}

private fun SceneMetricsSnapshot.renderPass(pass: SceneRenderPassMetric): SceneRenderPassSnapshot {
    return renderPasses.firstOrNull { it.pass == pass } ?: SceneRenderPassSnapshot(pass = pass)
}

private fun SceneMetricsOverlayState.backgroundColor(): Color {
    return when (status()) {
        SceneMetricsOverlayStatus.Healthy -> Color(0xAA123A25)
        SceneMetricsOverlayStatus.Tight -> Color(0xAA4A3A12)
        SceneMetricsOverlayStatus.Dropping -> Color(0xAA4A1616)
    }
}

private fun SceneMetricsOverlayState.status(): SceneMetricsOverlayStatus {
    val snapshot = snapshot
    if (snapshot.droppedFrames > 0L || snapshot.captureFailures > 0L) {
        return SceneMetricsOverlayStatus.Dropping
    }

    val targetRefreshRate = refreshRate ?: return SceneMetricsOverlayStatus.Healthy
    val budgetMs = frameBudgetMs ?: return SceneMetricsOverlayStatus.Healthy
    val mainRoomMs = budgetMs - snapshot.mainSubmitMs
    val glRoomMs = budgetMs - snapshot.glFrameMs
    val minRoomMs = minOf(mainRoomMs, glRoomMs)

    return when {
        minRoomMs < RED_ROOM_THRESHOLD_MS -> SceneMetricsOverlayStatus.Dropping
        minRoomMs < YELLOW_ROOM_THRESHOLD_MS -> SceneMetricsOverlayStatus.Tight
        else -> SceneMetricsOverlayStatus.Healthy
    }
}

private fun Float?.formatMetricMs(): String {
    return if (this == null) {
        "--"
    } else {
        String.format(Locale.US, "%.2f ms", this)
    }
}

private fun IntSize.formatResolution(): String {
    return if (this == IntSize.Zero) {
        "0x0"
    } else {
        "${width}x${height}"
    }
}

private fun Long.formatMetricCount(): String {
    return when {
        this >= 1_000_000L -> String.format(Locale.US, "%.1fM", this / 1_000_000f)
        this >= 1_000L -> String.format(Locale.US, "%.0fk", this / 1_000f)
        else -> toString()
    }
}

private fun ResourceLifetimeSnapshot.formatResourceLifetime(): String {
    return String.format(
        Locale.US,
        "active/peak %d/%d   created/destroyed %d/%d",
        active,
        peakActive,
        created,
        destroyed
    )
}

private fun View.currentRefreshRate(): Float? {
    val rate = display?.refreshRate ?: return null
    return rate.takeIf { it > 0f }
}

private fun Float?.frameBudgetNanos(): Long? {
    return this?.takeIf { it > 0f }?.let { rate ->
        (NANOS_PER_SECOND / rate).toLong()
    }
}

private const val METRICS_OVERLAY_REFRESH_MS = 200L
private const val MILLIS_PER_SECOND = 1_000f
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val DEFAULT_FRAME_BUDGET_NANOS = 16_666_667L
private const val RED_ROOM_THRESHOLD_MS = 1f
private const val YELLOW_ROOM_THRESHOLD_MS = 4f
