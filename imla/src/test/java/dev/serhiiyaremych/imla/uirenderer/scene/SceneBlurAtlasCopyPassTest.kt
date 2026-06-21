/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

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
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.legacy.BlurAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.Buffer
import java.nio.file.Files
import java.nio.file.Paths

class SceneBlurAtlasCopyPassTest {
    @Test
    fun copyPass_emptyPreprocessResultReturnsEmptyOutputWithoutAcquiringAtlas() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)
        val pool = RecordingFramebufferPool(commands)
        val copier = RecordingFramebufferCopy()
        val pass = SceneBlurAtlasCopyPass(commands, pool, copier)
        val source = SceneBlurAtlasCopySource(
            sourceFramebuffer = FakeFramebuffer(
                rendererId = 1,
                specification = framebufferSpec(IntSize(width = 300, height = 400))
            ),
            useCopyImage = true
        )
        val preprocessResult = SceneBlurAtlasPreprocessFrameResult(
            generation = 7L,
            rootSize = IntSize(width = 300, height = 400),
            batches = emptyList()
        )

        val output = pass.execute(source, preprocessResult)

        assertEquals(7L, output.generation)
        assertEquals(IntSize(width = 300, height = 400), output.rootSize)
        assertTrue(output.batches.isEmpty())
        assertTrue(pool.acquiredSpecs.isEmpty())
        assertTrue(copier.copyCalls.isEmpty())
    }

    @Test
    fun copyPass_preservesBatchOrderAndCropMetadata() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)
        val pool = RecordingFramebufferPool(commands)
        val copier = RecordingFramebufferCopy()
        val pass = SceneBlurAtlasCopyPass(commands, pool, copier)
        val source = SceneBlurAtlasCopySource(
            sourceFramebuffer = FakeFramebuffer(
                rendererId = 10,
                specification = framebufferSpec(IntSize(width = 300, height = 400)),
                textureId = 100,
                coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
            ),
            useCopyImage = true
        )
        val firstKey = SceneBlurAtlasCompatibilityKey(
            sigma = 8f,
            algorithm = BlurAlgorithm.GAUSSIAN
        )
        val maskMetadata = SceneBlurAtlasMaskTextureMetadata(
            textureId = 701,
            size = IntSize(width = 64, height = 32),
            coordinateOrigin = CoordinateOrigin.TOP_LEFT
        )
        val secondKey = SceneBlurAtlasCompatibilityKey(
            sigma = 12f,
            algorithm = BlurAlgorithm.GAUSSIAN
        )
        val firstPlacement = preprocessPlacement(
            slotId = "first",
            drawIndex = 2,
            sourceCopyRect = SceneBlurAtlasPixelRect(left = 10, top = 20, right = 30, bottom = 50),
            sourceSampleRect = SceneBlurAtlasPixelRect(left = 12, top = 24, right = 28, bottom = 46),
            atlasRect = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 20, bottom = 30),
            atlasSampleCrop = SceneBlurAtlasPixelRect(left = 2, top = 4, right = 18, bottom = 26),
            sourceSampleCrop = SceneBlurAtlasPixelRect(left = 2, top = 4, right = 18, bottom = 26),
            blurRadiusMask = maskMetadata
        )
        val secondPlacement = preprocessPlacement(
            slotId = "second",
            drawIndex = 5,
            sourceCopyRect = SceneBlurAtlasPixelRect(left = 100, top = 120, right = 130, bottom = 150),
            sourceSampleRect = SceneBlurAtlasPixelRect(left = 104, top = 124, right = 126, bottom = 146),
            atlasRect = SceneBlurAtlasPixelRect(left = 20, top = 0, right = 50, bottom = 30),
            atlasSampleCrop = SceneBlurAtlasPixelRect(left = 24, top = 4, right = 46, bottom = 26),
            sourceSampleCrop = SceneBlurAtlasPixelRect(left = 4, top = 4, right = 26, bottom = 26)
        )
        val thirdPlacement = preprocessPlacement(
            slotId = "third",
            drawIndex = 9,
            sourceCopyRect = SceneBlurAtlasPixelRect(left = 40, top = 60, right = 60, bottom = 80),
            sourceSampleRect = SceneBlurAtlasPixelRect(left = 42, top = 62, right = 58, bottom = 78),
            atlasRect = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 20, bottom = 20),
            atlasSampleCrop = SceneBlurAtlasPixelRect(left = 2, top = 2, right = 18, bottom = 18),
            sourceSampleCrop = SceneBlurAtlasPixelRect(left = 2, top = 2, right = 18, bottom = 18)
        )
        val preprocessResult = SceneBlurAtlasPreprocessFrameResult(
            generation = 11L,
            rootSize = IntSize(width = 300, height = 400),
            batches = listOf(
                SceneBlurAtlasBatchPreprocessResult(
                    key = firstKey,
                    atlasSize = IntSize(width = 80, height = 40),
                    placements = listOf(firstPlacement, secondPlacement)
                ),
                SceneBlurAtlasBatchPreprocessResult(
                    key = secondKey,
                    atlasSize = IntSize(width = 20, height = 20),
                    placements = listOf(thirdPlacement)
                )
            )
        )

        val output = pass.execute(source, preprocessResult)

        assertEquals(11L, output.generation)
        assertEquals(IntSize(width = 300, height = 400), output.rootSize)
        assertEquals(listOf(firstKey, secondKey), output.batches.map { batch -> batch.key })
        assertEquals(
            listOf(listOf("first", "second"), listOf("third")),
            output.batches.map { batch -> batch.placements.map { placement -> placement.slotId.value } }
        )
        assertEquals(
            listOf(
                firstPlacement.output.atlasSampleCrop,
                secondPlacement.output.atlasSampleCrop
            ),
            output.batches.first().placements.map { placement -> placement.atlasSampleCrop }
        )
        assertEquals(firstPlacement.output.sourceSampleCrop, output.batches.first().placements.first().sourceSampleCrop)
        assertEquals(maskMetadata, output.batches.first().placements.first().blurRadiusMask)
        assertEquals(null, output.batches.first().placements[1].blurRadiusMask)
        assertEquals(firstPlacement.output.copyScale, output.batches.first().placements.first().copyScale)
        assertEquals(firstPlacement.output.downsampleScale, output.batches.first().placements.first().downsampleScale, 0f)
        assertSame(output.batches.first().atlasFramebuffer.colorAttachmentTexture, output.batches.first().atlasTexture)
        assertEquals(IntSize(width = 80, height = 40), pool.acquiredSpecs[0].size)
        assertEquals(
            CoordinateOrigin.BOTTOM_LEFT,
            pool.acquiredSpecs[0].attachmentsSpec.attachments.single().coordinateOrigin
        )
        assertEquals(FramebufferTextureFormat.RGBA8, pool.acquiredSpecs[0].attachmentsSpec.attachments.single().format)
        assertEquals(
            listOf(
                CopyImageCall(srcX = 10, srcY = 350, dstX = 0, dstY = 10, width = 20, height = 30),
                CopyImageCall(srcX = 100, srcY = 250, dstX = 20, dstY = 10, width = 30, height = 30),
                CopyImageCall(srcX = 40, srcY = 320, dstX = 0, dstY = 0, width = 20, height = 20)
            ),
            copier.copyCalls.map { call ->
                CopyImageCall(
                    srcX = call.srcX,
                    srcY = call.srcY,
                    dstX = call.dstX,
                    dstY = call.dstY,
                    width = call.srcWidth,
                    height = call.srcHeight
                )
            }
        )
    }

    @Test
    fun copyPass_releaseReturnsBorrowedFbosAndIsIdempotent() {
        val api = RecordingRendererApi()
        val commands = RenderCommands(api)
        val pool = RecordingFramebufferPool(commands)
        val pass = SceneBlurAtlasCopyPass(commands, pool, RecordingFramebufferCopy())
        val output = pass.execute(
            source = SceneBlurAtlasCopySource(
                sourceFramebuffer = FakeFramebuffer(
                    rendererId = 10,
                    specification = framebufferSpec(IntSize(width = 300, height = 400)),
                    textureId = 100,
                    coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
                ),
                useCopyImage = true
            ),
            preprocessResult = SceneBlurAtlasPreprocessFrameResult(
                generation = 12L,
                rootSize = IntSize(width = 300, height = 400),
                batches = listOf(
                    SceneBlurAtlasBatchPreprocessResult(
                        key = SceneBlurAtlasCompatibilityKey(
                            sigma = 6f,
                            algorithm = BlurAlgorithm.GAUSSIAN
                        ),
                        atlasSize = IntSize(width = 20, height = 20),
                        placements = listOf(
                            preprocessPlacement(
                                slotId = "slot",
                                drawIndex = 1,
                                sourceCopyRect = SceneBlurAtlasPixelRect(0, 0, 20, 20),
                                sourceSampleRect = SceneBlurAtlasPixelRect(0, 0, 20, 20),
                                atlasRect = SceneBlurAtlasPixelRect(0, 0, 20, 20),
                                atlasSampleCrop = SceneBlurAtlasPixelRect(0, 0, 20, 20),
                                sourceSampleCrop = SceneBlurAtlasPixelRect(0, 0, 20, 20)
                            )
                        )
                    )
                )
            )
        )

        pass.release(output)
        pass.release(output)

        assertEquals(output.batches.map { batch -> batch.atlasFramebuffer }, pool.releasedFbos)
    }

    @Test
    fun copyPassSource_usesContextCommandsAndStaysOutOfLiveRendering() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasCopyPass.kt"
        )

        assertFalse("copy pass must not use the legacy command singleton", Regex("\\bRenderCommand\\b").containsMatchIn(source))
        assertTrue(source.contains("RenderCommands"))
        assertTrue(source.contains("FramebufferLendingPool"))
        listOf(
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
        ).forEach { forbiddenName ->
            assertFalse("SceneBlurAtlasCopyPass.kt must not reference $forbiddenName", source.contains(forbiddenName))
        }

        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt"
        )
        assertTrue(rendererSource.contains("SceneBlurAtlasCopyPass("))
        assertFalse(rendererSource.contains("SceneBlurAtlasCopyFrameOutput"))
        assertFalse(rendererSource.contains("SceneBlurAtlasCopyBatchOutput"))
        assertFalse(rendererSource.contains("SceneBlurAtlasCopiedPlacement"))
    }

    @Test
    fun copyPassOutputStructures_areImmutableAndDoNotExposeOwners() {
        copyOutputTypes.forEach { type ->
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
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasCopyPass.kt"
        )
        listOf(
            "RenderObject",
            "SceneLayerRepository",
            "SceneMaskRepository",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "SceneResourceStore",
            "GLRenderer",
            "GraphicsLayer"
        ).forEach { forbiddenName ->
            assertFalse("copy output must not reference $forbiddenName", source.contains(forbiddenName))
        }
    }

    private fun preprocessPlacement(
        slotId: String,
        drawIndex: Int,
        sourceCopyRect: SceneBlurAtlasPixelRect,
        sourceSampleRect: SceneBlurAtlasPixelRect,
        atlasRect: SceneBlurAtlasPixelRect,
        atlasSampleCrop: SceneBlurAtlasPixelRect,
        sourceSampleCrop: SceneBlurAtlasPixelRect,
        blurRadiusMask: SceneBlurAtlasMaskTextureMetadata? = null
    ): SceneBlurAtlasPreprocessPlacement {
        return SceneBlurAtlasPreprocessPlacement(
            request = SceneBlurAtlasPreprocessRequest(
                slotId = BlurSlotId(slotId),
                drawIndex = drawIndex,
                blurRadiusMask = blurRadiusMask,
                sourceCopyRect = sourceCopyRect,
                sourceSampleRect = sourceSampleRect,
                atlasRect = atlasRect,
                atlasSampleCrop = atlasSampleCrop
            ),
            output = SceneBlurAtlasPreprocessOutput(
                blurRadiusMask = blurRadiusMask,
                atlasRect = atlasRect,
                atlasSampleCrop = atlasSampleCrop,
                sourceCopyRect = sourceCopyRect,
                sourceSampleCrop = sourceSampleCrop,
                copyScale = SceneBlurAtlasCopyScale(x = 1f, y = 1f),
                downsampleScale = 1f
            )
        )
    }

    private fun framebufferSpec(size: IntSize): FramebufferSpecification {
        return FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
            )
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

    private data class CopyImageCall(
        val srcX: Int,
        val srcY: Int,
        val dstX: Int,
        val dstY: Int,
        val width: Int,
        val height: Int
    )

    private data class RecordedCopyImageCall(
        val srcX: Int,
        val srcY: Int,
        val dstX: Int,
        val dstY: Int,
        val srcWidth: Int,
        val srcHeight: Int
    )

    private class RecordingFramebufferCopy : SceneBlurAtlasFramebufferCopy {
        val copyCalls = mutableListOf<RecordedCopyImageCall>()

        override fun copy(
            src: Framebuffer,
            dst: Framebuffer,
            srcX0: Int,
            srcY0: Int,
            srcX1: Int,
            srcY1: Int,
            dstX0: Int,
            dstY0: Int,
            dstX1: Int,
            dstY1: Int,
            useCopyImage: Boolean
        ) {
            copyCalls += RecordedCopyImageCall(
                srcX = srcX0,
                srcY = srcY0,
                dstX = dstX0,
                dstY = dstY0,
                srcWidth = srcX1 - srcX0,
                srcHeight = srcY1 - srcY0
            )
        }
    }

    private class RecordingFramebufferPool(commands: RenderCommands) : FramebufferLendingPool(commands) {
        val acquiredSpecs = mutableListOf<FramebufferSpecification>()
        val releasedFbos = mutableListOf<Framebuffer>()
        private val borrowedFbos = LinkedHashSet<Framebuffer>()
        private var nextRendererId: Int = 200

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
        val copyOutputTypes: List<Class<*>> = listOf(
            SceneBlurAtlasCopySource::class.java,
            SceneBlurAtlasCopyFrameOutput::class.java,
            SceneBlurAtlasCopyBatchOutput::class.java,
            SceneBlurAtlasCopiedPlacement::class.java
        )
    }
}
