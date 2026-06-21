package dev.serhiiyaremych.imla.internal.modifier

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.imla.EffectLayerBoundsProvider
import dev.serhiiyaremych.imla.internal.layer.model.SceneBackdropComposite
import dev.serhiiyaremych.imla.internal.layer.model.SceneBackdropEffect
import dev.serhiiyaremych.imla.internal.layer.registry.LocalSceneRegistry
import dev.serhiiyaremych.imla.internal.layer.registry.SceneRegistry
import dev.serhiiyaremych.imla.internal.layer.registry.SceneSlotHandle
import dev.serhiiyaremych.imla.internal.render.LocalSceneRenderer
import dev.serhiiyaremych.imla.internal.render.SceneRenderer
import kotlin.math.roundToInt

internal fun Modifier.effectLayerInternal(config: EffectLayerConfig): Modifier {
    return this then SceneSlotElement(config)
}

internal fun Modifier.debugEffectLayerInternal(
    color: Color = Color.Magenta,
    debugBackdropBlurSigmaPx: Float? = null,
    debugProgressiveMaskBrush: Brush? = null,
    debugClipShape: Shape? = null
): Modifier {
    return this then SceneSlotElement(
        EffectLayerConfig(
            backdropBlurSigmaPx = debugBackdropBlurSigmaPx,
            progressiveMaskBrush = debugProgressiveMaskBrush,
            clipShape = debugClipShape ?: RectangleShape,
            debugColor = color
        )
    )
}

internal data class EffectLayerConfig(
    val backdropBlurSigmaPx: Float? = null,
    val backdropBlurRadius: Dp? = null,
    val progressiveMaskBrush: Brush? = null,
    val backdropTint: Color = Color.Transparent,
    val noiseAlpha: Float = 0f,
    val clipShape: Shape = RectangleShape,
    val clipInset: PaddingValues = PaddingValues(0.dp),
    val clipContent: Boolean = false,
    val visualBoundsProvider: EffectLayerBoundsProvider? = null,
    val debugColor: Color = Color.Magenta
)

