/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.IntSize

/**
 * Immutable atlas frame preflight for one enabled scene frame.
 *
 * The slot runner computes this once after the internal atlas flag is enabled and before it asks
 * for the atlas pipeline. It preserves eligibility, fallback, skipped, placement, and preprocess
 * diagnostics as value data only. It owns no renderer, session, resource store, render object,
 * framebuffer, callback, or texture lifecycle.
 */
internal data class SceneBlurAtlasPipelinePreflight(
    val atlasPlan: SceneBlurAtlasFramePlan,
    val placementPlan: SceneBlurAtlasPlacementFramePlan,
    val preprocessResult: SceneBlurAtlasPreprocessFrameResult
) {
    val generation: Long
        get() = atlasPlan.generation

    val rootSize: IntSize
        get() = atlasPlan.rootSize

    val hasEligiblePlacements: Boolean
        get() = preprocessResult.batches.any { batch -> batch.placements.isNotEmpty() }
}

internal fun interface SceneBlurAtlasPipelinePreflightPlanner {
    fun plan(frame: SceneRenderFrame): SceneBlurAtlasPipelinePreflight

    companion object {
        fun plan(frame: SceneRenderFrame): SceneBlurAtlasPipelinePreflight {
            val atlasPlan = SceneBlurAtlasPlanner.plan(frame = frame)
            val placementPlan = SceneBlurAtlasPlacementPlanner.plan(atlasPlan)
            val preprocessResult = SceneBlurAtlasPreprocessPlanner.plan(placementPlan)
            return SceneBlurAtlasPipelinePreflight(
                atlasPlan = atlasPlan,
                placementPlan = placementPlan,
                preprocessResult = preprocessResult
            )
        }

        val Default: SceneBlurAtlasPipelinePreflightPlanner = SceneBlurAtlasPipelinePreflightPlanner { frame ->
            plan(frame = frame)
        }
    }
}

/**
 * Immutable result from the isolated scene blur atlas pipeline.
 *
 * The output carries the one-time frame preflight, borrowed copy/blur outputs, and the lookup
 * produced from blurred atlas storage. It owns no renderer, session, coordinator, repository,
 * render object, callback, Compose layer, or texture lifecycle. Borrowed atlas storage remains
 * owned by the copy and blur passes and must be returned through [SceneBlurAtlasPipeline.release].
 */
internal data class SceneBlurAtlasPipelineOutput(
    val preflight: SceneBlurAtlasPipelinePreflight,
    val copyOutput: SceneBlurAtlasCopyFrameOutput?,
    val blurOutput: SceneBlurAtlasBlurFrameOutput?,
    val lookupOutput: SceneBlurAtlasCompositeLookupFrameOutput
) {
    val generation: Long
        get() = preflight.generation

    val rootSize: IntSize
        get() = preflight.rootSize

    val atlasPlan: SceneBlurAtlasFramePlan
        get() = preflight.atlasPlan

    val placementPlan: SceneBlurAtlasPlacementFramePlan
        get() = preflight.placementPlan

    val preprocessResult: SceneBlurAtlasPreprocessFrameResult
        get() = preflight.preprocessResult

    val isEmpty: Boolean
        get() = lookupOutput.entries.isEmpty()
}

internal interface SceneBlurAtlasPipelineCopyStage {
    fun execute(
        source: SceneBlurAtlasCopySource,
        preprocessResult: SceneBlurAtlasPreprocessFrameResult
    ): SceneBlurAtlasCopyFrameOutput

    fun release(output: SceneBlurAtlasCopyFrameOutput)
}

internal interface SceneBlurAtlasPipelineBlurStage {
    fun execute(copyOutput: SceneBlurAtlasCopyFrameOutput): SceneBlurAtlasBlurFrameOutput

    fun release(output: SceneBlurAtlasBlurFrameOutput)
}

internal fun interface SceneBlurAtlasLookupStage {
    fun adapt(output: SceneBlurAtlasBlurFrameOutput): SceneBlurAtlasCompositeLookupFrameOutput
}

internal interface SceneBlurAtlasPipeline {
    fun execute(
        preflight: SceneBlurAtlasPipelinePreflight,
        source: SceneBlurAtlasCopySource
    ): SceneBlurAtlasPipelineOutput

    fun release(output: SceneBlurAtlasPipelineOutput)
}

