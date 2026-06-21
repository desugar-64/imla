/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.processing.effects.EffectPipeline
import dev.serhiiyaremych.imla.internal.render.processing.effects.SizedFramebuffer
import dev.serhiiyaremych.imla.internal.render.processing.effects.withSize

/**
 * Immutable output metadata for one isolated atlas blur execution.
 *
 * The source copy output remains owned by its caller and should be released through
 * [SceneBlurAtlasCopyPass.release]. Blurred atlas FBOs are borrowed from [SceneBlurAtlasBlurPass]
 * through its pool and remain valid until callers release this output with
 * [SceneBlurAtlasBlurPass.release].
 */
internal data class SceneBlurAtlasBlurFrameOutput(
    val generation: Long,
    val rootSize: IntSize,
    val batches: List<SceneBlurAtlasBlurBatchOutput>
)

/**
 * Immutable borrowed blur output for one copied atlas compatibility batch.
 *
 * Crop rectangles use top-left logical atlas pixels unless prefixed with `blurred`; blurred crops
 * include any content offset introduced by bucketed output FBO allocation. [atlasSize] is the
 * logical atlas size, [blurredAtlasAllocatedSize] is the borrowed output texture size, and
 * [blurredAtlasContentCrop]/[blurredAtlasContentUv] describe where logical atlas pixels live inside
 * that output. [coordinateOriginHandoff] names the storage-to-composite origin contract. The batch
 * carries handles only; it does not own source atlas storage, renderer/session state, or composite
 * execution.
 */
internal data class SceneBlurAtlasBlurBatchOutput(
    val key: SceneBlurAtlasCompatibilityKey,
    val settings: SceneBlurAtlasBlurSettings,
    val atlasSize: IntSize,
    val sourceAtlasTexture: Texture2D,
    val sourceAtlasContentCrop: SceneBlurAtlasPixelRect,
    val blurredAtlasFramebuffer: Framebuffer,
    val blurredAtlasTexture: Texture2D,
    val blurredAtlasAllocatedSize: IntSize,
    val blurredAtlasContentCrop: SceneBlurAtlasPixelRect,
    val blurredAtlasContentUv: SceneBlurAtlasUvRect,
    val coordinateOriginHandoff: SceneBlurAtlasCoordinateOriginHandoff,
    val placements: List<SceneBlurAtlasBlurredPlacement>
)

/**
 * Immutable coordinate-origin handoff between atlas blur storage and composite lookup metadata.
 *
 * Atlas blur storage follows the borrowed output texture/FBO origin. Composite lookup intentionally
 * exposes a top-left logical origin because atlas crop metadata is recorded in top-left logical
 * pixels and the atlas composite path converts those crops directly to lookup UVs.
 */
internal data class SceneBlurAtlasCoordinateOriginHandoff(
    val storageOrigin: CoordinateOrigin,
    val logicalLookupOrigin: CoordinateOrigin
) {
    companion object {
        val TOP_LEFT_LOGICAL_LOOKUP_ORIGIN: CoordinateOrigin = CoordinateOrigin.TOP_LEFT

        fun fromStorage(storageOrigin: CoordinateOrigin): SceneBlurAtlasCoordinateOriginHandoff {
            return SceneBlurAtlasCoordinateOriginHandoff(
                storageOrigin = storageOrigin,
                logicalLookupOrigin = TOP_LEFT_LOGICAL_LOOKUP_ORIGIN
            )
        }
    }
}

/**
 * Immutable UV bounds in an allocated atlas texture.
 *
 * Values are normalized texture coordinates derived from allocated texture pixels. They own no
 * texture, FBO, or release responsibility and are valid only while the blur output that produced
 * them remains alive on the GL thread.
 */
internal data class SceneBlurAtlasUvRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Immutable per-slot lookup metadata preserved across atlas blur.
 *
 * Source rectangles mirror [SceneBlurAtlasCopiedPlacement]. [blurredAtlasSampleCrop] maps the same
 * logical atlas crop into the borrowed blurred FBO, including the output content offset needed for
 * later UV conversion. No crop transform is stored because atlas blur preserves logical atlas scale
 * and only translates into the output content viewport. The nullable blur-radius mask handle is
 * borrowed metadata for a later masked blur mode and is not released by this pass.
 */
internal data class SceneBlurAtlasBlurredPlacement(
    val slotId: BlurSlotId,
    val drawIndex: Int,
    val blurRadiusMask: SceneBlurAtlasMaskTextureMetadata?,
    val sourceCopyRect: SceneBlurAtlasPixelRect,
    val sourceSampleRect: SceneBlurAtlasPixelRect,
    val sourceSampleCrop: SceneBlurAtlasPixelRect,
    val sourceAtlasRect: SceneBlurAtlasPixelRect,
    val sourceAtlasSampleCrop: SceneBlurAtlasPixelRect,
    val blurredAtlasSampleCrop: SceneBlurAtlasPixelRect,
    val copyScale: SceneBlurAtlasCopyScale,
    val downsampleScale: Float
)

