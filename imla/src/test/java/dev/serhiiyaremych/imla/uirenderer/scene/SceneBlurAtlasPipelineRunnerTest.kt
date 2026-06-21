/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
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
import dev.serhiiyaremych.imla.internal.legacy.Style
import dev.serhiiyaremych.imla.internal.render.processing.effects.SizedFramebuffer
import dev.serhiiyaremych.imla.internal.render.processing.effects.withSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.Buffer
import java.nio.file.Files
import java.nio.file.Paths

class SceneBlurAtlasPipelineRunnerTest {
    @Test
    fun runner_emptyPlacementPlanReturnsEmptyOutputWithoutGlStages() {
        val copyStage = RecordingCopyStage()
        val blurStage = RecordingBlurStage()
        val runner = SceneBlurAtlasPipelineRunner(copyStage, blurStage)
        val frame = renderFrame(
            rootSize = IntSize(width = 100, height = 100),
            slots = listOf(
                record(
                    id = "outside",
                    drawIndex = 1,
                    area = Rect(left = 200f, top = 200f, right = 300f, bottom = 300f)
                )
            )
        )

        val preflight = SceneBlurAtlasPipelinePreflightPlanner.Default.plan(frame)
        val output = runner.execute(preflight, sourceFramebuffer())

        assertEquals(frame.generation, output.generation)
        assertEquals(frame.rootSize, output.rootSize)
        assertTrue(output.isEmpty)
        assertTrue(output.lookupOutput.entries.isEmpty())
        assertNull(output.copyOutput)
        assertNull(output.blurOutput)
        assertEquals(listOf(BlurSlotId("outside")), output.atlasPlan.skipped.map { skipped -> skipped.slotId })
        assertTrue(output.placementPlan.batches.isEmpty())
        assertTrue(copyStage.events.isEmpty())
        assertTrue(blurStage.events.isEmpty())
    }

    @Test
    fun runner_executesStagesInExpectedOrder() {
        val events = mutableListOf<String>()
        val frame = renderFrame()
        val atlasPlan = SceneBlurAtlasPlanner.plan(frame)
        val placementPlan = SceneBlurAtlasPlacementPlanner.plan(atlasPlan)
        val preprocessResult = SceneBlurAtlasPreprocessPlanner.plan(placementPlan)
        val preflight = SceneBlurAtlasPipelinePreflight(
            atlasPlan = atlasPlan,
            placementPlan = placementPlan,
            preprocessResult = preprocessResult
        )
        val copyOutput = copyOutput(preprocessResult)
        val blurOutput = blurOutput(copyOutput)
        val lookupOutput = SceneBlurAtlasCompositeLookupAdapter.adapt(blurOutput)
        val copyStage = RecordingCopyStage(events, copyOutput)
        val blurStage = RecordingBlurStage(events, blurOutput)
        val runner = SceneBlurAtlasPipelineRunner(
            copyStage = copyStage,
            blurStage = blurStage,
            lookupStage = SceneBlurAtlasLookupStage {
                events += "lookup"
                lookupOutput
            }
        )

        val output = runner.execute(preflight, sourceFramebuffer())

        assertEquals(listOf("copy", "blur", "lookup"), events)
        assertSame(preflight, output.preflight)
        assertSame(atlasPlan, output.atlasPlan)
        assertSame(placementPlan, output.placementPlan)
        assertSame(preprocessResult, output.preprocessResult)
        assertSame(copyOutput, output.copyOutput)
        assertSame(blurOutput, output.blurOutput)
        assertSame(lookupOutput, output.lookupOutput)
    }

    @Test
    fun runner_recordsCopyBlurAndLookupOutputCounters() {
        val frame = renderFrame()
        val preflight = SceneBlurAtlasPipelinePreflightPlanner.Default.plan(frame)
        val copyOutput = copyOutput(preflight.preprocessResult)
        val blurOutput = blurOutput(copyOutput)
        val runner = SceneBlurAtlasPipelineRunner(
            copyStage = RecordingCopyStage(output = copyOutput),
            blurStage = RecordingBlurStage(output = blurOutput)
        )

        withRecordingSceneTraceCounters { recorder ->
            val output = runner.execute(preflight, sourceFramebuffer())

            assertSame(copyOutput, output.copyOutput)
            assertSame(blurOutput, output.blurOutput)
            assertEquals(1, recorder.count("atlas.copy.batch"))
            assertEquals(1, recorder.count("atlas.blur.batch"))
            assertEquals(1, recorder.count("atlas.lookup.entry"))
            assertEquals(listOf(1L), recorder.values("gauge.atlas.copy.batchCount"))
            assertEquals(listOf(1L), recorder.values("gauge.atlas.blur.batchCount"))
            assertEquals(listOf(1L), recorder.values("gauge.atlas.lookup.entryCount"))
        }
    }

