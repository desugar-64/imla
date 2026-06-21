package dev.serhiiyaremych.imla.internal.render.gl

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.metrics.SceneMetricsFrame
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotDeclaration
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId

internal class GlStore(
    private val threadGuard: SceneGlThreadGuard
) {
    private var currentRoot: SceneRootResource? = null

    private val contentTextures = mutableMapOf<SceneSlotId, SceneSampledTexture>()
    private val maskTextures = mutableMapOf<SceneSlotId, Texture2D>()
    private val clipTextures = mutableMapOf<SceneSlotId, SceneSampledTexture>()

    fun replaceRoot(
        texture: Texture2D,
        rootSize: IntSize,
        rootCaptureDurationNanos: Long,
        frameBudgetNanos: Long,
        metricsFrame: SceneMetricsFrame?,
        slots: List<SceneSlotDeclaration>,
        releaseTexture: (Texture2D) -> Unit
    ) {
        threadGuard.checkCurrentThread()
        currentRoot?.release()
        currentRoot = SceneRootResource(
            texture = texture,
            rootSize = rootSize,
            rootCaptureDurationNanos = rootCaptureDurationNanos,
            frameBudgetNanos = frameBudgetNanos,
            metricsFrame = metricsFrame,
            slots = slots,
            releaseTexture = releaseTexture
        )
    }

    fun pruneSlotsTo(slotIds: Set<SceneSlotId>) {
        threadGuard.checkCurrentThread()
        releaseMissing(contentTextures, slotIds) { it.destroy() }
        releaseMissing(maskTextures, slotIds) { it.destroy() }
        releaseMissing(clipTextures, slotIds) { it.destroy() }
    }

    fun pruneClipSlotsTo(slotIds: Set<SceneSlotId>) {
        threadGuard.checkCurrentThread()
        releaseMissing(clipTextures, slotIds) { it.destroy() }
    }

    fun replaceContent(
        slotId: SceneSlotId,
        texture: SceneSampledTexture
    ) {
        threadGuard.checkCurrentThread()
        contentTextures.remove(slotId)?.destroy()
        contentTextures[slotId] = texture
    }

    fun replaceProgressiveMask(
        slotId: SceneSlotId,
        texture: Texture2D
    ) {
        threadGuard.checkCurrentThread()
        maskTextures.remove(slotId)?.destroy()
        maskTextures[slotId] = texture
    }

    fun replaceClip(
        slotId: SceneSlotId,
        texture: SceneSampledTexture
    ) {
        threadGuard.checkCurrentThread()
        clipTextures.remove(slotId)?.destroy()
        clipTextures[slotId] = texture
    }

    fun frameForDraw(targetSize: IntSize): SceneGlRenderFrame? {
        threadGuard.checkCurrentThread()
        val root = currentRoot ?: return null
        return root.toFrameForDraw(
            targetSize = targetSize,
            contentTextures = contentTextures,
            maskTextures = maskTextures,
            clipTextures = clipTextures
        )
    }

    fun destroy() {
        threadGuard.checkCurrentThread()
        currentRoot?.release()
        currentRoot = null
        contentTextures.values.forEach { it.destroy() }
        contentTextures.clear()
        maskTextures.values.forEach { it.destroy() }
        maskTextures.clear()
        clipTextures.values.forEach { it.destroy() }
        clipTextures.clear()
    }

    private fun <T> releaseMissing(
        textures: MutableMap<SceneSlotId, T>,
        liveIds: Set<SceneSlotId>,
        release: (T) -> Unit
    ) {
        val removedIds = textures.keys - liveIds
        removedIds.forEach { id ->
            textures.remove(id)?.let(release)
        }
    }
}

internal data class SceneSampledTexture(
    val texture: Texture2D,
    val contentSize: IntSize
) {
    val contentUv: Rect
        get() {
            val width = texture.width.toFloat().coerceAtLeast(1f)
            val height = texture.height.toFloat().coerceAtLeast(1f)
            return Rect(
                left = 0f,
                top = 0f,
                right = (contentSize.width / width).coerceIn(0f, 1f),
                bottom = (contentSize.height / height).coerceIn(0f, 1f)
            )
        }

    fun destroy() {
        texture.destroy()
    }
}

internal data class SceneRootResource(
    val texture: Texture2D,
    val rootSize: IntSize,
    val rootCaptureDurationNanos: Long,
    val frameBudgetNanos: Long,
    val metricsFrame: SceneMetricsFrame?,
    val slots: List<SceneSlotDeclaration>,
    private val releaseTexture: (Texture2D) -> Unit
) {
    fun release() {
        releaseTexture(texture)
    }
}

private fun SceneRootResource.toFrameForDraw(
    targetSize: IntSize,
    contentTextures: Map<SceneSlotId, SceneSampledTexture>,
    maskTextures: Map<SceneSlotId, Texture2D>,
    clipTextures: Map<SceneSlotId, SceneSampledTexture>
): SceneGlRenderFrame {
    return SceneGlRenderFrame(
        targetSize = targetSize,
        rootTexture = texture,
        rootSize = rootSize,
        rootTextureFlipYForScreen = texture.coordinateOrigin != CoordinateOrigin.TOP_LEFT,
        rootCaptureDurationNanos = rootCaptureDurationNanos,
        frameBudgetNanos = frameBudgetNanos,
        slots = slots.map { slot ->
            slot.toGlRenderSlot(
                contentTextures = contentTextures,
                maskTextures = maskTextures,
                clipTextures = clipTextures
            )
        },
        metricsFrame = metricsFrame
    )
}

private fun SceneSlotDeclaration.toGlRenderSlot(
    contentTextures: Map<SceneSlotId, SceneSampledTexture>,
    maskTextures: Map<SceneSlotId, Texture2D>,
    clipTextures: Map<SceneSlotId, SceneSampledTexture>
): SceneGlRenderSlot {
    return SceneGlRenderSlot(
        id = id,
        drawOrder = drawOrder,
        geometry = geometry,
        backdrop = backdrop,
        contentTexture = contentTextures[id],
        progressiveMaskTexture = maskTextures[id],
        requiresBackdropClip = requiresBackdropClip,
        requiresContentClip = requiresContentClip,
        clipTexture = clipTextureForDraw(clipTextures)
    )
}

private fun SceneSlotDeclaration.clipTextureForDraw(
    clipTextures: Map<SceneSlotId, SceneSampledTexture>
): SceneSampledTexture? {
    if (!requiresClip) return null
    // The clip mask is imported by a separate per-slot capture flow that can lag
    // the root declaration under heavy re-capture (e.g. continuously rotating
    // slots). A momentarily absent clip is transient: return null and let the
    // pipeline skip this slot for the frame instead of crashing the GL thread.
    return clipTextures[id]
}
