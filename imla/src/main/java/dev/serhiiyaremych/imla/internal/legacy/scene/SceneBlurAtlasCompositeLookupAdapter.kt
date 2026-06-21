/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin

/**
 * Immutable, frame-local lookup for a future atlas-backed backdrop composite path.
 *
 * Entries are adapted from [SceneBlurAtlasBlurFrameOutput] after atlas blur has produced borrowed
 * blurred atlas storage. The lookup owns only value metadata. It does not own or release atlas FBOs,
 * renderer/session state, resource stores, render objects, callbacks, or texture lifecycles.
 * Callers must keep the original blur output alive until lookup consumers finish, then release that
 * output through [SceneBlurAtlasBlurPass.release].
 */
internal data class SceneBlurAtlasCompositeLookupFrameOutput(
    val generation: Long,
    val rootSize: IntSize,
    val entries: List<SceneBlurAtlasCompositeLookupEntry>
)

/**
 * Immutable per-slot atlas lookup entry for future backdrop composite work.
 *
 * [compatibilityKey] identifies the blur/effect-compatible batch that produced the entry.
 * [blurredAtlasTexture] is a borrowed GL texture handle snapshot, not a lifecycle owner. Source
 * rectangles preserve the pre-blur copy/sample mapping, while blurred atlas crops are in the
 * blurred texture's allocated pixel space for later UV conversion. [blurredAtlasContentUv] is the
 * output viewport occupied by the logical atlas inside the allocated texture. [copyScale] and
 * [downsampleScale] preserve the scale metadata used by the copy and blur stages.
 */
internal data class SceneBlurAtlasCompositeLookupEntry(
    val slotId: BlurSlotId,
    val drawIndex: Int,
    val compatibilityKey: SceneBlurAtlasCompatibilityKey,
    val blurredAtlasTexture: SceneBlurAtlasTextureHandle,
    val sourceCopyRect: SceneBlurAtlasPixelRect,
    val sourceSampleRect: SceneBlurAtlasPixelRect,
    val sourceSampleCrop: SceneBlurAtlasPixelRect,
    val sourceAtlasContentCrop: SceneBlurAtlasPixelRect,
    val sourceAtlasRect: SceneBlurAtlasPixelRect,
    val sourceAtlasSampleCrop: SceneBlurAtlasPixelRect,
    val blurredAtlasContentCrop: SceneBlurAtlasPixelRect,
    val blurredAtlasContentUv: SceneBlurAtlasUvRect,
    val blurredAtlasSampleCrop: SceneBlurAtlasPixelRect,
    val copyScale: SceneBlurAtlasCopyScale,
    val downsampleScale: Float
)

/**
 * Immutable borrowed GL texture handle metadata.
 *
 * This is enough for composite code to bind the blurred atlas texture without taking over
 * destruction or pool release responsibilities. The handle's [coordinateOrigin] describes how the
 * lookup crops should be sampled by composite shaders. It comes from
 * [SceneBlurAtlasCoordinateOriginHandoff.logicalLookupOrigin], so atlas lookup crops remain
 * top-left logical pixels even when the producing framebuffer was allocated with a bottom-left GL
 * attachment. The atlas resource lifetime remains owned by the blur pass output and its
 * [SceneBlurAtlasBlurPass.release] boundary.
 */
internal data class SceneBlurAtlasTextureHandle(
    val textureId: Int,
    val size: IntSize,
    val coordinateOrigin: CoordinateOrigin
)

/**
 * Adapts isolated atlas blur output into draw-ordered composite lookup entries.
 *
 * Duplicate slot ids are represented deliberately as separate entries, because the composite
 * boundary is ordered by committed draw entries rather than by slot-id uniqueness. Entries are
 * sorted by [SceneBlurAtlasCompositeLookupEntry.drawIndex]; equal draw indexes keep the blur output
 * encounter order as a deterministic tie-breaker. The adapter is stateless and never draws,
 * releases, caches, or mutates atlas resources.
 */
internal object SceneBlurAtlasCompositeLookupAdapter {
    fun adapt(output: SceneBlurAtlasBlurFrameOutput): SceneBlurAtlasCompositeLookupFrameOutput {
        val orderedEntries = output.batches
            .flatMapIndexed(::entriesForBatch)
            .sortedWith(
                compareBy<OrderedLookupEntry>(
                    { orderedEntry -> orderedEntry.entry.drawIndex },
                    { orderedEntry -> orderedEntry.batchIndex },
                    { orderedEntry -> orderedEntry.placementIndex }
                )
            )
            .map { orderedEntry -> orderedEntry.entry }

        return SceneBlurAtlasCompositeLookupFrameOutput(
            generation = output.generation,
            rootSize = output.rootSize,
            entries = orderedEntries
        )
    }

    private fun entriesForBatch(
        batchIndex: Int,
        batch: SceneBlurAtlasBlurBatchOutput
    ): List<OrderedLookupEntry> {
        val textureHandle = batch.blurredAtlasTextureHandle()
        return batch.placements.mapIndexed { placementIndex, placement ->
            OrderedLookupEntry(
                batchIndex = batchIndex,
                placementIndex = placementIndex,
                entry = batch.entryFor(placement, textureHandle)
            )
        }
    }

    private fun SceneBlurAtlasBlurBatchOutput.entryFor(
        placement: SceneBlurAtlasBlurredPlacement,
        textureHandle: SceneBlurAtlasTextureHandle
    ): SceneBlurAtlasCompositeLookupEntry {
        return SceneBlurAtlasCompositeLookupEntry(
            slotId = placement.slotId,
            drawIndex = placement.drawIndex,
            compatibilityKey = key,
            blurredAtlasTexture = textureHandle,
            sourceCopyRect = placement.sourceCopyRect,
            sourceSampleRect = placement.sourceSampleRect,
            sourceSampleCrop = placement.sourceSampleCrop,
            sourceAtlasContentCrop = sourceAtlasContentCrop,
            sourceAtlasRect = placement.sourceAtlasRect,
            sourceAtlasSampleCrop = placement.sourceAtlasSampleCrop,
            blurredAtlasContentCrop = blurredAtlasContentCrop,
            blurredAtlasContentUv = blurredAtlasContentUv,
            blurredAtlasSampleCrop = placement.blurredAtlasSampleCrop,
            copyScale = placement.copyScale,
            downsampleScale = placement.downsampleScale
        )
    }

    private fun SceneBlurAtlasBlurBatchOutput.blurredAtlasTextureHandle(): SceneBlurAtlasTextureHandle {
        val texture = blurredAtlasTexture
        return SceneBlurAtlasTextureHandle(
            textureId = texture.id,
            size = IntSize(width = texture.width, height = texture.height),
            coordinateOrigin = coordinateOriginHandoff.logicalLookupOrigin
        )
    }

    private data class OrderedLookupEntry(
        val batchIndex: Int,
        val placementIndex: Int,
        val entry: SceneBlurAtlasCompositeLookupEntry
    )
}
