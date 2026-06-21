/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat

/**
 * Explicit source input for an isolated atlas copy pass.
 *
 * [sourceFramebuffer] is the already prepared root/scene source for this frame. [useCopyImage] is
 * supplied by the owning GL context capability decision; the pass does not query global state.
 */
internal data class SceneBlurAtlasCopySource(
    val sourceFramebuffer: Framebuffer,
    val useCopyImage: Boolean
)

/**
 * Immutable output metadata for one atlas copy pass execution.
 *
 * Atlas FBOs and textures are borrowed from [SceneBlurAtlasCopyPass] through its pool and remain
 * valid only until callers release this output with [SceneBlurAtlasCopyPass.release].
 */
internal data class SceneBlurAtlasCopyFrameOutput(
    val generation: Long,
    val rootSize: IntSize,
    val batches: List<SceneBlurAtlasCopyBatchOutput>
)

/**
 * Immutable borrowed atlas storage and ordered placement metadata for one compatibility batch.
 */
internal data class SceneBlurAtlasCopyBatchOutput(
    val key: SceneBlurAtlasCompatibilityKey,
    val atlasSize: IntSize,
    val atlasFramebuffer: Framebuffer,
    val atlasTexture: Texture2D,
    val placements: List<SceneBlurAtlasCopiedPlacement>
)

/**
 * Immutable metadata for one copied atlas placement.
 *
 * Rectangles keep the planner contract: source rects and atlas crops are top-left scene/atlas pixel
 * coordinates. GL-origin conversion is local to the pass copy command and is not stored here. The
 * nullable blur-radius mask handle is borrowed metadata and is never released by this pass.
 */
internal data class SceneBlurAtlasCopiedPlacement(
    val slotId: BlurSlotId,
    val drawIndex: Int,
    val blurRadiusMask: SceneBlurAtlasMaskTextureMetadata?,
    val sourceCopyRect: SceneBlurAtlasPixelRect,
    val sourceSampleRect: SceneBlurAtlasPixelRect,
    val sourceSampleCrop: SceneBlurAtlasPixelRect,
    val atlasRect: SceneBlurAtlasPixelRect,
    val atlasSampleCrop: SceneBlurAtlasPixelRect,
    val copyScale: SceneBlurAtlasCopyScale,
    val downsampleScale: Float
)

internal fun interface SceneBlurAtlasFramebufferCopy {
    fun copy(
        src: Framebuffer,
        dst: Framebuffer,
        srcX0: Int,
        srcY0: Int,
        srcX1: Int,
        srcY1: Int,
        dstX0: Int,
        dstY0: Int,
        dstX1: Int,
        dstY1: Int,
        useCopyImage: Boolean
    )
}

private fun defaultFramebufferCopy(commands: RenderCommands): SceneBlurAtlasFramebufferCopy {
    return SceneBlurAtlasFramebufferCopy { src,
        dst,
        srcX0,
        srcY0,
        srcX1,
        srcY1,
        dstX0,
        dstY0,
        dstX1,
        dstY1,
        useCopyImage ->
        commands.copyOrBlitFramebuffer(
            src = src,
            dst = dst,
            srcX0 = srcX0,
            srcY0 = srcY0,
            srcX1 = srcX1,
            srcY1 = srcY1,
            dstX0 = dstX0,
            dstY0 = dstY0,
            dstX1 = dstX1,
            dstY1 = dstY1,
            useCopyImage = useCopyImage
        )
    }
}

/**
 * Copies planned atlas source regions into borrowed atlas FBOs.
 *
 * The pass owns only GL-thread copy execution and temporary atlas FBO acquisition through the
 * supplied pool. Callers must consume or hand off the borrowed atlas handles, then call [release];
 * repeated release calls are harmless because the lending pool ignores FBOs it no longer owns.
 *
 * The pass deliberately does not plan frames, keep shared atlas state, blur atlas content, composite
 * atlas output, own captured scene resources, or route anything into live rendering.
 */
