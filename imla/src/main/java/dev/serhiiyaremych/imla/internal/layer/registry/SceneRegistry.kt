package dev.serhiiyaremych.imla.internal.layer.registry

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.imla.internal.layer.model.SceneBackdropEffect
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotGeometry
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import kotlin.math.roundToInt

internal val LocalSceneRegistry = staticCompositionLocalOf<SceneRegistry?> { null }

internal class SceneRegistry {
    private val slots = LinkedHashMap<SceneSlotId, RegisteredSceneSlot>()
    private var nextSlotId = 0L
    private var nextDrawOrder = 0

    fun createSlot(): SceneSlotHandle {
        val id = SceneSlotId(nextSlotId++)
        slots[id] = RegisteredSceneSlot(id)
        return SceneSlotHandle(id, this)
    }

    fun resetDrawOrder() {
        nextDrawOrder = 0
    }

    fun recordSlotDraw(id: SceneSlotId) {
        slots[id]?.drawOrder = nextDrawOrder++
    }

    fun snapshot(rootCoordinates: LayoutCoordinates?): SceneRegistrySnapshot {
        if (rootCoordinates == null || !rootCoordinates.isAttached) {
            return SceneRegistrySnapshot()
        }
        return SceneRegistrySnapshot(
            slots = slots.values.mapNotNull { slot ->
                slot.toDeclaration(rootCoordinates)
            }
        )
    }

    fun updateSlot(
        id: SceneSlotId,
        coordinates: LayoutCoordinates?,
        size: IntSize,
        visualBounds: Rect,
        color: Color,
        backdrop: SceneBackdropEffect?,
        progressiveMaskBrush: Brush?,
        clipShape: Shape?,
        clipInset: PaddingValues,
        clipContent: Boolean,
        contentLayer: GraphicsLayer?
    ) {
        slots[id]?.let { slot ->
            slot.updatedNanos = System.nanoTime()
            slot.coordinates = coordinates
            slot.size = size
            slot.visualBounds = visualBounds
            slot.color = color
            slot.backdrop = backdrop
            slot.progressiveMaskBrush = progressiveMaskBrush
            slot.clipShape = clipShape
            slot.clipInset = clipInset
            slot.clipContent = clipContent
            slot.contentLayer = contentLayer
            slot.dirty = true
        }
    }

    fun detachSlot(id: SceneSlotId) {
        slots.remove(id)
    }

    // The renderer publishes which content layer a slot's capture is currently
    // reading; the slot modifier reads it back so it never re-records that
    // instance. Both sides touch this on the main thread.
    fun setSlotCapturing(id: SceneSlotId, layer: GraphicsLayer?) {
        slots[id]?.captureState?.capturingLayer = layer
    }

    fun slotCapturingLayer(id: SceneSlotId): GraphicsLayer? {
        return slots[id]?.captureState?.capturingLayer
    }

    private class RegisteredSceneSlot(
        val id: SceneSlotId
    ) {
        var coordinates: LayoutCoordinates? = null
        var size: IntSize = IntSize.Zero
        var visualBounds: Rect = Rect.Zero
        var color: Color = Color.Magenta
        var backdrop: SceneBackdropEffect? = null
        var progressiveMaskBrush: Brush? = null
        var clipShape: Shape? = null
        var clipInset: PaddingValues = PaddingValues(0.dp)
        var clipContent: Boolean = false
        var contentLayer: GraphicsLayer? = null
        var drawOrder: Int = id.value.toInt()
        var dirty: Boolean = true
        var updatedNanos: Long = System.nanoTime()
        val captureState: SlotCaptureState = SlotCaptureState()

        fun toDeclaration(rootCoordinates: LayoutCoordinates): SceneRegisteredSlot? {
            val slotCoordinates = coordinates
            if (slotCoordinates == null || !slotCoordinates.isAttached || size == IntSize.Zero) {
                return null
            }
            val localToRoot = rootCoordinates.localToRootMatrix(slotCoordinates) ?: return null
            if (!visualBounds.hasMeaningfulSize) return null
            val geometry = SceneSlotGeometry.from(
                localSize = size,
                localToRoot = localToRoot,
                visualBounds = visualBounds
            )
            if (geometry.rootBounds.isEmpty) return null
            val contentSize = visualBounds.contentSize()
            return SceneRegisteredSlot(
                id = id,
                drawOrder = drawOrder,
                geometry = geometry,
                debugColor = color,
                backdrop = backdrop,
                progressiveMaskBrush = progressiveMaskBrush,
                clipShape = clipShape,
                clipInset = clipInset,
                clipContent = clipContent,
                contentLayer = contentLayer,
                layoutSize = size,
                contentSize = contentSize,
                contentOffset = Offset(visualBounds.left, visualBounds.top),
                updatedNanos = updatedNanos,
                captureState = captureState
            )
        }
    }
}

