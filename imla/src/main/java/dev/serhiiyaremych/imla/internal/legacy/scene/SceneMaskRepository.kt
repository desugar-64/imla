/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.graphics.opengl.GLRenderer
import androidx.tracing.trace as androidTrace
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.legacy.GraphicsLayerTextureFrame
import dev.serhiiyaremych.imla.internal.legacy.MaskTextureRenderer
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal class SceneMaskRepository(
    private val rendererFactory: SceneMaskCaptureRendererFactory,
    private val tracer: SceneMaskRepositoryTracer = AndroidSceneMaskRepositoryTracer
) : SceneMaskResourceRepository {
    constructor(
        density: Density,
        shaderLibrary: ShaderLibrary,
        shaderBinder: ShaderBinder,
        simpleQuadRenderer: SimpleQuadRenderer,
        glRenderer: GLRenderer,
        commandsProvider: () -> RenderCommands
    ) : this(
        rendererFactory = SceneMaskCaptureRendererFactory { onRenderComplete ->
            SceneMaskTextureRenderer(
                renderer = MaskTextureRenderer(
                    density = density,
                    shaderLibrary = shaderLibrary,
                    shaderBinder = shaderBinder,
                    simpleQuadRenderer = simpleQuadRenderer,
                    onRenderComplete = onRenderComplete,
                    glRenderer = glRenderer,
                    commandsProvider = commandsProvider
                )
            )
        }
    )

    private val maskRenderers = LinkedHashMap<BlurSlotId, SceneMaskCaptureRenderer>()
    private val clipRenderers = LinkedHashMap<BlurSlotId, SceneMaskCaptureRenderer>()
    private val maskFrames = ConcurrentHashMap<BlurSlotId, GraphicsLayerTextureFrame>()
    private val clipFrames = ConcurrentHashMap<BlurSlotId, GraphicsLayerTextureFrame>()
    private val releaseQueue = ConcurrentLinkedQueue<GraphicsLayerTextureFrame>()

    override fun captureSlotMasks(frame: CommittedSceneFrame) = tracer.trace("SceneMaskRepository#captureSlotMasks") {
        val liveIds = frame.slots.mapTo(mutableSetOf()) { it.id }
        removeStaleSlots(liveIds)

        frame.slots.forEach { slot ->
            val style = slot.style
            if (style.blurMask != null) {
                val geometry = slot.geometry
                if (geometry != null) {
                    val size = SceneCapturePolicy.slotTextureSize(geometry)
                    if (SceneCapturePolicy.shouldCaptureBlurMask(slot, maskFrames[slot.id])) {
                        SceneTraceCounters.maskCaptured()
                        maskRendererFor(slot.id).renderMask(
                            brush = style.blurMask,
                            shape = RectangleShape,
                            size = size
                        )
                    }
                }
            } else {
                removeMask(slot.id)
            }

            if (style.clipShape != RectangleShape) {
                val geometry = slot.geometry
                if (geometry != null) {
                    val size = SceneCapturePolicy.slotTextureSize(geometry)
                    if (SceneCapturePolicy.shouldCaptureClipMask(slot, clipFrames[slot.id])) {
                        SceneTraceCounters.clipCaptured()
                        clipRendererFor(slot.id).renderMask(
                            brush = null,
                            shape = style.clipShape,
                            size = size
                        )
                    }
                }
            } else {
                removeClip(slot.id)
            }
        }
    }

    override fun maskTextureFor(id: BlurSlotId): Texture2D? {
        return maskFrames[id]?.texture2D
    }

    override fun clipTextureFor(id: BlurSlotId): Texture2D? {
        return clipFrames[id]?.texture2D
    }

    override fun releasePendingFrames() = tracer.trace("SceneMaskRepository#releasePendingFrames") {
        while (true) {
            val frame = releaseQueue.poll() ?: break
            frame.release()
        }
    }

    override fun destroy() {
        maskRenderers.values.forEach { it.destroy() }
        clipRenderers.values.forEach { it.destroy() }
        maskRenderers.clear()
        clipRenderers.clear()
        maskFrames.values.forEach { releaseQueue.add(it) }
        clipFrames.values.forEach { releaseQueue.add(it) }
        maskFrames.clear()
        clipFrames.clear()
    }

    private fun maskRendererFor(id: BlurSlotId): SceneMaskCaptureRenderer {
        return maskRenderers.getOrPut(id) {
            createRenderer { newFrame -> updateFrame(maskFrames, id, newFrame) }
        }
    }

    private fun clipRendererFor(id: BlurSlotId): SceneMaskCaptureRenderer {
        return clipRenderers.getOrPut(id) {
            createRenderer { newFrame -> updateFrame(clipFrames, id, newFrame) }
        }
    }

    private fun createRenderer(
        onRenderComplete: (GraphicsLayerTextureFrame?) -> Unit
    ): SceneMaskCaptureRenderer {
        return rendererFactory.create(onRenderComplete)
    }

    private fun updateFrame(
        frames: ConcurrentHashMap<BlurSlotId, GraphicsLayerTextureFrame>,
        id: BlurSlotId,
        newFrame: GraphicsLayerTextureFrame?
    ) {
        if (newFrame == null) return
        val oldFrame = frames.put(id, newFrame)
        if (oldFrame != null && oldFrame !== newFrame) {
            releaseQueue.add(oldFrame)
        }
    }

    private fun removeStaleSlots(liveIds: Set<BlurSlotId>) {
        val staleMaskIds = maskRenderers.keys + maskFrames.keys - liveIds
        staleMaskIds.forEach(::removeMask)
        val staleClipIds = clipRenderers.keys + clipFrames.keys - liveIds
        staleClipIds.forEach(::removeClip)
    }

    private fun removeMask(id: BlurSlotId) {
        val renderer = maskRenderers.remove(id)
        val frame = maskFrames.remove(id)
        if (renderer != null || frame != null) {
            SceneTraceCounters.maskRemoved()
        }
        renderer?.destroy()
        frame?.let { releaseQueue.add(it) }
    }

    private fun removeClip(id: BlurSlotId) {
        val renderer = clipRenderers.remove(id)
        val frame = clipFrames.remove(id)
        if (renderer != null || frame != null) {
            SceneTraceCounters.clipRemoved()
        }
        renderer?.destroy()
        frame?.let { releaseQueue.add(it) }
    }
}

internal fun interface SceneMaskCaptureRendererFactory {
    fun create(onRenderComplete: (GraphicsLayerTextureFrame?) -> Unit): SceneMaskCaptureRenderer
}

internal interface SceneMaskCaptureRenderer {
    fun renderMask(brush: Brush?, shape: Shape = RectangleShape, size: IntSize)

    fun destroy()
}

internal interface SceneMaskRepositoryTracer {
    fun <T> trace(label: String, block: () -> T): T
}

private object AndroidSceneMaskRepositoryTracer : SceneMaskRepositoryTracer {
    override fun <T> trace(label: String, block: () -> T): T {
        return androidTrace(label, block)
    }
}

private class SceneMaskTextureRenderer(
    private val renderer: MaskTextureRenderer
) : SceneMaskCaptureRenderer {
    override fun renderMask(brush: Brush?, shape: Shape, size: IntSize) {
        renderer.renderMask(brush = brush, shape = shape, size = size)
    }

    override fun destroy() {
        renderer.destroy()
    }
}