    @Test
    fun runner_lookupEntriesAreProducedFromBlurredAtlasOutput() {
        val frame = renderFrame()
        val atlasPlan = SceneBlurAtlasPlanner.plan(frame)
        val placementPlan = SceneBlurAtlasPlacementPlanner.plan(atlasPlan)
        val preprocessResult = SceneBlurAtlasPreprocessPlanner.plan(placementPlan)
        val preflight = SceneBlurAtlasPipelinePreflight(
            atlasPlan = atlasPlan,
            placementPlan = placementPlan,
            preprocessResult = preprocessResult
        )
        val copyOutput = copyOutput(preprocessResult)
        val blurOutput = blurOutput(copyOutput, blurredTextureId = 702)
        val runner = SceneBlurAtlasPipelineRunner(
            copyStage = RecordingCopyStage(output = copyOutput),
            blurStage = RecordingBlurStage(output = blurOutput)
        )

        val output = runner.execute(preflight, sourceFramebuffer())

        val entry = output.lookupOutput.entries.single()
        val blurredBatch = blurOutput.batches.single()
        val blurredPlacement = blurredBatch.placements.single()
        assertEquals(BlurSlotId("slot"), entry.slotId)
        assertEquals(blurredBatch.key, entry.compatibilityKey)
        assertEquals(SceneBlurAtlasTextureHandle(702, blurredBatch.blurredAtlasTexture.specification.size, CoordinateOrigin.TOP_LEFT), entry.blurredAtlasTexture)
        assertEquals(blurredBatch.blurredAtlasContentCrop, entry.blurredAtlasContentCrop)
        assertEquals(blurredPlacement.blurredAtlasSampleCrop, entry.blurredAtlasSampleCrop)
    }

    @Test
    fun runner_releaseReturnsBlurThenCopyAndIsIdempotent() {
        val events = mutableListOf<String>()
        val frame = renderFrame()
        val preflight = SceneBlurAtlasPipelinePreflightPlanner.Default.plan(frame)
        val preprocessResult = preflight.preprocessResult
        val copyOutput = copyOutput(preprocessResult)
        val blurOutput = blurOutput(copyOutput)
        val copyStage = RecordingCopyStage(events, copyOutput)
        val blurStage = RecordingBlurStage(events, blurOutput)
        val runner = SceneBlurAtlasPipelineRunner(copyStage, blurStage)
        val output = runner.execute(preflight, sourceFramebuffer())
        events.clear()

        runner.release(output)
        runner.release(output)

        assertEquals(listOf("releaseBlur", "releaseCopy"), events)
    }

    @Test
    fun runner_copyFailureReleasesPartiallyCopiedResources() {
        val frame = renderFrame()
        val preprocessResult = preprocessResult(
            generation = frame.generation,
            rootSize = frame.rootSize,
            batchCount = 2
        )
        val copyPool = RecordingFramebufferPool()
        val copyPass = SceneBlurAtlasCopyPass(
            commands = RenderCommands(RecordingRendererApi()),
            fboPool = copyPool,
            framebufferCopy = ThrowingFramebufferCopy(failOnCall = 2)
        )
        val blurStage = RecordingBlurStage()
        val runner = SceneBlurAtlasPipelineRunner(
            copyStage = RealCopyStage(copyPass),
            blurStage = blurStage
        )

        val failure = expectStageFailure("copy") {
            runner.execute(preflightWith(frame, preprocessResult), sourceFramebuffer())
        }

        assertEquals("copy", failure.message)
        assertEquals(copyPool.acquiredFbos.toSet(), copyPool.releasedFbos.toSet())
        assertTrue(blurStage.events.isEmpty())
    }

