/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.BlurAlgorithm
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Immutable, frame-local blur atlas plan for one [SceneRenderFrame].
 *
 * The plan is intended for a future atlas stage between preprocess and backdrop composite. It owns
 * ordered eligible batches, fallback identities, skipped identities, and diagnostics only. It may
 * carry borrowed texture handles already resolved into the frame and has no packing texture, shader,
 * FBO, resource store, Compose layer, callback, or texture lifecycle.
 */
internal data class SceneBlurAtlasFramePlan(
    val generation: Long,
    val rootSize: IntSize,
    val batches: List<SceneBlurAtlasBatchPlan>,
    val fallbacks: List<SceneBlurAtlasFallbackRequest>,
    val skipped: List<SceneBlurAtlasSkippedRequest>,
    val diagnostics: List<SceneBlurAtlasEligibilityDecision>
)

/**
 * Immutable group of blur requests that can share the same future atlas blur configuration.
 *
 * Requests remain in committed frame order inside the batch. The batch owns no execution state and
 * does not imply that rendering should use it yet.
 */
internal data class SceneBlurAtlasBatchPlan(
    val key: SceneBlurAtlasCompatibilityKey,
    val requests: List<SceneBlurAtlasRequest>
)

/**
 * Immutable per-slot request for future blur atlas packing.
 *
 * The request carries stable slot identity, committed draw ordering, top-left scene sample bounds,
 * local content bounds, content size, and the compatibility key used for deterministic grouping.
 * The nullable blur-radius mask handle is borrowed metadata only; the current conservative policy
 * keeps masked slots on the fallback path. This request deliberately does not own output crops,
 * atlas placement, GL execution resources, texture lifecycles, or post-blur composite state.
 */
internal data class SceneBlurAtlasRequest(
    val slotId: BlurSlotId,
    val drawIndex: Int,
    val eligibilityReason: SceneBlurAtlasEligibilityReason,
    val sampleArea: Rect,
    val localContentArea: Rect,
    val contentSize: IntSize,
    val blurRadiusMask: SceneBlurAtlasMaskTextureMetadata?,
    val compatibilityKey: SceneBlurAtlasCompatibilityKey
)

/**
 * Immutable borrowed blur-radius mask texture metadata.
 *
 * Atlas planning carries only the texture id, size, and origin needed for deterministic fallback
 * diagnostics. The owning mask repository keeps the actual texture lifecycle.
 */
internal data class SceneBlurAtlasMaskTextureMetadata(
    val textureId: Int,
    val size: IntSize,
    val coordinateOrigin: CoordinateOrigin
)

/**
 * Immutable key for deciding which requests may be planned into the same atlas blur batch.
 *
 * The key is intentionally limited to the blur inputs that affect the current preprocess/blur stage.
 * Composite-only values such as tint, opacity, noise, clip, and content texture stay outside atlas
 * grouping and remain owned by the existing slot frame plan.
 */
internal data class SceneBlurAtlasCompatibilityKey(
    val sigma: Float,
    val algorithm: BlurAlgorithm
)

/**
 * Immutable fallback metadata for a slot whose current frame should keep using the existing
 * per-slot blur path.
 *
 * The atlas policy keeps this intentionally small: future live wiring can match [slotId] and
 * [drawIndex] back to the original [SceneRenderFrame] slot and run the existing slot pass. It owns
 * no renderer, session, resource store, render object, texture, clip, stencil, or GL state.
 */
internal data class SceneBlurAtlasFallbackRequest(
    val slotId: BlurSlotId,
    val drawIndex: Int,
    val reason: SceneBlurAtlasFallbackReason,
    val blurRadiusMask: SceneBlurAtlasMaskTextureMetadata?
)

/**
 * Immutable metadata for a slot whose backdrop blur request is already non-renderable under the
 * existing per-slot planning contract.
 */
internal data class SceneBlurAtlasSkippedRequest(
    val slotId: BlurSlotId,
    val drawIndex: Int,
    val reason: SceneBlurAtlasSkipReason
)

