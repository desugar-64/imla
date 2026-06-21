package dev.serhiiyaremych.imla.internal.layer.model

import dev.serhiiyaremych.imla.internal.capture.CapturedLayerFrame

internal data class UiCaptures(
    val contentFrames: Map<SceneSlotId, CapturedLayerFrame> = emptyMap(),
    val maskFrames: Map<SceneSlotId, CapturedLayerFrame> = emptyMap(),
    val maskKeys: Map<SceneSlotId, SceneProgressiveMaskKey> = emptyMap(),
    val clipFrames: Map<SceneSlotId, CapturedLayerFrame> = emptyMap(),
    val clipKeys: Map<SceneSlotId, SceneClipShapeKey> = emptyMap()
) {
    fun close() {
        contentFrames.values.forEach { it.close() }
        maskFrames.values.forEach { it.close() }
        clipFrames.values.forEach { it.close() }
    }

    companion object {
        val Empty = UiCaptures()
    }
}
