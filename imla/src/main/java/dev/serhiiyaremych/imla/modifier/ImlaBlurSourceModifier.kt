/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.modifier

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import dev.serhiiyaremych.imla.ext.logd
import dev.serhiiyaremych.imla.uirenderer.UiLayerRenderer

public fun Modifier.blurSource(uiLayerRenderer: UiLayerRenderer): Modifier {
    return this then ImlaSourceElement(uiLayerRenderer)
}

internal class ImlaSourceNode(
    internal var uiLayerRenderer: UiLayerRenderer
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {

    override fun onAttach() {
        super.onAttach()
        logd(
            TAG,
            "onAttach"
        )

    }

    override fun onDetach() {
        super.onDetach()
        logd(
            TAG,
            "onDetach"
        )
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        super.onPlaced(coordinates)
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        uiLayerRenderer.recordCanvas { this@draw.drawContent() }
        uiLayerRenderer.onUiLayerUpdated()
    }

    companion object {
        private const val TAG = "ImlaNode"
    }
}

internal class ImlaSourceElement(
    private val uiLayerRenderer: UiLayerRenderer
) : ModifierNodeElement<ImlaSourceNode>() {
    override fun create(): ImlaSourceNode {
        return ImlaSourceNode(uiLayerRenderer)
    }

    override fun update(node: ImlaSourceNode) {
        node.uiLayerRenderer = uiLayerRenderer
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImlaSourceElement

        return uiLayerRenderer == other.uiLayerRenderer
    }

    override fun hashCode(): Int {
        return uiLayerRenderer.hashCode()
    }
}