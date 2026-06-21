/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.effects

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import java.nio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class PreProcessGeometryTest {

    @Test
    fun centeredInBoundsSlot_keepsSymmetricPaddingAndCentersCropsInBucketedFbos() {
        val request = planPreProcessGeometry(
            rootSize = IntSize(200, 160),
            sampleArea = Rect(left = 50f, top = 40f, right = 110f, bottom = 90f),
            contentSize = IntSize(60, 50),
            sigma = 4f
        )
        val source = fakeSizedFramebuffer(allocatedSize = IntSize(128, 128), contentSize = request.sourceContentSize)
        val target = fakeSizedFramebuffer(allocatedSize = IntSize(128, 128), contentSize = request.targetContentSize)
        val plan = request.withContentOffsets(
            sourceContentOffset = source.contentOffset,
            targetContentOffset = target.contentOffset
        )

        assertRectEquals(Rect(30f, 20f, 130f, 110f), request.extendedArea)
        assertEquals(IntSize(100, 90), request.sourceContentSize)
        assertEquals(IntSize(100, 90), request.referenceSize)
        assertFloatEquals(1f, request.sigmaDownsampleScale)
        assertEquals(IntSize(100, 92), request.targetContentSize)
        assertFloatEquals(1f, plan.fitScale)
        assertEquals(IntOffset(14, 19), source.contentOffset)
        assertEquals(IntOffset(14, 18), target.contentOffset)
        assertRectEquals(Rect(34f, 39f, 94f, 89f), plan.sourceSampleCrop)
        assertRectEquals(Rect(34f, 39f, 94f, 89f), plan.sampleCrop)
        assertRectEquals(Rect(34f, 39f, 94f, 89f), plan.contentCrop)
        assertRectEquals(Rect(0.265625f, 0.3046875f, 0.734375f, 0.6953125f), target.toUvRect(plan.sampleCrop))
    }

    @Test
    fun leftTopEdge_allowsNegativeExtendedOriginAndKeepsSampleCropAtPadding() {
        val request = planPreProcessGeometry(
            rootSize = IntSize(200, 160),
            sampleArea = Rect(left = 5f, top = 7f, right = 45f, bottom = 47f),
            contentSize = IntSize(40, 40),
            sigma = 4f
        )
        val plan = request.withContentOffsets(
            sourceContentOffset = IntOffset.Zero,
            targetContentOffset = IntOffset.Zero
        )

        assertRectEquals(Rect(-15f, -13f, 65f, 67f), request.extendedArea)
        assertEquals(IntSize(80, 80), request.sourceContentSize)
        assertEquals(IntSize(80, 80), request.targetContentSize)
        assertRectEquals(Rect(20f, 20f, 60f, 60f), plan.sourceSampleCrop)
        assertRectEquals(Rect(20f, 20f, 60f, 60f), plan.sampleCrop)
        assertRectEquals(Rect(20f, 20f, 60f, 60f), plan.contentCrop)
    }

    @Test
    fun rightBottomEdge_clipsRightBottomPaddingAndCentersSmallerExtractedSource() {
        val request = planPreProcessGeometry(
            rootSize = IntSize(200, 160),
            sampleArea = Rect(left = 170f, top = 120f, right = 195f, bottom = 150f),
            contentSize = IntSize(25, 30),
            sigma = 4f
        )
        val plan = request.withContentOffsets(
            sourceContentOffset = IntOffset.Zero,
            targetContentOffset = IntOffset.Zero
        )

        assertRectEquals(Rect(150f, 100f, 200f, 160f), request.extendedArea)
        assertEquals(IntSize(50, 60), request.sourceContentSize)
        assertEquals(IntSize(65, 70), request.referenceSize)
        assertEquals(IntSize(68, 72), request.targetContentSize)
        assertFloatEquals(1f, plan.fitScale)
        assertRectEquals(Rect(20f, 20f, 45f, 50f), plan.sourceSampleCrop)
        assertRectEquals(Rect(29f, 26f, 54f, 56f), plan.sampleCrop)
        assertRectEquals(Rect(29f, 26f, 54f, 56f), plan.contentCrop)
    }

    @Test
    fun rotatedExpandedSampleArea_keepsBlurSampleCropSeparateFromIntrinsicContentCrop() {
        val request = planPreProcessGeometry(
            rootSize = IntSize(240, 200),
            sampleArea = Rect(left = 70f, top = 50f, right = 170f, bottom = 130f),
            contentSize = IntSize(60, 40),
            sigma = 4f
        )
        val plan = request.withContentOffsets(
            sourceContentOffset = IntOffset.Zero,
            targetContentOffset = IntOffset.Zero
        )

        assertRectEquals(Rect(50f, 30f, 190f, 150f), request.extendedArea)
        assertEquals(IntSize(140, 120), request.sourceContentSize)
        assertEquals(IntSize(100, 80), request.targetContentSize)
        assertFloatEquals(2f / 3f, plan.fitScale)
        assertRectEquals(Rect(20f, 20f, 120f, 100f), plan.sourceSampleCrop)
        assertRectEquals(Rect(16.666668f, 13.333334f, 83.333336f, 66.66667f), plan.sampleCrop)
        assertRectEquals(Rect(30f, 26.666668f, 70f, 53.333336f), plan.contentCrop)
    }

    @Test
    fun highSigmaDownsample_usesRoundedSigmaScaleAndFinalFitScaleFromRoundedTarget() {
        val request = planPreProcessGeometry(
            rootSize = IntSize(300, 300),
            sampleArea = Rect(left = 100f, top = 100f, right = 180f, bottom = 180f),
            contentSize = IntSize(80, 80),
            sigma = 16f
        )
        val plan = request.withContentOffsets(
            sourceContentOffset = IntOffset.Zero,
            targetContentOffset = IntOffset.Zero
        )

        assertRectEquals(Rect(80f, 80f, 200f, 200f), request.extendedArea)
        assertEquals(IntSize(120, 120), request.sourceContentSize)
        assertFloatEquals(0.25f, request.sigmaDownsampleScale)
        assertEquals(IntSize(32, 32), request.targetContentSize)
        assertFloatEquals(32f / 120f, plan.fitScale)
        assertRectEquals(Rect(20f, 20f, 100f, 100f), plan.sourceSampleCrop)
        assertRectEquals(Rect(5.3333335f, 5.3333335f, 26.666668f, 26.666668f), plan.sampleCrop)
        assertRectEquals(Rect(5.3333335f, 5.3333335f, 26.666668f, 26.666668f), plan.contentCrop)
    }

    private fun fakeSizedFramebuffer(
        allocatedSize: IntSize,
        contentSize: IntSize
    ): SizedFramebuffer {
        return FakeFramebuffer(allocatedSize).withSize(contentSize)
    }

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertFloatEquals(expected.left, actual.left)
        assertFloatEquals(expected.top, actual.top)
        assertFloatEquals(expected.right, actual.right)
        assertFloatEquals(expected.bottom, actual.bottom)
    }

    private fun assertFloatEquals(expected: Float, actual: Float) {
        assertEquals(expected, actual, EPS)
    }

    private class FakeFramebuffer(size: IntSize) : Framebuffer {
        override val rendererId: Int = 0
        override val specification: FramebufferSpecification = FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification.singleColor()
        )
        override val colorAttachmentTexture: Texture2D = FakeTexture(size)

        override fun invalidate() = Unit
        override fun bind(bind: Bind, updateViewport: Boolean) = Unit
        override fun bind(commands: RenderCommands, bind: Bind, updateViewport: Boolean) = Unit
        override fun bindForOverwrite(bind: Bind) = Unit
        override fun bindForOverwrite(commands: RenderCommands, bind: Bind) = Unit
        override fun bindForMipLevel(level: Int, size: IntSize, bind: Bind) = Unit
        override fun bindForMipLevel(commands: RenderCommands, level: Int, size: IntSize, bind: Bind) = Unit
        override fun unbind() = Unit
        override fun unbind(commands: RenderCommands) = Unit
        override fun resize(width: Int, height: Int) = Unit
        override fun invalidateAttachments() = Unit
        override fun clearAttachment(attachmentIndex: Int, value: Int) = Unit
        override fun getColorAttachmentRendererID(index: Int): Int = 0
        override fun destroy() = Unit
        override fun setColorAttachmentAt(attachmentIndex: Int) = Unit
    }

    private class FakeTexture(size: IntSize) : Texture2D() {
        override val id: Int = 0
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

    private companion object {
        private const val EPS = 1e-4f
    }
}
