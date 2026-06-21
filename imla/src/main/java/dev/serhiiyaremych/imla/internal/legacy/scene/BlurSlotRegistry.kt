/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

internal class BlurSlotRegistry {
    private val slots = LinkedHashMap<BlurSlotId, RegisteredBlurSlot>()

    fun register(
        id: BlurSlotId,
        debugName: String?,
        geometryProvider: BlurSlotGeometryProvider?
    ): BlurSlotNodeHandle {
        val slot = slots.getOrPut(id) {
            RegisteredBlurSlot(id = id, debugName = debugName)
        }
        slot.debugName = debugName
        slot.geometryProvider = geometryProvider
        return BlurSlotNodeHandle(id = id, registry = this)
    }

    fun unregister(id: BlurSlotId) {
        slots.remove(id)
    }

    fun update(record: BlurSlotRecord): BlurSlotDirtyFlags {
        val slot = slots.getOrPut(record.id) {
            RegisteredBlurSlot(id = record.id, debugName = record.debugName)
        }
        val computedDirtyFlags = BlurSlotDirtyDecider.dirtyFlags(slot.record, record)
        slot.debugName = record.debugName
        slot.record = record
        slot.dirtyFlags = slot.dirtyFlags
            .merge(computedDirtyFlags)
            .merge(record.dirtyFlags)
        return slot.dirtyFlags
    }

    fun markDirty(id: BlurSlotId, reason: BlurSlotDirtyReason) {
        slots[id]?.let { slot ->
            slot.dirtyFlags = slot.dirtyFlags.with(reason)
        }
    }

    fun snapshot(): List<BlurSlotRecord> {
        return slots.values
            .mapNotNull { slot ->
                slot.record?.copy(dirtyFlags = slot.dirtyFlags)
            }
            .sortedWith(compareBy<BlurSlotRecord> { it.geometry?.zIndex ?: it.drawIndex.toFloat() }.thenBy { it.drawIndex })
    }

    fun refreshGeometry(): Boolean {
        var changed = false
        slots.values.forEach { slot ->
            val record = slot.record ?: return@forEach
            val geometryDirty = BlurSlotDirtyReason.Geometry in slot.dirtyFlags.reasons
            val geometry = slot.geometryProvider?.readGeometry() ?: return@forEach
            if (!sameGeometry(record.geometry, geometry)) {
                slot.record = record.copy(geometry = geometry)
                slot.dirtyFlags = slot.dirtyFlags.with(BlurSlotDirtyReason.Geometry)
                changed = true
            } else if (geometryDirty) {
                changed = true
            }
        }
        return changed
    }

    fun clearDirty() {
        slots.values.forEach { slot ->
            slot.dirtyFlags = BlurSlotDirtyFlags.Clean
        }
    }

    private data class RegisteredBlurSlot(
        val id: BlurSlotId,
        var debugName: String?,
        var record: BlurSlotRecord? = null,
        var dirtyFlags: BlurSlotDirtyFlags = BlurSlotDirtyFlags.Clean,
        var geometryProvider: BlurSlotGeometryProvider? = null
    )

    private companion object {
        fun sameGeometry(first: BlurSlotGeometry?, second: BlurSlotGeometry): Boolean {
            return first != null &&
                first.area == second.area &&
                first.localRect == second.localRect &&
                first.contentOffset == second.contentOffset &&
                first.zIndex == second.zIndex &&
                first.transformMatrix.contentEquals(second.transformMatrix)
        }
    }
}

internal class BlurSlotNodeHandle internal constructor(
    val id: BlurSlotId,
    private val registry: BlurSlotRegistry
) {
    fun update(record: BlurSlotRecord) {
        registry.update(record)
    }

    fun markDirty(reason: BlurSlotDirtyReason) {
        registry.markDirty(id, reason)
    }

    fun unregister() {
        registry.unregister(id)
    }
}
