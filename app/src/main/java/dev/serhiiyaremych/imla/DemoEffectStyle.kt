package dev.serhiiyaremych.imla

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

internal data class DemoEffectStyle(
    val offset: Float = 1.3f,
    val passes: Int = 4,
    val tint: Color = Color.Transparent,
    val noiseAlpha: Float = 0.1f,
    val blurOpacity: Float = 1f,
    val algorithm: DemoBlurAlgorithm = DemoBlurAlgorithm.GAUSSIAN,
    val sigma: Float = 8f
) {
    companion object {
        val default: DemoEffectStyle = DemoEffectStyle()
    }
}

internal enum class DemoBlurAlgorithm {
    GAUSSIAN
}

internal fun Modifier.effectLayer(
    style: DemoEffectStyle = DemoEffectStyle.default,
    blurMask: Brush? = null,
    clipShape: Shape = RectangleShape,
    visibleAreaProvider: EffectLayerBoundsProvider? = null,
    zIndex: Float? = null,
    debugName: String? = null
): Modifier {
    return effectLayer {
        if (visibleAreaProvider != null) {
            visualBounds(visibleAreaProvider)
        }
        backdropBlur(
            sigmaPx = style.sigma,
            progressiveMask = blurMask
        )
        tint(style.tint.copy(alpha = style.tint.alpha * style.blurOpacity))
        noise(style.noiseAlpha)
        clip(clipShape)
    }
}
