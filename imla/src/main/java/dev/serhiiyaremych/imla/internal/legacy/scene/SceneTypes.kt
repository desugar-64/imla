/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.legacy.Style

@JvmInline
internal value class BlurSlotId(val value: String)

internal enum class BlurSlotDirtyReason {
    Geometry,
    Content,
    Style,
    Mask,
    Order,
}

internal enum class RenderReason {
    RootCaptured,
    SlotChanged,
    SurfaceChanged,
    SceneVisibilityChanged,
    SchedulerDrain,
}

internal data class BlurSlotDirtyFlags(
    val reasons: Set<BlurSlotDirtyReason> = emptySet()
) {
    val isDirty: Boolean
        get() = reasons.isNotEmpty()

    fun with(reason: BlurSlotDirtyReason): BlurSlotDirtyFlags {
        return BlurSlotDirtyFlags(reasons + reason)
    }

    fun merge(other: BlurSlotDirtyFlags): BlurSlotDirtyFlags {
        return BlurSlotDirtyFlags(reasons + other.reasons)
    }

    companion object {
        val Clean: BlurSlotDirtyFlags = BlurSlotDirtyFlags()
    }
}

internal data class BlurSlotGeometry(
    val area: Rect,
    val localRect: Rect,
    val contentOffset: Offset,
    val transformMatrix: FloatArray,
    val zIndex: Float
)

internal fun interface BlurSlotGeometryProvider {
    fun readGeometry(): BlurSlotGeometry?
}

internal data class BlurSlotStyleRecord(
    val style: Style,
    val blurMask: Brush?,
    val clipShape: Shape = RectangleShape
)

internal data class BlurSlotContentRecord(
    val layer: GraphicsLayer?,
    val size: IntSize
)

internal data class BlurSlotRecord(
    val id: BlurSlotId,
    val drawIndex: Int,
    val debugName: String?,
    val geometry: BlurSlotGeometry?,
    val style: BlurSlotStyleRecord,
    val content: BlurSlotContentRecord?,
    val dirtyFlags: BlurSlotDirtyFlags
)

internal data class CaptureTransaction(
    val generation: Long,
    val rootSize: IntSize,
    val records: MutableList<BlurSlotRecord> = mutableListOf()
) {
    val isActive: Boolean
        get() = rootSize != IntSize.Zero

    fun nextDrawIndex(): Int {
        return records.size
    }
}

internal data class CommittedSceneFrame(
    val generation: Long,
    val rootSize: IntSize,
    val slots: List<BlurSlotRecord>,
    val reasons: Set<RenderReason>
)
