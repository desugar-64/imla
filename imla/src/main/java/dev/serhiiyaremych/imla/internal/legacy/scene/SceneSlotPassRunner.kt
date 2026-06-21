/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.processing.effects.GaussianBlurEffect
import dev.serhiiyaremych.imla.internal.render.processing.effects.PreProcessEffect
import kotlin.math.ceil
import kotlin.math.floor

internal enum class SceneStencilClipSetupResult {
    Applied,
    FallbackUnclipped
}

internal fun interface SceneSlotBackdropRenderer {
    fun execute(
        scene: Framebuffer,
        slot: SceneSlotPlan,
        transform: Mat4,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        beforeSceneComposite: () -> Unit
    )
}

internal interface SceneContentCompositeExecutor {
    fun execute(slot: SceneSlotPlan, transform: Mat4, targetSize: IntSize)
}

internal interface SceneStencilClipExecutor {
    fun execute(
        clipTexture: Texture2D,
        transform: Mat4,
        targetSize: IntSize
    ): SceneStencilClipSetupResult

    fun disable()
}

/**
 * Runs the ordered per-slot scene passes for one immutable [SceneRenderFrame].
 *
 * [SceneGlRenderer] calls this after it has seeded the scene framebuffer and prepared optional
 * frame resources. The runner assumes it executes on the GL thread with the scene target valid,
 * owns only slot pass ordering, atlas branch selection, one-time enabled-frame atlas preflight, and
 * context-local command access. It deliberately does not own scene commits, captured layer
 * resources, or long-lived GPU resources.
 */
internal class SceneSlotPassRunner(
    private val commandsProvider: () -> RenderCommands,
    private val perSlotBackdropRenderer: SceneSlotBackdropRenderer,
    private val contentCompositePass: SceneContentCompositeExecutor,
    private val stencilClipPass: SceneStencilClipExecutor,
    private val atlasRenderConfig: SceneBlurAtlasRenderConfig = SceneBlurAtlasRenderConfig.Disabled,
    private val atlasPreflightPlanner: SceneBlurAtlasPipelinePreflightPlanner =
        SceneBlurAtlasPipelinePreflightPlanner.Default,
    private val atlasPipelineProvider: () -> SceneBlurAtlasPipeline? = { null },
    private val atlasBackdropCompositePass: SceneBlurAtlasBackdropCompositeExecutor? = null
) {
    fun execute(
        scene: Framebuffer,
        frame: SceneRenderFrame,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        useAtlasCopyImage: Boolean = false
    ) = trace("SceneSlotPassRunner#execute") {
        executeFrame(
            scene = scene,
            frame = frame,
            targetSize = targetSize,
            noiseTexture = noiseTexture,
            useAtlasCopyImage = useAtlasCopyImage
        )
    }

    internal fun executeFrame(
        scene: Framebuffer,
        frame: SceneRenderFrame,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        useAtlasCopyImage: Boolean = false
    ) {
        val atlasFrameState = executeAtlasPipeline(
            scene = scene,
            frame = frame,
            useAtlasCopyImage = useAtlasCopyImage
        )
        try {
            val atlasLookups = atlasFrameState?.output?.lookupOutput?.entries.orEmpty()
                .associateBy { lookup -> AtlasSlotKey(lookup.slotId, lookup.drawIndex) }

            frame.slots.forEach { slot ->
                SceneTraceCounters.slotCompositePass()
                val transform = slot.transform
                val clipTexture = slot.clipTexture
                var stencilActive = false
                var stencilSetupAttempted = false
                val beginSceneComposite = {
                    scene.bind(commandsProvider(), Bind.DRAW, updateViewport = true)
                    if (clipTexture != null && !stencilSetupAttempted) {
                        stencilSetupAttempted = true
                        val stencilResult = stencilClipPass.execute(clipTexture, transform, targetSize)
                        stencilActive = stencilResult == SceneStencilClipSetupResult.Applied
                    }
                }
                try {
                    compositeBackdrop(
                        scene = scene,
                        slot = slot,
                        transform = transform,
                        targetSize = targetSize,
                        noiseTexture = noiseTexture,
                        atlasLookups = atlasLookups,
                        atlasEnabled = atlasFrameState != null,
                        beforeSceneComposite = beginSceneComposite
                    )
                    beginSceneComposite()
                    contentCompositePass.execute(slot, transform, targetSize)
                } finally {
                    if (stencilActive) {
                        stencilClipPass.disable()
                    }
                }
            }
        } finally {
            atlasFrameState?.release()
        }
    }

    private fun executeAtlasPipeline(
        scene: Framebuffer,
        frame: SceneRenderFrame,
        useAtlasCopyImage: Boolean
    ): AtlasFrameState? {
        if (!atlasRenderConfig.enabled) return null

        val preflight = atlasPreflightPlanner.plan(frame)
        SceneTraceCounters.atlasPreflightComputed(preflight)
        if (!preflight.hasEligiblePlacements) {
            return AtlasFrameState(pipeline = null, output = null)
        }

        val pipeline = atlasPipelineProvider()
            ?: return AtlasFrameState(pipeline = null, output = null)
        SceneTraceCounters.atlasPipelineExecuted(preflight)
        val output = try {
            pipeline.execute(
                preflight = preflight,
                source = SceneBlurAtlasCopySource(
                    sourceFramebuffer = scene,
                    useCopyImage = useAtlasCopyImage
                )
            )
        } catch (failure: Throwable) {
            if (preflight.hasBlurRadiusMaskPlacements) {
                return AtlasFrameState(pipeline = null, output = null)
            }
            throw failure
        }
        SceneBlurAtlasGeometryDiagnostics.atlasPipeline(
            frame = frame,
            preflight = preflight,
            output = output
        )
        return AtlasFrameState(pipeline = pipeline, output = output)
    }

    private fun compositeBackdrop(
        scene: Framebuffer,
        slot: SceneSlotPlan,
        transform: Mat4,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        atlasLookups: Map<AtlasSlotKey, SceneBlurAtlasCompositeLookupEntry>,
        atlasEnabled: Boolean,
        beforeSceneComposite: () -> Unit
    ) {
        val lookup = atlasLookups[AtlasSlotKey(slot.id, slot.drawIndex)]
        val atlasCompositePass = atlasBackdropCompositePass
        if (lookup == null || atlasCompositePass == null || needsMissingNoiseFallback(slot, noiseTexture)) {
            if (atlasEnabled) {
                SceneTraceCounters.atlasFallbackSlot()
            }
            perSlotBackdropRenderer.execute(
                scene = scene,
                slot = slot,
                transform = transform,
                targetSize = targetSize,
                noiseTexture = noiseTexture,
                beforeSceneComposite = beforeSceneComposite
            )
            return
        }

        SceneTraceCounters.atlasCompositeSlot()
        SceneTraceCounters.backdropCompositePass()
        beforeSceneComposite()
        SceneBlurAtlasGeometryDiagnostics.atlasComposite(slot, lookup)
        atlasCompositePass.execute(
            lookup = lookup,
            slot = slot,
            transform = transform,
            style = slot.style,
            targetSize = targetSize,
            noiseTexture = noiseTexture,
            maskTexture = slot.compositeCoverageMask
        )
    }

    private fun needsMissingNoiseFallback(
        slot: SceneSlotPlan,
        noiseTexture: Texture2D?
    ): Boolean {
        return slot.style.noiseAlpha >= MIN_ATLAS_NOISE_ALPHA && noiseTexture == null
    }

    private data class AtlasSlotKey(
        val slotId: BlurSlotId,
        val drawIndex: Int
    )

    private data class AtlasFrameState(
        val pipeline: SceneBlurAtlasPipeline?,
        val output: SceneBlurAtlasPipelineOutput?
    ) {
        fun release() {
            if (pipeline != null && output != null) {
                pipeline.release(output)
            }
        }
    }

    private companion object {
        const val MIN_ATLAS_NOISE_ALPHA = 0.05f
    }
}

