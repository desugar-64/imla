/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.RenderQuad

/**
 * Composites one captured slot foreground texture over the already-composited backdrop.
 *
 * SceneGlRenderer invokes this after backdrop composite while the scene framebuffer remains bound.
 * The pass must run on the GL thread and owns only the content draw boundary; it does not own scene
 * state, capture sources, texture lifetime, clipping, or session lifecycle.
 */
internal class SceneContentCompositePass(
    private val quadBatchRenderer: QuadBatchRenderer
) : SceneContentCompositeExecutor {
    override fun execute(
        slot: SceneSlotPlan,
        transform: Mat4,
        targetSize: IntSize
    ) = trace("SceneContentCompositePass#execute") {
        val texture = slot.contentTexture ?: return@trace
        SceneTraceCounters.contentCompositePass()
        val area = slot.area
        quadBatchRenderer.begin(
            targetSize = targetSize,
            enableBlending = true,
            traceLabel = "QuadBatchRenderer#sceneContentComposite"
        )
        quadBatchRenderer.submit(
            RenderQuad(
                id = "${slot.id.value}_content",
                center = Offset(
                    x = area.left + area.width / 2f,
                    y = area.top + area.height / 2f
                ),
                size = area.size,
                uv = Rect(0f, 0f, 1f, 1f),
                texture = texture,
                alpha = 1f,
                maskValue = 0f,
                flipTexture = texture.flipTexture,
                transform = transform
            )
        )
        quadBatchRenderer.flush()
    }
}
