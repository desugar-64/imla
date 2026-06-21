/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.BlurAlgorithm
import dev.serhiiyaremych.imla.internal.legacy.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.Buffer
import java.nio.file.Files
import java.nio.file.Paths

class SceneBlurAtlasBackdropCompositePassTest {
    @Test
    fun pass_buildsAndSubmitsScreenSpaceQuadFromAtlasLookup() {
        val sink = RecordingDrawSink()
        val pass = SceneBlurAtlasBackdropCompositePass(sink)
        val transform = Mat4.identity()
        val style = Style.default.copy(
            blurOpacity = 0.42f,
            tint = Color(red = 0.2f, green = 0.3f, blue = 0.4f, alpha = 0.5f)
        )
        val lookup = lookupEntry(
            blurredAtlasTexture = SceneBlurAtlasTextureHandle(
                textureId = 42,
                size = IntSize(width = 200, height = 100),
                coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
            ),
            sourceSampleRect = SceneBlurAtlasPixelRect(left = 10, top = 20, right = 60, bottom = 80),
            blurredAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 20, top = 10, right = 70, bottom = 60)
        )

        pass.execute(
            lookup = lookup,
            slot = slotPlan(style = style),
            transform = transform,
            style = style,
            targetSize = IntSize(width = 300, height = 200),
            noiseTexture = null,
            maskTexture = null
        )