/**
 * Deterministic per-slot policy diagnostic.
 *
 * Eligible decisions carry the compatibility key that placed the slot into an atlas batch.
 * Fallback decisions carry only the reason to preserve the screen-space blur / quad-space clip
 * rule: clipped, masked, or otherwise uncertain slots stay on the existing per-slot path until
 * their atlas behavior has been explicitly verified. Skipped decisions are reserved for requests
 * the current renderer already treats as non-renderable.
 */
internal data class SceneBlurAtlasEligibilityDecision(
    val slotId: BlurSlotId,
    val slotDebugName: String?,
    val drawIndex: Int,
    val outcome: SceneBlurAtlasEligibilityOutcome,
    val eligibilityReason: SceneBlurAtlasEligibilityReason?,
    val fallbackReason: SceneBlurAtlasFallbackReason?,
    val skipReason: SceneBlurAtlasSkipReason?,
    val compatibilityKey: SceneBlurAtlasCompatibilityKey?,
    val blurRadiusMask: SceneBlurAtlasMaskTextureMetadata?
)

internal enum class SceneBlurAtlasEligibilityOutcome {
    AtlasEligible,
    Fallback,
    SkippedInvalid
}

internal enum class SceneBlurAtlasEligibilityReason {
    PlainScreenSpaceBlur
}

internal enum class SceneBlurAtlasFallbackReason {
    MaskedBlurUnsupported,
    MaskedBlurMissingTexture,
    MaskedBlurAtlasExecutionFailed,
    UnsupportedBlurAlgorithm,
    InvalidBlurSigma
}

internal enum class SceneBlurAtlasSkipReason {
    SampleAreaOutsideRoot
}

/**
 * Immutable, frame-local placement plan for future atlas blur delivery.
 *
 * Each compatibility batch owns one logical atlas coordinate space. The structure describes where
 * already planned requests would be copied and sampled if a future atlas pass consumes it. It does
 * not allocate atlas textures, enforce device texture limits, run preprocess/blur/composite work, or
 * keep mutable placement state across frames.
 */
internal data class SceneBlurAtlasPlacementFramePlan(
    val generation: Long,
    val rootSize: IntSize,
    val batches: List<SceneBlurAtlasBatchPlacementPlan>
)

/**
 * Immutable placement result for one compatibility batch.
 *
 * The current planner assumes one logical atlas per compatibility batch. [atlasSize] is only the
 * pixel extent required by the deterministic shelf layout; texture page splitting and GPU max-size
 * policy remain future atlas-pass responsibilities.
 */
internal data class SceneBlurAtlasBatchPlacementPlan(
    val key: SceneBlurAtlasCompatibilityKey,
    val atlasSize: IntSize,
    val placements: List<SceneBlurAtlasRequestPlacement>
)

/**
 * Immutable placement metadata for one blur request.
 *
 * Rectangles are integer pixel coordinates. [sourceSampleRect] is the snapped scene sample area,
 * [paddedSourceRect] is the source copy area including sigma-derived blur padding, [atlasRect] is
 * the padded destination tile in atlas pixels, and [atlasSampleCrop] is the unpadded sample crop
 * inside that atlas tile for future composite lookup. The placement owns no textures or lifecycle.
 */
internal data class SceneBlurAtlasRequestPlacement(
    val slotId: BlurSlotId,
    val drawIndex: Int,
    val blurRadiusMask: SceneBlurAtlasMaskTextureMetadata?,
    val sourceSampleRect: SceneBlurAtlasPixelRect,
    val paddedSourceRect: SceneBlurAtlasPixelRect,
    val atlasRect: SceneBlurAtlasPixelRect,
    val atlasSampleCrop: SceneBlurAtlasPixelRect
)

/**
 * Immutable whole-pixel rectangle used by atlas placement planning.
 */
