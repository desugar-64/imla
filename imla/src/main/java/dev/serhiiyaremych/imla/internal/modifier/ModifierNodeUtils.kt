package dev.serhiiyaremych.imla.internal.modifier

import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.node.requireGraphicsContext

internal fun Modifier.Node.detachSafe(layer: GraphicsLayer?) {
    layer?.let {
        try {
            requireGraphicsContext().releaseGraphicsLayer(it)
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to release GraphicsLayer", exception)
        }
    }
}

private const val TAG = "ModifierNodeUtils"