        assertEquals(1, sink.screenSpaceCalls.size)
        assertTrue(sink.backdropCompositeCalls.isEmpty())
        val call = sink.screenSpaceCalls.single()
        val draw = call.draw
        val quad = draw.quad
        assertEquals(IntSize(width = 300, height = 200), call.targetSize)
        assertEquals(Rect(left = 10f, top = 20f, right = 60f, bottom = 80f), draw.sampleArea)
        assertEquals(Rect(left = 0.1f, top = 0.1f, right = 0.35f, bottom = 0.6f), draw.sampleUv)
        assertEquals("slot", quad.id)
        assertEquals(35f, quad.center.x, 0f)
        assertEquals(50f, quad.center.y, 0f)
        assertEquals(50f, quad.size.width, 0f)
        assertEquals(60f, quad.size.height, 0f)
        assertEquals(draw.sampleUv, quad.uv)
        assertEquals(42, quad.texture?.id)
        assertEquals(IntSize(width = 200, height = 100), quad.texture?.specification?.size)
        assertEquals(0.42f, quad.alpha, 0f)
        assertEquals(1f, quad.maskValue, 0f)
        assertEquals(style.tint, quad.tint)
        assertSame(transform, quad.transform)
        assertEquals(false, quad.flipTexture)
    }

    @Test
    fun pass_usesAtlasTextureOriginMetadataWithoutChangingUvRect() {
        val draw = SceneBlurAtlasBackdropCompositeDraw.from(
            lookup = lookupEntry(
                blurredAtlasTexture = SceneBlurAtlasTextureHandle(
                    textureId = 43,
                    size = IntSize(width = 128, height = 64),
                    coordinateOrigin = CoordinateOrigin.TOP_LEFT
                ),
                blurredAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 32, top = 8, right = 96, bottom = 40)
            ),
            slot = slotPlan(),
            transform = Mat4.identity(),
            style = Style.default
        )

        assertEquals(Rect(left = 0.25f, top = 0.125f, right = 0.75f, bottom = 0.625f), draw.sampleUv)
        assertEquals(true, draw.quad.flipTexture)
        assertEquals(CoordinateOrigin.TOP_LEFT, draw.quad.texture?.coordinateOrigin)
    }

    @Test
    fun pass_preservesStyleAndDelegatesNoiseMaskPath() {
        val sink = RecordingDrawSink()
        val pass = SceneBlurAtlasBackdropCompositePass(sink)
        val noiseTexture = FakeTexture(id = 80, size = IntSize(width = 16, height = 16))
        val maskTexture = FakeTexture(id = 81, size = IntSize(width = 32, height = 32))
        val style = Style.default.copy(
            sigma = 6f,
            noiseAlpha = 0.34f,
            blurOpacity = 0.67f,
            tint = Color.Magenta
        )

        pass.execute(
            lookup = lookupEntry(),
            slot = slotPlan(style = style, maskTexture = maskTexture),
            transform = Mat4.identity(),
            style = style,
            targetSize = IntSize(width = 300, height = 200),
            noiseTexture = noiseTexture,
            maskTexture = maskTexture
        )

        assertTrue(sink.screenSpaceCalls.isEmpty())
        val call = sink.backdropCompositeCalls.single()
        assertEquals(style, call.style)
        assertEquals(IntSize(width = 300, height = 200), call.targetSize)
        assertSame(noiseTexture, call.noiseTexture)
        assertSame(maskTexture, call.maskTexture)
        assertEquals(0.67f, call.draw.quad.alpha, 0f)
        assertEquals(Color.Magenta, call.draw.quad.tint)
    }

    @Test
    fun pass_routesNoisyAtlasLookupThroughBackdropCompositeEffect() {
        val sink = RecordingDrawSink()
        val pass = SceneBlurAtlasBackdropCompositePass(sink)
        val noiseTexture = FakeTexture(id = 82, size = IntSize(width = 16, height = 16))
        val style = Style.default.copy(
            noiseAlpha = 0.24f,
            blurOpacity = 0.68f,
            tint = Color.Green
        )

        pass.execute(
            lookup = lookupEntry(),
            slot = slotPlan(style = style),
            transform = Mat4.identity(),
            style = style,
            targetSize = IntSize(width = 300, height = 200),
            noiseTexture = noiseTexture,
            maskTexture = null
        )

        assertTrue(sink.screenSpaceCalls.isEmpty())
        val call = sink.backdropCompositeCalls.single()
        assertSame(noiseTexture, call.noiseTexture)
        assertEquals(0.24f, call.style.noiseAlpha, 0f)
        assertEquals(0.68f, call.draw.quad.alpha, 0f)
        assertEquals(Color.Green, call.draw.quad.tint)
    }

    @Test
    fun pass_routesCoverageMaskAtlasLookupThroughBackdropCompositeEffect() {
        val sink = RecordingDrawSink()
        val pass = SceneBlurAtlasBackdropCompositePass(sink)
        val maskTexture = FakeTexture(
            id = 83,
            size = IntSize(width = 32, height = 32),
            coordinateOrigin = CoordinateOrigin.TOP_LEFT
        )

        pass.execute(
            lookup = lookupEntry(),
            slot = slotPlan(compositeCoverageMask = maskTexture),
            transform = Mat4.identity(),
            style = Style.default,
            targetSize = IntSize(width = 300, height = 200),
            noiseTexture = null,
            maskTexture = maskTexture
        )

        assertTrue(sink.screenSpaceCalls.isEmpty())
        val call = sink.backdropCompositeCalls.single()
        assertSame(maskTexture, call.maskTexture)
    }

    @Test
    fun liveRendererWiresAtlasBackdropCompositeBehindInternalFlag() {
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt"
        )
        val slotRunnerSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt"
        )
        val scenePublicBoundary = scenePublicBoundarySource()

        assertTrue(rendererSource.contains("SceneBlurAtlasBackdropCompositePass("))
        assertTrue(slotRunnerSource.contains("atlasRenderConfig.enabled"))
        assertTrue(slotRunnerSource.contains("atlasBackdropCompositePass"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasBackdropCompositePass"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasRenderConfig"))
    }

    @Test
    fun pass_drawsFromAtlasLookupAndNotPerSlotBlurOutput() {
        val source = passSource()

        assertTrue(source.contains("lookup.blurredAtlasSampleCrop"))
        assertTrue(source.contains("lookup.blurredAtlasTexture"))
        assertTrue(source.contains("SceneBlurAtlasCompositeLookupEntry"))
        assertFalse(source.contains("GaussianBlurEffect"))
    }

    @Test
    fun pass_reusesBackdropCompositeShaderConcepts() {
        val source = passSource()

        assertTrue(source.contains("QuadBatchRenderer"))
        assertTrue(source.contains("BackdropCompositeEffect"))
        assertTrue(source.contains("screen_space_quad"))
        assertTrue(source.contains("u_SampleRect"))
        assertTrue(source.contains("u_SampleUvMin"))
        assertTrue(source.contains("u_SampleUvMax"))
        assertTrue(source.contains("drawWithBackdropCompositeEffect"))
    }

    @Test
    fun pass_doesNotIntroduceManualUvFlipOrRotationSemantics() {
        val source = passSource()

        assertFalse(source.contains("needsFlipForOpenGL"))
        assertFalse(source.contains("CoordinateOrigin."))
        assertFalse(source.contains("1.0 -"))
        assertFalse(source.contains("1f -"))
        assertFalse(source.contains("uvRotation"))
        assertFalse(source.contains("rotationZ"))
    }

    @Test
    fun pass_doesNotReferenceSceneOwnershipTypes() {
        val source = passSource()
        listOf(
            "RenderObject",
            "Repository",
            "GraphicsLayer",
            "GLRenderer",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "SceneResourceStore",
            "FramebufferLendingPool"
        ).forEach { forbiddenName ->
            assertFalse(
                "SceneBlurAtlasBackdropCompositePass.kt must not reference $forbiddenName",
                source.contains(forbiddenName)
            )
        }

        listOf(
            SceneBlurAtlasBackdropCompositePass::class.java,
            SceneBlurAtlasBackdropCompositeDraw::class.java
        ).assertNoForbiddenType(
            "RenderObject",
            "Repository",
            "GraphicsLayer",
            "GLRenderer",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "SceneResourceStore"
        )
    }

    private fun lookupEntry(
        blurredAtlasTexture: SceneBlurAtlasTextureHandle = SceneBlurAtlasTextureHandle(
            textureId = 42,
            size = IntSize(width = 100, height = 100),
            coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
        ),
        sourceSampleRect: SceneBlurAtlasPixelRect = SceneBlurAtlasPixelRect(
            left = 12,
            top = 22,
            right = 40,
            bottom = 50
        ),
        blurredAtlasSampleCrop: SceneBlurAtlasPixelRect = SceneBlurAtlasPixelRect(
            left = 2,
            top = 2,
            right = 30,
            bottom = 30
        )
    ): SceneBlurAtlasCompositeLookupEntry {
        val blurredAtlasContentCrop = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 32, bottom = 32)
        return SceneBlurAtlasCompositeLookupEntry(
            slotId = BlurSlotId("slot"),
            drawIndex = 3,
            compatibilityKey = SceneBlurAtlasCompatibilityKey(
                sigma = 8f,
                algorithm = BlurAlgorithm.GAUSSIAN
            ),
            blurredAtlasTexture = blurredAtlasTexture,
            sourceCopyRect = SceneBlurAtlasPixelRect(left = 10, top = 20, right = 42, bottom = 52),
            sourceSampleRect = sourceSampleRect,
            sourceSampleCrop = SceneBlurAtlasPixelRect(left = 2, top = 2, right = 30, bottom = 30),
            sourceAtlasContentCrop = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 32, bottom = 32),
            sourceAtlasRect = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 32, bottom = 32),
            sourceAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 2, top = 2, right = 30, bottom = 30),
            blurredAtlasContentCrop = blurredAtlasContentCrop,
            blurredAtlasContentUv = blurredAtlasContentCrop.toTestUvRect(blurredAtlasTexture.size),
            blurredAtlasSampleCrop = blurredAtlasSampleCrop,
            copyScale = SceneBlurAtlasCopyScale(x = 1f, y = 1f),
            downsampleScale = 1f
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

    private fun slotPlan(
        style: Style = Style.default,
        maskTexture: Texture2D? = null,
        blurRadiusMask: Texture2D? = maskTexture,
        compositeCoverageMask: Texture2D? = maskTexture
    ): SceneSlotPlan {
        return SceneSlotPlan(
            id = BlurSlotId("slot"),
            drawIndex = 3,
            debugName = "slot",
            area = Rect(left = 10f, top = 20f, right = 60f, bottom = 80f),
            localRect = Rect(left = 0f, top = 0f, right = 50f, bottom = 60f),
            contentSize = IntSize(width = 50, height = 60),
            transform = Mat4.identity(),
            zIndex = 0f,
            style = style,
            contentTexture = null,
            hasBlurRadiusMask = blurRadiusMask != null,
            blurRadiusMask = blurRadiusMask,
            compositeCoverageMask = compositeCoverageMask,
            clipTexture = null,
            dirtyFlags = BlurSlotDirtyFlags()
        )
    }

    private fun passSource(): String {
        return sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasBackdropCompositePass.kt"
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

    private fun List<Class<*>>.assertNoForbiddenType(vararg forbiddenNames: String) {
        forEach { type ->
            val fieldTypes = type.declaredFields
                .filterNot { field -> field.isSynthetic }
                .map { field -> field.genericType.typeName }
            val constructorTypes = type.declaredConstructors
                .flatMap { constructor -> constructor.genericParameterTypes.asList() }
                .map { parameterType -> parameterType.typeName }
            val methodTypes = type.declaredMethods
                .filterNot { method -> method.isSynthetic }
                .flatMap { method ->
                    method.genericParameterTypes.asList() + method.genericReturnType
                }
                .map { parameterType -> parameterType.typeName }

            val referencedTypes = fieldTypes + constructorTypes + methodTypes
            forbiddenNames.forEach { forbiddenName ->
                assertFalse(
                    "${type.simpleName} must not reference $forbiddenName",
                    referencedTypes.any { referencedType -> referencedType.contains(forbiddenName) }
                )
            }
        }
    }

    private data class ScreenSpaceCall(
        val draw: SceneBlurAtlasBackdropCompositeDraw,
        val targetSize: IntSize
    )

    private data class BackdropCompositeCall(
        val draw: SceneBlurAtlasBackdropCompositeDraw,
        val style: Style,
        val targetSize: IntSize,
        val noiseTexture: Texture2D?,
        val maskTexture: Texture2D?
    )

    private class RecordingDrawSink : SceneBlurAtlasBackdropCompositeDrawSink {
        val screenSpaceCalls = mutableListOf<ScreenSpaceCall>()
        val backdropCompositeCalls = mutableListOf<BackdropCompositeCall>()

        override fun drawScreenSpace(
            draw: SceneBlurAtlasBackdropCompositeDraw,
            targetSize: IntSize
        ) {
            screenSpaceCalls += ScreenSpaceCall(draw = draw, targetSize = targetSize)
        }

        override fun drawWithBackdropCompositeEffect(
            draw: SceneBlurAtlasBackdropCompositeDraw,
            style: Style,
            targetSize: IntSize,
            noiseTexture: Texture2D?,
            maskTexture: Texture2D?
        ) {
            backdropCompositeCalls += BackdropCompositeCall(
                draw = draw,
                style = style,
                targetSize = targetSize,
                noiseTexture = noiseTexture,
                maskTexture = maskTexture
            )
        }
    }

    private class FakeTexture(
        override val id: Int,
        size: IntSize,
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
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
}