internal data class SceneBlurAtlasPixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Derives immutable blur atlas planning data from an existing immutable scene frame.
 *
 * The planner is stateless and page-local by construction: callers pass a single frame, and the
 * returned plan contains no shared mutable state or links back to frame owners. It may run anywhere
 * the immutable [SceneRenderFrame] is already safe to inspect, but the borrowed texture handles
 * remain valid only under the same lifecycle as the frame that supplied them.
 */
internal object SceneBlurAtlasPlanner {
    fun plan(frame: SceneRenderFrame): SceneBlurAtlasFramePlan {
        val batches = LinkedHashMap<SceneBlurAtlasCompatibilityKey, MutableList<SceneBlurAtlasRequest>>()
        val fallbacks = ArrayList<SceneBlurAtlasFallbackRequest>()
        val skipped = ArrayList<SceneBlurAtlasSkippedRequest>()
        val diagnostics = ArrayList<SceneBlurAtlasEligibilityDecision>(frame.slots.size)

        frame.slots.forEach { slot ->
            when (
                val classification = SceneBlurAtlasEligibilityPolicy.classify(
                    slot = slot,
                    rootSize = frame.rootSize
                )
            ) {
                is SceneBlurAtlasSlotClassification.Eligible -> {
                    val request = requestFor(slot, classification)
                    batches.getOrPut(request.compatibilityKey) { ArrayList() }.add(request)
                    diagnostics += classification.decision
                }

                is SceneBlurAtlasSlotClassification.Fallback -> {
                    fallbacks += classification.request
                    diagnostics += classification.decision
                }

                is SceneBlurAtlasSlotClassification.Skipped -> {
                    skipped += classification.request
                    diagnostics += classification.decision
                }
            }
        }

        return SceneBlurAtlasFramePlan(
            generation = frame.generation,
            rootSize = frame.rootSize,
            batches = batches.map { (key, requests) ->
                SceneBlurAtlasBatchPlan(
                    key = key,
                    requests = requests.toList()
                )
            },
            fallbacks = fallbacks.toList(),
            skipped = skipped.toList(),
            diagnostics = diagnostics.toList()
        )
    }

    private fun requestFor(
        slot: SceneSlotPlan,
        classification: SceneBlurAtlasSlotClassification.Eligible
    ): SceneBlurAtlasRequest {
        return SceneBlurAtlasRequest(
            slotId = slot.id,
            drawIndex = slot.drawIndex,
            eligibilityReason = classification.reason,
            sampleArea = slot.area,
            localContentArea = slot.localRect,
            contentSize = slot.contentSize,
            blurRadiusMask = slot.blurRadiusMask?.toMaskTextureMetadata(),
            compatibilityKey = classification.compatibilityKey
        )
    }
}

/**
 * Conservative, stateless atlas eligibility policy for scene blur slots.
 *
 * A slot is atlas eligible only when the next atlas wiring can copy and blur the request without
 * changing visible semantics: a finite positive gaussian screen-space blur, a renderable
 * sample area inside the root, and no blur-radius mask. Noise, clip textures, and composite coverage
 * masks remain final per-slot composite inputs and do not affect atlas blur grouping. Everything
 * uncertain falls back to the existing per-slot blur path instead of becoming an error. Fallbacks
 * deliberately keep radius-mask and clip/stencil decisions outside atlas blur so blur remains
 * screen-space while clipping and coverage remain quad-space.
 *
 * The policy owns only immutable classification values. It does not own renderers, sessions,
 * repositories, render objects, resources, texture lifetimes, GL state, atlas storage, or pass
 * execution.
 */
