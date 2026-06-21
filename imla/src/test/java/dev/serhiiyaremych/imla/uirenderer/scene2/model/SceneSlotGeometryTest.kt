package dev.serhiiyaremych.imla.internal.layer.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneSlotGeometryTest {
    @Test
    fun fullBoundsUseMeasuredLocalSize() {
        val geometry = SceneSlotGeometry.from(
            localSize = IntSize(100, 80),
            localToRoot = Matrix()
        )

        assertEquals(IntSize(100, 80), geometry.localSize)
        assertEquals(Offset(0f, 0f), geometry.rootQuad.topLeft)
        assertEquals(Offset(100f, 80f), geometry.rootQuad.bottomRight)
    }

    @Test
    fun visualBoundsOverrideLocalQuadAndContentSize() {
        val geometry = SceneSlotGeometry.from(
            localSize = IntSize(100, 240),
            localToRoot = Matrix(),
            visualBounds = Rect(
                left = 0f,
                top = 140f,
                right = 100f,
                bottom = 240f
            )
        )

        assertEquals(IntSize(100, 100), geometry.localSize)
        assertEquals(Offset(0f, 140f), geometry.rootQuad.topLeft)
        assertEquals(Offset(100f, 240f), geometry.rootQuad.bottomRight)
    }
}
