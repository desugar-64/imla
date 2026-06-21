/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.GlTextureFrame
import dev.serhiiyaremych.imla.internal.legacy.GraphicsLayerTextureFrame
import dev.serhiiyaremych.imla.internal.legacy.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.Buffer

class SceneMaskRepositoryCaptureTest {
    @Test
    fun captureSlotMasks_slotThatPreviouslyHadBlurMaskAndLaterHasNullBlurMask_clearsMaskFrame() {
        val factory = FakeMaskRendererFactory()
        val repository = repository(factory)
        val slotId = BlurSlotId("slot")

        repository.captureSlotMasks(committedFrame(slot(slotId, blurMask = SolidColor(Color.White))))
        val staleTexture = repository.maskTextureFor(slotId)
        assertNotNull(staleTexture)

        repository.captureSlotMasks(committedFrame(slot(slotId, blurMask = null)))

        assertNull(repository.maskTextureFor(slotId))
        repository.releasePendingFrames()
        assertTrue((staleTexture as FakeTexture).destroyed)
    }

    @Test
    fun captureSlotMasks_slotWithNullGeometryAndNullBlurMask_clearsStaleMaskFrame() {
        val factory = FakeMaskRendererFactory()
        val repository = repository(factory)
        val slotId = BlurSlotId("slot")

        repository.captureSlotMasks(committedFrame(slot(slotId, blurMask = SolidColor(Color.White))))
        val staleTexture = repository.maskTextureFor(slotId)
        assertNotNull(staleTexture)

        repository.captureSlotMasks(
            committedFrame(
                slot(
                    id = slotId,
                    geometry = null,
                    blurMask = null
                )
            )
        )

        assertNull(repository.maskTextureFor(slotId))
        repository.releasePendingFrames()
        assertTrue((staleTexture as FakeTexture).destroyed)
    }

    @Test
    fun captureSlotMasks_removeStaleSlotsRemovesOrphanedMaskFrameWithoutRendererEntry() {
        val factory = FakeMaskRendererFactory(completeImmediately = false)
        val repository = repository(factory)
        val slotId = BlurSlotId("slot")

        repository.captureSlotMasks(committedFrame(slot(slotId, blurMask = SolidColor(Color.White))))
        assertNull(repository.maskTextureFor(slotId))

        repository.captureSlotMasks(committedFrame())
        factory.renderers.single().completeRender(frame = graphicsFrame())
        val orphanedTexture = repository.maskTextureFor(slotId)
        assertNotNull(orphanedTexture)

        repository.captureSlotMasks(committedFrame())

        assertNull(repository.maskTextureFor(slotId))
        repository.releasePendingFrames()
        assertTrue((orphanedTexture as FakeTexture).destroyed)
    }

    @Test
    fun captureSlotMasks_clipOnlyStateDoesNotCreateBlurRadiusMaskState() {
        val factory = FakeMaskRendererFactory()
        val repository = repository(factory)
        val slotId = BlurSlotId("clip-only")

        repository.captureSlotMasks(
            committedFrame(
                slot(
                    id = slotId,
                    blurMask = null,
                    clipShape = NonRectangleShape
                )
            )
        )

        assertNull(repository.maskTextureFor(slotId))
        assertNotNull(repository.clipTextureFor(slotId))
        assertEquals(1, factory.renderers.size)
    }

    private fun committedFrame(vararg slots: BlurSlotRecord): CommittedSceneFrame {
        return CommittedSceneFrame(
            generation = 1L,
            rootSize = IntSize(300, 400),
            slots = slots.toList(),
            reasons = setOf(RenderReason.SlotChanged)
        )
    }

    private fun repository(factory: SceneMaskCaptureRendererFactory): SceneMaskRepository {
        return SceneMaskRepository(
            rendererFactory = factory,
            tracer = UntracedSceneMaskRepositoryTracer
        )
    }

    private fun slot(
        id: BlurSlotId,
        geometry: BlurSlotGeometry? = geometry(),
        blurMask: Brush? = null,
        clipShape: Shape = RectangleShape
    ): BlurSlotRecord {
        return BlurSlotRecord(
            id = id,
            drawIndex = 0,
            debugName = id.value,
            geometry = geometry,
            style = BlurSlotStyleRecord(
                style = Style.default,
                blurMask = blurMask,
                clipShape = clipShape
            ),
            content = null,
            dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Mask))
        )
    }

    private fun geometry(): BlurSlotGeometry {
        return BlurSlotGeometry(
            area = Rect(0f, 0f, 80f, 40f),
            localRect = Rect(0f, 0f, 80f, 40f),
            contentOffset = Offset.Zero,
            transformMatrix = FloatArray(16),
            zIndex = 0f
        )
    }

    private class FakeMaskRendererFactory(
        private val completeImmediately: Boolean = true
    ) : SceneMaskCaptureRendererFactory {
        val renderers = mutableListOf<FakeMaskRenderer>()

        override fun create(onRenderComplete: (GraphicsLayerTextureFrame?) -> Unit): SceneMaskCaptureRenderer {
            return FakeMaskRenderer(
                onRenderComplete = onRenderComplete,
                completeImmediately = completeImmediately
            ).also(renderers::add)
        }
    }

    private class FakeMaskRenderer(
        private val onRenderComplete: (GraphicsLayerTextureFrame?) -> Unit,
        private val completeImmediately: Boolean
    ) : SceneMaskCaptureRenderer {
        var destroyed: Boolean = false
            private set

        override fun renderMask(brush: Brush?, shape: Shape, size: IntSize) {
            if (completeImmediately) {
                completeRender(frame = graphicsFrame(size))
            }
        }

        fun completeRender(frame: GraphicsLayerTextureFrame) {
            onRenderComplete(frame)
        }

        override fun destroy() {
            destroyed = true
        }
    }

    private object NonRectangleShape : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density
        ): Outline {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
    }

    private object UntracedSceneMaskRepositoryTracer : SceneMaskRepositoryTracer {
        override fun <T> trace(label: String, block: () -> T): T {
            return block()
        }
    }

    private class FakeTexture(
        private val size: IntSize
    ) : Texture2D() {
        override val id: Int = nextId++
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = size.width
        override val height: Int = size.height
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.TOP_LEFT
        override val specification: Texture.Specification = Texture.Specification(size = size)

        var destroyed: Boolean = false
            private set

        override fun bind(slot: Int) = Unit
        override fun setData(data: Buffer) = Unit
        override fun isLoaded(): Boolean = true
        override fun destroy() {
            destroyed = true
        }

        override fun generateMipMaps() = Unit

        companion object {
            private var nextId = 1
        }
    }

    private companion object {
        fun graphicsFrame(size: IntSize = IntSize(80, 40)): GraphicsLayerTextureFrame {
            return GlTextureFrame(
                sizePx = size,
                textureOrigin = CoordinateOrigin.TOP_LEFT,
                texture2D = FakeTexture(size = size)
            )
        }
    }
}
