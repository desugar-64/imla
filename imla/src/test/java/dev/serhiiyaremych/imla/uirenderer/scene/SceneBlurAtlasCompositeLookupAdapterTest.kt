/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.legacy.BlurAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.Buffer
import java.nio.file.Files
import java.nio.file.Paths

class SceneBlurAtlasCompositeLookupAdapterTest {
    @Test
    fun adapter_emptyBlurOutputProducesEmptyLookup() {
        val blurOutput = SceneBlurAtlasBlurFrameOutput(
            generation = 30L,
            rootSize = IntSize(width = 320, height = 240),
            batches = emptyList()
        )

        val lookup = SceneBlurAtlasCompositeLookupAdapter.adapt(blurOutput)

        assertEquals(30L, lookup.generation)
        assertEquals(IntSize(width = 320, height = 240), lookup.rootSize)
        assertTrue(lookup.entries.isEmpty())
    }

    @Test
    fun adapter_flattensMultipleBatchesIntoDrawOrderEntries() {
        val firstKey = compatibilityKey(sigma = 4f)
        val secondKey = compatibilityKey(sigma = 8f)
        val firstBatch = blurBatch(
            key = firstKey,
            atlasSize = IntSize(width = 80, height = 40),
            sourceTextureId = 100,
            blurredTextureId = 200,
            blurredTextureSize = IntSize(width = 96, height = 64),
            sourceAtlasContentCrop = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 80, bottom = 40),
            blurredAtlasContentCrop = SceneBlurAtlasPixelRect(left = 8, top = 12, right = 88, bottom = 52),
            placements = listOf(
                blurredPlacement(
                    slotId = "first",
                    drawIndex = 2,
                    sourceAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 3, top = 4, right = 18, bottom = 24),
                    blurredAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 11, top = 16, right = 26, bottom = 36)
                ),
                blurredPlacement(
                    slotId = "third",
                    drawIndex = 5,
                    sourceAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 24, top = 4, right = 46, bottom = 24),
                    blurredAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 32, top = 16, right = 54, bottom = 36)
                )
            )
        )
        val secondBatch = blurBatch(
            key = secondKey,
            atlasSize = IntSize(width = 32, height = 32),
            sourceTextureId = 101,
            blurredTextureId = 201,
            blurredTextureSize = IntSize(width = 32, height = 48),
            sourceAtlasContentCrop = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 32, bottom = 32),
            blurredAtlasContentCrop = SceneBlurAtlasPixelRect(left = 0, top = 8, right = 32, bottom = 40),
            placements = listOf(
                blurredPlacement(
                    slotId = "second",
                    drawIndex = 1,
                    sourceAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 5, top = 6, right = 25, bottom = 26),
                    blurredAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 5, top = 14, right = 25, bottom = 34),
                    copyScale = SceneBlurAtlasCopyScale(x = 0.5f, y = 0.5f),
                    downsampleScale = 0.5f
                )
            )
        )
        val blurOutput = SceneBlurAtlasBlurFrameOutput(
            generation = 31L,
            rootSize = IntSize(width = 320, height = 240),
            batches = listOf(firstBatch, secondBatch)
        )

        val lookup = SceneBlurAtlasCompositeLookupAdapter.adapt(blurOutput)

        assertEquals(listOf("second", "first", "third"), lookup.slotIds())
        assertEquals(listOf(1, 2, 5), lookup.entries.map { entry -> entry.drawIndex })
        assertEquals(listOf(secondKey, firstKey, firstKey), lookup.entries.map { entry -> entry.compatibilityKey })

        val firstEntry = lookup.entries[1]
        assertEquals(BlurSlotId("first"), firstEntry.slotId)
        assertEquals(SceneBlurAtlasTextureHandle(200, IntSize(width = 96, height = 64), CoordinateOrigin.TOP_LEFT), firstEntry.blurredAtlasTexture)
        assertEquals(firstBatch.sourceAtlasContentCrop, firstEntry.sourceAtlasContentCrop)
        assertEquals(firstBatch.blurredAtlasContentCrop, firstEntry.blurredAtlasContentCrop)
        assertEquals(firstBatch.placements.first().sourceCopyRect, firstEntry.sourceCopyRect)
        assertEquals(firstBatch.placements.first().sourceSampleRect, firstEntry.sourceSampleRect)
        assertEquals(firstBatch.placements.first().sourceSampleCrop, firstEntry.sourceSampleCrop)
        assertEquals(firstBatch.placements.first().sourceAtlasRect, firstEntry.sourceAtlasRect)
        assertEquals(firstBatch.placements.first().sourceAtlasSampleCrop, firstEntry.sourceAtlasSampleCrop)
        assertEquals(firstBatch.blurredAtlasContentUv, firstEntry.blurredAtlasContentUv)
        assertEquals(firstBatch.placements.first().blurredAtlasSampleCrop, firstEntry.blurredAtlasSampleCrop)

        val secondEntry = lookup.entries.first()
        assertEquals(SceneBlurAtlasCopyScale(x = 0.5f, y = 0.5f), secondEntry.copyScale)
        assertEquals(0.5f, secondEntry.downsampleScale, 0f)
    }

    @Test
    fun adapter_publishesTopLeftAtlasTextureOriginForTopLeftCropMetadata() {
        val key = compatibilityKey(sigma = 6f)
        val storageOrigin = CoordinateOrigin.BOTTOM_LEFT
        val blurOutput = SceneBlurAtlasBlurFrameOutput(
            generation = 34L,
            rootSize = IntSize(width = 320, height = 240),
            batches = listOf(
                blurBatch(
                    key = key,
                    sourceTextureId = 100,
                    blurredTextureId = 200,
                    blurredTextureSize = IntSize(width = 128, height = 96),
                    storageOrigin = storageOrigin,
                    placements = listOf(
                        blurredPlacement(
                            slotId = "slot",
                            drawIndex = 1,
                            blurredAtlasSampleCrop = SceneBlurAtlasPixelRect(
                                left = 12,
                                top = 8,
                                right = 64,
                                bottom = 48
                            )
                        )
                    )
                )
            )
        )

        val lookup = SceneBlurAtlasCompositeLookupAdapter.adapt(blurOutput)

        val entry = lookup.entries.single()
        assertEquals(storageOrigin, blurOutput.batches.single().coordinateOriginHandoff.storageOrigin)
        assertEquals(CoordinateOrigin.TOP_LEFT, blurOutput.batches.single().coordinateOriginHandoff.logicalLookupOrigin)
        assertEquals(CoordinateOrigin.TOP_LEFT, entry.blurredAtlasTexture.coordinateOrigin)
        assertEquals(SceneBlurAtlasPixelRect(left = 12, top = 8, right = 64, bottom = 48), entry.blurredAtlasSampleCrop)
    }

    @Test
    fun adapter_drawOrderIsDeterministicForEqualDrawIndexes() {
        val key = compatibilityKey(sigma = 6f)
        val blurOutput = SceneBlurAtlasBlurFrameOutput(
            generation = 32L,
            rootSize = IntSize(width = 320, height = 240),
            batches = listOf(
                blurBatch(
                    key = key,
                    sourceTextureId = 100,
                    blurredTextureId = 200,
                    placements = listOf(
                        blurredPlacement(slotId = "same-draw-first", drawIndex = 2),
                        blurredPlacement(slotId = "later", drawIndex = 3)
                    )
                ),
                blurBatch(
                    key = key,
                    sourceTextureId = 101,
                    blurredTextureId = 201,
                    placements = listOf(
                        blurredPlacement(slotId = "same-draw-second", drawIndex = 2)
                    )
                )
            )
        )

        val firstLookup = SceneBlurAtlasCompositeLookupAdapter.adapt(blurOutput)
        val secondLookup = SceneBlurAtlasCompositeLookupAdapter.adapt(blurOutput)

        assertEquals(firstLookup, secondLookup)
        assertEquals(listOf("same-draw-first", "same-draw-second", "later"), firstLookup.slotIds())
    }

    @Test
    fun adapter_representsDuplicateSlotIdsAsDrawOrderEntries() {
        val key = compatibilityKey(sigma = 6f)
        val blurOutput = SceneBlurAtlasBlurFrameOutput(
            generation = 33L,
            rootSize = IntSize(width = 320, height = 240),
            batches = listOf(
                blurBatch(
                    key = key,
                    sourceTextureId = 100,
                    blurredTextureId = 200,
                    placements = listOf(
                        blurredPlacement(slotId = "reused", drawIndex = 1),
                        blurredPlacement(slotId = "other", drawIndex = 2),
                        blurredPlacement(slotId = "reused", drawIndex = 3)
                    )
                )
            )
        )

        val lookup = SceneBlurAtlasCompositeLookupAdapter.adapt(blurOutput)

        assertEquals(listOf("reused", "other", "reused"), lookup.slotIds())
        assertEquals(listOf(1, 3), lookup.entries.filter { entry -> entry.slotId.value == "reused" }.map { entry -> entry.drawIndex })
    }

    @Test
    fun liveLookupUseStaysInternalAndLookupDriven() {
        val slotRunnerSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt"
        )
        val scenePublicBoundary = scenePublicBoundarySource()

        assertTrue(slotRunnerSource.contains("lookupOutput?.entries"))
        assertTrue(slotRunnerSource.contains("AtlasSlotKey(lookup.slotId, lookup.drawIndex)"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasCompositeLookupAdapter"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasCompositeLookupFrameOutput"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasCompositeLookupEntry"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasTextureHandle"))
    }

    @Test
    fun lookupOutputStructures_areImmutableAndOwnershipFree() {
        lookupOutputTypes.forEach { type ->
            type.declaredFields
                .filterNot { field -> field.isSynthetic }
                .forEach { field ->
                    assertTrue("${type.simpleName}.${field.name} must be final", Modifier.isFinal(field.modifiers))
                    assertFalse("${type.simpleName}.${field.name} must not expose arrays", field.type.isArray)
                    assertFalse(
                        "${type.simpleName}.${field.name} must not expose mutable types",
                        field.genericType.typeName.contains("Mutable")
                    )
                    forbiddenOwnerTypeNames.forEach { forbiddenName ->
                        assertFalse(
                            "${type.simpleName}.${field.name} must not expose $forbiddenName",
                            field.genericType.typeName.contains(forbiddenName)
                        )
                    }
                }

            assertFalse(
                "${type.simpleName} must not expose setters",
                type.methods.any { method -> method.name.startsWith("set") }
            )
        }

        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasCompositeLookupAdapter.kt"
        )
        assertTrue(source.contains("SceneBlurAtlasBlurFrameOutput"))
        assertFalse("adapter must not call release", source.contains(".release("))
        forbiddenOwnerTypeNames.forEach { forbiddenName ->
            assertFalse("adapter source must not reference $forbiddenName", source.contains(forbiddenName))
        }
    }

    private fun SceneBlurAtlasCompositeLookupFrameOutput.slotIds(): List<String> {
        return entries.map { entry -> entry.slotId.value }
    }

    private fun blurBatch(
        key: SceneBlurAtlasCompatibilityKey,
        sourceTextureId: Int,
        blurredTextureId: Int,
        placements: List<SceneBlurAtlasBlurredPlacement>,
        atlasSize: IntSize = IntSize(width = 32, height = 32),
        blurredTextureSize: IntSize = atlasSize,
        sourceAtlasContentCrop: SceneBlurAtlasPixelRect = SceneBlurAtlasPixelRect(
            left = 0,
            top = 0,
            right = atlasSize.width,
            bottom = atlasSize.height
        ),
        blurredAtlasContentCrop: SceneBlurAtlasPixelRect = sourceAtlasContentCrop,
        storageOrigin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
    ): SceneBlurAtlasBlurBatchOutput {
        val blurredTexture = FakeTexture(
            id = blurredTextureId,
            size = blurredTextureSize,
            coordinateOrigin = storageOrigin
        )
        return SceneBlurAtlasBlurBatchOutput(
            key = key,
            settings = SceneBlurAtlasBlurSettings(
                sigma = key.sigma,
                sigmaTexels = key.sigma,
                downsampleScale = placements.firstOrNull()?.downsampleScale ?: 1f
            ),
            atlasSize = atlasSize,
            sourceAtlasTexture = FakeTexture(
                id = sourceTextureId,
                size = atlasSize,
                coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
            ),
            sourceAtlasContentCrop = sourceAtlasContentCrop,
            blurredAtlasFramebuffer = FakeFramebuffer(
                rendererId = blurredTextureId + 1000,
                specification = framebufferSpec(blurredTextureSize),
                colorAttachmentTexture = blurredTexture
            ),
            blurredAtlasTexture = blurredTexture,
            blurredAtlasAllocatedSize = blurredTextureSize,
            blurredAtlasContentCrop = blurredAtlasContentCrop,
            blurredAtlasContentUv = blurredAtlasContentCrop.toTestUvRect(blurredTextureSize),
            coordinateOriginHandoff = SceneBlurAtlasCoordinateOriginHandoff.fromStorage(storageOrigin),
            placements = placements
        )
    }

    private fun SceneBlurAtlasPixelRect.toTestUvRect(size: IntSize): SceneBlurAtlasUvRect {
        return SceneBlurAtlasUvRect(
            left = left.toFloat() / size.width.coerceAtLeast(1),
            top = top.toFloat() / size.height.coerceAtLeast(1),
            right = right.toFloat() / size.width.coerceAtLeast(1),
            bottom = bottom.toFloat() / size.height.coerceAtLeast(1)
        )
    }

    private fun blurredPlacement(
        slotId: String,
        drawIndex: Int,
        sourceAtlasSampleCrop: SceneBlurAtlasPixelRect = SceneBlurAtlasPixelRect(left = 2, top = 2, right = 30, bottom = 30),
        blurredAtlasSampleCrop: SceneBlurAtlasPixelRect = sourceAtlasSampleCrop,
        copyScale: SceneBlurAtlasCopyScale = SceneBlurAtlasCopyScale(x = 1f, y = 1f),
        downsampleScale: Float = 1f
    ): SceneBlurAtlasBlurredPlacement {
        return SceneBlurAtlasBlurredPlacement(
            slotId = BlurSlotId(slotId),
            drawIndex = drawIndex,
            blurRadiusMask = null,
            sourceCopyRect = SceneBlurAtlasPixelRect(left = 10, top = 20, right = 42, bottom = 52),
            sourceSampleRect = SceneBlurAtlasPixelRect(left = 12, top = 22, right = 40, bottom = 50),
            sourceSampleCrop = SceneBlurAtlasPixelRect(left = 2, top = 2, right = 30, bottom = 30),
            sourceAtlasRect = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 32, bottom = 32),
            sourceAtlasSampleCrop = sourceAtlasSampleCrop,
            blurredAtlasSampleCrop = blurredAtlasSampleCrop,
            copyScale = copyScale,
            downsampleScale = downsampleScale
        )
    }

    private fun compatibilityKey(sigma: Float): SceneBlurAtlasCompatibilityKey {
        return SceneBlurAtlasCompatibilityKey(
            sigma = sigma,
            algorithm = BlurAlgorithm.GAUSSIAN
        )
    }

    private fun sourceFile(relativePath: String): String {
        val path = Paths.get(relativePath)
        val cwd = Paths.get("").toAbsolutePath()
        val sourcePath = listOfNotNull(
            cwd.resolve(path),
            cwd.parent?.resolve(path)
        ).firstOrNull { candidate -> Files.exists(candidate) } ?: path

        return String(Files.readAllBytes(sourcePath), Charsets.UTF_8)
    }

    private fun scenePublicBoundarySource(): String {
        return listOf(
            sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt"),
            sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/host/BlurSurfaceHost.kt")
        ).joinToString(separator = "\n")
    }

    private class FakeFramebuffer(
        override val rendererId: Int,
        override val specification: FramebufferSpecification,
        override val colorAttachmentTexture: Texture2D
    ) : Framebuffer {
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
        override fun getColorAttachmentRendererID(index: Int): Int = colorAttachmentTexture.id
        override fun destroy() = Unit
        override fun setColorAttachmentAt(attachmentIndex: Int) = Unit
    }

    private class FakeTexture(
        override val id: Int,
        size: IntSize,
        override val coordinateOrigin: CoordinateOrigin
    ) : Texture2D() {
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = size.width
        override val height: Int = size.height
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
        fun framebufferSpec(size: IntSize): FramebufferSpecification {
            return FramebufferSpecification(
                size = size,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                    coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
                )
            )
        }

        val forbiddenOwnerTypeNames: List<String> = listOf(
            "Framebuffer",
            "FramebufferLendingPool",
            "Texture2D",
            "ThreadLocal",
            "RenderObject",
            "SceneLayerRepository",
            "SceneMaskRepository",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "SceneResourceStore",
            "GLRenderer",
            "GraphicsLayer"
        )

        val lookupOutputTypes: List<Class<*>> = listOf(
            SceneBlurAtlasCompositeLookupFrameOutput::class.java,
            SceneBlurAtlasCompositeLookupEntry::class.java,
            SceneBlurAtlasTextureHandle::class.java
        )
    }
}