    @Test
    fun runner_blurFailureReleasesBlurResourcesAcquiredSoFarAndCopyOutput() {
        val frame = renderFrame()
        val preprocessResult = preprocessResult(
            generation = frame.generation,
            rootSize = frame.rootSize,
            batchCount = 2
        )
        val copyOutput = copyOutput(preprocessResult)
        val copyStage = RecordingCopyStage(output = copyOutput)
        val blurPool = RecordingFramebufferPool()
        val blurPass = SceneBlurAtlasBlurPass(
            blurOutputPool = blurPool,
            batchBlurrer = ThrowingAtlasBlurrer(pool = blurPool, failOnCall = 2)
        )
        val runner = SceneBlurAtlasPipelineRunner(
            copyStage = copyStage,
            blurStage = RealBlurStage(blurPass)
        )

        val failure = expectStageFailure("blur") {
            runner.execute(preflightWith(frame, preprocessResult), sourceFramebuffer())
        }

        assertEquals("blur", failure.message)
        assertEquals(blurPool.acquiredFbos, blurPool.releasedFbos)
        assertEquals(listOf(copyOutput), copyStage.releasedOutputs)
    }

    @Test
    fun liveRendererWiresAtlasPipelineBehindInternalDefaultOnFlag() {
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt"
        )
        val slotRunnerSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt"
        )
        val configSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasRenderConfig.kt"
        )
        val diagnosticSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasDiagnosticMode.kt"
        )
        val scenePublicBoundary = scenePublicBoundarySource()

        assertTrue(configSource.contains("val enabled: Boolean = true"))
        assertTrue(configSource.contains("val Enabled = SceneBlurAtlasRenderConfig()"))
        assertTrue(configSource.contains("val Disabled = SceneBlurAtlasRenderConfig(enabled = false)"))
        assertFalse(configSource.contains("blurRadiusMasksEnabled"))
        assertTrue(diagnosticSource.contains("ImlaAtlasDisabled"))
        assertTrue(diagnosticSource.contains("Log.isLoggable(ATLAS_DISABLED_TAG, Log.DEBUG)"))
        assertTrue(rendererSource.contains("SceneBlurAtlasDiagnosticMode.renderConfig()"))
        assertTrue(rendererSource.contains("atlasPipelineRunnerDelegate = lazy"))
        assertTrue(rendererSource.contains("SceneBlurAtlasPipelineRunner("))
        assertTrue(rendererSource.contains("atlasRenderConfig.enabled && graphicsContextProvider().supportsFastTextureOps()"))
        assertTrue(slotRunnerSource.contains("if (!atlasRenderConfig.enabled) return null"))
        assertFalse(slotRunnerSource.contains("atlasRenderConfig.blurRadiusMasksEnabled"))
        assertTrue(slotRunnerSource.contains("atlasPreflightPlanner.plan(frame)"))
        assertTrue(slotRunnerSource.contains("pipeline.execute("))
        assertTrue(slotRunnerSource.contains("preflight = preflight"))
        assertTrue(slotRunnerSource.contains("finally {\n            atlasFrameState?.release()\n        }"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasPipelineRunner"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasRenderConfig"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasDiagnosticMode"))
        assertFalse(scenePublicBoundary.contains("ImlaSceneAtlas"))
    }

    @Test
    fun pipelineOutputStructures_areImmutableAndDoNotExposeOwners() {
        pipelineOutputTypes.forEach { type ->
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
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasPipelineRunner.kt"
        )
        assertFalse(source.contains("ThreadLocal"))
        forbiddenOwnerTypeNames.forEach { forbiddenName ->
            assertFalse("pipeline runner source must not reference $forbiddenName", source.contains(forbiddenName))
        }
    }

    private fun preflightWith(
        frame: SceneRenderFrame,
        preprocessResult: SceneBlurAtlasPreprocessFrameResult
    ): SceneBlurAtlasPipelinePreflight {
        val atlasPlan = SceneBlurAtlasPlanner.plan(frame)
        return SceneBlurAtlasPipelinePreflight(
            atlasPlan = atlasPlan,
            placementPlan = SceneBlurAtlasPlacementPlanner.plan(atlasPlan),
            preprocessResult = preprocessResult
        )
    }

    private fun preprocessResult(
        generation: Long,
        rootSize: IntSize,
        batchCount: Int
    ): SceneBlurAtlasPreprocessFrameResult {
        return SceneBlurAtlasPreprocessFrameResult(
            generation = generation,
            rootSize = rootSize,
            batches = (0 until batchCount).map { index ->
                SceneBlurAtlasBatchPreprocessResult(
                    key = compatibilityKey(sigma = 4f + index),
                    atlasSize = IntSize(width = 32, height = 32),
                    placements = listOf(preprocessPlacement(slotId = "slot-$index", drawIndex = index))
                )
            }
        )
    }

    private fun copyOutput(preprocessResult: SceneBlurAtlasPreprocessFrameResult): SceneBlurAtlasCopyFrameOutput {
        return SceneBlurAtlasCopyFrameOutput(
            generation = preprocessResult.generation,
            rootSize = preprocessResult.rootSize,
            batches = preprocessResult.batches.mapIndexedNotNull { index, batch ->
                if (batch.placements.isEmpty()) return@mapIndexedNotNull null
                val framebuffer = FakeFramebuffer(
                    rendererId = 100 + index,
                    specification = framebufferSpec(batch.atlasSize),
                    textureId = 500 + index
                )
                SceneBlurAtlasCopyBatchOutput(
                    key = batch.key,
                    atlasSize = batch.atlasSize,
                    atlasFramebuffer = framebuffer,
                    atlasTexture = framebuffer.colorAttachmentTexture,
                    placements = batch.placements.map { placement ->
                        copiedPlacement(placement)
                    }
                )
            }
        )
    }

    private fun blurOutput(
        copyOutput: SceneBlurAtlasCopyFrameOutput,
        blurredTextureId: Int = 700
    ): SceneBlurAtlasBlurFrameOutput {
        return SceneBlurAtlasBlurFrameOutput(
            generation = copyOutput.generation,
            rootSize = copyOutput.rootSize,
            batches = copyOutput.batches.mapIndexed { index, batch ->
                val framebuffer = FakeFramebuffer(
                    rendererId = 300 + index,
                    specification = framebufferSpec(batch.atlasSize),
                    textureId = blurredTextureId + index
                )
                SceneBlurAtlasBlurBatchOutput(
                    key = batch.key,
                    settings = SceneBlurAtlasBlurSettings(
                        sigma = batch.key.sigma,
                        sigmaTexels = batch.key.sigma,
                        downsampleScale = 1f
                    ),
                    atlasSize = batch.atlasSize,
                    sourceAtlasTexture = batch.atlasTexture,
                    sourceAtlasContentCrop = SceneBlurAtlasPixelRect(0, 0, batch.atlasSize.width, batch.atlasSize.height),
                    blurredAtlasFramebuffer = framebuffer,
                    blurredAtlasTexture = framebuffer.colorAttachmentTexture,
                    blurredAtlasAllocatedSize = batch.atlasSize,
                    blurredAtlasContentCrop = SceneBlurAtlasPixelRect(0, 0, batch.atlasSize.width, batch.atlasSize.height),
                    blurredAtlasContentUv = SceneBlurAtlasUvRect(0f, 0f, 1f, 1f),
                    coordinateOriginHandoff = SceneBlurAtlasCoordinateOriginHandoff.fromStorage(
                        framebuffer.colorAttachmentTexture.coordinateOrigin
                    ),
                    placements = batch.placements.map { placement ->
                        blurredPlacement(placement)
                    }
                )
            }
        )
    }

    private fun copiedPlacement(placement: SceneBlurAtlasPreprocessPlacement): SceneBlurAtlasCopiedPlacement {
        return SceneBlurAtlasCopiedPlacement(
            slotId = placement.request.slotId,
            drawIndex = placement.request.drawIndex,
            blurRadiusMask = placement.output.blurRadiusMask,
            sourceCopyRect = placement.output.sourceCopyRect,
            sourceSampleRect = placement.request.sourceSampleRect,
            sourceSampleCrop = placement.output.sourceSampleCrop,
            atlasRect = placement.output.atlasRect,
            atlasSampleCrop = placement.output.atlasSampleCrop,
            copyScale = placement.output.copyScale,
            downsampleScale = placement.output.downsampleScale
        )
    }

    private fun blurredPlacement(placement: SceneBlurAtlasCopiedPlacement): SceneBlurAtlasBlurredPlacement {
        return SceneBlurAtlasBlurredPlacement(
            slotId = placement.slotId,
            drawIndex = placement.drawIndex,
            blurRadiusMask = placement.blurRadiusMask,
            sourceCopyRect = placement.sourceCopyRect,
            sourceSampleRect = placement.sourceSampleRect,
            sourceSampleCrop = placement.sourceSampleCrop,
            sourceAtlasRect = placement.atlasRect,
            sourceAtlasSampleCrop = placement.atlasSampleCrop,
            blurredAtlasSampleCrop = placement.atlasSampleCrop,
            copyScale = placement.copyScale,
            downsampleScale = placement.downsampleScale
        )
    }

    private fun preprocessPlacement(
        slotId: String,
        drawIndex: Int
    ): SceneBlurAtlasPreprocessPlacement {
        val rect = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 32, bottom = 32)
        return SceneBlurAtlasPreprocessPlacement(
            request = SceneBlurAtlasPreprocessRequest(
                slotId = BlurSlotId(slotId),
                drawIndex = drawIndex,
                blurRadiusMask = null,
                sourceCopyRect = rect,
                sourceSampleRect = rect,
                atlasRect = rect,
                atlasSampleCrop = rect
            ),
            output = SceneBlurAtlasPreprocessOutput(
                blurRadiusMask = null,
                atlasRect = rect,
                atlasSampleCrop = rect,
                sourceCopyRect = rect,
                sourceSampleCrop = rect,
                copyScale = SceneBlurAtlasCopyScale(x = 1f, y = 1f),
                downsampleScale = 1f
            )
        )
    }

    private fun renderFrame(
        rootSize: IntSize = IntSize(width = 320, height = 240),
        slots: List<BlurSlotRecord> = listOf(record(id = "slot", drawIndex = 1))
    ): SceneRenderFrame {
        return SceneFramePlanner.plan(
            frame = CommittedSceneFrame(
                generation = 42L,
                rootSize = rootSize,
                slots = slots,
                reasons = emptySet()
            ),
            resources = SceneResolvedResources(
                rootTexture = FakeTexture(id = 900, size = rootSize),
                slots = emptyMap()
            )
        )
    }

    private fun record(
        id: String,
        drawIndex: Int,
        area: Rect = Rect(left = 10f, top = 20f, right = 110f, bottom = 140f),
        localRect: Rect = Rect(left = 0f, top = 0f, right = 100f, bottom = 120f),
        style: Style = Style.default.copy(sigma = 8f, noiseAlpha = 0f),
        zIndex: Float = 0f
    ): BlurSlotRecord {
        return BlurSlotRecord(
            id = BlurSlotId(id),
            drawIndex = drawIndex,
            debugName = id,
            geometry = BlurSlotGeometry(
                area = area,
                localRect = localRect,
                contentOffset = Offset.Zero,
                transformMatrix = FloatArray(16),
                zIndex = zIndex
            ),
            style = BlurSlotStyleRecord(
                style = style,
                blurMask = null
            ),
            content = null,
            dirtyFlags = BlurSlotDirtyFlags()
        )
    }

    private fun sourceFramebuffer(): SceneBlurAtlasCopySource {
        return SceneBlurAtlasCopySource(
            sourceFramebuffer = FakeFramebuffer(
                rendererId = 1,
                specification = framebufferSpec(IntSize(width = 320, height = 240)),
                textureId = 100
            ),
            useCopyImage = true
        )
    }

    private fun compatibilityKey(sigma: Float): SceneBlurAtlasCompatibilityKey {
        return SceneBlurAtlasCompatibilityKey(
            sigma = sigma,
            algorithm = BlurAlgorithm.GAUSSIAN
        )
    }

    private fun expectStageFailure(
        message: String,
        block: () -> Unit
    ): StageFailure {
        try {
            block()
            fail("Expected StageFailure($message)")
        } catch (failure: StageFailure) {
            return failure
        }
        error("unreachable")
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

    private class RecordingCopyStage(
        val events: MutableList<String> = mutableListOf(),
        private val output: SceneBlurAtlasCopyFrameOutput? = null
    ) : SceneBlurAtlasPipelineCopyStage {
        val releasedOutputs = mutableListOf<SceneBlurAtlasCopyFrameOutput>()
        private val borrowedOutputs = LinkedHashSet<SceneBlurAtlasCopyFrameOutput>()

        override fun execute(
            source: SceneBlurAtlasCopySource,
            preprocessResult: SceneBlurAtlasPreprocessFrameResult
        ): SceneBlurAtlasCopyFrameOutput {
            events += "copy"
            val result = output ?: error("copy output required")
            borrowedOutputs += result
            return result
        }

        override fun release(output: SceneBlurAtlasCopyFrameOutput) {
            if (borrowedOutputs.remove(output)) {
                releasedOutputs += output
                events += "releaseCopy"
            }
        }
    }

    private class RecordingBlurStage(
        val events: MutableList<String> = mutableListOf(),
        private val output: SceneBlurAtlasBlurFrameOutput? = null
    ) : SceneBlurAtlasPipelineBlurStage {
        private val borrowedOutputs = LinkedHashSet<SceneBlurAtlasBlurFrameOutput>()

        override fun execute(copyOutput: SceneBlurAtlasCopyFrameOutput): SceneBlurAtlasBlurFrameOutput {
            events += "blur"
            val result = output ?: error("blur output required")
            borrowedOutputs += result
            return result
        }

        override fun release(output: SceneBlurAtlasBlurFrameOutput) {
            if (borrowedOutputs.remove(output)) {
                events += "releaseBlur"
            }
        }
    }

    private class RealCopyStage(
        private val copyPass: SceneBlurAtlasCopyPass
    ) : SceneBlurAtlasPipelineCopyStage {
        override fun execute(
            source: SceneBlurAtlasCopySource,
            preprocessResult: SceneBlurAtlasPreprocessFrameResult
        ): SceneBlurAtlasCopyFrameOutput {
            return copyPass.execute(source, preprocessResult)
        }

        override fun release(output: SceneBlurAtlasCopyFrameOutput) {
            copyPass.release(output)
        }
    }

    private class RealBlurStage(
        private val blurPass: SceneBlurAtlasBlurPass
    ) : SceneBlurAtlasPipelineBlurStage {
        override fun execute(copyOutput: SceneBlurAtlasCopyFrameOutput): SceneBlurAtlasBlurFrameOutput {
            return blurPass.execute(copyOutput)
        }

        override fun release(output: SceneBlurAtlasBlurFrameOutput) {
            blurPass.release(output)
        }
    }

    private class ThrowingFramebufferCopy(
        private val failOnCall: Int
    ) : SceneBlurAtlasFramebufferCopy {
        private var callCount = 0

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
            callCount++
            if (callCount == failOnCall) {
                throw StageFailure("copy")
            }
        }
    }

    private class ThrowingAtlasBlurrer(
        private val pool: RecordingFramebufferPool,
        private val failOnCall: Int
    ) : SceneBlurAtlasBatchBlurrer {
        private var callCount = 0

        override fun blur(
            input: SizedFramebuffer,
            sampleCrop: Rect,
            settings: SceneBlurAtlasBlurSettings
        ): SizedFramebuffer {
            callCount++
            if (callCount == failOnCall) {
                throw StageFailure("blur")
            }
            return pool.borrowOutput(input.contentSize)
        }
    }

    private class RecordingFramebufferPool : FramebufferLendingPool(RenderCommands(RecordingRendererApi())) {
        val acquiredFbos = mutableListOf<Framebuffer>()
        val releasedFbos = mutableListOf<Framebuffer>()
        private val borrowedFbos = LinkedHashSet<Framebuffer>()
        private var nextRendererId: Int = 200

        fun borrowOutput(contentSize: IntSize): SizedFramebuffer {
            return acquire(framebufferSpec(contentSize)).withSize(contentSize)
        }

        override fun acquire(spec: FramebufferSpecification): Framebuffer {
            return FakeFramebuffer(
                rendererId = nextRendererId,
                specification = spec,
                textureId = nextRendererId + 1000
            ).also { framebuffer ->
                nextRendererId++
                acquiredFbos += framebuffer
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
        textureId: Int = rendererId + 1000
    ) : Framebuffer {
        override val colorAttachmentTexture: Texture2D = FakeTexture(
            id = textureId,
            size = specification.size,
            coordinateOrigin = specification.attachmentsSpec.attachments.single().coordinateOrigin
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

    private class StageFailure(message: String) : RuntimeException(message)

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

        val pipelineOutputTypes: List<Class<*>> = listOf(
            SceneBlurAtlasPipelinePreflight::class.java,
            SceneBlurAtlasPipelineOutput::class.java
        )
    }
}
