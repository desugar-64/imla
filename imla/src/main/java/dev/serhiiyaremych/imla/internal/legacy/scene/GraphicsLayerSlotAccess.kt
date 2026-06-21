/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.graphics.opengl.GLRenderer
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.legacy.ContentCaptureDelegate
import dev.serhiiyaremych.imla.internal.legacy.GraphicsLayerRenderer
import dev.serhiiyaremych.imla.internal.legacy.GraphicsLayerTextureFrame
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer

internal class GraphicsLayerSlotAccess(
    private val density: Density,
    private val shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder,
    private val simpleQuadRenderer: SimpleQuadRenderer,
    private val glRenderer: GLRenderer,
    private val commandsProvider: () -> RenderCommands,
) : SlotContentCaptureAccess {

    private val renderers = LinkedHashMap<BlurSlotId, ContentCaptureDelegate>()

    override fun hasContent(slot: BlurSlotRecord): Boolean = slot.content != null

    override fun hasLayer(slot: BlurSlotRecord): Boolean = slot.content?.layer != null

    override fun capture(
        slotId: BlurSlotId,
        slot: BlurSlotRecord,
        size: IntSize,
        contentOffset: Offset,
        onCaptured: (GraphicsLayerTextureFrame?) -> Unit
    ) {
        val layer = slot.content?.layer ?: return
        renderers.getOrPut(slotId) {
            GraphicsLayerRenderer(
                density = density,
                shaderLibrary = shaderLibrary,
                shaderBinder = shaderBinder,
                simpleQuadRenderer = simpleQuadRenderer,
                onRenderComplete = onCaptured,
                glRenderer = glRenderer,
                commandsProvider = commandsProvider
            )
        }.renderGraphicsLayer(
            graphicsLayer = layer,
            size = size,
            contentOffset = contentOffset
        )
    }

    override fun activeSlotIds(): Set<BlurSlotId> = renderers.keys

    override fun destroySlot(slotId: BlurSlotId) {
        renderers.remove(slotId)?.destroy()
    }

    override fun destroyAll() {
        renderers.values.forEach { it.destroy() }
        renderers.clear()
    }
}
