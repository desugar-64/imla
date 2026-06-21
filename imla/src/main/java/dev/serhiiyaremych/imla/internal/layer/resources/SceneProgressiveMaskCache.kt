package dev.serhiiyaremych.imla.internal.layer.resources

import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import dev.serhiiyaremych.imla.internal.capture.CanvasTextureCaptureFactory
import dev.serhiiyaremych.imla.internal.capture.CaptureThread
import dev.serhiiyaremych.imla.internal.capture.CapturedLayerFrame
import dev.serhiiyaremych.imla.internal.layer.model.SceneProgressiveMaskKey
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import dev.serhiiyaremych.imla.internal.layer.registry.SceneRegisteredSlot
import kotlin.math.abs

internal class SceneProgressiveMaskCache(
    private val textureCaptureFactory: CanvasTextureCaptureFactory?,
    private val captureThread: CaptureThread
) {
    private val entries = mutableMapOf<SceneSlotId, CachedProgressiveMask>()

    @MainThread
    fun captureChangedMasks(
        slots: List<SceneRegisteredSlot>,
        density: Density,
        layoutDirection: LayoutDirection,
        glKeys: Map<SceneSlotId, SceneProgressiveMaskKey>
    ): SceneProgressiveMaskCaptureResult {
        checkMainThread()
        val maskSlots = slots.mapNotNull { slot ->
            val brush = slot.progressiveMaskBrush
            if (brush != null) {
                ProgressiveMaskSlot(slot = slot, brush = brush)
            } else {
                null
            }
        }
        val liveIds = maskSlots.mapTo(mutableSetOf()) { it.slot.id }
        removeStaleSlots(liveIds)

        val frames = mutableMapOf<SceneSlotId, CapturedLayerFrame>()
        val changedKeys = mutableMapOf<SceneSlotId, SceneProgressiveMaskKey>()
        val availableIds = mutableSetOf<SceneSlotId>()

        maskSlots.forEach { maskSlot ->
            val slot = maskSlot.slot
            val cachedMask = entries.getOrPut(slot.id) {
                CachedProgressiveMask(textureCaptureFactory, captureThread)
            }
            val key = maskSlot.toProgressiveMaskKey(
                size = slot.contentSize,
                density = density,
                layoutDirection = layoutDirection
            )
            val glKey = glKeys[slot.id]
            val frame = cachedMask.captureIfChanged(
                key = key,
                density = density,
                forceCapture = glKey != key
            )
            when {
                frame != null -> {
                    frames[slot.id] = frame
                    changedKeys[slot.id] = key
                    availableIds += slot.id
                }
                glKey == key && cachedMask.hasMask -> {
                    availableIds += slot.id
                }
                else -> Unit
            }
        }

        return SceneProgressiveMaskCaptureResult(
            changedFrames = frames,
            changedKeys = changedKeys,
            availableIds = availableIds
        )
    }

    fun release() {
        entries.values.forEach { it.release() }
        entries.clear()
    }

    private fun removeStaleSlots(liveIds: Set<SceneSlotId>) {
        val removedIds = entries.keys - liveIds
        removedIds.forEach(::removeSlot)
    }

    private fun removeSlot(id: SceneSlotId) {
        entries.remove(id)?.release()
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SceneProgressiveMaskCache must run on the main thread"
        }
    }
}

private data class ProgressiveMaskSlot(
    val slot: SceneRegisteredSlot,
    val brush: Brush
)

private fun ProgressiveMaskSlot.toProgressiveMaskKey(
    size: IntSize,
    density: Density,
    layoutDirection: LayoutDirection
): SceneProgressiveMaskKey {
    return SceneProgressiveMaskKey(
        brush = brush,
        size = size,
        density = density.density,
        fontScale = density.fontScale,
        layoutDirection = layoutDirection
    )
}

internal data class SceneProgressiveMaskCaptureResult(
    val changedFrames: Map<SceneSlotId, CapturedLayerFrame>,
    val changedKeys: Map<SceneSlotId, SceneProgressiveMaskKey>,
    val availableIds: Set<SceneSlotId>
)

private class CachedProgressiveMask(
    private val textureCaptureFactory: CanvasTextureCaptureFactory?,
    private val captureThread: CaptureThread
) {
    private val drawScope = CanvasDrawScope()
    @RequiresApi(Build.VERSION_CODES.Q)
    private val renderNode = RenderNode("SceneProgressiveMask")

    private var renderer: SceneCanvasFrameRenderer? = null
    private var currentSize: IntSize = IntSize.Zero
    private var key: SceneProgressiveMaskKey? = null

    val hasMask: Boolean
        get() = key != null

    fun captureIfChanged(
        key: SceneProgressiveMaskKey,
        density: Density,
        forceCapture: Boolean
    ): CapturedLayerFrame? {
        val frame = if (key.size == IntSize.Zero) {
            null
        } else if (!forceCapture && this.key == key) {
            null
        } else {
            when {
                ensureRenderer(key.size) -> captureMaskFrame(
                    key = key,
                    density = density
                )
                else -> null
            }
        }
        return frame
    }

    fun release() {
        renderer?.requestClose()
        renderer = null
        currentSize = IntSize.Zero
        key = null
    }

    private fun captureMaskFrame(
        key: SceneProgressiveMaskKey,
        density: Density
    ): CapturedLayerFrame? {
        drawBrushToRenderNode(
            brush = key.brush,
            size = key.size,
            density = density,
            layoutDirection = key.layoutDirection
        )
        val frame = requireNotNull(renderer).captureFrame(
            size = key.size,
            timeoutMs = MAIN_THREAD_DRAW_WAIT_TIMEOUT_MS
        ).also { result ->
            if (result == null) {
                Log.w(TAG, "Timed out waiting for progressive mask render")
            }
        }
        if (frame != null) {
            this.key = key
        }
        return frame
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawBrushToRenderNode(
        brush: Brush,
        size: IntSize,
        density: Density,
        layoutDirection: LayoutDirection
    ) {
        val canvas = renderNode.beginRecording()
        try {
            canvas.drawColor(Color.Transparent.toArgb(), PorterDuff.Mode.CLEAR)
            drawScope.draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = Canvas(canvas),
                size = size.toSize()
            ) {
                drawRect(brush)
            }
        } finally {
            renderNode.endRecording()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ensureRenderer(size: IntSize): Boolean {
        val shouldRecreateRenderer = renderer == null || hasMeaningfulSizeChange(size)
        if (shouldRecreateRenderer) {
            renderer?.requestClose()
            renderer = SceneCanvasFrameRenderer.create(
                label = "progressive mask",
                size = size,
                renderNode = renderNode,
                textureCaptureFactory = textureCaptureFactory,
                captureThread = captureThread
            )
            currentSize = size
            key = null
        }
        return renderer != null
    }

    private fun hasMeaningfulSizeChange(newSize: IntSize): Boolean {
        return currentSize == IntSize.Zero ||
                abs(currentSize.width - newSize.width) > 1 ||
                abs(currentSize.height - newSize.height) > 1
    }

    private companion object {
        private const val TAG = "SceneProgressiveMask"
        private const val MAIN_THREAD_DRAW_WAIT_TIMEOUT_MS = 500L
    }
}
