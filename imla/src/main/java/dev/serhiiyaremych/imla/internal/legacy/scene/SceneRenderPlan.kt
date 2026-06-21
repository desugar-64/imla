/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.Style
import dev.serhiiyaremych.imla.internal.legacy.buildRenderTransform
import kotlin.math.roundToInt

/**
 * Immutable GL-facing scene frame for one renderer draw.
 *
 * The frame owns only value snapshots and borrowed texture handles needed by
 * GL passes. It is built on the GL thread after repositories have resolved
 * their current textures, and it deliberately does not own renderer sessions,
 * repositories, Compose layers, callbacks, or texture lifecycle.
 */
internal data class SceneRenderFrame(
    val generation: Long,
    val rootSize: IntSize,
    val rootTexture: Texture2D,
    val committedSlotCount: Int,
    val reasons: Set<RenderReason>,
    val requiresNoiseTexture: Boolean,
    val slots: List<SceneSlotPlan>
)

/**
 * Immutable draw plan for one blur slot.
 *
 * A slot plan carries only the geometry, transform, style, ordering metadata,
 * resolved textures, and dirty metadata that GL drawing consumes. The textures
 * are borrowed from the scene resource store and remain owned by that store.
 *
 * [blurRadiusMask] is sampled by the gaussian blur pass through the preprocessed
 * sample crop. [compositeCoverageMask] is sampled by the backdrop composite pass
 * in quad/local space. [clipTexture] is sampled by the stencil clip shader in
 * slot-local quad space and must satisfy [SceneStencilClipTextureContract].
 * Today both mask roles usually borrow the same captured texture, but the names
 * preserve the separate pass contracts. [hasBlurRadiusMask] stays true when a
 * blur mask was requested but its texture has not been captured yet.
 */
internal data class SceneSlotPlan(
    val id: BlurSlotId,
    val drawIndex: Int,
    val debugName: String?,
    val area: Rect,
    val localRect: Rect,
    val contentSize: IntSize,
    val transform: Mat4,
    val zIndex: Float,
    val style: Style,
    val contentTexture: Texture2D?,
    val hasBlurRadiusMask: Boolean,
    val blurRadiusMask: Texture2D?,
    val compositeCoverageMask: Texture2D?,
    val clipTexture: Texture2D?,
    val dirtyFlags: BlurSlotDirtyFlags
)

/**
 * Resolved resource snapshot used to build a frame plan.
 *
 * This structure carries borrowed texture handles only. Resource ownership,
 * release queues, and recapture decisions stay in [SceneResourceStore].
 */
internal data class SceneResolvedResources(
    val rootTexture: Texture2D,
    val slots: Map<BlurSlotId, SceneResolvedSlotResources>
)

/**
 * Borrowed textures currently available for one slot.
 *
 * The plan can reference these textures for drawing, but it never releases or
 * recaptures them. [blurRadiusMask] maps through a preprocessed sample crop;
 * [compositeCoverageMask] maps through quad/local composite coordinates.
 * [clipTexture] maps through the stencil clip shader's slot-local top-left
 * coordinate contract.
 */
internal data class SceneResolvedSlotResources(
    val contentTexture: Texture2D?,
    val blurRadiusMask: Texture2D?,
    val compositeCoverageMask: Texture2D?,
    val clipTexture: Texture2D?
) {
    internal companion object {
        val Empty: SceneResolvedSlotResources = SceneResolvedSlotResources(
            contentTexture = null,
            blurRadiusMask = null,
            compositeCoverageMask = null,
            clipTexture = null
        )
    }
}

/**
 * Converts committed capture data into the immutable GL frame-plan boundary.
 *
 * The planner is stateless and performs no resource ownership work. It filters
 * slots that cannot draw because they have no geometry, copies mutable capture
 * metadata into read-only collections, and preserves committed slot order.
 */
internal object SceneFramePlanner {
    fun plan(
        frame: CommittedSceneFrame,
        resources: SceneResolvedResources
    ): SceneRenderFrame {
        return SceneRenderFrame(
            generation = frame.generation,
            rootSize = frame.rootSize,
            rootTexture = resources.rootTexture,
            committedSlotCount = frame.slots.size,
            reasons = frame.reasons.toSet(),
            requiresNoiseTexture = frame.slots.any { it.style.style.noiseAlpha >= MIN_NOISE_ALPHA },
            slots = frame.slots.mapNotNull { slot -> slotPlan(slot, resources) }
        )
    }

    private fun slotPlan(
        slot: BlurSlotRecord,
        resources: SceneResolvedResources
    ): SceneSlotPlan? {
        val geometry = slot.geometry ?: return null
        val slotResources = resources.slots[slot.id] ?: SceneResolvedSlotResources.Empty
        return SceneSlotPlan(
            id = slot.id,
            drawIndex = slot.drawIndex,
            debugName = slot.debugName,
            area = geometry.area,
            localRect = geometry.localRect,
            contentSize = contentSizeFor(geometry),
            transform = buildRenderTransform(geometry.transformMatrix, geometry.localRect),
            zIndex = geometry.zIndex,
            style = slot.style.style,
            contentTexture = slotResources.contentTexture,
            hasBlurRadiusMask = slot.style.blurMask != null || slotResources.blurRadiusMask != null,
            blurRadiusMask = slotResources.blurRadiusMask,
            compositeCoverageMask = slotResources.compositeCoverageMask,
            clipTexture = slotResources.clipTexture,
            dirtyFlags = BlurSlotDirtyFlags(slot.dirtyFlags.reasons.toSet())
        )
    }

    private fun contentSizeFor(geometry: BlurSlotGeometry): IntSize {
        return IntSize(
            width = geometry.localRect.width.roundToInt().coerceAtLeast(1),
            height = geometry.localRect.height.roundToInt().coerceAtLeast(1)
        )
    }

    private const val MIN_NOISE_ALPHA = 0.05f
}