internal object SceneBlurAtlasEligibilityPolicy {
    fun classify(
        slot: SceneSlotPlan,
        rootSize: IntSize
    ): SceneBlurAtlasSlotClassification {
        if (snapRenderableSampleArea(slot.area, rootSize) == null) {
            return skipped(slot, SceneBlurAtlasSkipReason.SampleAreaOutsideRoot)
        }

        if (!slot.style.sigma.isFinite() || slot.style.sigma <= 0f) {
            return fallback(slot, SceneBlurAtlasFallbackReason.InvalidBlurSigma)
        }
        if (!isSupportedAlgorithm(slot.style.algorithm)) {
            return fallback(slot, SceneBlurAtlasFallbackReason.UnsupportedBlurAlgorithm)
        }
        if (slot.hasBlurRadiusMask) {
            return fallback(slot, SceneBlurAtlasFallbackReason.MaskedBlurUnsupported)
        }

        val key = SceneBlurAtlasCompatibilityKey(
            sigma = slot.style.sigma,
            algorithm = slot.style.algorithm
        )
        return eligible(
            slot = slot,
            reason = SceneBlurAtlasEligibilityReason.PlainScreenSpaceBlur,
            compatibilityKey = key
        )
    }

    private fun isSupportedAlgorithm(algorithm: BlurAlgorithm): Boolean {
        return algorithm == BlurAlgorithm.GAUSSIAN
    }

    private fun snapRenderableSampleArea(
        area: Rect,
        rootSize: IntSize
    ): Rect? {
        val left = floor(area.left).coerceAtLeast(0f)
        val top = floor(area.top).coerceAtLeast(0f)
        val right = ceil(area.right).coerceAtMost(rootSize.width.toFloat())
        val bottom = ceil(area.bottom).coerceAtMost(rootSize.height.toFloat())
        if (right - left <= 1f || bottom - top <= 1f) return null
        return Rect(left = left, top = top, right = right, bottom = bottom)
    }

    private fun eligible(
        slot: SceneSlotPlan,
        reason: SceneBlurAtlasEligibilityReason,
        compatibilityKey: SceneBlurAtlasCompatibilityKey
    ): SceneBlurAtlasSlotClassification.Eligible {
        val decision = SceneBlurAtlasEligibilityDecision(
            slotId = slot.id,
            slotDebugName = slot.debugName,
            drawIndex = slot.drawIndex,
            outcome = SceneBlurAtlasEligibilityOutcome.AtlasEligible,
            eligibilityReason = reason,
            fallbackReason = null,
            skipReason = null,
            compatibilityKey = compatibilityKey,
            blurRadiusMask = slot.blurRadiusMask?.toMaskTextureMetadata()
        )
        return SceneBlurAtlasSlotClassification.Eligible(
            reason = reason,
            compatibilityKey = compatibilityKey,
            decision = decision
        )
    }

    private fun fallback(
        slot: SceneSlotPlan,
        reason: SceneBlurAtlasFallbackReason
    ): SceneBlurAtlasSlotClassification.Fallback {
        val request = SceneBlurAtlasFallbackRequest(
            slotId = slot.id,
            drawIndex = slot.drawIndex,
            reason = reason,
            blurRadiusMask = slot.blurRadiusMask?.toMaskTextureMetadata()
        )
        val decision = SceneBlurAtlasEligibilityDecision(
            slotId = slot.id,
            slotDebugName = slot.debugName,
            drawIndex = slot.drawIndex,
            outcome = SceneBlurAtlasEligibilityOutcome.Fallback,
            eligibilityReason = null,
            fallbackReason = reason,
            skipReason = null,
            compatibilityKey = null,
            blurRadiusMask = slot.blurRadiusMask?.toMaskTextureMetadata()
        )
        return SceneBlurAtlasSlotClassification.Fallback(
            request = request,
            decision = decision
        )
    }

    private fun skipped(
        slot: SceneSlotPlan,
        reason: SceneBlurAtlasSkipReason
    ): SceneBlurAtlasSlotClassification.Skipped {
        val request = SceneBlurAtlasSkippedRequest(
            slotId = slot.id,
            drawIndex = slot.drawIndex,
            reason = reason
        )
        val decision = SceneBlurAtlasEligibilityDecision(
            slotId = slot.id,
            slotDebugName = slot.debugName,
            drawIndex = slot.drawIndex,
            outcome = SceneBlurAtlasEligibilityOutcome.SkippedInvalid,
            eligibilityReason = null,
            fallbackReason = null,
            skipReason = reason,
            compatibilityKey = null,
            blurRadiusMask = slot.blurRadiusMask?.toMaskTextureMetadata()
        )
        return SceneBlurAtlasSlotClassification.Skipped(
            request = request,
            decision = decision
        )
    }
}

