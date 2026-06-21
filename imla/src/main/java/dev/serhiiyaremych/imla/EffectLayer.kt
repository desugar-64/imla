package dev.serhiiyaremych.imla

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.imla.internal.modifier.EffectLayerConfig
import dev.serhiiyaremych.imla.internal.modifier.effectGroupInternal
import dev.serhiiyaremych.imla.internal.modifier.effectLayerInternal

/**
 * Marks this subtree as one Imla effect group.
 *
 * The group is captured as backdrop content and defines the coordinate space used by descendant
 * [effectLayer] modifiers. Imla handles this capture as part of its rendering pipeline; callers
 * should not treat it as generic post-processing over the whole app window.
 */
public fun Modifier.effectGroup(): Modifier {
    return effectGroupInternal()
}

/**
 * Marks this composable as an Imla-managed foreground layer with rendering effects.
 */
public fun Modifier.effectLayer(
    configure: EffectLayerScope.() -> Unit
): Modifier {
    val config = EffectLayerScope().apply(configure).toConfig()
    return effectLayerInternal(config)
}

@DslMarker
public annotation class EffectLayerDsl

public fun interface EffectLayerBoundsProvider {
    public fun provideVisualBounds(
        coordinates: LayoutCoordinates,
        layoutSize: IntSize
    ): Rect?
}

@EffectLayerDsl
public class EffectLayerScope internal constructor() {
    private var backdropBlurSigmaPx: Float? = null
    private var backdropBlurRadius: Dp? = null
    private var progressiveMaskBrush: Brush? = null
    private var backdropTint: Color = Color.Transparent
    private var noiseAlpha: Float = 0f
    private var clipShape: Shape = RectangleShape
    private var clipInset: PaddingValues = PaddingValues(0.dp)
    private var clipContent: Boolean = false
    private var visualBoundsProvider: EffectLayerBoundsProvider? = null

    public fun backdropBlur(
        sigmaPx: Float,
        progressiveMask: Brush? = null
    ) {
        checkNoBackdropBlur()
        backdropBlurSigmaPx = sigmaPx
        progressiveMaskBrush = progressiveMask
    }

    /**
     * Density-independent counterpart of [backdropBlur]. [radius] is the Gaussian blur sigma
     * expressed in dp; it is converted to pixels at draw time. Prefer this overload in UI code so
     * blur strength stays consistent across screen densities.
     */
    public fun backdropBlur(
        radius: Dp,
        progressiveMask: Brush? = null
    ) {
        checkNoBackdropBlur()
        backdropBlurRadius = radius
        progressiveMaskBrush = progressiveMask
    }

    private fun checkNoBackdropBlur() {
        check(backdropBlurSigmaPx == null && backdropBlurRadius == null) {
            "Effect layer currently supports only one backdropBlur declaration"
        }
    }

    public fun tint(color: Color) {
        backdropTint = color
    }

    public fun noise(alpha: Float) {
        noiseAlpha = alpha.coerceIn(0f, 1f)
    }

    public fun clip(
        shape: Shape,
        inset: PaddingValues = PaddingValues(0.dp),
        clipContent: Boolean = false
    ) {
        clipShape = shape
        clipInset = inset
        this.clipContent = clipContent
    }

    public fun visualBounds(provider: EffectLayerBoundsProvider) {
        visualBoundsProvider = provider
    }

    internal fun toConfig(): EffectLayerConfig {
        return EffectLayerConfig(
            backdropBlurSigmaPx = backdropBlurSigmaPx,
            backdropBlurRadius = backdropBlurRadius,
            progressiveMaskBrush = progressiveMaskBrush,
            backdropTint = backdropTint,
            noiseAlpha = noiseAlpha,
            clipShape = clipShape,
            clipInset = clipInset,
            clipContent = clipContent,
            visualBoundsProvider = visualBoundsProvider
        )
    }
}
