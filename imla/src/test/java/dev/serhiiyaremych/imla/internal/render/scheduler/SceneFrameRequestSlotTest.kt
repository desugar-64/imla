package dev.serhiiyaremych.imla.internal.render.scheduler

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotGeometry
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import dev.serhiiyaremych.imla.internal.layer.model.UiSceneCapture
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.gl.SceneGlRenderFrame
import dev.serhiiyaremych.imla.internal.render.gl.SceneGlRenderSlot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.Buffer

class SceneFrameRequestSlotTest {
    @Test
    fun captureAndGlFrameExposeOneSlotProperty() {
        assertEquals("slot", UiSceneCapture::slot.name)
        assertEquals("slot", SceneGlRenderFrame::slot.name)
        assertEquals("uiCapture", SceneFrameRequest::uiCapture.name)
    }

    @Test
    fun glFrameHoldsASingleSlot() {
        val drawn = SceneSlotId(1L)
        val size = IntSize(64, 64)
        val texture = FakeTexture(size)
        val frame = SceneGlRenderFrame(
            targetSize = size,
            rootTexture = texture,
            rootSize = size,
            rootTextureFlipYForScreen = false,
            rootCaptureDurationNanos = 0L,
            frameBudgetNanos = 0L,
            slot = SceneGlRenderSlot(
                id = drawn,
                drawOrder = 0,
                geometry = SceneSlotGeometry.from(size, Matrix()),
                backdrop = null,
                contentTexture = null,
                progressiveMaskTexture = null,
                requiresBackdropClip = false,
                requiresContentClip = false,
                clipTexture = null
            ),
            metricsFrame = null
        )

        assertEquals(drawn, frame.slot.id)
    }

    private class FakeTexture(
        size: IntSize
    ) : Texture2D() {
        override val id: Int = 1
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = size.width
        override val height: Int = size.height
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.TOP_LEFT
        override val specification: Texture.Specification = Texture.Specification(
            size = size,
            coordinateOrigin = coordinateOrigin
        )

        override fun bind(slot: Int) = Unit
        override fun setData(data: Buffer) = Unit
        override fun isLoaded(): Boolean = true
        override fun destroy() = Unit
        override fun generateMipMaps() = Unit
    }
}