/**
 * Immutable blur settings derived from the copied atlas batch identity.
 *
 * [sigmaTexels] is the active kernel blur input passed to the gaussian atlas effect.
 * [downsampleScale] comes from copied placement metadata; current atlas copies preserve a common
 * scale across a compatibility batch.
 */
internal data class SceneBlurAtlasBlurSettings(
    val sigma: Float,
    val sigmaTexels: Float,
    val downsampleScale: Float
)

internal fun interface SceneBlurAtlasBatchBlurrer {
    fun blur(
        input: SizedFramebuffer,
        sampleCrop: Rect,
        settings: SceneBlurAtlasBlurSettings
    ): SizedFramebuffer
}

private fun SceneBlurAtlasPixelRect.toRect(): Rect {
    return Rect(
        left = left.toFloat(),
        top = top.toFloat(),
        right = right.toFloat(),
        bottom = bottom.toFloat()
    )
}

private fun defaultAtlasBatchBlurrer(
    effectPipeline: () -> EffectPipeline
): SceneBlurAtlasBatchBlurrer {
    return SceneBlurAtlasBatchBlurrer { input, sampleCrop, settings ->
        effectPipeline().gaussianBlurAtlas(
            input = input,
            sampleCrop = sampleCrop,
            sigmaTexels = settings.sigmaTexels,
            maskTexture = null,
            releaseInput = false
        )
    }
}

/**
 * Blurs copied atlas batches into borrowed output FBOs without wiring them into live rendering.
 *
 * The pass owns only GL-thread blur execution and release of its borrowed blur outputs through the
 * supplied [FramebufferLendingPool]. Callers own the input [SceneBlurAtlasCopyFrameOutput] and must
 * release it separately through the copy pass. [execute] expects copied atlas crops in top-left
 * logical atlas pixels. Atlas blur may allocate a larger output texture, but it draws into the
 * logical atlas content viewport and reports the viewport pixels/UVs needed for aspect-correct
 * lookup. The pass deliberately does not own renderer instances, sessions, coordinators, resource
 * repositories, Compose layers, render objects, texture lifecycles, or composite scheduling.
 */
