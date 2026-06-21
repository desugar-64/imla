/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.processing.effects.PreProcessEffect
import java.util.Locale

internal object SceneBlurAtlasGeometryDiagnostics {
    private const val TAG = "ImlaSceneAtlasGeometry"

    fun perSlotPreprocess(
        slot: SceneSlotPlan,
        targetSize: IntSize,
        sampleArea: Rect,
        output: PreProcessEffect.PreProcessOutput
    ) {
        if (!enabled) return
        Log.d(
            TAG,
            "per-slot ${slot.label()} target=${targetSize.short()} " +
                "layout=${slot.area.short()} local=${slot.localRect.short()} content=${slot.contentSize.short()} " +
                "sample=${sampleArea.short()} pre.sampleCrop=${output.sampleCrop.short()} " +
                "pre.contentCrop=${output.contentCrop.short()} pre.sourceSampleCrop=${output.sourceSampleCrop.short()} " +
                "pre.fboContent=${output.fbo.contentSize.short()} pre.fboAllocated=${output.fbo.allocatedSize.short()} " +
                "pre.fboOffset=${output.fbo.contentOffset.short()} downsample=${output.downsampleScale.fmt()}"
        )
    }

    fun atlasPipeline(
        frame: SceneRenderFrame,
        preflight: SceneBlurAtlasPipelinePreflight,
        output: SceneBlurAtlasPipelineOutput
    ) {
        if (!enabled) return
        logPlacement(frame, preflight.placementPlan)
        logPreprocess(frame, preflight.preprocessResult)
        output.copyOutput?.let { copyOutput -> logCopy(frame, copyOutput) }
        output.blurOutput?.let { blurOutput -> logBlur(frame, blurOutput) }
        logLookup(frame, output.lookupOutput)
    }

    fun atlasComposite(
        slot: SceneSlotPlan,
        lookup: SceneBlurAtlasCompositeLookupEntry
    ) {
        if (!enabled) return
        Log.d(
            TAG,
            "atlas-final ${slot.label()} sampleRect=${lookup.sourceSampleRect.short()} " +
                "sampleUv=${lookup.blurredAtlasTexture.uv(lookup.blurredAtlasSampleCrop).short()} " +
                "blurredCrop=${lookup.blurredAtlasSampleCrop.short()} texture=${lookup.blurredAtlasTexture.size.short()} " +
                "origin=${lookup.blurredAtlasTexture.coordinateOrigin}"
        )
    }

    private fun logPlacement(
        frame: SceneRenderFrame,
        plan: SceneBlurAtlasPlacementFramePlan
    ) {
        plan.batches.forEach { batch ->
            batch.placements.forEach { placement ->
                val slot = frame.slotFor(placement.slotId, placement.drawIndex)
                Log.d(
                    TAG,
                    "atlas-placement ${slot.label(placement.slotId, placement.drawIndex)} " +
                        "layout=${slot?.area?.short()} local=${slot?.localRect?.short()} content=${slot?.contentSize?.short()} " +
                        "snapped=${placement.sourceSampleRect.short()} padded=${placement.paddedSourceRect.short()} " +
                        "atlasRect=${placement.atlasRect.short()} atlasSampleCrop=${placement.atlasSampleCrop.short()}"
                )
            }
        }
    }

    private fun logPreprocess(
        frame: SceneRenderFrame,
        result: SceneBlurAtlasPreprocessFrameResult
    ) {
        result.batches.forEach { batch ->
            batch.placements.forEach { placement ->
                val request = placement.request
                val output = placement.output
                val slot = frame.slotFor(request.slotId, request.drawIndex)
                Log.d(
                    TAG,
                    "atlas-preprocess ${slot.label(request.slotId, request.drawIndex)} " +
                        "sourceCopy=${output.sourceCopyRect.short()} sourceSample=${request.sourceSampleRect.short()} " +
                        "sourceSampleCrop=${output.sourceSampleCrop.short()} atlasRect=${output.atlasRect.short()} " +
                        "atlasSampleCrop=${output.atlasSampleCrop.short()} copyScale=${output.copyScale.x.fmt()}x${output.copyScale.y.fmt()} " +
                        "downsample=${output.downsampleScale.fmt()}"
                )
            }
        }
    }

    private fun logCopy(
        frame: SceneRenderFrame,
        output: SceneBlurAtlasCopyFrameOutput
    ) {
        output.batches.forEach { batch ->
            batch.placements.forEach { placement ->
                val slot = frame.slotFor(placement.slotId, placement.drawIndex)
                Log.d(
                    TAG,
                    "atlas-copy ${slot.label(placement.slotId, placement.drawIndex)} " +
                        "sourceCopy=${placement.sourceCopyRect.short()} sourceSample=${placement.sourceSampleRect.short()} " +
                        "sourceSampleCrop=${placement.sourceSampleCrop.short()} atlasRect=${placement.atlasRect.short()} " +
                        "atlasSampleCrop=${placement.atlasSampleCrop.short()} atlasSize=${batch.atlasSize.short()} " +
                        "origin=${batch.atlasTexture.coordinateOrigin}"
                )
            }
        }
    }

