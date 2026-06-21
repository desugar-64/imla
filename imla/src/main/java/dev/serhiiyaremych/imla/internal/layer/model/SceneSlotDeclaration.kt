package dev.serhiiyaremych.imla.internal.layer.model

import androidx.compose.ui.graphics.Color

internal data class SceneSlotDeclaration(
    val id: SceneSlotId,
    val drawOrder: Int,
    val geometry: SceneSlotGeometry,
    val backdrop: SceneBackdropEffect?,
    val debugColor: Color,
    val requiresBackdropClip: Boolean = false,
    val requiresContentClip: Boolean = false
) {
    val requiresClip: Boolean
        get() = requiresBackdropClip || requiresContentClip
}