internal class SceneBlurAtlasCopyPass(
    commands: RenderCommands,
    private val fboPool: FramebufferLendingPool,
    private val framebufferCopy: SceneBlurAtlasFramebufferCopy = defaultFramebufferCopy(commands)
) {
    fun execute(
        source: SceneBlurAtlasCopySource,
        preprocessResult: SceneBlurAtlasPreprocessFrameResult
    ): SceneBlurAtlasCopyFrameOutput {
        val batches = ArrayList<SceneBlurAtlasCopyBatchOutput>(preprocessResult.batches.size)
        try {
            preprocessResult.batches.mapNotNullTo(batches) { batch ->
                copyBatch(source, batch)
            }
        } catch (failure: Throwable) {
            releaseAfterFailure(
                output = SceneBlurAtlasCopyFrameOutput(
                    generation = preprocessResult.generation,
                    rootSize = preprocessResult.rootSize,
                    batches = batches.toList()
                ),
                failure = failure
            )
        }

        return SceneBlurAtlasCopyFrameOutput(
            generation = preprocessResult.generation,
            rootSize = preprocessResult.rootSize,
            batches = batches
        )
    }

    fun release(output: SceneBlurAtlasCopyFrameOutput) {
        output.batches.forEach { batch ->
            fboPool.release(batch.atlasFramebuffer)
        }
    }

    private fun copyBatch(
        source: SceneBlurAtlasCopySource,
        batch: SceneBlurAtlasBatchPreprocessResult
    ): SceneBlurAtlasCopyBatchOutput? {
        if (batch.placements.isEmpty() || batch.atlasSize.isEmpty) return null

        val atlasFramebuffer = fboPool.acquire(atlasSpec(batch.atlasSize))
        val placements = try {
            batch.placements.map { placement ->
                copyPlacement(
                    source = source,
                    atlasFramebuffer = atlasFramebuffer,
                    atlasSize = batch.atlasSize,
                    placement = placement
                )
            }
        } catch (failure: Throwable) {
            fboPool.release(atlasFramebuffer)
            throw failure
        }

        return SceneBlurAtlasCopyBatchOutput(
            key = batch.key,
            atlasSize = batch.atlasSize,
            atlasFramebuffer = atlasFramebuffer,
            atlasTexture = atlasFramebuffer.colorAttachmentTexture,
            placements = placements
        )
    }

    private fun copyPlacement(
        source: SceneBlurAtlasCopySource,
        atlasFramebuffer: Framebuffer,
        atlasSize: IntSize,
        placement: SceneBlurAtlasPreprocessPlacement
    ): SceneBlurAtlasCopiedPlacement {
        val sourceRect = placement.request.sourceCopyRect.toFramebufferRect(
            size = source.sourceFramebuffer.specification.size,
            origin = source.sourceFramebuffer.colorAttachmentTexture.coordinateOrigin
        )
        val atlasRect = placement.request.atlasRect.toFramebufferRect(
            size = atlasSize,
            origin = ATLAS_ORIGIN
        )
        framebufferCopy.copy(
            src = source.sourceFramebuffer,
            dst = atlasFramebuffer,
            srcX0 = sourceRect.x0,
            srcY0 = sourceRect.y0,
            srcX1 = sourceRect.x1,
            srcY1 = sourceRect.y1,
            dstX0 = atlasRect.x0,
            dstY0 = atlasRect.y0,
            dstX1 = atlasRect.x1,
            dstY1 = atlasRect.y1,
            useCopyImage = source.useCopyImage
        )

        return SceneBlurAtlasCopiedPlacement(
            slotId = placement.request.slotId,
            drawIndex = placement.request.drawIndex,
            blurRadiusMask = placement.output.blurRadiusMask,
            sourceCopyRect = placement.output.sourceCopyRect,
            sourceSampleRect = placement.request.sourceSampleRect,
            sourceSampleCrop = placement.output.sourceSampleCrop,
            atlasRect = placement.output.atlasRect,
            atlasSampleCrop = placement.output.atlasSampleCrop,
            copyScale = placement.output.copyScale,
            downsampleScale = placement.output.downsampleScale
        )
    }

    private fun atlasSpec(size: IntSize): FramebufferSpecification {
        return FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                format = FramebufferTextureFormat.RGBA8,
                coordinateOrigin = ATLAS_ORIGIN
            )
        )
    }

    private fun SceneBlurAtlasPixelRect.toFramebufferRect(
        size: IntSize,
        origin: CoordinateOrigin
    ): FramebufferCopyRect {
        return when (origin) {
            CoordinateOrigin.TOP_LEFT -> FramebufferCopyRect(
                x0 = left,
                y0 = top,
                x1 = right,
                y1 = bottom
            )
            CoordinateOrigin.BOTTOM_LEFT -> FramebufferCopyRect(
                x0 = left,
                y0 = size.height - bottom,
                x1 = right,
                y1 = size.height - top
            )
        }
    }

    private val IntSize.isEmpty: Boolean
        get() = width <= 0 || height <= 0

    private fun releaseAfterFailure(
        output: SceneBlurAtlasCopyFrameOutput,
        failure: Throwable
    ): Nothing {
        try {
            release(output)
        } catch (releaseFailure: Throwable) {
            failure.addSuppressed(releaseFailure)
        }
        throw failure
    }

    private data class FramebufferCopyRect(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int
    )

    private companion object {
        val ATLAS_ORIGIN: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
    }
}
