package dev.serhiiyaremych.imla.internal.layer.resources

import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.os.Build
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import dev.serhiiyaremych.imla.internal.capture.CanvasTextureCaptureFactory
import dev.serhiiyaremych.imla.internal.capture.CaptureSizeBuckets
import dev.serhiiyaremych.imla.internal.capture.CaptureThread
import dev.serhiiyaremych.imla.internal.capture.CapturedLayerFrame
import dev.serhiiyaremych.imla.internal.layer.model.SceneClipInsetPx
import dev.serhiiyaremych.imla.internal.layer.model.SceneClipShapeKey
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import dev.serhiiyaremych.imla.internal.layer.registry.SceneRegisteredSlot

internal class SceneClipShapeCache(
    private val textureCaptureFactory: CanvasTextureCaptureFactory?,
    private val captureThread: CaptureThread
) {
    private val entries = mutableMapOf<SceneSlotId, CachedClipShape>()

    @MainThread
    fun captureChangedClips(
        slots: List<SceneRegisteredSlot>,
        density: Density,
        layoutDirection: LayoutDirection,
        glKeys: Map<SceneSlotId, SceneClipShapeKey>
    ): SceneClipShapeCaptureResult {
        checkMainThread()
        val clipSlots = slots.mapNotNull { slot ->
            val shape = slot.clipShape
            if (shape != null && shape != RectangleShape && slot.needsClipTexture) {
                ClipShapeSlot(
                    slot = slot,
                    shape = shape,
                    inset = slot.clipInset
                )
            } else {
                null
            }
        }
        val liveIds = clipSlots.mapTo(mutableSetOf()) { it.slot.id }
        removeStaleSlots(liveIds)

        val frames = mutableMapOf<SceneSlotId, CapturedLayerFrame>()
        val changedKeys = mutableMapOf<SceneSlotId, SceneClipShapeKey>()
        val availableIds = mutableSetOf<SceneSlotId>()

        clipSlots.forEach { clipSlot ->
            val slot = clipSlot.slot
            val cachedClip = entries.getOrPut(slot.id) {
                CachedClipShape(textureCaptureFactory, captureThread)
            }
            val key = clipSlot.toClipShapeKey(
                density = density,
                layoutDirection = layoutDirection,
                size = slot.contentSize
            )
            val glKey = glKeys[slot.id]
            val frame = cachedClip.captureIfChanged(
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
                glKey == key && cachedClip.hasRasterizedClip -> {
                    availableIds += slot.id
                }
                else -> Unit
            }
        }

        return SceneClipShapeCaptureResult(
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
        removedIds.forEach { id -> entries.remove(id)?.release() }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SceneClipShapeCache must run on the main thread"
        }
    }
}

private val SceneRegisteredSlot.needsClipTexture: Boolean
    get() = backdrop != null || clipContent

private data class ClipShapeSlot(
    val slot: SceneRegisteredSlot,
    val shape: Shape,
    val inset: PaddingValues
)

private fun ClipShapeSlot.toClipShapeKey(
    density: Density,
    layoutDirection: LayoutDirection,
    size: IntSize
): SceneClipShapeKey {
    return SceneClipShapeKey(
        shape = shape,
        inset = inset.toPx(
            density = density,
            layoutDirection = layoutDirection,
            size = size
        ),
        size = size,
        density = density.density,
        fontScale = density.fontScale,
        layoutDirection = layoutDirection
    )
}

internal data class SceneClipShapeCaptureResult(
    val changedFrames: Map<SceneSlotId, CapturedLayerFrame>,
    val changedKeys: Map<SceneSlotId, SceneClipShapeKey>,
    val availableIds: Set<SceneSlotId>
)