internal sealed interface SceneBlurAtlasSlotClassification {
    data class Eligible(
        val reason: SceneBlurAtlasEligibilityReason,
        val compatibilityKey: SceneBlurAtlasCompatibilityKey,
        val decision: SceneBlurAtlasEligibilityDecision
    ) : SceneBlurAtlasSlotClassification

    data class Fallback(
        val request: SceneBlurAtlasFallbackRequest,
        val decision: SceneBlurAtlasEligibilityDecision
    ) : SceneBlurAtlasSlotClassification

    data class Skipped(
        val request: SceneBlurAtlasSkippedRequest,
        val decision: SceneBlurAtlasEligibilityDecision
    ) : SceneBlurAtlasSlotClassification
}

/**
 * Derives immutable, deterministic atlas placement metadata from an existing blur atlas frame plan.
 *
 * The planner is stateless: every call computes placement from the supplied immutable frame plan and
 * local variables only. It may run on any thread that can safely inspect the frame plan. The returned
 * data is intended for a future atlas pass to consume after preprocess and before backdrop composite;
 * it deliberately does not own render execution, renderer/session state, texture resources, or any
 * Compose-layer lifecycle.
 */
internal object SceneBlurAtlasPlacementPlanner {
    fun plan(framePlan: SceneBlurAtlasFramePlan): SceneBlurAtlasPlacementFramePlan {
        return SceneBlurAtlasPlacementFramePlan(
            generation = framePlan.generation,
            rootSize = framePlan.rootSize,
            batches = framePlan.batches.map { batch ->
                planBatch(batch, framePlan.rootSize)
            }
        )
    }

    private fun planBatch(
        batch: SceneBlurAtlasBatchPlan,
        rootSize: IntSize
    ): SceneBlurAtlasBatchPlacementPlan {
        val tiles = batch.requests.mapNotNull { request ->
            val sourceSampleRect = snapSourceRect(request.sampleArea, rootSize) ?: return@mapNotNull null
            val paddedSourceRect = paddedSourceRect(
                sourceSampleRect = sourceSampleRect,
                rootSize = rootSize,
                padding = blurPaddingPx(request.compatibilityKey.sigma)
            )
            PlacementTile(
                request = request,
                sourceSampleRect = sourceSampleRect,
                paddedSourceRect = paddedSourceRect
            )
        }
        val placements = placeTiles(tiles, rootSize.width)

        return SceneBlurAtlasBatchPlacementPlan(
            key = batch.key,
            atlasSize = atlasSizeFor(placements),
            placements = placements
        )
    }

    private fun placeTiles(
        tiles: List<PlacementTile>,
        preferredRowWidth: Int
    ): List<SceneBlurAtlasRequestPlacement> {
        if (tiles.isEmpty()) return emptyList()

        val rowWidth = preferredRowWidth.coerceAtLeast(tiles.maxOf { tile -> tile.paddedSourceRect.width })
        val placements = ArrayList<SceneBlurAtlasRequestPlacement>(tiles.size)
        var cursorX = 0
        var cursorY = 0
        var shelfHeight = 0

        tiles.forEach { tile ->
            val tileWidth = tile.paddedSourceRect.width
            val tileHeight = tile.paddedSourceRect.height
            if (cursorX > 0 && cursorX + tileWidth > rowWidth) {
                cursorX = 0
                cursorY += shelfHeight
                shelfHeight = 0
            }

            val atlasRect = SceneBlurAtlasPixelRect(
                left = cursorX,
                top = cursorY,
                right = cursorX + tileWidth,
                bottom = cursorY + tileHeight
            )
            placements += SceneBlurAtlasRequestPlacement(
                slotId = tile.request.slotId,
                drawIndex = tile.request.drawIndex,
                blurRadiusMask = tile.request.blurRadiusMask,
                sourceSampleRect = tile.sourceSampleRect,
                paddedSourceRect = tile.paddedSourceRect,
                atlasRect = atlasRect,
                atlasSampleCrop = atlasSampleCrop(tile, atlasRect)
            )

            cursorX += tileWidth
            shelfHeight = maxOf(shelfHeight, tileHeight)
        }

        return placements.toList()
    }

