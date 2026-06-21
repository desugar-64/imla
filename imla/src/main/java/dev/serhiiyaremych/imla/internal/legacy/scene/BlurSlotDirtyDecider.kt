/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.IntSize

internal object BlurSlotDirtyDecider {
    private val FullDirty = BlurSlotDirtyFlags(BlurSlotDirtyReason.entries.toSet())

    fun dirtyFlags(previous: BlurSlotRecord?, next: BlurSlotRecord): BlurSlotDirtyFlags {
        if (previous == null) return FullDirty

        val reasons = buildSet {
            if (previous.drawIndex != next.drawIndex) add(BlurSlotDirtyReason.Order)
            if (!sameGeometry(previous.geometry, next.geometry)) add(BlurSlotDirtyReason.Geometry)
            if (contentChanged(previous.content, next.content)) add(BlurSlotDirtyReason.Content)
            if (previous.style.style != next.style.style) add(BlurSlotDirtyReason.Style)
            if (previous.style.blurMask != next.style.blurMask) add(BlurSlotDirtyReason.Mask)
            if (previous.style.clipShape != next.style.clipShape) add(BlurSlotDirtyReason.Mask)
        }
        return BlurSlotDirtyFlags(reasons)
    }

    private fun sameGeometry(first: BlurSlotGeometry?, second: BlurSlotGeometry?): Boolean {
        return first != null &&
            second != null &&
            first.area == second.area &&
            first.localRect == second.localRect &&
            first.contentOffset == second.contentOffset &&
            first.zIndex == second.zIndex &&
            first.transformMatrix.contentEquals(second.transformMatrix)
    }

    fun contentChanged(first: BlurSlotContentRecord?, second: BlurSlotContentRecord?): Boolean {
        return contentIdentityChanged(
            firstLayer = first?.layer,
            firstSize = first?.size,
            secondLayer = second?.layer,
            secondSize = second?.size
        )
    }

    fun contentIdentityChanged(
        firstLayer: Any?,
        firstSize: IntSize?,
        secondLayer: Any?,
        secondSize: IntSize?
    ): Boolean {
        return firstLayer !== secondLayer || firstSize != secondSize
    }
}