/**
 * Orchestrates the isolated atlas stages without enabling atlas rendering.
 *
 * The runner consumes one immutable [SceneBlurAtlasPipelinePreflight] plus the explicit source
 * framebuffer/copy capability needed by the existing atlas copy pass. Planning happens once in the
 * slot runner before this runner is requested; fallback diagnostics stay in [preflight] and are
 * carried into the output. This runner owns only atlas copy, blur, lookup adaptation, and borrowed
 * output release. Callers still own the scene source framebuffer, frame/resource lifetimes,
 * renderer/session scheduling, and any composite decision. The runner assumes copy and blur
 * execution happens on the GL thread for the supplied resources.
 *
 * If preflight has no eligible placements, [execute] returns an empty output without copy or blur
 * work. If a later stage fails, the runner releases every complete borrowed output it already
 * received in reverse order before rethrowing. The copy and blur stages also clean up their own
 * partially completed outputs. Live rendering remains default-off because [SceneSlotPassRunner]
 * computes preflight only behind [SceneBlurAtlasRenderConfig.enabled].
 */
internal class SceneBlurAtlasPipelineRunner(
    private val copyStage: SceneBlurAtlasPipelineCopyStage,
    private val blurStage: SceneBlurAtlasPipelineBlurStage,
    private val lookupStage: SceneBlurAtlasLookupStage =
        SceneBlurAtlasLookupStage { output -> SceneBlurAtlasCompositeLookupAdapter.adapt(output) }
) : SceneBlurAtlasPipeline {
    constructor(
        copyPass: SceneBlurAtlasCopyPass,
        blurPass: SceneBlurAtlasBlurPass
    ) : this(
        copyStage = SceneBlurAtlasCopyPassStage(copyPass),
        blurStage = SceneBlurAtlasBlurPassStage(blurPass)
    )

    override fun execute(
        preflight: SceneBlurAtlasPipelinePreflight,
        source: SceneBlurAtlasCopySource
    ): SceneBlurAtlasPipelineOutput {
        if (!preflight.hasEligiblePlacements) {
            return emptyOutput(preflight)
        }

        var copyOutput: SceneBlurAtlasCopyFrameOutput? = null
        var blurOutput: SceneBlurAtlasBlurFrameOutput? = null
        try {
            copyOutput = copyStage.execute(source, preflight.preprocessResult)
            SceneTraceCounters.atlasCopyOutputProduced(copyOutput)
            blurOutput = blurStage.execute(copyOutput)
            SceneTraceCounters.atlasBlurOutputProduced(blurOutput)
            val lookupOutput = lookupStage.adapt(blurOutput)
            SceneTraceCounters.atlasLookupOutputProduced(lookupOutput)
            return SceneBlurAtlasPipelineOutput(
                preflight = preflight,
                copyOutput = copyOutput,
                blurOutput = blurOutput,
                lookupOutput = lookupOutput
            )
        } catch (failure: Throwable) {
            releaseAfterFailure(blurOutput, copyOutput, failure)
        }
    }

    override fun release(output: SceneBlurAtlasPipelineOutput) {
        output.blurOutput?.let(blurStage::release)
        output.copyOutput?.let(copyStage::release)
    }

    private fun emptyOutput(preflight: SceneBlurAtlasPipelinePreflight): SceneBlurAtlasPipelineOutput {
        return SceneBlurAtlasPipelineOutput(
            preflight = preflight,
            copyOutput = null,
            blurOutput = null,
            lookupOutput = SceneBlurAtlasCompositeLookupFrameOutput(
                generation = preflight.generation,
                rootSize = preflight.rootSize,
                entries = emptyList()
            )
        )
    }

    private fun releaseAfterFailure(
        blurOutput: SceneBlurAtlasBlurFrameOutput?,
        copyOutput: SceneBlurAtlasCopyFrameOutput?,
        failure: Throwable
    ): Nothing {
        try {
            blurOutput?.let(blurStage::release)
        } catch (releaseFailure: Throwable) {
            failure.addSuppressed(releaseFailure)
        }
        try {
            copyOutput?.let(copyStage::release)
        } catch (releaseFailure: Throwable) {
            failure.addSuppressed(releaseFailure)
        }
        throw failure
    }
}

private class SceneBlurAtlasCopyPassStage(
    private val copyPass: SceneBlurAtlasCopyPass
) : SceneBlurAtlasPipelineCopyStage {
    override fun execute(
        source: SceneBlurAtlasCopySource,
        preprocessResult: SceneBlurAtlasPreprocessFrameResult
    ): SceneBlurAtlasCopyFrameOutput {
        return copyPass.execute(source, preprocessResult)
    }

    override fun release(output: SceneBlurAtlasCopyFrameOutput) {
        copyPass.release(output)
    }
}

private class SceneBlurAtlasBlurPassStage(
    private val blurPass: SceneBlurAtlasBlurPass
) : SceneBlurAtlasPipelineBlurStage {
    override fun execute(copyOutput: SceneBlurAtlasCopyFrameOutput): SceneBlurAtlasBlurFrameOutput {
        return blurPass.execute(copyOutput)
    }

    override fun release(output: SceneBlurAtlasBlurFrameOutput) {
        blurPass.release(output)
    }
}
