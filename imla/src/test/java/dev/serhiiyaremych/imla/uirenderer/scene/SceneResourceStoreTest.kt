/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.Buffer

class SceneResourceStoreTest {
    @Test
    fun resolveResources_returnsRootContentMaskAndClipTextures() {
        val rootTexture = FakeTexture(id = 1)
        val contentTexture = FakeTexture(id = 2)
        val maskTexture = FakeTexture(id = 3)
        val clipTexture = FakeTexture(id = 4)
        val slotId = BlurSlotId("slot")
        val store = store(
            rootTexture = rootTexture,
            contentTextures = mapOf(slotId to contentTexture),
            maskTextures = mapOf(slotId to maskTexture),
            clipTextures = mapOf(slotId to clipTexture)
        )

        val resources = store.resolveResources(
            frame = frame(slotId, blurMask = SolidColor(Color.White)),
            rootTexture = store.consumePendingRootTexture() ?: error("missing root texture")
        )

        assertSame(rootTexture, resources.rootTexture)
        val slotResources = resources.slots.getValue(slotId)
        assertSame(contentTexture, slotResources.contentTexture)
        assertSame(maskTexture, slotResources.blurRadiusMask)
        assertSame(maskTexture, slotResources.compositeCoverageMask)
        assertSame(clipTexture, slotResources.clipTexture)
    }

    @Test
    fun resolveResources_slotWithoutBlurMask_setsBlurRadiusMaskToNull() {
        val rootTexture = FakeTexture(id = 1)
        val maskTexture = FakeTexture(id = 3)
        val slotId = BlurSlotId("plain")
        val store = store(
            rootTexture = rootTexture,
            maskTextures = mapOf(slotId to maskTexture)
        )

        val resources = store.resolveResources(
            frame = frame(slotId),
            rootTexture = rootTexture
        )

        val slotResources = resources.slots.getValue(slotId)
        assertNull(
            "blurRadiusMask must be null when slot.style.blurMask is null",
            slotResources.blurRadiusMask
        )
        assertSame(maskTexture, slotResources.compositeCoverageMask)
    }

    @Test
    fun resolveResources_slotWithBlurMask_setsBlurRadiusMaskFromRepository() {
        val rootTexture = FakeTexture(id = 1)
        val maskTexture = FakeTexture(id = 3)
        val slotId = BlurSlotId("masked")
        val store = store(
            rootTexture = rootTexture,
            maskTextures = mapOf(slotId to maskTexture)
        )

        val resources = store.resolveResources(
            frame = frame(slotId, blurMask = SolidColor(Color.White)),
            rootTexture = rootTexture
        )

        val slotResources = resources.slots.getValue(slotId)
        assertSame(maskTexture, slotResources.blurRadiusMask)
        assertSame(maskTexture, slotResources.compositeCoverageMask)
    }

    @Test
    fun resolveResources_clipTextureDoesNotSetBlurRadiusMask() {
        val rootTexture = FakeTexture(id = 1)
        val clipTexture = FakeTexture(id = 4)
        val slotId = BlurSlotId("clipped")
        val store = store(
            rootTexture = rootTexture,
            clipTextures = mapOf(slotId to clipTexture)
        )

        val resources = store.resolveResources(
            frame = frame(slotId),
            rootTexture = rootTexture
        )

        val slotResources = resources.slots.getValue(slotId)
        assertNull(slotResources.blurRadiusMask)
        assertNull(slotResources.compositeCoverageMask)
        assertSame(clipTexture, slotResources.clipTexture)
    }

    @Test
    fun resolveResources_keepsMissingSlotTexturesNullable() {
        val rootTexture = FakeTexture(id = 1)
        val slotId = BlurSlotId("slot")
        val store = store(rootTexture = rootTexture)

        val resources = store.resolveResources(
            frame = frame(slotId),
            rootTexture = rootTexture
        )

        val slotResources = resources.slots.getValue(slotId)
        assertNull(slotResources.contentTexture)
        assertNull(slotResources.blurRadiusMask)
        assertNull(slotResources.compositeCoverageMask)
        assertNull(slotResources.clipTexture)
    }

    @Test
    fun resolveResources_splitsCoverageOnlyDiagnosticMasksFromRadiusMasks() {
        val rootTexture = FakeTexture(id = 1)
        val maskTexture = FakeTexture(id = 3)
        val slotId = BlurSlotId("coverage-mask-atlas-simple")
        val store = store(
            rootTexture = rootTexture,
            maskTextures = mapOf(slotId to maskTexture)
        )

        val resources = store.resolveResources(
            frame = frame(slotId),
            rootTexture = rootTexture
        )

        val slotResources = resources.slots.getValue(slotId)
        assertNull(slotResources.blurRadiusMask)
        assertSame(maskTexture, slotResources.compositeCoverageMask)
    }

    @Test
    fun captureReleaseAndDestroy_delegateToResourceRepositories() {
        val layerRepository = FakeLayerRepository()
        val maskRepository = FakeMaskRepository()
        val sceneFrame = frame(BlurSlotId("slot"))
        val store = SceneResourceStore(
            rootTextureConsumer = { null },
            layerRepository = layerRepository,
            maskRepository = maskRepository
        )

        store.captureFrameResources(sceneFrame)
        store.releasePendingFrameResources()
        store.destroy()

        assertSame(sceneFrame, layerRepository.capturedFrame)
        assertSame(sceneFrame, maskRepository.capturedFrame)
        assertEquals(1, layerRepository.releaseCount)
        assertEquals(1, maskRepository.releaseCount)
        assertTrue(layerRepository.destroyed)
        assertTrue(maskRepository.destroyed)
    }