private val SceneBlurAtlasPipelinePreflight.hasBlurRadiusMaskPlacements: Boolean
    get() = preprocessResult.batches.any { batch ->
        batch.placements.any { placement -> placement.output.blurRadiusMask != null }
    }

internal class ScenePerSlotBackdropRenderer(
    private val commandsProvider: () -> RenderCommands,
    private val backdropPreprocessPass: SceneBackdropPreprocessPass,
    private val blurPass: SceneBlurPass,
    private val backdropCompositePass: SceneBackdropCompositePass,
    private val effectFrameReleaser: EffectFrameReleaser
) : SceneSlotBackdropRenderer {
    override fun execute(
        scene: Framebuffer,
        slot: SceneSlotPlan,
        transform: Mat4,
        targetSize: IntSize,
        noiseTexture: Texture2D?,
        beforeSceneComposite: () -> Unit
    ) = trace("ScenePerSlotBackdropRenderer#execute") {
        val sampleArea = snapAreaToTarget(slot.area, targetSize) ?: return@trace
        SceneTraceCounters.backdropCompositePass()
        val style = slot.style
        val blurRadiusMask = slot.blurRadiusMask
        val compositeCoverageMask = slot.compositeCoverageMask
        prepareOffscreenEffectState()
        val preOutput = backdropPreprocessPass.execute(
            rootFbo = scene,
            sampleArea = sampleArea,
            contentSize = slot.contentSize,
            sigma = style.sigma
        )
        SceneBlurAtlasGeometryDiagnostics.perSlotPreprocess(
            slot = slot,
            targetSize = targetSize,
            sampleArea = sampleArea,
            output = preOutput
        )
        prepareOffscreenEffectState()
        val blurOutput = blurPass.execute(preOutput, style.sigma, blurRadiusMask)
        try {
            beforeSceneComposite()
            backdropCompositePass.execute(
                slot = slot,
                transform = transform,
                sampleArea = sampleArea,
                style = style,
                output = blurOutput,
                targetSize = targetSize,
                noiseTexture = noiseTexture,
                compositeCoverageMask = compositeCoverageMask
            )
        } finally {
            effectFrameReleaser.release(preOutput, blurOutput)
        }
    }

    private fun prepareOffscreenEffectState() {
        val commands = commandsProvider()
        commands.disableStencilTest()
        commands.disableBlending()
    }

    private fun snapAreaToTarget(area: Rect, targetSize: IntSize): Rect? {
        val left = floor(area.left).coerceAtLeast(0f)
        val top = floor(area.top).coerceAtLeast(0f)
        val right = ceil(area.right).coerceAtMost(targetSize.width.toFloat())
        val bottom = ceil(area.bottom).coerceAtMost(targetSize.height.toFloat())
        if (right - left <= 1f || bottom - top <= 1f) return null
        return Rect(left = left, top = top, right = right, bottom = bottom)
    }

    fun interface EffectFrameReleaser {
        fun release(
            preOutput: PreProcessEffect.PreProcessOutput,
            blurOutput: GaussianBlurEffect.Output
        )
    }
}

internal class SceneEffectFrameReleaser(
    private val fboPool: () -> FramebufferLendingPool
) : ScenePerSlotBackdropRenderer.EffectFrameReleaser {
    override fun release(
        preOutput: PreProcessEffect.PreProcessOutput,
        blurOutput: GaussianBlurEffect.Output
    ) {
        val pool = fboPool()
        pool.release(preOutput.sourceFbo.fbo)
        pool.release(blurOutput.fbo.fbo)
    }
}
