/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.GlTextureFrame
import dev.serhiiyaremych.imla.internal.legacy.GraphicsLayerTextureFrame
import dev.serhiiyaremych.imla.internal.legacy.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.Buffer

class SceneLayerRepositoryCaptureTest {

    private val fakeSlotAccess = FakeSlotAccess()

    @Test
    fun firstEligibleContentSlot_capturesAndStoresFrameAndKey() {
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("first")
        val geometry = geometry(localRect = Rect(0f, 0f, 64f, 32f))

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = geometry)
        ))

        assertEquals(1, fakeSlotAccess.renderCount)
        assertNotNull("frame must be stored after capture", repo.textureFor(slotId))
    }

    @Test
    fun sameSizeContentOffsetChange_reusesStaleContentForTwoGeometryFrames() {
        fakeSlotAccess.reset()
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("offset-change")
        val firstGeometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset.Zero
        )

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = firstGeometry)
        ))
        assertEquals(1, fakeSlotAccess.renderCount)

        val secondGeometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset(12f, 8f)
        )
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = secondGeometry)
        ))

        val thirdGeometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset(24f, 16f)
        )
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = thirdGeometry)
        ))

        assertEquals("two geometry-only offset changes must reuse stale content", 1, fakeSlotAccess.renderCount)

        val fourthGeometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset(36f, 24f)
        )
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = fourthGeometry)
        ))

        assertEquals("third geometry-only offset change must force recapture", 2, fakeSlotAccess.renderCount)
    }

    @Test
    fun sameSizeLocalRectChange_reusesStaleContentForTwoGeometryFrames() {
        fakeSlotAccess.reset()
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("rect-change")
        val firstGeometry = geometry(localRect = Rect(0f, 0f, 64f, 32f))

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = firstGeometry)
        ))
        assertEquals(1, fakeSlotAccess.renderCount)

        val secondGeometry = geometry(localRect = Rect(2f, 2f, 66f, 34f))
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = secondGeometry)
        ))

        val thirdGeometry = geometry(localRect = Rect(4f, 4f, 68f, 36f))
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = thirdGeometry)
        ))

        assertEquals("two geometry-only localRect changes must reuse stale content", 1, fakeSlotAccess.renderCount)

        val fourthGeometry = geometry(localRect = Rect(6f, 6f, 70f, 38f))
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = fourthGeometry)
        ))

        assertEquals("third geometry-only localRect change must force recapture", 2, fakeSlotAccess.renderCount)
    }

    @Test
    fun captureSuccess_resetsGeometryOnlyReuseBudget() {
        fakeSlotAccess.reset()
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("reset-budget")

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = geometry(contentOffset = Offset.Zero))
        ))
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = geometry(contentOffset = Offset(8f, 0f)))
        ))
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = geometry(contentOffset = Offset(16f, 0f)))
        ))
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = geometry(contentOffset = Offset(24f, 0f)))
        ))
        assertEquals("third geometry-only frame must force the second capture", 2, fakeSlotAccess.renderCount)

        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = geometry(contentOffset = Offset(32f, 0f)))
        ))

        assertEquals("first geometry-only frame after forced capture must reuse again", 2, fakeSlotAccess.renderCount)
    }

    @Test
    fun staleGeometryReuse_andBudgetCapture_emitCounters() {
        fakeSlotAccess.reset()
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("counter-proof")

        withRecordingSceneTraceCounters { recorder ->
            repo.doCaptureSlotContent(committedFrame(
                slot(id = slotId, geometry = geometry(contentOffset = Offset.Zero))
            ))
            repo.doCaptureSlotContent(committedFrame(
                geometryOnlySlot(id = slotId, geometry = geometry(contentOffset = Offset(8f, 0f)))
            ))
            repo.doCaptureSlotContent(committedFrame(
                geometryOnlySlot(id = slotId, geometry = geometry(contentOffset = Offset(16f, 0f)))
            ))
            repo.doCaptureSlotContent(committedFrame(
                geometryOnlySlot(id = slotId, geometry = geometry(contentOffset = Offset(24f, 0f)))
            ))

            assertEquals(2, recorder.count("slot.content.capture"))
            assertEquals(1, recorder.count("slot.content.capture.normal"))
            assertEquals(2, recorder.count("slot.content.reuse.staleGeometry"))
            assertEquals(1, recorder.count("slot.content.capture.budgetForced"))
        }
    }

    @Test
    fun unchangedKey_doesNotRecapture() {
        fakeSlotAccess.reset()
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("unchanged")
        val g = geometry(localRect = Rect(0f, 0f, 64f, 32f))

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = g, dirtyReasons = setOf(BlurSlotDirtyReason.Content))
        ))
        assertEquals(1, fakeSlotAccess.renderCount)

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = g, dirtyReasons = setOf(BlurSlotDirtyReason.Geometry))
        ))

        assertEquals("unchanged capture key must not recapture", 1, fakeSlotAccess.renderCount)
    }

    @Test
    fun failedCapture_doesNotUpdateKey_subsequentEligibleFrameStillCaptures() {
        fakeSlotAccess.reset()
        fakeSlotAccess.produceFrame = false
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("fail-then-succeed")
        val geometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset.Zero
        )

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = geometry)
        ))
        assertEquals(1, fakeSlotAccess.renderCount)
        assertNull("no frame stored on failed capture", repo.textureFor(slotId))

        fakeSlotAccess.produceFrame = true
        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = geometry)
        ))

        assertEquals("must attempt capture again on next eligible frame", 2, fakeSlotAccess.renderCount)
        assertNotNull("frame must be stored on successful re-capture", repo.textureFor(slotId))
    }

    @Test
    fun oldFrameReleased_onlyAfterReplacementStoredAndReleasePendingFramesRuns() {
        fakeSlotAccess.reset()
        fakeSlotAccess.produceFrame = true
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("release-prove")
        val firstGeometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset.Zero
        )

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = firstGeometry)
        ))
        val firstTexture = repo.textureFor(slotId) as FakeTexture2D
        assertNotNull(firstTexture)

        val secondGeometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset(12f, 8f)
        )
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = secondGeometry)
        ))
        val thirdGeometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset(24f, 16f)
        )
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = thirdGeometry)
        ))
        val fourthGeometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset(36f, 24f)
        )
        repo.doCaptureSlotContent(committedFrame(
            geometryOnlySlot(id = slotId, geometry = fourthGeometry)
        ))

        val secondTexture = repo.textureFor(slotId) as FakeTexture2D
        assertNotNull(secondTexture)
        assertEquals("third geometry-only frame must force recapture", 2, fakeSlotAccess.renderCount)
        assertTrue("recaptured texture must differ from first", firstTexture !== secondTexture)
        assertTrue("old frame not yet released before releasePendingFrames", !firstTexture.destroyed)

        repo.doReleasePendingFrames()

        assertTrue("old frame must be released after releasePendingFrames", firstTexture.destroyed)
        assertTrue("current frame must still be alive", !secondTexture.destroyed)
    }

    @Test
    fun slotWithNullContent_isSkipped() {
        val access = SlotInspectingAccess(layerRequired = true)
        val repo = createRepo(access)
        val frame = committedFrame(
            BlurSlotRecord(
                id = BlurSlotId("no-content"),
                drawIndex = 0,
                debugName = "no-content",
                geometry = geometry(),
                style = BlurSlotStyleRecord(style = Style.default, blurMask = null),
                content = null,
                dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Content))
            )
        )

        repo.doCaptureSlotContent(frame)

        assertEquals(0, access.renderCount)
        assertNull(repo.textureFor(BlurSlotId("no-content")))
    }

    @Test
    fun slotWithNullGeometry_isSkipped() {
        fakeSlotAccess.reset()
        val repo = createRepo(fakeSlotAccess)
        val frame = committedFrame(
            BlurSlotRecord(
                id = BlurSlotId("no-geometry"),
                drawIndex = 0,
                debugName = "no-geometry",
                geometry = null,
                style = BlurSlotStyleRecord(style = Style.default, blurMask = null),
                content = null,
                dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Content))
            )
        )

        repo.doCaptureSlotContent(frame)

        assertEquals(0, fakeSlotAccess.renderCount)
        assertNull(repo.textureFor(BlurSlotId("no-geometry")))
    }

    @Test
    fun slotWithNullLayer_isSkipped() {
        val nullLayerAccess = NullLayerSlotAccess()
        val repo = createRepo(nullLayerAccess)
        val frame = committedFrame(
            BlurSlotRecord(
                id = BlurSlotId("no-layer"),
                drawIndex = 0,
                debugName = "no-layer",
                geometry = geometry(),
                style = BlurSlotStyleRecord(style = Style.default, blurMask = null),
                content = null,
                dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Content))
            )
        )

        repo.doCaptureSlotContent(frame)

        assertEquals(0, nullLayerAccess.renderCount)
        assertNull(repo.textureFor(BlurSlotId("no-layer")))
    }

    @Test
    fun captureProducesCorrectContentOffsetAndSize() {
        fakeSlotAccess.reset()
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("params")
        val geometry = geometry(
            localRect = Rect(10f, 20f, 74f, 52f),
            contentOffset = Offset(5f, 15f)
        )

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = geometry)
        ))

        assertEquals(1, fakeSlotAccess.renderCount)
        assertEquals(IntSize(64, 32), fakeSlotAccess.lastSize)
        assertEquals(Offset(5f, 15f), fakeSlotAccess.lastContentOffset)
    }

    @Test
    fun multipleSlots_captureIndependently() {
        fakeSlotAccess.reset()
        val repo = createRepo(fakeSlotAccess)
        val slotA = slot(
            id = BlurSlotId("A"),
            geometry = geometry(localRect = Rect(0f, 0f, 64f, 32f)),
            drawIndex = 0
        )
        val slotB = slot(
            id = BlurSlotId("B"),
            geometry = geometry(localRect = Rect(0f, 0f, 48f, 24f), contentOffset = Offset(4f, 4f)),
            drawIndex = 1
        )

        repo.doCaptureSlotContent(committedFrame(slotA, slotB))

        assertEquals(2, fakeSlotAccess.renderCount)
        assertNotNull(repo.textureFor(BlurSlotId("A")))
        assertNotNull(repo.textureFor(BlurSlotId("B")))
    }

    @Test
    fun staleSlot_isRemovedWhenNoLongerInFrame() {
        fakeSlotAccess.reset()
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("stale")

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = geometry())
        ))
        assertNotNull(repo.textureFor(slotId))

        repo.doCaptureSlotContent(committedFrame())

        assertNull("stale slot texture must be cleared", repo.textureFor(slotId))
    }

    @Test
    fun destroy_clearsAllFramesAndCaptureKeys() {
        fakeSlotAccess.reset()
        val repo = createRepo(fakeSlotAccess)
        val slotId = BlurSlotId("destroy")

        repo.doCaptureSlotContent(committedFrame(
            slot(id = slotId, geometry = geometry())
        ))
        assertNotNull(repo.textureFor(slotId))

        repo.destroy()

        assertNull(repo.textureFor(slotId))
    }

    private fun createRepo(
        access: SlotContentCaptureAccess
    ): SceneLayerRepository = SceneLayerRepository(captureAccess = access)

    private fun geometry(
        area: Rect = Rect(0f, 0f, 64f, 32f),
        localRect: Rect = Rect(0f, 0f, 64f, 32f),
        contentOffset: Offset = Offset.Zero,
        transformMatrix: FloatArray = FloatArray(16),
        zIndex: Float = 0f
    ): BlurSlotGeometry = BlurSlotGeometry(
        area = area,
        localRect = localRect,
        contentOffset = contentOffset,
        transformMatrix = transformMatrix,
        zIndex = zIndex
    )

    private fun slot(
        id: BlurSlotId,
        geometry: BlurSlotGeometry,
        drawIndex: Int = 0,
        dirtyReasons: Set<BlurSlotDirtyReason> = setOf(BlurSlotDirtyReason.Content)
    ): BlurSlotRecord = BlurSlotRecord(
        id = id,
        drawIndex = drawIndex,
        debugName = id.value,
        geometry = geometry,
        style = BlurSlotStyleRecord(style = Style.default, blurMask = null),
        content = null,
        dirtyFlags = BlurSlotDirtyFlags(dirtyReasons)
    )

    private fun geometryOnlySlot(
        id: BlurSlotId,
        geometry: BlurSlotGeometry,
        drawIndex: Int = 0
    ): BlurSlotRecord = slot(
        id = id,
        geometry = geometry,
        drawIndex = drawIndex,
        dirtyReasons = setOf(BlurSlotDirtyReason.Geometry)
    )

    private fun committedFrame(vararg slots: BlurSlotRecord): CommittedSceneFrame =
        CommittedSceneFrame(
            generation = 1,
            rootSize = IntSize(1920, 1080),
            slots = slots.toList(),
            reasons = setOf(RenderReason.SlotChanged)
        )

    private class FakeSlotAccess(
        var produceFrame: Boolean = true
    ) : SlotContentCaptureAccess {
        var renderCount = 0
        var lastSize: IntSize? = null
        var lastContentOffset: Offset? = null
        private val renderedIds = mutableSetOf<BlurSlotId>()

        fun reset() {
            renderCount = 0
            lastSize = null
            lastContentOffset = null
            produceFrame = true
            renderedIds.clear()
        }

        override fun hasContent(slot: BlurSlotRecord): Boolean = true

        override fun hasLayer(slot: BlurSlotRecord): Boolean = true

        override fun capture(
            slotId: BlurSlotId,
            slot: BlurSlotRecord,
            size: IntSize,
            contentOffset: Offset,
            onCaptured: (GraphicsLayerTextureFrame?) -> Unit
        ) {
            renderCount++
            lastSize = size
            lastContentOffset = contentOffset
            renderedIds.add(slotId)
            if (produceFrame) {
                onCaptured(
                    GlTextureFrame(
                        sizePx = size,
                        textureOrigin = CoordinateOrigin.TOP_LEFT,
                        texture2D = FakeTexture2D()
                    )
                )
            } else {
                onCaptured(null)
            }
        }

        override fun activeSlotIds(): Set<BlurSlotId> = renderedIds

        override fun destroySlot(slotId: BlurSlotId) {
            renderedIds.remove(slotId)
        }

        override fun destroyAll() {
            renderedIds.clear()
        }
    }

    private class SlotInspectingAccess(
        private val layerRequired: Boolean
    ) : SlotContentCaptureAccess {
        var renderCount = 0

        override fun hasContent(slot: BlurSlotRecord): Boolean = slot.content != null

        override fun hasLayer(slot: BlurSlotRecord): Boolean =
            !layerRequired || slot.content?.layer != null

        override fun capture(
            slotId: BlurSlotId,
            slot: BlurSlotRecord,
            size: IntSize,
            contentOffset: Offset,
            onCaptured: (GraphicsLayerTextureFrame?) -> Unit
        ) {
            renderCount++
        }

        override fun activeSlotIds(): Set<BlurSlotId> = emptySet()

        override fun destroySlot(slotId: BlurSlotId) = Unit

        override fun destroyAll() = Unit
    }

    private class NullLayerSlotAccess : SlotContentCaptureAccess {
        var renderCount = 0

        override fun hasContent(slot: BlurSlotRecord): Boolean = true

        override fun hasLayer(slot: BlurSlotRecord): Boolean = false

        override fun capture(
            slotId: BlurSlotId,
            slot: BlurSlotRecord,
            size: IntSize,
            contentOffset: Offset,
            onCaptured: (GraphicsLayerTextureFrame?) -> Unit
        ) {
            renderCount++
        }

        override fun activeSlotIds(): Set<BlurSlotId> = emptySet()

        override fun destroySlot(slotId: BlurSlotId) = Unit

        override fun destroyAll() = Unit
    }

    private class FakeTexture2D : Texture2D() {
        override val id: Int = nextId++

        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = 64
        override val height: Int = 32
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.TOP_LEFT
        override val specification: Texture.Specification
            get() = Texture.Specification(size = IntSize(width, height))

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
}