private class CachedClipShape(
    private val textureCaptureFactory: CanvasTextureCaptureFactory?,
    private val captureThread: CaptureThread
) {
    private val drawScope = CanvasDrawScope()
    @RequiresApi(Build.VERSION_CODES.Q)
    private val renderNode = RenderNode("SceneClipShape")

    private var renderer: SceneCanvasFrameRenderer? = null
    private var bufferSize: IntSize = IntSize.Zero
    private var key: SceneClipShapeKey? = null

    val hasRasterizedClip: Boolean
        get() = key != null

    fun captureIfChanged(
        key: SceneClipShapeKey,
        density: Density,
        forceCapture: Boolean
    ): CapturedLayerFrame? {
        if (key.size == IntSize.Zero) return null
        if (!forceCapture && this.key == key) return null
        check(ensureRenderer(key.size)) {
            "Unable to create scene clip renderer for ${key.size}"
        }
        return captureClipFrame(
            key = key,
            density = density
        )
    }

    fun release() {
        renderer?.requestClose()
        renderer = null
        bufferSize = IntSize.Zero
        key = null
    }

    private fun captureClipFrame(
        key: SceneClipShapeKey,
        density: Density
    ): CapturedLayerFrame {
        // Grow-only buffer: realloc only when a larger clip is needed, never on shrink, so a resize
        // never recreates mid-cycle (recreating dropped the clip from availableIds for one frame ->
        // backdrop drew unclipped = the "pop"). Shape drawn at exact key.size top-left; contentSize
        // crops the sub-rect and keeps frame.size == buffer size (avoids the <=1px glCopyImageSubData crash).
        drawShapeToRenderNode(
            shape = key.shape,
            inset = key.inset,
            size = key.size,
            density = density,
            layoutDirection = key.layoutDirection
        )
        val frame = requireNotNull(renderer).captureFrame(
            size = bufferSize,
            contentSize = key.size,
            timeoutMs = MAIN_THREAD_DRAW_WAIT_TIMEOUT_MS
        )
            ?: error("Timed out waiting for scene clip shape render")
        this.key = key
        return frame
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawShapeToRenderNode(
        shape: Shape,
        inset: SceneClipInsetPx,
        size: IntSize,
        density: Density,
        layoutDirection: LayoutDirection
    ) {
        val canvas = renderNode.beginRecording()
        try {
            canvas.drawColor(Color.Transparent.toArgb(), PorterDuff.Mode.CLEAR)
            val outlineSize = Size(
                width = (size.width - inset.left - inset.right).coerceAtLeast(0f),
                height = (size.height - inset.top - inset.bottom).coerceAtLeast(0f)
            )
            val outline = shape.createOutline(
                size = outlineSize,
                layoutDirection = layoutDirection,
                density = density
            )
            drawScope.draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = Canvas(canvas),
                size = size.toSize()
            ) {
                translate(left = inset.left, top = inset.top) {
                    drawOutline(outline = outline, color = Color.White)
                }
            }
        } finally {
            renderNode.endRecording()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ensureRenderer(contentSize: IntSize): Boolean {
        // Bucket the requested size so a resize ramp reallocates only at coarse boundaries instead
        // of on every 1px growth. The unbucketed grow recreated ~39 times across the first expand,
        // and each recreation dropped the clip for a frame (backdrop drawn unclipped = a "pop").
        // The shape is still drawn at the exact key.size, so corners stay correct; only the buffer
        // is coarsened. The grow-only contract holds: a smaller clip reuses the larger buffer.
        val bucketed = CaptureSizeBuckets.bucket(contentSize)
        val needsGrow = renderer == null ||
            bucketed.width > bufferSize.width ||
            bucketed.height > bufferSize.height
        if (needsGrow) {
            val grown = IntSize(
                width = maxOf(bucketed.width, bufferSize.width),
                height = maxOf(bucketed.height, bufferSize.height)
            )
            renderer?.requestClose()
            renderer = SceneCanvasFrameRenderer.create(
                label = "clip shape",
                size = grown,
                renderNode = renderNode,
                textureCaptureFactory = textureCaptureFactory,
                captureThread = captureThread
            )
            bufferSize = grown
            key = null
        }
        return renderer != null
    }

    private companion object {
        private const val MAIN_THREAD_DRAW_WAIT_TIMEOUT_MS = 500L
    }
}

private fun PaddingValues.toPx(
    density: Density,
    layoutDirection: LayoutDirection,
    size: IntSize
): SceneClipInsetPx {
    val left = calculateLeftPadding(layoutDirection).toNonNegativePx(density)
    val top = calculateTopPadding().toNonNegativePx(density)
    val right = calculateRightPadding(layoutDirection).toNonNegativePx(density)
    val bottom = calculateBottomPadding().toNonNegativePx(density)
    val horizontalScale = fitScale(left + right, size.width.toFloat())
    val verticalScale = fitScale(top + bottom, size.height.toFloat())
    return SceneClipInsetPx(
        left = left * horizontalScale,
        top = top * verticalScale,
        right = right * horizontalScale,
        bottom = bottom * verticalScale
    )
}

private fun Dp.toNonNegativePx(density: Density): Float {
    return (value * density.density).coerceAtLeast(0f)
}

private fun fitScale(totalInset: Float, available: Float): Float {
    if (totalInset <= 0f || available <= 0f || totalInset <= available) {
        return 1f
    }
    return available / totalInset
}
