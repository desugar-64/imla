/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.legacy.GraphicsLayerTextureFrame

internal interface SlotContentCaptureAccess {
    fun hasContent(slot: BlurSlotRecord): Boolean
    fun hasLayer(slot: BlurSlotRecord): Boolean

    fun capture(
        slotId: BlurSlotId,
        slot: BlurSlotRecord,
        size: IntSize,
        contentOffset: Offset,
        onCaptured: (GraphicsLayerTextureFrame?) -> Unit
    )

    fun activeSlotIds(): Set<BlurSlotId>

    fun destroySlot(slotId: BlurSlotId)
    fun destroyAll()
}