internal class SceneBlurAtlasBlurPass(
    private val blurOutputPool: FramebufferLendingPool,
    private val batchBlurrer: SceneBlurAtlasBatchBlurrer
) {
    constructor(
        blurOutputPool: FramebufferLendingPool,
        effectPipeline: () -> EffectPipeline
    ) : this(
        blurOutputPool = blurOutputPool,
        batchBlurrer = defaultAtlasBatchBlurrer(effectPipeline)
    )

    fun execute(copyOutput: SceneBlurAtlasCopyFrameOutput): SceneBlurAtlasBlurFrameOutput {
        val batches = ArrayList<SceneBlurAtlasBlurBatchOutput>(copyOutput.batches.size)
        try {
            copyOutput.batches.mapNotNullTo(batches, ::blurBatch)
        } catch (failure: Throwable) {
            releaseAfterFailure(
                output = SceneBlurAtlasBlurFrameOutput(
                    generation = copyOutput.generation,
                    rootSize = copyOutput.rootSize,
                    batches = batches.toList()
                ),
                failure = failure
            )
        }

        return SceneBlurAtlasBlurFrameOutput(
            generation = copyOutput.generation,
            rootSize = copyOutput.rootSize,
            batches = batches
        )
    }

    fun release(output: SceneBlurAtlasBlurFrameOutput) {
        output.batches.forEach { batch ->
            blurOutputPool.release(batch.blurredAtlasFramebuffer)
        }
    }

    private fun blurBatch(batch: SceneBlurAtlasCopyBatchOutput): SceneBlurAtlasBlurBatchOutput? {
        if (batch.placements.isEmpty() || batch.atlasSize.isEmpty) return null

        val settings = settingsFor(batch)
        val sourceAtlas = batch.atlasFramebuffer.withSize(batch.atlasSize)
        val sourceAtlasContentCrop = batch.atlasSize.toPixelRect()
        val blurredAtlas = blurAtlasBatch(batch, sourceAtlas, sourceAtlasContentCrop, settings)
        val blurredContentOffset = blurredAtlas.contentOffset
        val blurredAtlasAllocatedSize = blurredAtlas.allocatedSize
        val blurredAtlasContentCrop = batch.atlasSize.toPixelRect(blurredContentOffset)

        return SceneBlurAtlasBlurBatchOutput(
            key = batch.key,
            settings = settings,
            atlasSize = batch.atlasSize,
            sourceAtlasTexture = batch.atlasTexture,
            sourceAtlasContentCrop = sourceAtlasContentCrop,
            blurredAtlasFramebuffer = blurredAtlas.fbo,
            blurredAtlasTexture = blurredAtlas.texture,
            blurredAtlasAllocatedSize = blurredAtlasAllocatedSize,
            blurredAtlasContentCrop = blurredAtlasContentCrop,
            blurredAtlasContentUv = blurredAtlasContentCrop.toUvRect(blurredAtlasAllocatedSize),
            coordinateOriginHandoff = SceneBlurAtlasCoordinateOriginHandoff.fromStorage(
                storageOrigin = blurredAtlas.texture.coordinateOrigin
            ),
            placements = batch.placements.map { placement ->
                placement.toBlurredPlacement(blurredContentOffset)
            }
        )
    }

    private fun blurAtlasBatch(
        batch: SceneBlurAtlasCopyBatchOutput,
        sourceAtlas: SizedFramebuffer,
        sourceAtlasContentCrop: SceneBlurAtlasPixelRect,
        settings: SceneBlurAtlasBlurSettings
    ): SizedFramebuffer {
        require(batch.placements.none { placement -> placement.blurRadiusMask != null }) {
            "Blur-radius masked placements must fall back before atlas blur execution"
        }
        return batchBlurrer.blur(
            input = sourceAtlas,
            sampleCrop = sourceAtlasContentCrop.toRect(),
            settings = settings
        )
    }

    private fun settingsFor(batch: SceneBlurAtlasCopyBatchOutput): SceneBlurAtlasBlurSettings {
        val downsampleScale = batch.placements.firstOrNull()?.downsampleScale ?: 1f

        return SceneBlurAtlasBlurSettings(
            sigma = batch.key.sigma,
            sigmaTexels = batch.key.sigma * downsampleScale,
            downsampleScale = downsampleScale
        )
    }

    private fun SceneBlurAtlasCopiedPlacement.toBlurredPlacement(
        blurredContentOffset: IntOffset
    ): SceneBlurAtlasBlurredPlacement {
        return SceneBlurAtlasBlurredPlacement(
            slotId = slotId,
            drawIndex = drawIndex,
            blurRadiusMask = blurRadiusMask,
            sourceCopyRect = sourceCopyRect,
            sourceSampleRect = sourceSampleRect,
            sourceSampleCrop = sourceSampleCrop,
            sourceAtlasRect = atlasRect,
            sourceAtlasSampleCrop = atlasSampleCrop,
            blurredAtlasSampleCrop = atlasSampleCrop.translated(blurredContentOffset),
            copyScale = copyScale,
            downsampleScale = downsampleScale
        )
    }

    private fun SceneBlurAtlasPixelRect.translated(offset: IntOffset): SceneBlurAtlasPixelRect {
        return SceneBlurAtlasPixelRect(
            left = left + offset.x,
            top = top + offset.y,
            right = right + offset.x,
            bottom = bottom + offset.y
        )
    }

    private fun SceneBlurAtlasPixelRect.toRect(): Rect {
        return Rect(
            left = left.toFloat(),
            top = top.toFloat(),
            right = right.toFloat(),
            bottom = bottom.toFloat()
        )
    }

    private fun IntSize.toPixelRect(offset: IntOffset = IntOffset.Zero): SceneBlurAtlasPixelRect {
        return SceneBlurAtlasPixelRect(
            left = offset.x,
            top = offset.y,
            right = offset.x + width,
            bottom = offset.y + height
        )
    }

    private fun SceneBlurAtlasPixelRect.toUvRect(size: IntSize): SceneBlurAtlasUvRect {
        val safeWidth = size.width.coerceAtLeast(1).toFloat()
        val safeHeight = size.height.coerceAtLeast(1).toFloat()
        return SceneBlurAtlasUvRect(
            left = left / safeWidth,
            top = top / safeHeight,
            right = right / safeWidth,
            bottom = bottom / safeHeight
        )
    }

    private val IntSize.isEmpty: Boolean
        get() = width <= 0 || height <= 0

    private fun releaseAfterFailure(
        output: SceneBlurAtlasBlurFrameOutput,
        failure: Throwable
    ): Nothing {
        try {
            release(output)
        } catch (releaseFailure: Throwable) {
            failure.addSuppressed(releaseFailure)
        }
        throw failure
    }
}
