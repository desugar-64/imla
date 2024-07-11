/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.modifier

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.LocalView
import dev.serhiiyaremych.imla.ext.logd
import dev.serhiiyaremych.imla.uirenderer.UiLayerRenderer

public fun Modifier.blurSource(uiLayerRenderer: UiLayerRenderer): Modifier {
    return this then ImlaSourceElement(uiLayerRenderer)
}

internal class ImlaSourceNode(
    internal var uiLayerRenderer: UiLayerRenderer
) : Modifier.Node(), DrawModifierNode, ObserverModifierNode, CompositionLocalConsumerModifierNode {

    private var currentView: View? by mutableStateOf(null)

    @Suppress("ObjectLiteralToLambda")
    private val onDrawListener = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            uiLayerRenderer.onUiLayerUpdated()
            return true
        }
    }

    override fun onAttach() {
        super.onAttach()
        logd(TAG, "onAttach")
        onObservedReadsChanged()
    }

    private fun subscribeOnDrawListener() {
        currentView?.viewTreeObserver?.addOnPreDrawListener(onDrawListener)
    }

    private fun removeOnDrawListener() {
        currentView?.viewTreeObserver?.removeOnPreDrawListener(onDrawListener)
    }

    override fun onObservedReadsChanged() {
        observeReads {
            currentView = currentValueOf(LocalView)
            subscribeOnDrawListener()
        }
    }

    override fun onDetach() {
        super.onDetach()
        logd(TAG, "onDetach")
        removeOnDrawListener()
    }

    override fun ContentDrawScope.draw() {
        uiLayerRenderer.recordCanvas { this@draw.drawContent() }
        drawLayer(uiLayerRenderer.renderableLayer.graphicsLayer)
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