private class SceneSlotNode(
    private var config: EffectLayerConfig
) : Modifier.Node(),
    DrawModifierNode,
    LayoutAwareModifierNode,
    ObserverModifierNode,
    CompositionLocalConsumerModifierNode {

    private var registry: SceneRegistry? = null
    private var handle: SceneSlotHandle? = null
    private val layerRing = PingPongLayerRing()
    private var coordinates: LayoutCoordinates? = null
    private var size: IntSize = IntSize.Zero
    private var currentView: View? = null
    private var sceneRenderer: SceneRenderer? = null
    private var outOfWindowKickView: View? = null

    // A slot in a separate window (e.g. a ModalBottomSheet overlay) is invisible
    // to the effect group's main-window draw listener, so its content and moving
    // position would only re-composite when the main window happens to redraw.
    // Drive a full capture kick off this window's own draws instead.
    private val outOfWindowDrawListener = ViewTreeObserver.OnDrawListener {
        sceneRenderer?.captureKickRequester?.invoke()
    }

    override fun onAttach() {
        super.onAttach()
        onObservedReadsChanged()
    }

    override fun onObservedReadsChanged() {
        observeReads {
            val nextRegistry = currentValueOf(LocalSceneRegistry)
            if (registry !== nextRegistry) {
                handle?.detach()
                handle = null
                registry = nextRegistry
            }
            val nextView = currentValueOf(LocalView)
            val nextRenderer = currentValueOf(LocalSceneRenderer)
            if (currentView !== nextView || sceneRenderer !== nextRenderer) {
                currentView = nextView
                sceneRenderer = nextRenderer
                refreshOutOfWindowKick()
            }
            updateSlot()
        }
    }

    private fun refreshOutOfWindowKick() {
        removeOutOfWindowKick()
        val view = currentView ?: return
        val hostView = sceneRenderer?.hostView
        // Same-window slots are already pumped by the effect group's draw
        // listener; only subscribe when this slot lives in a different window.
        if (hostView != null && view.rootView === hostView.rootView) return
        view.viewTreeObserver.addOnDrawListener(outOfWindowDrawListener)
        outOfWindowKickView = view
    }

    private fun removeOutOfWindowKick() {
        outOfWindowKickView?.viewTreeObserver?.removeOnDrawListener(outOfWindowDrawListener)
        outOfWindowKickView = null
    }

    override fun ContentDrawScope.draw() {
        if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            recordContentLayer()
        } else {
            drawContent()
        }
    }

    private fun ContentDrawScope.recordContentLayer() {
        if (handle == null) {
            updateSlot()
        }
        val layer = layerRing.acquire(this@SceneSlotNode, handle?.capturingLayer)
        val contentScope = this

        handle?.id?.let { slotId ->
            registry?.recordSlotDraw(slotId)
        }
        layer.record {
            observeReads {
                contentScope.drawContent()
            }
        }
        updateSlot()
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        updateSlot()
    }

    override fun onRemeasured(size: IntSize) {
        this.size = size
        updateSlot()
    }

    override fun onDetach() {
        super.onDetach()
        removeOutOfWindowKick()
        handle?.detach()
        layerRing.releaseAll(this)
        handle = null
        registry = null
        coordinates = null
        size = IntSize.Zero
        currentView = null
        sceneRenderer = null
    }

    fun update(config: EffectLayerConfig) {
        this.config = config
        updateSlot()
    }

    private fun updateSlot() {
        val registry = registry ?: return
        val handle = handle ?: registry.createSlot().also { handle = it }
        val visualBounds = visualBounds()
        handle.update(
            coordinates = coordinates,
            size = size,
            visualBounds = visualBounds,
            color = config.debugColor,
            backdrop = resolveBlurSigmaPx()?.let { sigmaPx ->
                SceneBackdropEffect.blur(
                    sigmaPx = sigmaPx,
                    hasProgressiveMask = config.progressiveMaskBrush != null,
                    composite = SceneBackdropComposite(
                        tint = config.backdropTint,
                        noiseAlpha = config.noiseAlpha
                    )
                )
            },
            progressiveMaskBrush = config.progressiveMaskBrush,
            clipShape = config.clipShape,
            clipInset = config.clipInset,
            clipContent = config.clipContent,
            contentLayer = layerRing.latestRecorded
        )
    }

    private fun resolveBlurSigmaPx(): Float? {
        config.backdropBlurSigmaPx?.let { return it }
        val radius = config.backdropBlurRadius ?: return null
        return with(currentValueOf(LocalDensity)) { radius.toPx() }
    }

    private fun visualBounds(): Rect {
        val fullBounds = size.fullBounds()
        val provider = config.visualBoundsProvider ?: return fullBounds
        val coordinates = coordinates ?: return fullBounds
        var providedBounds: Rect? = null
        observeReads {
            providedBounds = provider.provideVisualBounds(coordinates, size)
        }
        return (providedBounds ?: fullBounds).clampTo(size)
    }
}

private val Rect.hasMeaningfulSize: Boolean
    get() = width > 1f && height > 1f

private fun Rect.contentSize(): IntSize {
    return IntSize(
        width = width.roundToInt().coerceAtLeast(1),
        height = height.roundToInt().coerceAtLeast(1)
    )
}

private fun IntSize.fullBounds(): Rect {
    return Rect(
        left = 0f,
        top = 0f,
        right = width.toFloat(),
        bottom = height.toFloat()
    )
}

private fun Rect.clampTo(size: IntSize): Rect {
    val maxX = size.width.toFloat()
    val maxY = size.height.toFloat()
    val clampedLeft = left.coerceIn(0f, maxX)
    val clampedTop = top.coerceIn(0f, maxY)
    return Rect(
        left = clampedLeft,
        top = clampedTop,
        right = right.coerceIn(clampedLeft, maxX),
        bottom = bottom.coerceIn(clampedTop, maxY)
    )
}

private data class SceneSlotElement(
    private val config: EffectLayerConfig
) : ModifierNodeElement<SceneSlotNode>() {
    override fun create(): SceneSlotNode {
        return SceneSlotNode(config)
    }

    override fun update(node: SceneSlotNode) {
        node.update(config)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "effectLayer"
        properties["backdropBlurSigmaPx"] = config.backdropBlurSigmaPx
        properties["backdropBlurRadius"] = config.backdropBlurRadius
        properties["progressiveMaskBrush"] = config.progressiveMaskBrush
        properties["backdropTint"] = config.backdropTint
        properties["noiseAlpha"] = config.noiseAlpha
        properties["clipShape"] = config.clipShape
        properties["clipInset"] = config.clipInset
        properties["clipContent"] = config.clipContent
        properties["visualBoundsProvider"] = config.visualBoundsProvider
        properties["debugColor"] = config.debugColor
    }
}