    private fun snapSourceRect(
        area: Rect,
        rootSize: IntSize
    ): SceneBlurAtlasPixelRect? {
        val left = floor(area.left).toInt().coerceInRootWidth(rootSize)
        val top = floor(area.top).toInt().coerceInRootHeight(rootSize)
        val right = ceil(area.right).toInt().coerceInRootWidth(rootSize)
        val bottom = ceil(area.bottom).toInt().coerceInRootHeight(rootSize)
        if (right <= left || bottom <= top) return null
        return SceneBlurAtlasPixelRect(left = left, top = top, right = right, bottom = bottom)
    }

    private fun paddedSourceRect(
        sourceSampleRect: SceneBlurAtlasPixelRect,
        rootSize: IntSize,
        padding: Int
    ): SceneBlurAtlasPixelRect {
        return SceneBlurAtlasPixelRect(
            left = (sourceSampleRect.left - padding).coerceAtLeast(0),
            top = (sourceSampleRect.top - padding).coerceAtLeast(0),
            right = (sourceSampleRect.right + padding).coerceAtMost(rootSize.width),
            bottom = (sourceSampleRect.bottom + padding).coerceAtMost(rootSize.height)
        )
    }

    private fun atlasSampleCrop(
        tile: PlacementTile,
        atlasRect: SceneBlurAtlasPixelRect
    ): SceneBlurAtlasPixelRect {
        return SceneBlurAtlasPixelRect(
            left = atlasRect.left + tile.sourceSampleRect.left - tile.paddedSourceRect.left,
            top = atlasRect.top + tile.sourceSampleRect.top - tile.paddedSourceRect.top,
            right = atlasRect.left + tile.sourceSampleRect.right - tile.paddedSourceRect.left,
            bottom = atlasRect.top + tile.sourceSampleRect.bottom - tile.paddedSourceRect.top
        )
    }

    private fun atlasSizeFor(placements: List<SceneBlurAtlasRequestPlacement>): IntSize {
        if (placements.isEmpty()) return IntSize(width = 0, height = 0)
        return IntSize(
            width = placements.maxOf { placement -> placement.atlasRect.right },
            height = placements.maxOf { placement -> placement.atlasRect.bottom }
        )
    }

    private fun blurPaddingPx(sigma: Float): Int {
        if (sigma <= 0f) return 0
        return ceil(BLUR_PADDING_SIGMA_MULTIPLIER * sigma).toInt()
    }

    private fun Int.coerceInRootWidth(rootSize: IntSize): Int {
        return coerceIn(0, rootSize.width)
    }

    private fun Int.coerceInRootHeight(rootSize: IntSize): Int {
        return coerceIn(0, rootSize.height)
    }

    private data class PlacementTile(
        val request: SceneBlurAtlasRequest,
        val sourceSampleRect: SceneBlurAtlasPixelRect,
        val paddedSourceRect: SceneBlurAtlasPixelRect
    )

    private const val BLUR_PADDING_SIGMA_MULTIPLIER = 3f
}

private fun Texture2D.toMaskTextureMetadata(): SceneBlurAtlasMaskTextureMetadata {
    return SceneBlurAtlasMaskTextureMetadata(
        textureId = id,
        size = IntSize(width = width, height = height),
        coordinateOrigin = coordinateOrigin
    )
}