internal data class SceneRegistrySnapshot(
    val slots: List<SceneRegisteredSlot> = emptyList()
)

internal data class SceneRegisteredSlot(
    val id: SceneSlotId,
    val drawOrder: Int,
    val geometry: SceneSlotGeometry,
    val debugColor: Color,
    val backdrop: SceneBackdropEffect?,
    val progressiveMaskBrush: Brush?,
    val clipShape: Shape?,
    val clipInset: PaddingValues,
    val clipContent: Boolean,
    val contentLayer: GraphicsLayer?,
    val layoutSize: IntSize,
    val contentSize: IntSize,
    val contentOffset: Offset,
    val updatedNanos: Long,
    val captureState: SlotCaptureState
)

// Shared between a slot's registry record and every snapshot copy so the
// renderer (producer of the capturing layer) and the slot modifier (consumer)
// observe the same value. Confined to the main thread.
internal class SlotCaptureState {
    var capturingLayer: GraphicsLayer? = null
}

internal class SceneSlotHandle internal constructor(
    val id: SceneSlotId,
    private val registry: SceneRegistry
) {
    val capturingLayer: GraphicsLayer?
        get() = registry.slotCapturingLayer(id)

    fun update(
        coordinates: LayoutCoordinates?,
        size: IntSize,
        visualBounds: Rect,
        color: Color,
        backdrop: SceneBackdropEffect?,
        progressiveMaskBrush: Brush?,
        clipShape: Shape?,
        clipInset: PaddingValues,
        clipContent: Boolean,
        contentLayer: GraphicsLayer?
    ) {
        registry.updateSlot(
            id = id,
            coordinates = coordinates,
            size = size,
            visualBounds = visualBounds,
            color = color,
            backdrop = backdrop,
            progressiveMaskBrush = progressiveMaskBrush,
            clipShape = clipShape,
            clipInset = clipInset,
            clipContent = clipContent,
            contentLayer = contentLayer
        )
    }

    fun detach() {
        registry.detachSlot(id)
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

private fun LayoutCoordinates.localToRootMatrix(source: LayoutCoordinates): Matrix? {
    val directMatrix = Matrix()
    try {
        transformFrom(source, directMatrix)
        return directMatrix
    } catch (_: IllegalArgumentException) {
    }

    return localToRootMatrixFromScreen(source) ?: localToRootMatrixFromWindow(source)
}

private fun LayoutCoordinates.localToRootMatrixFromScreen(source: LayoutCoordinates): Matrix? {
    val origin = screenToLocal(source.localToScreen(Offset.Zero))
    val xUnit = screenToLocal(source.localToScreen(Offset(1f, 0f)))
    val yUnit = screenToLocal(source.localToScreen(Offset(0f, 1f)))
    if (!origin.isSpecified || !xUnit.isSpecified || !yUnit.isSpecified) return null
    return affineMatrix(origin, xUnit, yUnit)
}

private fun LayoutCoordinates.localToRootMatrixFromWindow(source: LayoutCoordinates): Matrix {
    val origin = windowToLocal(source.localToWindow(Offset.Zero))
    val xUnit = windowToLocal(source.localToWindow(Offset(1f, 0f)))
    val yUnit = windowToLocal(source.localToWindow(Offset(0f, 1f)))
    return affineMatrix(origin, xUnit, yUnit)
}

private fun affineMatrix(origin: Offset, xUnit: Offset, yUnit: Offset): Matrix {
    return Matrix().also { matrix ->
        val values = matrix.values
        values[0] = xUnit.x - origin.x
        values[1] = xUnit.y - origin.y
        values[4] = yUnit.x - origin.x
        values[5] = yUnit.y - origin.y
        values[12] = origin.x
        values[13] = origin.y
    }
}
