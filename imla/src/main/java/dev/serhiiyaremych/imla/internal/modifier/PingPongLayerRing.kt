package dev.serhiiyaremych.imla.internal.modifier

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.node.requireGraphicsContext

/**
 * A small ring of [GraphicsLayer]s that hands recording the main thread a layer
 * the capture thread is not currently reading.
 *
 * The main thread re-records a layer's `RenderNode` every frame while the capture
 * thread reads it via `HardwareRenderer.syncAndDraw`. Recording into the in-flight
 * instance is an unguarded concurrent `RenderNode` access that crashes, so
 * [acquire] always returns a layer other than the one a capture is reading.
 * Captures are single-in-flight, so at most one layer is ever busy and two
 * instances always leave one free to record into.
 */
internal class PingPongLayerRing(size: Int = 2) {
    private val layers = arrayOfNulls<GraphicsLayer>(size)
    private var latest: GraphicsLayer? = null

    /** The layer most recently returned by [acquire]; the one to hand to capture. */
    val latestRecorded: GraphicsLayer?
        get() = latest

    /**
     * Returns a layer [busyLayer] is not, reusing the latest while it stays free so
     * unchanged content keeps a stable instance; only switches when it goes in flight.
     */
    fun acquire(node: Modifier.Node, busyLayer: GraphicsLayer?): GraphicsLayer {
        latest?.let { reuse ->
            if (reuse !== busyLayer) return reuse
        }
        layers.firstOrNull { it != null && it !== busyLayer }?.let { free ->
            latest = free
            return free
        }
        val freeIndex = layers.indexOfFirst { it == null }
        check(freeIndex >= 0) { "Ping-pong layer ring exhausted: every layer is in flight" }
        return node.requireGraphicsContext().createGraphicsLayer().also {
            layers[freeIndex] = it
            latest = it
        }
    }

    fun releaseAll(node: Modifier.Node) {
        layers.forEachIndexed { index, layer ->
            node.detachSafe(layer)
            layers[index] = null
        }
        latest = null
    }
}
