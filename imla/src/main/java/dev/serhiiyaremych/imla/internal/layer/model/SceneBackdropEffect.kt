package dev.serhiiyaremych.imla.internal.layer.model

import androidx.compose.ui.graphics.Color

internal data class SceneBackdropEffect(
    val operations: List<SceneBackdropOperation>,
    val composite: SceneBackdropComposite = SceneBackdropComposite()
) {
    companion object {
        fun blur(
            sigmaPx: Float,
            hasProgressiveMask: Boolean = false,
            composite: SceneBackdropComposite = SceneBackdropComposite()
        ): SceneBackdropEffect {
            return SceneBackdropEffect(
                operations = listOf(
                    SceneBackdropOperation.Blur(
                        sigmaPx = sigmaPx,
                        hasProgressiveMask = hasProgressiveMask
                    )
                ),
                composite = composite
            )
        }
    }
}

internal data class SceneBackdropComposite(
    val tint: Color = Color.Transparent,
    val noiseAlpha: Float = 0f
) {
    val hasNoise: Boolean
        get() = noiseAlpha > 0f
}

internal sealed interface SceneBackdropOperation {
    data class Blur(
        val sigmaPx: Float,
        val hasProgressiveMask: Boolean = false
    ) : SceneBackdropOperation
}
