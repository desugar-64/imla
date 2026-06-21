package dev.serhiiyaremych.imla.internal.layer.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.roundToIntRect
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.legacy.buildRenderTransform
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Transform-aware slot geometry in root coordinates.
 *
 * [rootQuad] is the transformed visible content/material quad. [rootBounds] is the axis-aligned
 * root-space patch that covers that quad. For rotated children this means the future backdrop/effect
 * patch stays unrotated and screen-aligned, while content and material drawing use [renderTransform].
 * Rounded corners or alpha masks can make the visible shape smaller than [rootBounds]; that padding
 * is expected and later gives blur kernels room to sample outside the material edge.
 */
internal data class SceneSlotGeometry(
    val localSize: IntSize,
    val localToRoot: Matrix,
    val rootToLocal: Matrix,
    val rootQuad: SceneQuad,
    val rootBounds: IntRect,
    val renderTransform: Mat4
) {
    companion object {
        fun from(
            localSize: IntSize,
            localToRoot: Matrix,
            visualBounds: Rect = localSize.fullBounds()
        ): SceneSlotGeometry {
            val localRect = visualBounds
            val rootToLocal = Matrix(localToRoot.values.copyOf()).also { it.invert() }
            val rootQuad = SceneQuad.from(localToRoot, localRect)
            return SceneSlotGeometry(
                localSize = localRect.size.toIntSize(),
                localToRoot = Matrix(localToRoot.values.copyOf()),
                rootToLocal = rootToLocal,
                rootQuad = rootQuad,
                rootBounds = rootQuad.axisAlignedBounds.roundToIntRect(),
                renderTransform = buildRenderTransform(localToRoot.values, localRect)
            )
        }
    }
}

internal data class SceneQuad(
    val topLeft: Offset,
    val topRight: Offset,
    val bottomRight: Offset,
    val bottomLeft: Offset
) {
    val axisAlignedBounds: Rect
        get() = Rect(
            left = minOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x),
            top = minOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y),
            right = maxOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x),
            bottom = maxOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y)
        )

    /**
     * True when the quad's edges are parallel to the screen axes (translate/scale only, no
     * rotation/skew/perspective). Resize and scroll keep a slot axis-aligned; 3D tilt does not.
     */
    val isAxisAligned: Boolean
        get() = abs(topLeft.y - topRight.y) < AXIS_ALIGN_EPS_PX &&
                abs(bottomLeft.y - bottomRight.y) < AXIS_ALIGN_EPS_PX &&
                abs(topLeft.x - bottomLeft.x) < AXIS_ALIGN_EPS_PX &&
                abs(topRight.x - bottomRight.x) < AXIS_ALIGN_EPS_PX

    companion object {
        fun from(
            matrix: Matrix,
            size: IntSize
        ): SceneQuad {
            return from(matrix, size.fullBounds())
        }

        fun from(
            matrix: Matrix,
            localRect: Rect
        ): SceneQuad {
            return SceneQuad(
                topLeft = matrix.map(Offset(localRect.left, localRect.top)),
                topRight = matrix.map(Offset(localRect.right, localRect.top)),
                bottomRight = matrix.map(Offset(localRect.right, localRect.bottom)),
                bottomLeft = matrix.map(Offset(localRect.left, localRect.bottom))
            )
        }
    }
}

// Sub-pixel tolerance: floating-point matrix maps leave tiny residues on
// genuinely axis-aligned quads, so compare edges with a half-pixel slack.
private const val AXIS_ALIGN_EPS_PX = 0.5f

private fun IntSize.fullBounds(): Rect {
    return Rect(
        left = 0f,
        top = 0f,
        right = width.toFloat(),
        bottom = height.toFloat()
    )
}

private fun Size.toIntSize(): IntSize {
    return IntSize(
        width = width.roundToInt().coerceAtLeast(1),
        height = height.roundToInt().coerceAtLeast(1)
    )
}
