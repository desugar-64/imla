package dev.serhiiyaremych.imla.internal.render.gl

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.metrics.SceneMetricsFrame
import dev.serhiiyaremych.imla.internal.layer.model.SceneBackdropEffect
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotGeometry
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId

internal data class SceneGlRenderFrame(
    val targetSize: IntSize,
    val rootTexture: Texture2D,
    val rootSize: IntSize,
    val rootTextureFlipYForScreen: Boolean,
    val rootCaptureDurationNanos: Long,
    val frameBudgetNanos: Long,
    val slots: List<SceneGlRenderSlot>,
    val metricsFrame: SceneMetricsFrame?
)

internal data class SceneGlRenderSlot(
    val id: SceneSlotId,
    val drawOrder: Int,
    val geometry: SceneSlotGeometry,
    val backdrop: SceneBackdropEffect?,
    val contentTexture: SceneSampledTexture?,
    val progressiveMaskTexture: Texture2D?,
    val requiresBackdropClip: Boolean,
    val requiresContentClip: Boolean,
    val clipTexture: SceneSampledTexture?
) {
    val requiresClip: Boolean
        get() = requiresBackdropClip || requiresContentClip
}
