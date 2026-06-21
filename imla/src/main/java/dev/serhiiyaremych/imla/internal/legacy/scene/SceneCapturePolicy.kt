/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.legacy.GraphicsLayerTextureFrame
import kotlin.math.roundToInt

internal class ContentCaptureKey(
    val localRect: Rect,
    val contentOffset: Offset,
    private val contentLayer: Any? = null,
    private val contentSize: IntSize? = null
) {
    fun hasSameContentAs(other: ContentCaptureKey): Boolean {
        return contentLayer === other.contentLayer && contentSize == other.contentSize
    }

    fun hasSameGeometryAs(geometry: BlurSlotGeometry): Boolean {
        return localRect == geometry.localRect && contentOffset == geometry.contentOffset
    }

    override fun equals(other: Any?): Boolean {
        return other is ContentCaptureKey &&
            localRect == other.localRect &&
            contentOffset == other.contentOffset &&
            contentLayer === other.contentLayer &&
            contentSize == other.contentSize
    }

    override fun hashCode(): Int {
        var result = localRect.hashCode()
        result = 31 * result + contentOffset.hashCode()
        result = 31 * result + (contentLayer?.let { System.identityHashCode(it) } ?: 0)
        result = 31 * result + (contentSize?.hashCode() ?: 0)
        return result
    }

    companion object {
        fun from(slot: BlurSlotRecord, geometry: BlurSlotGeometry): ContentCaptureKey {
            return ContentCaptureKey(
                localRect = geometry.localRect,
                contentOffset = geometry.contentOffset,
                contentLayer = slot.content?.layer,
                contentSize = slot.content?.size
            )
        }
    }
}

internal data class ContentCaptureFreshnessRecord(
    val captureKey: ContentCaptureKey,
    val geometryOnlyReuseFrames: Int = 0
) {
    fun afterGeometryReuse(): ContentCaptureFreshnessRecord {
        return copy(geometryOnlyReuseFrames = geometryOnlyReuseFrames + 1)
    }

    companion object {
        fun afterCapture(slot: BlurSlotRecord, geometry: BlurSlotGeometry): ContentCaptureFreshnessRecord {
            return ContentCaptureFreshnessRecord(
                captureKey = ContentCaptureKey.from(slot, geometry)
            )
        }
    }
}

internal enum class SlotContentCaptureDecision {
    Capture,
    ReuseFresh,
    ReuseStaleGeometry,
    ForceAfterBudget;

    val requiresCapture: Boolean
        get() = this == Capture || this == ForceAfterBudget
}

internal object SceneCapturePolicy {
    private const val MAX_GEOMETRY_ONLY_REUSE_FRAMES = 2

    fun slotTextureSize(geometry: BlurSlotGeometry): IntSize {
        return IntSize(
            width = geometry.localRect.width.roundToInt().coerceAtLeast(1),
            height = geometry.localRect.height.roundToInt().coerceAtLeast(1)
        )
    }

    fun shouldCaptureSlotContent(
        slot: BlurSlotRecord,
        existingFrame: GraphicsLayerTextureFrame?,
        freshnessRecord: ContentCaptureFreshnessRecord?
    ): Boolean {
        if (slot.geometry == null) return false
        return slotContentCaptureDecision(slot, existingFrame, freshnessRecord).requiresCapture
    }

    fun slotContentCaptureDecision(
        slot: BlurSlotRecord,
        existingFrame: GraphicsLayerTextureFrame?,
        freshnessRecord: ContentCaptureFreshnessRecord?
    ): SlotContentCaptureDecision {
        val geometry = slot.geometry ?: return SlotContentCaptureDecision.ReuseFresh
        val requiredKey = ContentCaptureKey.from(slot, geometry)
        if (existingFrame?.texture2D == null) return SlotContentCaptureDecision.Capture
        val existingFreshness = freshnessRecord ?: return SlotContentCaptureDecision.Capture
        if (!existingFreshness.captureKey.hasSameContentAs(requiredKey)) {
            return SlotContentCaptureDecision.Capture
        }
        if (slot.hasContentOrStyleDirty()) return SlotContentCaptureDecision.Capture
        if (existingFrame.sizePx == slotTextureSize(geometry) &&
            existingFreshness.captureKey.hasSameGeometryAs(geometry)
        ) {
            return SlotContentCaptureDecision.ReuseFresh
        }
        if (slot.dirtyFlags.reasons == setOf(BlurSlotDirtyReason.Geometry)) {
            return if (existingFreshness.geometryOnlyReuseFrames < MAX_GEOMETRY_ONLY_REUSE_FRAMES) {
                SlotContentCaptureDecision.ReuseStaleGeometry
            } else {
                SlotContentCaptureDecision.ForceAfterBudget
            }
        }
        return SlotContentCaptureDecision.Capture
    }

    fun captureKeyChanged(
        geometry: BlurSlotGeometry,
        existingCaptureKey: ContentCaptureKey?
    ): Boolean {
        return existingCaptureKey == null || !existingCaptureKey.hasSameGeometryAs(geometry)
    }

    fun shouldCaptureBlurMask(
        slot: BlurSlotRecord,
        existingFrame: GraphicsLayerTextureFrame?
    ): Boolean {
        if (slot.style.blurMask == null || slot.geometry == null) return false
        return shouldCaptureSlotTexture(
            dirtyFlags = slot.dirtyFlags,
            dirtyReason = BlurSlotDirtyReason.Mask,
            requiredSize = slotTextureSize(slot.geometry),
            existingFrame = existingFrame
        )
    }

    fun shouldCaptureClipMask(
        slot: BlurSlotRecord,
        existingFrame: GraphicsLayerTextureFrame?
    ): Boolean {
        if (slot.style.clipShape == RectangleShape || slot.geometry == null) return false
        return shouldCaptureSlotTexture(
            dirtyFlags = slot.dirtyFlags,
            dirtyReason = BlurSlotDirtyReason.Mask,
            requiredSize = slotTextureSize(slot.geometry),
            existingFrame = existingFrame
        )
    }

    fun shouldCaptureSlotTexture(
        dirtyFlags: BlurSlotDirtyFlags,
        dirtyReason: BlurSlotDirtyReason,
        requiredSize: IntSize,
        existingFrame: GraphicsLayerTextureFrame?
    ): Boolean {
        if (dirtyReason in dirtyFlags.reasons) return true
        if (existingFrame?.texture2D == null) return true
        return existingFrame.sizePx != requiredSize
    }

    private fun BlurSlotRecord.hasContentOrStyleDirty(): Boolean {
        return BlurSlotDirtyReason.Content in dirtyFlags.reasons ||
            BlurSlotDirtyReason.Style in dirtyFlags.reasons
    }
}
