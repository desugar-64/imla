package dev.serhiiyaremych.imla.internal.layer.model

import dev.serhiiyaremych.imla.internal.capture.CapturedLayerFrame
import dev.serhiiyaremych.imla.internal.metrics.SceneMetricsFrame

internal data class UiSceneCapture(
    val rootFrame: CapturedLayerFrame,
    val rootGeneration: Long,
    val rootCaptureDurationNanos: Long = 0L,
    val frameBudgetNanos: Long = 0L,
    val slots: List<SceneSlotDeclaration> = emptyList(),
    val uiCaptures: UiCaptures = UiCaptures.Empty,
    val metricsFrame: SceneMetricsFrame? = null
) {
    fun close() {
        rootFrame.close()
        uiCaptures.close()
    }
}
