/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.RendererApi
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.VertexArray
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.legacy.BlurAlgorithm
import dev.serhiiyaremych.imla.internal.render.processing.effects.SizedFramebuffer
import dev.serhiiyaremych.imla.internal.render.processing.effects.withSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.Buffer
import java.nio.file.Files
import java.nio.file.Paths

class SceneBlurAtlasBlurPassTest {
    @Test
    fun blurPass_emptyCopyOutputReturnsEmptyOutputWithoutBlurring() {
        val pool = RecordingFramebufferPool()
        val blurrer = RecordingAtlasBlurrer(pool)
        val pass = SceneBlurAtlasBlurPass(pool, blurrer)
        val copyOutput = SceneBlurAtlasCopyFrameOutput(
            generation = 17L,
            rootSize = IntSize(width = 300, height = 400),
            batches = emptyList()
        )

        val output = pass.execute(copyOutput)

        assertEquals(17L, output.generation)
        assertEquals(IntSize(width = 300, height = 400), output.rootSize)
        assertTrue(output.batches.isEmpty())
        assertTrue(blurrer.calls.isEmpty())
        assertTrue(pool.acquiredSpecs.isEmpty())
    }

    @Test
    fun blurPassPreservesBatchSettingsAndPlacementMetadata() {
        val pool = RecordingFramebufferPool(
            allocatedSizes = ArrayDeque(
                listOf(
                    IntSize(width = 96, height = 64),
                    IntSize(width = 32, height = 32)
                )
            )
        )
        val blurrer = RecordingAtlasBlurrer(pool)
        val pass = SceneBlurAtlasBlurPass(pool, blurrer)
        val firstKey = SceneBlurAtlasCompatibilityKey(
            sigma = 4.4f,
            algorithm = BlurAlgorithm.GAUSSIAN
        )
        val secondKey = SceneBlurAtlasCompatibilityKey(
            sigma = 8f,
            algorithm = BlurAlgorithm.GAUSSIAN
        )
        val firstBatch = copyBatch(
            key = firstKey,
            atlasSize = IntSize(width = 80, height = 40),
            framebufferId = 10,
            placements = listOf(
                copiedPlacement(
                    slotId = "first",
                    drawIndex = 2,
                    atlasRect = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 20, bottom = 30),
                    atlasSampleCrop = SceneBlurAtlasPixelRect(left = 2, top = 4, right = 18, bottom = 26),
                    downsampleScale = 0.5f,
                    blurRadiusMask = null
                ),
                copiedPlacement(
                    slotId = "second",
                    drawIndex = 4,
                    atlasRect = SceneBlurAtlasPixelRect(left = 20, top = 0, right = 50, bottom = 30),
                    atlasSampleCrop = SceneBlurAtlasPixelRect(left = 24, top = 4, right = 46, bottom = 26),
                    downsampleScale = 0.5f
                )
            )
        )
        val secondBatch = copyBatch(
            key = secondKey,
            atlasSize = IntSize(width = 20, height = 20),
            framebufferId = 11,
            placements = listOf(
                copiedPlacement(
                    slotId = "third",
                    drawIndex = 7,
                    atlasRect = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 20, bottom = 20),
                    atlasSampleCrop = SceneBlurAtlasPixelRect(left = 2, top = 2, right = 18, bottom = 18),
                    downsampleScale = 1f
                )
            )
        )
        val copyOutput = SceneBlurAtlasCopyFrameOutput(
            generation = 19L,
            rootSize = IntSize(width = 300, height = 400),
            batches = listOf(firstBatch, secondBatch)
        )

        val output = pass.execute(copyOutput)

        assertEquals(19L, output.generation)
        assertEquals(IntSize(width = 300, height = 400), output.rootSize)
        assertEquals(listOf(firstKey, secondKey), output.batches.map { batch -> batch.key })
        assertSettings(
            expected = SceneBlurAtlasBlurSettings(
                sigma = 4.4f,
                sigmaTexels = 2.2f,
                downsampleScale = 0.5f
            ),
            actual = output.batches[0].settings
        )
        assertSettings(
            expected = SceneBlurAtlasBlurSettings(
                sigma = 8f,
                sigmaTexels = 8f,
                downsampleScale = 1f
            ),
            actual = output.batches[1].settings
        )
        assertSame(firstBatch.atlasTexture, output.batches.first().sourceAtlasTexture)
        assertEquals(SceneBlurAtlasPixelRect(0, 0, 80, 40), output.batches.first().sourceAtlasContentCrop)
        assertEquals(IntSize(width = 96, height = 64), output.batches.first().blurredAtlasAllocatedSize)
        assertEquals(SceneBlurAtlasPixelRect(8, 12, 88, 52), output.batches.first().blurredAtlasContentCrop)
        assertEquals(
            SceneBlurAtlasCoordinateOriginHandoff(
                storageOrigin = CoordinateOrigin.BOTTOM_LEFT,
                logicalLookupOrigin = CoordinateOrigin.TOP_LEFT
            ),
            output.batches.first().coordinateOriginHandoff
        )
        assertEquals(
            SceneBlurAtlasUvRect(
                left = 8f / 96f,
                top = 12f / 64f,
                right = 88f / 96f,
                bottom = 52f / 64f
            ),
            output.batches.first().blurredAtlasContentUv
        )
        assertEquals(
            listOf("first", "second"),
            output.batches.first().placements.map { placement -> placement.slotId.value }
        )
        assertEquals(
            SceneBlurAtlasPixelRect(left = 10, top = 16, right = 26, bottom = 38),
            output.batches.first().placements.first().blurredAtlasSampleCrop
        )
        assertEquals(
            firstBatch.placements.first().atlasSampleCrop,
            output.batches.first().placements.first().sourceAtlasSampleCrop
        )
        assertEquals(
            firstBatch.placements.first().sourceSampleCrop,
            output.batches.first().placements.first().sourceSampleCrop
        )
        assertEquals(null, output.batches.first().placements.first().blurRadiusMask)
        assertEquals(null, output.batches.first().placements[1].blurRadiusMask)
        assertSame(
            output.batches.first().blurredAtlasFramebuffer.colorAttachmentTexture,
            output.batches.first().blurredAtlasTexture
        )
        assertEquals(
            listOf(
                BlurCall(
                    inputFramebuffer = firstBatch.atlasFramebuffer,
                    inputContentSize = IntSize(width = 80, height = 40),
                    sampleCrop = Rect(left = 0f, top = 0f, right = 80f, bottom = 40f),
                    settings = output.batches[0].settings
                ),
                BlurCall(
                    inputFramebuffer = secondBatch.atlasFramebuffer,
                    inputContentSize = IntSize(width = 20, height = 20),
                    sampleCrop = Rect(left = 0f, top = 0f, right = 20f, bottom = 20f),
                    settings = output.batches[1].settings
                )
            ),
            blurrer.calls
        )
    }

    @Test
    fun blurPassMapsLogicalAtlasCropIntoBucketedOutputViewport() {
        val pool = RecordingFramebufferPool(
            allocatedSizes = ArrayDeque(listOf(IntSize(width = 96, height = 64)))
        )
        val pass = SceneBlurAtlasBlurPass(pool, RecordingAtlasBlurrer(pool))
        val copyBatch = copyBatch(
            key = SceneBlurAtlasCompatibilityKey(
                sigma = 4f,
                algorithm = BlurAlgorithm.GAUSSIAN
            ),
            atlasSize = IntSize(width = 80, height = 40),
            framebufferId = 10,
            placements = listOf(
                copiedPlacement(
                    slotId = "slot",
                    drawIndex = 1,
                    atlasRect = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 80, bottom = 40),
                    atlasSampleCrop = SceneBlurAtlasPixelRect(left = 20, top = 10, right = 60, bottom = 30),
                    downsampleScale = 1f,
                    blurRadiusMask = null
                )
            )
        )

        val output = pass.execute(
            SceneBlurAtlasCopyFrameOutput(
                generation = 20L,
                rootSize = IntSize(width = 300, height = 400),
                batches = listOf(copyBatch)
            )
        )
        val lookup = SceneBlurAtlasCompositeLookupAdapter.adapt(output).entries.single()
        val sampleUv = lookup.blurredAtlasTexture.toTestUvRect(lookup.blurredAtlasSampleCrop)

        assertEquals(CoordinateOrigin.BOTTOM_LEFT, output.batches.single().coordinateOriginHandoff.storageOrigin)
        assertEquals(CoordinateOrigin.TOP_LEFT, output.batches.single().coordinateOriginHandoff.logicalLookupOrigin)
        assertEquals(CoordinateOrigin.TOP_LEFT, lookup.blurredAtlasTexture.coordinateOrigin)
        assertEquals(IntSize(width = 96, height = 64), lookup.blurredAtlasTexture.size)
        assertEquals(SceneBlurAtlasPixelRect(left = 8, top = 12, right = 88, bottom = 52), lookup.blurredAtlasContentCrop)
        assertEquals(null, output.batches.single().placements.single().blurRadiusMask)
        assertEquals(SceneBlurAtlasPixelRect(left = 20, top = 10, right = 60, bottom = 30), output.batches.single().placements.single().sourceAtlasSampleCrop)
        assertEquals(
            SceneBlurAtlasUvRect(
                left = 8f / 96f,
                top = 12f / 64f,
                right = 88f / 96f,
                bottom = 52f / 64f
            ),
            lookup.blurredAtlasContentUv
        )
        assertEquals(SceneBlurAtlasPixelRect(left = 28, top = 22, right = 68, bottom = 42), lookup.blurredAtlasSampleCrop)
        assertEquals(40f, sampleUv.width * lookup.blurredAtlasTexture.size.width, 0.0001f)
        assertEquals(20f, sampleUv.height * lookup.blurredAtlasTexture.size.height, 0.0001f)
        assertEquals(2f, sampleUv.width * lookup.blurredAtlasTexture.size.width / (sampleUv.height * lookup.blurredAtlasTexture.size.height), 0.0001f)
    }

    @Test
    fun blurPassRejectsBlurRadiusMaskedPlacementBatchesDeterministically() {
        val pool = RecordingFramebufferPool()
        val pass = SceneBlurAtlasBlurPass(pool, RecordingAtlasBlurrer(pool))
        val maskMetadata = SceneBlurAtlasMaskTextureMetadata(
            textureId = 803,
            size = IntSize(width = 64, height = 64),
            coordinateOrigin = CoordinateOrigin.TOP_LEFT
        )
        val copyBatch = copyBatch(
            key = SceneBlurAtlasCompatibilityKey(
                sigma = 4f,
                algorithm = BlurAlgorithm.GAUSSIAN
            ),
            atlasSize = IntSize(width = 80, height = 40),
            framebufferId = 10,
            placements = listOf(
                copiedPlacement(
                    slotId = "masked",
                    drawIndex = 1,
                    atlasRect = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 40, bottom = 40),
                    atlasSampleCrop = SceneBlurAtlasPixelRect(left = 4, top = 4, right = 36, bottom = 36),
                    downsampleScale = 1f,
                    blurRadiusMask = maskMetadata
                )
            )
        )

        val failure = runCatching {
            pass.execute(
                SceneBlurAtlasCopyFrameOutput(
                    generation = 21L,
                    rootSize = IntSize(width = 300, height = 400),
                    batches = listOf(copyBatch)
                )
            )
        }.exceptionOrNull()

        assertEquals("Blur-radius masked placements must fall back before atlas blur execution", failure?.message)
    }

    @Test
    fun blurPassReleaseReturnsOnlyBorrowedBlurOutputsAndIsIdempotent() {
        val pool = RecordingFramebufferPool()
        val blurrer = RecordingAtlasBlurrer(pool)
        val pass = SceneBlurAtlasBlurPass(pool, blurrer)
        val copyBatch = copyBatch(
            key = SceneBlurAtlasCompatibilityKey(
                sigma = 6f,
                algorithm = BlurAlgorithm.GAUSSIAN
            ),
            atlasSize = IntSize(width = 20, height = 20),
            framebufferId = 10,
            placements = listOf(
                copiedPlacement(
                    slotId = "slot",
                    drawIndex = 1,
                    atlasRect = SceneBlurAtlasPixelRect(0, 0, 20, 20),
                    atlasSampleCrop = SceneBlurAtlasPixelRect(0, 0, 20, 20),
                    downsampleScale = 1f
                )
            )
        )
        val output = pass.execute(
            SceneBlurAtlasCopyFrameOutput(
                generation = 12L,
                rootSize = IntSize(width = 300, height = 400),
                batches = listOf(copyBatch)
            )
        )

        pass.release(output)
        pass.release(output)

        assertEquals(output.batches.map { batch -> batch.blurredAtlasFramebuffer }, pool.releasedFbos)
        assertFalse(pool.releasedFbos.contains(copyBatch.atlasFramebuffer))
    }

    @Test
    fun blurPassSource_usesCopyOutputAndStaysOutOfLiveRendering() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasBlurPass.kt"
        )

        assertTrue(source.contains("SceneBlurAtlasCopyFrameOutput"))
        assertTrue(source.contains("FramebufferLendingPool"))
        assertTrue(source.contains("EffectPipeline"))
        assertFalse("blur pass must not use the legacy command singleton", Regex("\\bRenderCommand\\b").containsMatchIn(source))
        forbiddenOwnerNames.forEach { forbiddenName ->
            assertFalse("SceneBlurAtlasBlurPass.kt must not reference $forbiddenName", source.contains(forbiddenName))
        }

        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt"
        )
        assertTrue(rendererSource.contains("SceneBlurAtlasBlurPass("))
        assertFalse(rendererSource.contains("SceneBlurAtlasBlurFrameOutput"))
        assertFalse(rendererSource.contains("SceneBlurAtlasBlurBatchOutput"))
        assertFalse(rendererSource.contains("SceneBlurAtlasBlurredPlacement"))
    }

    @Test
    fun gaussianAtlasBlurUsesBucketedOutputViewportToPreserveAtlasCropAspect() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianBlurEffect.kt"
        )

        assertFalse(source.contains("bucketed = false"))
        assertTrue(source.contains("context.pool.acquireBucketed(spec)"))
        assertTrue(source.contains("drawIntoContentViewport = true"))
        assertTrue(source.contains("x = outputOffset.x"))
        assertTrue(source.contains("writeTextureCoordinates(uvBounds)"))
    }

    @Test
    fun gaussianAtlasBlurDoesNotExposeMaskedAtlasExecution() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianBlurEffect.kt"
        )

        assertFalse(source.contains("applyMaskedAtlas"))
        assertFalse(source.contains("applyKernelMaskedAtlas"))
        assertFalse(source.contains("AtlasMaskedPlacement"))
        assertFalse(source.contains("drawMaskedAtlasPassTo"))
    }

    @Test
    fun blurSettingsExposeKernelSigmaWithoutFallbackPassFields() {
        val fieldNames = SceneBlurAtlasBlurSettings::class.java.declaredFields
            .filterNot { field -> field.isSynthetic }
            .map { field -> field.name }
            .toSet()

        assertTrue(fieldNames.contains("sigmaTexels"))
        assertFalse(fieldNames.contains("fullIterations"))
        assertFalse(fieldNames.contains("extraWeight"))
    }

    @Test
    fun blurPassOutputStructures_areImmutableAndDoNotExposeOwners() {
        blurOutputTypes.forEach { type ->
            type.declaredFields
                .filterNot { field -> field.isSynthetic }
                .forEach { field ->
                    assertTrue("${type.simpleName}.${field.name} must be final", Modifier.isFinal(field.modifiers))
                    assertFalse("${type.simpleName}.${field.name} must not expose arrays", field.type.isArray)
                    assertFalse(
                        "${type.simpleName}.${field.name} must not expose mutable types",
                        field.genericType.typeName.contains("Mutable")
                    )
                }

            assertFalse(
                "${type.simpleName} must not expose setters",
                type.methods.any { method -> method.name.startsWith("set") }
            )
        }

        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasBlurPass.kt"
        )
        forbiddenOwnerNames.filterNot { forbiddenName -> forbiddenName == "ThreadLocal" }.forEach { forbiddenName ->
            assertFalse("blur output must not reference $forbiddenName", source.contains(forbiddenName))
        }
    }

    private fun copyBatch(
        key: SceneBlurAtlasCompatibilityKey,
        atlasSize: IntSize,
        framebufferId: Int,
        placements: List<SceneBlurAtlasCopiedPlacement>
    ): SceneBlurAtlasCopyBatchOutput {
        val framebuffer = FakeFramebuffer(
            rendererId = framebufferId,
            specification = framebufferSpec(atlasSize),
            textureId = framebufferId + 1000
        )
        return SceneBlurAtlasCopyBatchOutput(
            key = key,
            atlasSize = atlasSize,
            atlasFramebuffer = framebuffer,
            atlasTexture = framebuffer.colorAttachmentTexture,
            placements = placements
        )
    }

    private fun copiedPlacement(
        slotId: String,
        drawIndex: Int,
        atlasRect: SceneBlurAtlasPixelRect,
        atlasSampleCrop: SceneBlurAtlasPixelRect,
        downsampleScale: Float,
        blurRadiusMask: SceneBlurAtlasMaskTextureMetadata? = null
    ): SceneBlurAtlasCopiedPlacement {
        return SceneBlurAtlasCopiedPlacement(
            slotId = BlurSlotId(slotId),
            drawIndex = drawIndex,
            blurRadiusMask = blurRadiusMask,
            sourceCopyRect = SceneBlurAtlasPixelRect(left = 10, top = 20, right = 30, bottom = 50),
            sourceSampleRect = SceneBlurAtlasPixelRect(left = 12, top = 24, right = 28, bottom = 46),
            sourceSampleCrop = SceneBlurAtlasPixelRect(left = 2, top = 4, right = 18, bottom = 26),
            atlasRect = atlasRect,
            atlasSampleCrop = atlasSampleCrop,
            copyScale = SceneBlurAtlasCopyScale(x = 1f, y = 1f),
            downsampleScale = downsampleScale
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

    private fun assertSettings(
        expected: SceneBlurAtlasBlurSettings,
        actual: SceneBlurAtlasBlurSettings
    ) {
        assertEquals(expected.sigma, actual.sigma, 0.0001f)
        assertEquals(expected.sigmaTexels, actual.sigmaTexels, 0.0001f)
        assertEquals(expected.downsampleScale, actual.downsampleScale, 0.0001f)
    }

    private data class BlurCall(
        val inputFramebuffer: Framebuffer,
        val inputContentSize: IntSize,
        val sampleCrop: Rect,
        val settings: SceneBlurAtlasBlurSettings
    )

    private fun SceneBlurAtlasTextureHandle.toTestUvRect(pixelRect: SceneBlurAtlasPixelRect): Rect {
        return Rect(
            left = pixelRect.left.toFloat() / size.width.coerceAtLeast(1),
            top = pixelRect.top.toFloat() / size.height.coerceAtLeast(1),
            right = pixelRect.right.toFloat() / size.width.coerceAtLeast(1),
            bottom = pixelRect.bottom.toFloat() / size.height.coerceAtLeast(1)
        )
    }

    private class RecordingAtlasBlurrer(
        private val pool: RecordingFramebufferPool
    ) : SceneBlurAtlasBatchBlurrer {
        val calls = mutableListOf<BlurCall>()

        override fun blur(
            input: SizedFramebuffer,
            sampleCrop: Rect,
            settings: SceneBlurAtlasBlurSettings
        ): SizedFramebuffer {
            calls += BlurCall(
                inputFramebuffer = input.fbo,
                inputContentSize = input.contentSize,
                sampleCrop = sampleCrop,
                settings = settings
            )
            return pool.borrowOutput(input.contentSize)
        }
    }

    private class RecordingFramebufferPool(
        private val allocatedSizes: ArrayDeque<IntSize> = ArrayDeque()
    ) : FramebufferLendingPool(RenderCommands(RecordingRendererApi())) {
        val acquiredSpecs = mutableListOf<FramebufferSpecification>()
        val releasedFbos = mutableListOf<Framebuffer>()
        private val borrowedFbos = LinkedHashSet<Framebuffer>()
        private var nextRendererId: Int = 200

        fun borrowOutput(contentSize: IntSize): SizedFramebuffer {
            val allocatedSize = allocatedSizes.removeFirstOrNull() ?: contentSize
            return acquire(framebufferSpec(allocatedSize)).withSize(contentSize)
        }

        override fun acquire(spec: FramebufferSpecification): Framebuffer {
            acquiredSpecs += spec
            return FakeFramebuffer(
                rendererId = nextRendererId,
                specification = spec,
                textureId = nextRendererId + 1000,
                coordinateOrigin = spec.attachmentsSpec.attachments.single().coordinateOrigin
            ).also { framebuffer ->
                nextRendererId++
                borrowedFbos += framebuffer
            }
        }

        override fun release(fb: Framebuffer) {
            if (borrowedFbos.remove(fb)) {
                releasedFbos += fb
            }
        }
    }

    private class FakeFramebuffer(
        override val rendererId: Int,
        override val specification: FramebufferSpecification,
        textureId: Int = rendererId + 1000,
        coordinateOrigin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
    ) : Framebuffer {
        override val colorAttachmentTexture: Texture2D = FakeTexture(
            id = textureId,
            size = specification.size,
            coordinateOrigin = coordinateOrigin
        )

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

    private class RecordingRendererApi : RendererApi {
        override val colorBufferBit: Int = 1
        override val stencilBufferBit: Int = 2
        override val linearTextureFilter: Int = 3
        override val stencilAlways: Int = 4
        override val stencilEqual: Int = 5
        override val stencilKeep: Int = 6
        override val stencilReplace: Int = 7

        override fun init() = Unit
        override fun setClearColor(color: Color) = Unit
        override fun clear() = Unit
        override fun enableStencilTest() = Unit
        override fun disableStencilTest() = Unit
        override fun stencilFunc(func: Int, ref: Int, mask: Int) = Unit
        override fun stencilOp(sfail: Int, dpfail: Int, dppass: Int) = Unit
        override fun stencilMask(mask: Int) = Unit
        override fun clearStencil(value: Int) = Unit
        override fun enableScissorTest() = Unit
        override fun disableScissorTest() = Unit
        override fun setScissor(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun clear(mask: Int) = Unit
        override fun drawIndexed(vertexArray: VertexArray, indexCount: Int) = Unit
        override fun setViewPort(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun disableDepthTest() = Unit
        override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = Unit
        override fun enableBlending() = Unit
        override fun disableBlending() = Unit
        override fun bindFramebuffer(bind: Bind, fboId: Int) = Unit
        override fun bindDefaultFramebuffer(bind: Bind) = Unit
        override fun bindDefaultProgram() = Unit
        override fun blitFramebuffer(
            srcX0: Int,
            srcY0: Int,
            srcX1: Int,
            srcY1: Int,
            dstX0: Int,
            dstY0: Int,
            dstX1: Int,
            dstY1: Int,
            mask: Int,
            filter: Int
        ) = Unit

        override fun copyImageSubData(
            srcName: Int,
            srcTarget: Int,
            srcLevel: Int,
            srcX: Int,
            srcY: Int,
            srcZ: Int,
            dstName: Int,
            dstTarget: Int,
            dstLevel: Int,
            dstX: Int,
            dstY: Int,
            dstZ: Int,
            srcWidth: Int,
            srcHeight: Int,
            srcDepth: Int
        ) = Unit
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

        val forbiddenOwnerNames: List<String> = listOf(
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

        val blurOutputTypes: List<Class<*>> = listOf(
            SceneBlurAtlasBlurFrameOutput::class.java,
            SceneBlurAtlasBlurBatchOutput::class.java,
            SceneBlurAtlasBlurredPlacement::class.java,
            SceneBlurAtlasBlurSettings::class.java,
            SceneBlurAtlasCoordinateOriginHandoff::class.java,
            SceneBlurAtlasUvRect::class.java
        )
    }
}