    private fun logBlur(
        frame: SceneRenderFrame,
        output: SceneBlurAtlasBlurFrameOutput
    ) {
        output.batches.forEach { batch ->
            Log.d(
                TAG,
                "atlas-blur-batch sigma=${batch.settings.sigma.fmt()} sigmaTexels=${batch.settings.sigmaTexels.fmt()} " +
                    "atlasSize=${batch.atlasSize.short()} blurredAllocated=${batch.blurredAtlasAllocatedSize.short()} " +
                    "blurredContentCrop=${batch.blurredAtlasContentCrop.short()} blurredContentUv=${batch.blurredAtlasContentUv.short()} " +
                    "origin=${batch.coordinateOriginHandoff.storageOrigin}->${batch.coordinateOriginHandoff.logicalLookupOrigin}"
            )
            batch.placements.forEach { placement ->
                val slot = frame.slotFor(placement.slotId, placement.drawIndex)
                Log.d(
                    TAG,
                    "atlas-blur ${slot.label(placement.slotId, placement.drawIndex)} " +
                        "sourceAtlasRect=${placement.sourceAtlasRect.short()} sourceAtlasSampleCrop=${placement.sourceAtlasSampleCrop.short()} " +
                        "blurredAtlasSampleCrop=${placement.blurredAtlasSampleCrop.short()} sourceSample=${placement.sourceSampleRect.short()} " +
                        "copyScale=${placement.copyScale.x.fmt()}x${placement.copyScale.y.fmt()} downsample=${placement.downsampleScale.fmt()}"
                )
            }
        }
    }

    private fun logLookup(
        frame: SceneRenderFrame,
        output: SceneBlurAtlasCompositeLookupFrameOutput
    ) {
        output.entries.forEach { lookup ->
            val slot = frame.slotFor(lookup.slotId, lookup.drawIndex)
            Log.d(
                TAG,
                "atlas-lookup ${slot.label(lookup.slotId, lookup.drawIndex)} " +
                    "sourceSample=${lookup.sourceSampleRect.short()} sourceCopy=${lookup.sourceCopyRect.short()} " +
                    "sourceAtlasSampleCrop=${lookup.sourceAtlasSampleCrop.short()} blurredCrop=${lookup.blurredAtlasSampleCrop.short()} " +
                    "sampleUv=${lookup.blurredAtlasTexture.uv(lookup.blurredAtlasSampleCrop).short()} " +
                    "texture=${lookup.blurredAtlasTexture.size.short()} origin=${lookup.blurredAtlasTexture.coordinateOrigin}"
            )
        }
    }

    private val enabled: Boolean
        get() = runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)

    private fun SceneRenderFrame.slotFor(slotId: BlurSlotId, drawIndex: Int): SceneSlotPlan? {
        return slots.firstOrNull { slot -> slot.id == slotId && slot.drawIndex == drawIndex }
    }

    private fun SceneSlotPlan.label(): String {
        return "${debugName ?: id.value}#${drawIndex}"
    }

    private fun SceneSlotPlan?.label(slotId: BlurSlotId, drawIndex: Int): String {
        return this?.label() ?: "${slotId.value}#${drawIndex}"
    }

    private fun SceneBlurAtlasTextureHandle.uv(rect: SceneBlurAtlasPixelRect): SceneBlurAtlasUvRect {
        val width = size.width.coerceAtLeast(1).toFloat()
        val height = size.height.coerceAtLeast(1).toFloat()
        return SceneBlurAtlasUvRect(
            left = rect.left / width,
            top = rect.top / height,
            right = rect.right / width,
            bottom = rect.bottom / height
        )
    }

    private fun Rect.short(): String {
        return "[${left.fmt()},${top.fmt()}..${right.fmt()},${bottom.fmt()} ${width.fmt()}x${height.fmt()}]"
    }

    private fun SceneBlurAtlasPixelRect.short(): String {
        return "[$left,$top..$right,$bottom ${width}x$height]"
    }

    private fun SceneBlurAtlasUvRect.short(): String {
        return "[${left.fmt()},${top.fmt()}..${right.fmt()},${bottom.fmt()}]"
    }

    private fun IntSize.short(): String {
        return "${width}x$height"
    }

    private fun IntOffset.short(): String {
        return "${x},${y}"
    }

    private fun Float.fmt(): String {
        return String.format(Locale.US, "%.2f", this)
    }
}