    @Test
    fun sceneGlRenderer_usesResourceStoreInsteadOfIndividualRepositories() {
        val rendererFields = SceneGlRenderer::class.java.declaredFields
            .filterNot { it.isSynthetic }
        val constructorParameterTypes = SceneGlRenderer::class.java.declaredConstructors
            .flatMap { constructor -> constructor.parameterTypes.asList() }

        assertTrue(
            "SceneGlRenderer must keep SceneResourceStore as its scene resource facade",
            rendererFields.any { field -> field.type == SceneResourceStore::class.java }
        )
        assertFalse(
            "SceneGlRenderer must not store SceneLayerRepository directly",
            rendererFields.any { field -> field.type == SceneLayerRepository::class.java }
        )
        assertFalse(
            "SceneGlRenderer must not store SceneMaskRepository directly",
            rendererFields.any { field -> field.type == SceneMaskRepository::class.java }
        )
        assertFalse(
            "SceneGlRenderer constructor must not require SceneLayerRepository directly",
            constructorParameterTypes.any { type -> type == SceneLayerRepository::class.java }
        )
        assertFalse(
            "SceneGlRenderer constructor must not require SceneMaskRepository directly",
            constructorParameterTypes.any { type -> type == SceneMaskRepository::class.java }
        )
    }

    private fun store(
        rootTexture: Texture2D,
        contentTextures: Map<BlurSlotId, Texture2D> = emptyMap(),
        maskTextures: Map<BlurSlotId, Texture2D> = emptyMap(),
        clipTextures: Map<BlurSlotId, Texture2D> = emptyMap()
    ): SceneResourceStore {
        return SceneResourceStore(
            rootTextureConsumer = { rootTexture },
            layerRepository = FakeLayerRepository(contentTextures),
            maskRepository = FakeMaskRepository(maskTextures, clipTextures)
        )
    }

    private fun frame(
        slotId: BlurSlotId,
        blurMask: androidx.compose.ui.graphics.Brush? = null
    ): CommittedSceneFrame {
        return frame(slotIds = listOf(slotId), blurMask = blurMask)
    }

    private fun frame(
        slotIds: List<BlurSlotId>,
        blurMask: androidx.compose.ui.graphics.Brush? = null
    ): CommittedSceneFrame {
        return CommittedSceneFrame(
            generation = 1L,
            rootSize = IntSize(100, 100),
            slots = slotIds.mapIndexed { index, id ->
                record(id, drawIndex = index, blurMask = blurMask)
            },
            reasons = setOf(RenderReason.RootCaptured)
        )
    }

    private fun record(
        id: BlurSlotId,
        drawIndex: Int,
        blurMask: androidx.compose.ui.graphics.Brush? = null
    ): BlurSlotRecord {
        return BlurSlotRecord(
            id = id,
            drawIndex = drawIndex,
            debugName = id.value,
            geometry = null,
            style = BlurSlotStyleRecord(
                style = Style.default,
                blurMask = blurMask
            ),
            content = null,
            dirtyFlags = BlurSlotDirtyFlags.Clean
        )
    }

    private class FakeLayerRepository(
        private val textures: Map<BlurSlotId, Texture2D> = emptyMap()
    ) : SceneLayerResourceRepository {
        var capturedFrame: CommittedSceneFrame? = null
        var releaseCount: Int = 0
        var destroyed: Boolean = false

        override fun captureSlotContent(frame: CommittedSceneFrame) {
            capturedFrame = frame
        }

        override fun textureFor(id: BlurSlotId): Texture2D? {
            return textures[id]
        }

        override fun releasePendingFrames() {
            releaseCount += 1
        }

        override fun destroy() {
            destroyed = true
        }
    }

    private class FakeMaskRepository(
        private val maskTextures: Map<BlurSlotId, Texture2D> = emptyMap(),
        private val clipTextures: Map<BlurSlotId, Texture2D> = emptyMap()
    ) : SceneMaskResourceRepository {
        var capturedFrame: CommittedSceneFrame? = null
        var releaseCount: Int = 0
        var destroyed: Boolean = false

        override fun captureSlotMasks(frame: CommittedSceneFrame) {
            capturedFrame = frame
        }

        override fun maskTextureFor(id: BlurSlotId): Texture2D? {
            return maskTextures[id]
        }

        override fun clipTextureFor(id: BlurSlotId): Texture2D? {
            return clipTextures[id]
        }

        override fun releasePendingFrames() {
            releaseCount += 1
        }

        override fun destroy() {
            destroyed = true
        }
    }

    private class FakeTexture(
        override val id: Int
    ) : Texture2D() {
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = 10
        override val height: Int = 10
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.TOP_LEFT
        override val specification: Texture.Specification = Texture.Specification(size = IntSize(10, 10))

        override fun bind(slot: Int) = Unit
        override fun setData(data: Buffer) = Unit
        override fun isLoaded(): Boolean = true
        override fun destroy() = Unit
        override fun generateMipMaps() = Unit
    }
}
