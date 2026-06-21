/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.Density
import androidx.graphics.opengl.GLRenderer
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.legacy.GraphicsLayerTextureFrame
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal class SceneLayerRepository(
    private val captureAccess: SlotContentCaptureAccess
) : SceneLayerResourceRepository {

    constructor(
        density: Density,
        shaderLibrary: ShaderLibrary,
        shaderBinder: ShaderBinder,
        simpleQuadRenderer: SimpleQuadRenderer,
        glRenderer: GLRenderer,
        commandsProvider: () -> RenderCommands,
    ) : this(
        captureAccess = GraphicsLayerSlotAccess(
            density = density,
            shaderLibrary = shaderLibrary,
            shaderBinder = shaderBinder,
            simpleQuadRenderer = simpleQuadRenderer,
            glRenderer = glRenderer,
            commandsProvider = commandsProvider
        )
    )

    private val frames = ConcurrentHashMap<BlurSlotId, GraphicsLayerTextureFrame>()
    private val captureFreshness = HashMap<BlurSlotId, ContentCaptureFreshnessRecord>()
    private val releaseQueue = ConcurrentLinkedQueue<GraphicsLayerTextureFrame>()

    override fun captureSlotContent(frame: CommittedSceneFrame) = trace("SceneLayerRepository#captureSlotContent") {
        doCaptureSlotContent(frame)
    }

    internal fun doCaptureSlotContent(frame: CommittedSceneFrame) {
        val liveIds = frame.slots.mapTo(mutableSetOf()) { it.id }
        removeStaleSlots(liveIds)

        frame.slots.forEach { slot ->
            if (!captureAccess.hasContent(slot)) {
                SceneTraceCounters.slotContentSkippedNoContent()
                captureFreshness.remove(slot.id)
                return@forEach
            }
            if (!captureAccess.hasLayer(slot)) {
                SceneTraceCounters.slotContentSkippedNoLayer()
                captureFreshness.remove(slot.id)
                return@forEach
            }
            val geometry = slot.geometry
            if (geometry == null) {
                SceneTraceCounters.slotContentSkippedNoGeometry()
                captureFreshness.remove(slot.id)
                return@forEach
            }
            val id = slot.id
            val decision = SceneCapturePolicy.slotContentCaptureDecision(slot, frames[id], captureFreshness[id])
            when (decision) {
                SlotContentCaptureDecision.ReuseFresh -> return@forEach
                SlotContentCaptureDecision.ReuseStaleGeometry -> {
                    captureFreshness[id] = captureFreshness.getValue(id).afterGeometryReuse()
                    SceneTraceCounters.slotContentReusedStaleGeometry()
                    return@forEach
                }
                SlotContentCaptureDecision.ForceAfterBudget -> {
                    SceneTraceCounters.slotContentBudgetCaptureForced()
                }
                SlotContentCaptureDecision.Capture -> {
                    captureFreshness.remove(id)
                    SceneTraceCounters.slotContentNormalCaptured()
                }
            }

            val captureSize = SceneCapturePolicy.slotTextureSize(geometry)
            SceneTraceCounters.slotContentCaptured()

            val storedFrameBefore = frames[id]
            captureAccess.capture(id, slot, captureSize, geometry.contentOffset) { newFrame ->
                if (newFrame != null) {
                    val oldFrame = frames.put(id, newFrame)
                    if (oldFrame != null) {
                        releaseQueue.add(oldFrame)
                    }
                }
            }
            val storedFrameAfter = frames[id]
            if (storedFrameAfter != null && storedFrameAfter !== storedFrameBefore) {
                captureFreshness[id] = ContentCaptureFreshnessRecord.afterCapture(slot, geometry)
            }
        }
    }

    override fun textureFor(id: BlurSlotId): Texture2D? {
        return frames[id]?.texture2D
    }

    override fun releasePendingFrames() = trace("SceneLayerRepository#releasePendingFrames") {
        doReleasePendingFrames()
    }

    internal fun doReleasePendingFrames() {
        while (true) {
            val frame = releaseQueue.poll() ?: return
            frame.release()
        }
    }

    override fun destroy() {
        captureAccess.destroyAll()
        doReleasePendingFrames()
        frames.values.forEach { releaseQueue.add(it) }
        frames.clear()
        captureFreshness.clear()
    }

    private fun removeStaleSlots(liveIds: Set<BlurSlotId>) {
        (captureAccess.activeSlotIds() - liveIds).forEach(::removeSlot)
    }

    private fun removeSlot(id: BlurSlotId) {
        SceneTraceCounters.slotContentStaleRemoved()
        captureAccess.destroySlot(id)
        frames.remove(id)?.let { releaseQueue.add(it) }
        captureFreshness.remove(id)
    }
}
