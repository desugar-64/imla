/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.IntSize

/**
 * Immutable, frame-local atlas copy/preprocess skeleton for one placement plan.
 *
 * Future atlas work can consume this data by copying each request's source rect into its logical
 * atlas rect, then using the output crop and scale metadata for batched blur and composite lookup.
 * This structure deliberately does not allocate atlas storage, run copy/preprocess work, own
 * rendering resources, or replace the current per-slot path.
 */
internal data class SceneBlurAtlasPreprocessFrameResult(
    val generation: Long,
    val rootSize: IntSize,
    val batches: List<SceneBlurAtlasBatchPreprocessResult>
)

/**
 * Immutable copy/preprocess skeleton for one compatible atlas batch.
 *
 * The [key] and [atlasSize] come from placement planning unchanged. [placements] keeps the same
 * order as the placement plan so later batched work can preserve committed draw order explicitly.
 */
internal data class SceneBlurAtlasBatchPreprocessResult(
    val key: SceneBlurAtlasCompatibilityKey,
    val atlasSize: IntSize,
    val placements: List<SceneBlurAtlasPreprocessPlacement>
)

/**
 * Immutable per-placement copy request derived only from atlas placement metadata.
 *
 * [sourceCopyRect] is the padded scene-space copy region. [sourceSampleRect] is the unpadded sample
 * region in the same scene-space coordinate system. [atlasRect] is the padded destination in the
 * logical atlas, and [atlasSampleCrop] is the unpadded sample crop in atlas coordinates. The
 * nullable blur-radius mask handle is borrowed metadata for fallback diagnostics.
 */
internal data class SceneBlurAtlasPreprocessRequest(
    val slotId: BlurSlotId,
    val drawIndex: Int,
    val blurRadiusMask: SceneBlurAtlasMaskTextureMetadata?,
    val sourceCopyRect: SceneBlurAtlasPixelRect,
    val sourceSampleRect: SceneBlurAtlasPixelRect,
    val atlasRect: SceneBlurAtlasPixelRect,
    val atlasSampleCrop: SceneBlurAtlasPixelRect
)

/**
 * Immutable per-placement copy/preprocess result metadata.
 *
 * This result mirrors the crop boundary that current preprocess output exposes without carrying any
 * live resources. [sourceSampleCrop] is relative to [sourceCopyRect], [atlasSampleCrop] is relative
 * to the batch atlas, [copyScale] describes the current source-to-atlas pixel scale, and
 * [downsampleScale] records the effective scale future blur math should account for. Blur-radius
 * mask metadata remains a borrowed handle only.
 */
internal data class SceneBlurAtlasPreprocessOutput(
    val blurRadiusMask: SceneBlurAtlasMaskTextureMetadata?,
    val atlasRect: SceneBlurAtlasPixelRect,
    val atlasSampleCrop: SceneBlurAtlasPixelRect,
    val sourceCopyRect: SceneBlurAtlasPixelRect,
    val sourceSampleCrop: SceneBlurAtlasPixelRect,
    val copyScale: SceneBlurAtlasCopyScale,
    val downsampleScale: Float
)

/**
 * Immutable paired request and derived output for one placement.
 */
internal data class SceneBlurAtlasPreprocessPlacement(
    val request: SceneBlurAtlasPreprocessRequest,
    val output: SceneBlurAtlasPreprocessOutput
)

/**
 * Immutable source-to-atlas scale metadata for a logical copy/preprocess operation.
 */
internal data class SceneBlurAtlasCopyScale(
    val x: Float,
    val y: Float
)

/**
 * Derives atlas copy/preprocess skeleton data from immutable placement planning data.
 *
 * The planner is stateless and page-local by construction: each call consumes one placement plan and
 * returns value data only. It does not execute, cache, allocate, sample, blur, composite, or mutate
 * renderer state.
 */
internal object SceneBlurAtlasPreprocessPlanner {
    fun plan(placementPlan: SceneBlurAtlasPlacementFramePlan): SceneBlurAtlasPreprocessFrameResult {
        return SceneBlurAtlasPreprocessFrameResult(
            generation = placementPlan.generation,
            rootSize = placementPlan.rootSize,
            batches = placementPlan.batches.map(::planBatch)
        )
    }

    private fun planBatch(batch: SceneBlurAtlasBatchPlacementPlan): SceneBlurAtlasBatchPreprocessResult {
        return SceneBlurAtlasBatchPreprocessResult(
            key = batch.key,
            atlasSize = batch.atlasSize,
            placements = batch.placements.map(::planPlacement)
        )
    }

    private fun planPlacement(placement: SceneBlurAtlasRequestPlacement): SceneBlurAtlasPreprocessPlacement {
        val request = SceneBlurAtlasPreprocessRequest(
            slotId = placement.slotId,
            drawIndex = placement.drawIndex,
            blurRadiusMask = placement.blurRadiusMask,
            sourceCopyRect = placement.paddedSourceRect,
            sourceSampleRect = placement.sourceSampleRect,
            atlasRect = placement.atlasRect,
            atlasSampleCrop = placement.atlasSampleCrop
        )
        val copyScale = copyScaleFor(placement)
        val output = SceneBlurAtlasPreprocessOutput(
            blurRadiusMask = placement.blurRadiusMask,
            atlasRect = placement.atlasRect,
            atlasSampleCrop = placement.atlasSampleCrop,
            sourceCopyRect = placement.paddedSourceRect,
            sourceSampleCrop = sourceSampleCropFor(placement),
            copyScale = copyScale,
            downsampleScale = minOf(copyScale.x, copyScale.y)
        )
        return SceneBlurAtlasPreprocessPlacement(request = request, output = output)
    }

    private fun sourceSampleCropFor(
        placement: SceneBlurAtlasRequestPlacement
    ): SceneBlurAtlasPixelRect {
        return SceneBlurAtlasPixelRect(
            left = placement.sourceSampleRect.left - placement.paddedSourceRect.left,
            top = placement.sourceSampleRect.top - placement.paddedSourceRect.top,
            right = placement.sourceSampleRect.right - placement.paddedSourceRect.left,
            bottom = placement.sourceSampleRect.bottom - placement.paddedSourceRect.top
        )
    }

    private fun copyScaleFor(placement: SceneBlurAtlasRequestPlacement): SceneBlurAtlasCopyScale {
        return SceneBlurAtlasCopyScale(
            x = placement.atlasRect.width.toFloat() / placement.paddedSourceRect.width.coerceAtLeast(1),
            y = placement.atlasRect.height.toFloat() / placement.paddedSourceRect.height.coerceAtLeast(1)
        )
    }
}
