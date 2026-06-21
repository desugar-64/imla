/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.GlTextureFrame
import dev.serhiiyaremych.imla.internal.legacy.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.Buffer
import java.nio.file.Files
import java.nio.file.Paths

class Renderer2CaptureImportCorrectnessTest {
    @Test
    fun shouldCaptureSlotTexture_contentOffsetChangeWithSameSizeDoesNotTriggerCapture() {
        val existingFrame = validFrame(IntSize(64, 32))
        val previous = record(
            geometry = geometry(
                localRect = Rect(0f, 0f, 64f, 32f),
                contentOffset = Offset.Zero
            )
        )
        val next = previous.copy(
            geometry = geometry(
                localRect = Rect(0f, 0f, 64f, 32f),
                contentOffset = Offset(12f, 8f)
            )
        )
        val dirtyFlags = BlurSlotDirtyDecider.dirtyFlags(previous, next)

        assertEquals(setOf(BlurSlotDirtyReason.Geometry), dirtyFlags.reasons)
        assertFalse(
            SceneCapturePolicy.shouldCaptureSlotTexture(
                dirtyFlags = dirtyFlags,
                dirtyReason = BlurSlotDirtyReason.Content,
                requiredSize = SceneCapturePolicy.slotTextureSize(next.geometry ?: error("missing geometry")),
                existingFrame = existingFrame
            )
        )
    }

    @Test
    fun shouldCaptureSlotTexture_visibleAreaChangeWithSameSizeDoesNotTriggerCapture() {
        val existingFrame = validFrame(IntSize(64, 32))
        val previous = record(
            geometry = geometry(
                area = Rect(0f, 0f, 64f, 32f),
                localRect = Rect(0f, 0f, 64f, 32f)
            )
        )
        val next = previous.copy(
            geometry = geometry(
                area = Rect(10f, 20f, 74f, 52f),
                localRect = Rect(0f, 0f, 64f, 32f)
            )
        )
        val dirtyFlags = BlurSlotDirtyDecider.dirtyFlags(previous, next)

        assertEquals(setOf(BlurSlotDirtyReason.Geometry), dirtyFlags.reasons)
        assertFalse(
            SceneCapturePolicy.shouldCaptureSlotTexture(
                dirtyFlags = dirtyFlags,
                dirtyReason = BlurSlotDirtyReason.Content,
                requiredSize = SceneCapturePolicy.slotTextureSize(next.geometry ?: error("missing geometry")),
                existingFrame = existingFrame
            )
        )
    }

    @Test
    fun capturePolicy_maskDirtyRecapturesMasksAndClipMasks() {
        val existingFrame = validFrame(IntSize(64, 32))
        val slot = record(
            geometry = geometry(localRect = Rect(0f, 0f, 64f, 32f)),
            style = BlurSlotStyleRecord(
                style = Style.default,
                blurMask = Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                clipShape = RoundedCornerShape(12.dp)
            ),
            dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Mask))
        )

        assertTrue(SceneCapturePolicy.shouldCaptureBlurMask(slot, existingFrame))
        assertTrue(SceneCapturePolicy.shouldCaptureClipMask(slot, existingFrame))
    }

    @Test
    fun capturePolicy_styleOrGeometryDirtyWithSameTextureSizeReusesMasksAndClipMasks() {
        val existingFrame = validFrame(IntSize(64, 32))
        listOf(BlurSlotDirtyReason.Style, BlurSlotDirtyReason.Geometry).forEach { dirtyReason ->
            val slot = record(
                geometry = geometry(localRect = Rect(0f, 0f, 64f, 32f)),
                style = BlurSlotStyleRecord(
                    style = Style.default,
                    blurMask = Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                    clipShape = RoundedCornerShape(12.dp)
                ),
                dirtyFlags = BlurSlotDirtyFlags(setOf(dirtyReason))
            )

            assertFalse(SceneCapturePolicy.shouldCaptureBlurMask(slot, existingFrame))
            assertFalse(SceneCapturePolicy.shouldCaptureClipMask(slot, existingFrame))
        }
    }

    @Test
    fun capturePolicy_contentOffsetChangeWithSameTextureSizeCanReuseStaleGeometryContent() {
        val existingFrame = validFrame(IntSize(64, 32))
        val previousGeometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset.Zero
        )
        val geometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset(12f, 8f)
        )
        val freshness = ContentCaptureFreshnessRecord(
            captureKey = ContentCaptureKey(
                localRect = previousGeometry.localRect,
                contentOffset = previousGeometry.contentOffset
            )
        )
        assertEquals(
            SlotContentCaptureDecision.ReuseStaleGeometry,
            SceneCapturePolicy.slotContentCaptureDecision(
                slot = record(
                    geometry = geometry,
                    dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Geometry))
                ),
                existingFrame = existingFrame,
                freshnessRecord = freshness
            )
        )
    }

    @Test
    fun capturePolicy_visibleAreaChangeWithSameTextureSizeCanReuseStaleGeometryContent() {
        val existingFrame = validFrame(IntSize(64, 32))
        val previousGeometry = geometry(
            area = Rect(0f, 0f, 64f, 32f),
            localRect = Rect(0f, 0f, 64f, 32f)
        )
        val geometry = geometry(
            area = Rect(10f, 10f, 73.9f, 41.9f),
            localRect = Rect(1f, 2f, 65f, 34f)
        )
        val freshness = ContentCaptureFreshnessRecord(
            captureKey = ContentCaptureKey(
                localRect = previousGeometry.localRect,
                contentOffset = previousGeometry.contentOffset
            )
        )
        assertEquals(
            SlotContentCaptureDecision.ReuseStaleGeometry,
            SceneCapturePolicy.slotContentCaptureDecision(
                slot = record(
                    geometry = geometry,
                    dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Geometry))
                ),
                existingFrame = existingFrame,
                freshnessRecord = freshness
            )
        )
    }

    @Test
    fun capturePolicy_sameContentOffsetAndLocalRectDoesNotRecapture() {
        val geometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset.Zero,
            zIndex = 5f,
            transformMatrix = FloatArray(16) { it * 2f }
        )
        val existingKey = ContentCaptureKey(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset.Zero
        )
        assertFalse(
            SceneCapturePolicy.captureKeyChanged(geometry, existingKey)
        )
    }

    @Test
    fun capturePolicy_nullCaptureKeyTriggersRecapture() {
        val geometry = geometry(
            localRect = Rect(0f, 0f, 64f, 32f),
            contentOffset = Offset.Zero
        )
        assertTrue(
            SceneCapturePolicy.captureKeyChanged(geometry, existingCaptureKey = null)
        )
    }

    @Test
    fun capturePolicy_nullBlurMaskReturnsFalseForBlurMaskCapture() {
        val slot = record(
            geometry = geometry(localRect = Rect(0f, 0f, 64f, 32f)),
            style = BlurSlotStyleRecord(
                style = Style.default,
                blurMask = null
            ),
            dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Mask))
        )

        assertFalse(SceneCapturePolicy.shouldCaptureBlurMask(slot, existingFrame = null))
    }

    @Test
    fun capturePolicy_rectangleShapeReturnsFalseForClipMaskCapture() {
        val slot = record(
            geometry = geometry(localRect = Rect(0f, 0f, 64f, 32f)),
            style = BlurSlotStyleRecord(
                style = Style.default,
                blurMask = null,
                clipShape = RectangleShape
            ),
            dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Mask))
        )

        assertFalse(SceneCapturePolicy.shouldCaptureClipMask(slot, existingFrame = null))
    }

    @Test
    fun capturePolicy_maskAddedWithNullFrameTriggersCapture() {
        val slot = record(
            geometry = geometry(localRect = Rect(0f, 0f, 64f, 32f)),
            style = BlurSlotStyleRecord(
                style = Style.default,
                blurMask = Brush.horizontalGradient(listOf(Color.Black, Color.White))
            ),
            dirtyFlags = BlurSlotDirtyFlags.Clean
        )

        assertTrue(SceneCapturePolicy.shouldCaptureBlurMask(slot, existingFrame = null))
    }

    @Test
    fun capturePolicy_clipAddedWithNullFrameTriggersCapture() {
        val slot = record(
            geometry = geometry(localRect = Rect(0f, 0f, 64f, 32f)),
            style = BlurSlotStyleRecord(
                style = Style.default,
                blurMask = null,
                clipShape = RoundedCornerShape(12.dp)
            ),
            dirtyFlags = BlurSlotDirtyFlags.Clean
        )

        assertTrue(SceneCapturePolicy.shouldCaptureClipMask(slot, existingFrame = null))
    }

    @Test
    fun capturePolicy_unchangedMaskInputsSkipRecapture() {
        val existingFrame = validFrame(IntSize(64, 32))
        val slot = record(
            geometry = geometry(localRect = Rect(0f, 0f, 64f, 32f)),
            style = BlurSlotStyleRecord(
                style = Style.default,
                blurMask = Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                clipShape = RoundedCornerShape(12.dp)
            ),
            dirtyFlags = BlurSlotDirtyFlags.Clean
        )

        assertFalse(SceneCapturePolicy.shouldCaptureBlurMask(slot, existingFrame))
        assertFalse(SceneCapturePolicy.shouldCaptureClipMask(slot, existingFrame))
    }

    @Test
    fun glTextureFrameReleaseDestroysTextureAndDropsHandle() {
        val texture = FakeTexture(size = IntSize(20, 10), coordinateOrigin = CoordinateOrigin.TOP_LEFT)
        val frame = GlTextureFrame(
            sizePx = IntSize(20, 10),
            textureOrigin = CoordinateOrigin.TOP_LEFT,
            texture2D = texture
        )

        frame.release()

        assertTrue(texture.destroyed)
        assertNull(frame.texture2D)
    }

    @Test
    fun capturedFramesKeepTheirDeclaredCoordinateOrigin() {
        val topLeftFrame = GlTextureFrame(
            sizePx = IntSize(20, 10),
            textureOrigin = CoordinateOrigin.TOP_LEFT,
            texture2D = FakeTexture(size = IntSize(20, 10), coordinateOrigin = CoordinateOrigin.TOP_LEFT)
        )
        val bottomLeftFrame = GlTextureFrame(
            sizePx = IntSize(20, 10),
            textureOrigin = CoordinateOrigin.BOTTOM_LEFT,
            texture2D = FakeTexture(size = IntSize(20, 10), coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT)
        )

        assertEquals(CoordinateOrigin.TOP_LEFT, topLeftFrame.textureOrigin)
        assertEquals(CoordinateOrigin.TOP_LEFT, topLeftFrame.texture2D?.coordinateOrigin)
        assertEquals(CoordinateOrigin.BOTTOM_LEFT, bottomLeftFrame.textureOrigin)
        assertEquals(CoordinateOrigin.BOTTOM_LEFT, bottomLeftFrame.texture2D?.coordinateOrigin)
    }

    @Test
    fun rootCaptureSourceKeepsPreviousRootWhenReplacementIsMissingOrStale() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/RenderableRootLayer.kt"
        )

        assertTrue(source.contains("private val pendingFrame = AtomicReference<GraphicsLayerTextureFrame?>(null)"))
        assertTrue(source.contains("val pending = pendingFrame.getAndSet(null) ?: return@trace currentTexture"))
        assertTrue(source.contains("val texture = resolveFrameTexture(pending) ?: run"))
        assertTrue(source.contains("return@trace currentTexture"))
        assertTrue(source.contains("fun captureLayer(graphicsLayer: GraphicsLayer, size: IntSize): Boolean"))
        assertTrue(source.contains("?: return@trace false"))
        assertTrue(source.contains("val oldFrame = currentFrame"))
        assertTrue(source.contains("enqueueRelease(oldFrame)"))
        assertTrue(source.contains("textureOrigin = CoordinateOrigin.TOP_LEFT"))
        assertTrue(source.contains("isCanvasFlippedY = false"))
    }

    @Test
    fun slotContentCaptureSourceUsesTopLeftFlippedCanvasAndContentOffset() {
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerRenderer.kt"
        )
        val baseTextureSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerTexture.kt"
        )

        assertTrue(rendererSource.contains("textureOrigin = CoordinateOrigin.TOP_LEFT"))
        assertTrue(rendererSource.contains("isCanvasFlippedY = true"))
        assertTrue(rendererSource.contains("contentOffset = contentOffset"))
        assertTrue(baseTextureSource.contains("translate(left = -contentOffset.x, top = -contentOffset.y)"))
    }

    @Test
    fun maskCaptureSourceUsesTopLeftR8TexturesAndReusesSameFrameWithoutQueuingRelease() {
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/MaskTextureRenderer.kt"
        )
        val repositorySource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneMaskRepository.kt"
        )

        assertTrue(rendererSource.contains("fboFormat = FramebufferTextureFormat.R8"))
        assertTrue(rendererSource.contains("textureOrigin = CoordinateOrigin.TOP_LEFT"))
        assertTrue(rendererSource.contains("lastRenderedFrame?.let(onRenderComplete)"))
        assertTrue(repositorySource.contains("if (oldFrame != null && oldFrame !== newFrame)"))
        assertTrue(repositorySource.contains("releaseQueue.add(oldFrame)"))
    }

    @Test
    fun graphicsLayerTextureImportSourceImportsSynchronouslyAndReleasesOldFramesThroughOwners() {
        val textureSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerTexture.kt"
        )
        val layerRepositorySource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneLayerRepository.kt"
        )

        assertTrue(textureSource.contains("OpenGLHardwareBufferTexture2D.createFromBuffer(buffer, sizePx, textureOrigin)"))
        assertTrue(textureSource.contains("renderer.releaseBuffer(result.hardwareBuffer)"))
        assertTrue(textureSource.contains("GlTextureFrame("))
        assertTrue(textureSource.contains("flipOutput = textureOrigin.needsFlipForOpenGL()"))
        assertTrue(textureSource.contains("textureOrigin = textureOrigin"))
        assertTrue(layerRepositorySource.contains("val oldFrame = frames.put(id, newFrame)"))
        assertTrue(layerRepositorySource.contains("releaseQueue.add(oldFrame)"))
    }

    @Test
    fun api29CaptureImportDiagnosticsSourceKeepsCurrentNullImportPolicyObservable() {
        val textureSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerTexture.kt"
        )
        val diagnosticsSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerCaptureDiagnostics.kt"
        )

        assertTrue(textureSource.contains("GraphicsLayerCaptureDiagnostics.renderResultFailed(result.status, sizePx)"))
        assertTrue(
            textureSource.contains(
                "GraphicsLayerCaptureDiagnostics.hardwareBufferImportReturnedNull(sizePx, textureOrigin)"
            )
        )
        assertTrue(textureSource.contains("texture?.let {"))
        assertTrue(diagnosticsSource.contains("private const val MAX_WARNING_LOGS = 16"))
        assertTrue(diagnosticsSource.contains("failure.renderResult"))
        assertTrue(diagnosticsSource.contains("failure.importNull"))
    }

    @Test
    fun api29CaptureImportTimingSourcePublishesFenceImportAndTotalDurations() {
        val textureSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerTexture.kt"
        )
        val diagnosticsSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerCaptureDiagnostics.kt"
        )

        assertTrue(textureSource.contains("val captureStartNs = SystemClock.elapsedRealtimeNanos()"))
        assertTrue(textureSource.contains("val renderStartNs = SystemClock.elapsedRealtimeNanos()"))
        assertTrue(textureSource.contains("val fenceStartNs = SystemClock.elapsedRealtimeNanos()"))
        assertTrue(textureSource.contains("val importStartNs = SystemClock.elapsedRealtimeNanos()"))
        assertTrue(textureSource.contains("GraphicsLayerCaptureDiagnostics.timing("))
        assertTrue(diagnosticsSource.contains("timing.render.us"))
        assertTrue(diagnosticsSource.contains("timing.fence.us"))
        assertTrue(diagnosticsSource.contains("timing.import.us"))
        assertTrue(diagnosticsSource.contains("timing.total.us"))
        assertTrue(diagnosticsSource.contains("private const val MAX_TIMING_LOGS = 120"))
    }

    @Test
    fun hardwareBufferImportSourceLogsEglImageAndBindFailuresWithCleanup() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/opengl/OpenGLHardwareBufferTexture2D.kt"
        )

        assertTrue(source.contains("eglCreateImageFromHardwareBuffer failed eglError="))
        assertTrue(source.contains("glEGLImageTargetTexture2DOES failed glError="))
        assertTrue(source.contains("GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)"))
        assertTrue(source.contains("EGLExt.eglDestroyImageKHR(eglDisplay, eglImage)"))
        assertTrue(source.contains("private const val MAX_IMPORT_WARNING_LOGS = 16"))
        assertTrue(source.contains("return@trace null"))
    }

    @Test
    fun surfaceTextureFallbackSourceCopiesToFboAndPublishesFramebufferTexture() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )

        assertTrue(source.contains("private fun copyToFramebuffer()"))
        assertTrue(source.contains("hasValidContent = true"))
        assertTrue(source.contains("framebuffer?.colorAttachmentTexture?.let(onTextureReady)"))
        assertTrue(source.contains("hasValidContent = false"))
    }

    @Test
    fun legacyFallbackRejectsGLThreadCaptureAndReturnsNull() {
        val legacyBody = legacyImplSource()
        val captureCanvasBody = extractFunctionBodyFromClass(legacyBody, "override fun captureCanvas(")

        assertTrue(captureCanvasBody.contains("if (isGLThread())"))
        assertTrue(captureCanvasBody.contains("captureCanvas ignored on GL thread"))
        assertTrue(captureCanvasBody.contains("return@trace null"))

        val glCheckIdx = captureCanvasBody.indexOf("if (isGLThread())")
        val nullReturnIdx = captureCanvasBody.indexOf("return@trace null", glCheckIdx)
        assertTrue(glCheckIdx < nullReturnIdx)
    }

    @Test
    fun legacyFallbackReturnsNullWhenRendererStoppedOrZeroSizedCapture() {
        val legacyBody = legacyImplSource()
        val captureCanvasBody = extractFunctionBodyFromClass(legacyBody, "override fun captureCanvas(")

        assertTrue(captureCanvasBody.contains("!glRenderer.isRunning() || sizePx == IntSize.Zero"))
        assertTrue(captureCanvasBody.contains("return@trace null"))

        val glThreadIdx = captureCanvasBody.indexOf("isGLThread()")
        val stoppedIdx = captureCanvasBody.indexOf("!glRenderer.isRunning()", glThreadIdx)
        assertTrue("stopped/renderer check must follow GL-thread check in captureCanvas", stoppedIdx > glThreadIdx)
    }

    @Test
    fun legacyFallbackLatchFlowAwaitsCallbackThenReleasesPendingTexture() {
        val legacyBody = legacyImplSource()
        val captureCanvasBody = extractFunctionBodyFromClass(legacyBody, "override fun captureCanvas(")

        assertTrue(captureCanvasBody.contains("val latch = CountDownLatch(1)"))
        assertTrue(captureCanvasBody.contains("pendingFrameLatch = latch"))
        assertTrue(captureCanvasBody.contains("latch.await()"))
        assertTrue(captureCanvasBody.contains("val texture = pendingTexture"))
        assertTrue(captureCanvasBody.contains("pendingTexture = null"))
        assertTrue(captureCanvasBody.contains("pendingFrameLatch = null"))

        val createIdx = captureCanvasBody.indexOf("val latch = CountDownLatch(1)")
        val assignIdx = captureCanvasBody.indexOf("pendingFrameLatch = latch", createIdx)
        val awaitIdx = captureCanvasBody.indexOf("latch.await()", assignIdx)
        val readIdx = captureCanvasBody.indexOf("val texture = pendingTexture", awaitIdx)
        val clearTexIdx = captureCanvasBody.indexOf("pendingTexture = null", readIdx)
        val clearLatchIdx = captureCanvasBody.indexOf("pendingFrameLatch = null", clearTexIdx)

        val ordersValid = createIdx != -1 && assignIdx != -1 && awaitIdx != -1 &&
                readIdx != -1 && clearTexIdx != -1 && clearLatchIdx != -1 &&
                createIdx < assignIdx && assignIdx < awaitIdx &&
                awaitIdx < readIdx && readIdx < clearTexIdx &&
                clearTexIdx < clearLatchIdx

        assertTrue(
            "create -> assign -> await -> read -> clearTexture -> clearLatch",
            ordersValid
        )
    }

    @Test
    fun legacyFallbackNoTimeoutOnLatchAwait() {
        val legacyBody = legacyImplSource()

        val awaitLines = legacyBody.lines().filter { it.contains("latch.await") }
        val allAwaitCalls = awaitLines.size

        val timedCalls = awaitLines.count { line ->
            line.contains("latch.await(") && !line.contains("latch.await()")
        }

        assertTrue("latch.await() calls should exist in legacy impl", allAwaitCalls > 0)
        assertEquals(
            "no latch.await calls should have timeout arguments in legacy impl",
            0,
            timedCalls
        )
    }

    @Test
    fun legacyFallbackHandleTextureReadyCountsDownLatchAndSetsPendingTexture() {
        val legacyBody = legacyImplSource()
        val handleBody = extractFunctionBodyFromClass(
            legacyBody,
            "private fun handleTextureReady(texture: Texture2D)"
        )

        assertTrue(handleBody.contains("val latch = pendingFrameLatch ?: return"))
        assertTrue(handleBody.contains("pendingTexture = texture"))
        assertTrue(handleBody.contains("latch.countDown()"))
    }

    @Test
    fun legacyFallbackReleaseClearsLatchAndTextureAndSchedulesRendererRelease() {
        val legacyBody = legacyImplSource()
        val releaseBody = extractFunctionBodyFromClass(legacyBody, "override fun release()")

        assertTrue(releaseBody.contains("pendingFrameLatch = null"))
        assertTrue(releaseBody.contains("pendingTexture = null"))
        assertTrue(releaseBody.contains("currentSizePx = IntSize.Zero"))
        assertTrue(releaseBody.contains("glRenderer.execute {"))
        assertTrue(releaseBody.contains("renderer.release()"))
    }

    @Test
    fun legacyFallbackEnsureRendererBlocksOnGLThreadInitWithLatch() {
        val legacyBody = legacyImplSource()
        val ensureBody = extractFunctionBodyFromClass(
            legacyBody,
            "private fun ensureRenderer(sizePx: IntSize)"
        )

        assertTrue(ensureBody.contains("renderer.ensureInitialized(sizePx)"))
        assertTrue(ensureBody.contains("currentSizePx = renderer.currentSize"))
        assertTrue(ensureBody.contains("latch.countDown()"))
        assertTrue(ensureBody.contains("latch.await()"))
        assertTrue(ensureBody.contains("renderer.isInitialized"))
    }

    @Test
    fun legacyFallbackHasMeaningfulSizeChangeWithDeltaGreaterThanOnePixel() {
        val legacyBody = legacyImplSource()
        val sizeChangeBody = extractFunctionBodyFromClass(
            legacyBody,
            "private fun hasMeaningfulSizeChange(newSizePx: IntSize)"
        )

        assertTrue(sizeChangeBody.contains("currentSizePx.width - newSizePx.width) > 1"))
        assertTrue(sizeChangeBody.contains("currentSizePx.height - newSizePx.height) > 1"))
        assertTrue(sizeChangeBody.contains("currentSizePx == IntSize.Zero"))
    }

    @Test
    fun legacyFallbackCaptureCanvasDistinctFromDrawThreadBlockedByGLImport() {
        val legacyBody = legacyImplSource()
        val captureCanvasBody = extractFunctionBodyFromClass(legacyBody, "override fun captureCanvas(")

        val drawIdx = captureCanvasBody.indexOf("renderer.draw {")
        val awaitIdx = captureCanvasBody.indexOf("latch.await()", drawIdx)
        val textureIdx = captureCanvasBody.indexOf("val texture = pendingTexture", awaitIdx)

        val ordersValid = drawIdx != -1 && awaitIdx != -1 && textureIdx != -1 &&
                drawIdx < awaitIdx && awaitIdx < textureIdx

        assertTrue("draw -> await -> pendingTexture order must hold in captureCanvas", ordersValid)
    }

    @Test
    fun legacyFallbackCallbackAssumesValidGLContextForUpdateTexImageAndCopyToFbo() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )
        val ensureBody = extractFunctionBody(source, "fun ensureInitialized(size: IntSize)")

        assertTrue(ensureBody.contains("st.updateTexImage()"))
        assertTrue(ensureBody.contains("copyToFramebuffer()"))
        assertTrue(ensureBody.contains("framebuffer?.colorAttachmentTexture?.let(onTextureReady)"))
        assertTrue(ensureBody.contains("setOnFrameAvailableListener { st ->"))
    }

    @Test
    fun surfaceTextureRendererCopyToFboDrawsOesTextureWithFlipYFalse() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )
        val copyBody = extractFunctionBody(source, "private fun copyToFramebuffer()")

        assertTrue(copyBody.contains("simpleQuadRenderer.draw(shader, texture as Texture, flipY = false)"))
        assertTrue(copyBody.contains("hasValidContent = true"))
    }

    @Test
    fun surfaceTextureRendererFboOriginMatchesFlipOutputAndDoesNotFlipInCopyPass() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )
        val textureSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerTexture.kt"
        )
        val ensureBody = extractFunctionBody(source, "fun ensureInitialized(size: IntSize)")

        assertTrue(ensureBody.contains(
            "coordinateOrigin = if (flipOutput) CoordinateOrigin.TOP_LEFT else CoordinateOrigin.BOTTOM_LEFT"
        ))
        assertTrue(textureSource.contains("flipOutput = textureOrigin.needsFlipForOpenGL()"))
    }

    @Test
    fun surfaceTextureRendererOnFrameAvailableCatchesUpdateTexImageFailure() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )
        val ensureBody = extractFunctionBody(source, "fun ensureInitialized(size: IntSize)")

        assertTrue(ensureBody.contains("try {"))
        assertTrue(ensureBody.contains("st.updateTexImage()"))
        assertTrue(ensureBody.contains("catch (e: RuntimeException)"))
        assertTrue(ensureBody.contains("return@executeOnGlThread"))

        val tryIdx = ensureBody.indexOf("try {")
        val catchIdx = ensureBody.indexOf("catch (e: RuntimeException)", tryIdx)
        assertTrue(tryIdx < catchIdx)
    }

    @Test
    fun surfaceTextureRendererRecoverFromCanvasErrorPostsFallbackFrame() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )
        val recoverBody = extractFunctionBody(source, "private fun recoverFromCanvasError")

        assertTrue(recoverBody.contains("val fallback = surface.lockCanvas(null)"))
        assertTrue(recoverBody.contains("surface.unlockCanvasAndPost(fallback)"))
    }

    @Test
    fun surfaceTextureRendererReleaseNullifiesListenerAndReleasesGlResources() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )
        val releaseBody = extractFunctionBody(source, "fun release()")

        assertTrue(releaseBody.contains("surfaceTexture?.setOnFrameAvailableListener(null)"))
        assertTrue(releaseBody.contains("surface?.release()"))
        assertTrue(releaseBody.contains("surfaceTexture?.release()"))
        assertTrue(releaseBody.contains("extOesTexture?.destroy()"))
        assertTrue(releaseBody.contains("framebuffer?.destroy()"))
        assertTrue(releaseBody.contains("surface = null"))
        assertTrue(releaseBody.contains("surfaceTexture = null"))
        assertTrue(releaseBody.contains("extOesTexture = null"))
        assertTrue(releaseBody.contains("framebuffer = null"))
        assertTrue(releaseBody.contains("currentSize = IntSize.Zero"))
        assertTrue(releaseBody.contains("hasValidContent = false"))
    }

    @Test
    fun surfaceTextureRendererSharedOesShaderLazilyLoadedAndReleasedBySession() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt"
        )

        assertTrue(source.contains("private var sharedExtOesShader: Shader? = null"))
        assertTrue(source.contains("if (sharedExtOesShader == null) {"))
        assertTrue(source.contains("\"simple_quad\""))
        assertTrue(source.contains("\"simple_ext_quad\""))
        assertTrue(source.contains("fun releaseSharedResources()"))
        assertTrue(source.contains("sharedExtOesShader = null"))
        assertTrue(rendererSource.contains("SurfaceTextureRenderer::releaseSharedResources"))
    }

    @Test
    fun surfaceTextureRendererDrawLocksHardwareCanvasClearsDrawsAndUnlocksWithPost() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )
        val drawBody = extractFunctionBody(source, "inline fun draw(crossinline block: (Canvas) -> Unit)")

        assertTrue(drawBody.contains("s.lockHardwareCanvas()"))
        assertTrue(drawBody.contains("canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)"))
        assertTrue(drawBody.contains("block(canvas)"))
        assertTrue(drawBody.contains("s.unlockCanvasAndPost(canvas)"))
        assertTrue(drawBody.contains("catch (e: IllegalArgumentException)"))
    }

    @Test
    fun maskTextureRendererPassesR8FormatAndTopLeftOriginToCreateGraphicsLayerTexture() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/MaskTextureRenderer.kt"
        )

        assertTrue(source.contains("fboFormat = FramebufferTextureFormat.R8"))
        assertTrue(source.contains("textureOrigin = CoordinateOrigin.TOP_LEFT"))
        assertTrue(source.contains("createGraphicsLayerTexture("))
    }

    @Test
    fun maskTextureRendererUsesCaptureCanvasNotCaptureGraphicsLayer() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/MaskTextureRenderer.kt"
        )

        assertTrue(source.contains("graphicsLayerTexture.captureCanvas("))
        assertFalse(source.contains("graphicsLayerTexture.captureGraphicsLayer("))
    }

    @Test
    fun copyToFramebufferBindsFramebufferAndClearsBeforeOesTextureDraw() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )

        val copyIdx = source.indexOf("private fun copyToFramebuffer()")
        val fbBindIdx = source.indexOf("fb.bind(commands, Bind.DRAW", copyIdx)
        val clearIdx = source.indexOf("commands.clear()", fbBindIdx)
        val drawIdx = source.indexOf("simpleQuadRenderer.draw(", clearIdx)
        val validIdx = source.indexOf("hasValidContent = true", drawIdx)

        val ordersValid = copyIdx != -1 &&
                fbBindIdx != -1 &&
                clearIdx != -1 &&
                drawIdx != -1 &&
                validIdx != -1 &&
                copyIdx < fbBindIdx &&
                fbBindIdx < clearIdx &&
                clearIdx < drawIdx &&
                drawIdx < validIdx

        assertTrue("bind -> clear -> draw -> validContent order must hold", ordersValid)
    }

    @Test
    fun ensureInitializedDoesNotRecreateWhenSizeAndStateAreUnchanged() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )
        val ensureBody = extractFunctionBody(source, "fun ensureInitialized(size: IntSize)")

        assertTrue(ensureBody.contains("if (currentSize == size && isInitialized) return"))
    }

    @Test
    fun surfaceTextureRendererEnsureInitializedResizesFrameBufferAndReleasesOldOnMeaningfulSizeChange() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )

        val initIdx = source.indexOf("fun ensureInitialized(size: IntSize)")
        val releaseIdx = source.indexOf("release()", initIdx)
        val fboIdx = source.indexOf("Framebuffer.create(", releaseIdx)
        val oesIdx = source.indexOf("Texture2D.create(", fboIdx)

        val ordersValid = initIdx != -1 &&
                releaseIdx != -1 &&
                fboIdx != -1 &&
                oesIdx != -1 &&
                initIdx < releaseIdx &&
                releaseIdx < fboIdx &&
                fboIdx < oesIdx

        assertTrue("release -> FBO -> OES order must hold", ordersValid)
    }

    @Test
    fun legacyBackendValidatesCaptureOriginMatchesConstructor() {
        val legacyBody = legacyImplSource()
        val captureCanvasBody = extractFunctionBodyFromClass(legacyBody, "override fun captureCanvas(")

        assertTrue(captureCanvasBody.contains("check(textureOrigin == this.textureOrigin)"))
        assertTrue(captureCanvasBody.contains("captureCanvas origin"))
        assertTrue(captureCanvasBody.contains("does not match backend origin"))
    }

    @Test
    fun createGraphicsLayerTextureForwardsOriginToLegacyConstructor() {
        val textureSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerTexture.kt"
        )

        assertTrue(textureSource.contains("internal fun createGraphicsLayerTexture("))
        assertTrue(textureSource.contains("textureOrigin: CoordinateOrigin"))
        assertTrue(textureSource.contains("GraphicsLayerTextureV29Impl(glRenderer)"))
        assertTrue(textureSource.contains("GraphicsLayerTextureLegacyImpl("))
        assertTrue(textureSource.contains("textureOrigin = textureOrigin"))
    }

    @Test
    fun graphicsLayerTextureV29DoesNotStoreConstructorOrigin() {
        val textureSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerTexture.kt"
        )
        val v29Body = extractFunctionBodyFromClass(
            textureSource,
            "internal class GraphicsLayerTextureV29Impl("
        )

        assertTrue(v29Body.contains("importToGLTexture(result.hardwareBuffer, sizePx, textureOrigin)"))
    }

    @Test
    fun allCaptureCallersUseTopLeftOrigin() {
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerRenderer.kt"
        )
        val maskSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/MaskTextureRenderer.kt"
        )
        val rootSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/RenderableRootLayer.kt"
        )

        listOf(
            rendererSource to "GraphicsLayerRenderer",
            maskSource to "MaskTextureRenderer",
            rootSource to "RenderableRootLayer"
        ).forEach { (source, name) ->
            assertTrue(
                "$name must use TOP_LEFT as texture origin",
                source.contains("textureOrigin = CoordinateOrigin.TOP_LEFT")
            )
        }
    }

    @Test
    fun allCaptureCallersUseTopLeftForFactoryAndNoOtherOriginPassed() {
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerRenderer.kt"
        )
        val maskSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/MaskTextureRenderer.kt"
        )
        val rootSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/RenderableRootLayer.kt"
        )

        listOf(
            rendererSource to "GraphicsLayerRenderer",
            maskSource to "MaskTextureRenderer",
            rootSource to "RenderableRootLayer"
        ).forEach { (source, name) ->
            assertTrue(
                "$name must call createGraphicsLayerTexture factory",
                source.contains("createGraphicsLayerTexture(")
            )
            assertFalse(
                "$name must NOT use BOTTOM_LEFT origin",
                source.contains("CoordinateOrigin.BOTTOM_LEFT")
            )
        }
    }

    @Test
    fun glTextureFrameOriginPropagatedFromCaptureMatchesTextureOrigin() {
        val topLeftFrame = GlTextureFrame(
            sizePx = IntSize(20, 10),
            textureOrigin = CoordinateOrigin.TOP_LEFT,
            texture2D = FakeTexture(size = IntSize(20, 10), coordinateOrigin = CoordinateOrigin.TOP_LEFT)
        )
        val bottomLeftFrame = GlTextureFrame(
            sizePx = IntSize(20, 10),
            textureOrigin = CoordinateOrigin.BOTTOM_LEFT,
            texture2D = FakeTexture(size = IntSize(20, 10), coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT)
        )

        assertEquals(topLeftFrame.textureOrigin, topLeftFrame.texture2D?.coordinateOrigin)
        assertEquals(bottomLeftFrame.textureOrigin, bottomLeftFrame.texture2D?.coordinateOrigin)

        assertTrue(topLeftFrame.textureOrigin.needsFlipForOpenGL())
        assertFalse(bottomLeftFrame.textureOrigin.needsFlipForOpenGL())
    }

    @Test
    fun surfaceTextureRendererFlipOutputDerivesCorrectFboOrigin() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt"
        )

        assertTrue(source.contains(
            "coordinateOrigin = if (flipOutput) CoordinateOrigin.TOP_LEFT else CoordinateOrigin.BOTTOM_LEFT"
        ))
        assertTrue(source.contains("private val flipOutput: Boolean = false"))
    }

    private fun record(
        geometry: BlurSlotGeometry = geometry(),
        style: BlurSlotStyleRecord = BlurSlotStyleRecord(
            style = Style.default,
            blurMask = null
        ),
        content: BlurSlotContentRecord? = null,
        dirtyFlags: BlurSlotDirtyFlags = BlurSlotDirtyFlags.Clean
    ): BlurSlotRecord {
        return BlurSlotRecord(
            id = BlurSlotId("slot"),
            drawIndex = 0,
            debugName = "slot",
            geometry = geometry,
            style = style,
            content = content,
            dirtyFlags = dirtyFlags
        )
    }

    private fun geometry(
        area: Rect = Rect(0f, 0f, 64f, 32f),
        localRect: Rect = Rect(0f, 0f, 64f, 32f),
        contentOffset: Offset = Offset.Zero,
        transformMatrix: FloatArray = FloatArray(16),
        zIndex: Float = 0f
    ): BlurSlotGeometry {
        return BlurSlotGeometry(
            area = area,
            localRect = localRect,
            contentOffset = contentOffset,
            transformMatrix = transformMatrix,
            zIndex = zIndex
        )
    }

    private fun validFrame(size: IntSize): GlTextureFrame {
        return GlTextureFrame(
            sizePx = size,
            textureOrigin = CoordinateOrigin.TOP_LEFT,
            texture2D = FakeTexture(size = size, coordinateOrigin = CoordinateOrigin.TOP_LEFT)
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

    private fun legacyImplSource(): String {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerTexture.kt"
        )
        val startMarker = "internal class GraphicsLayerTextureLegacyImpl("
        val endMarker = "internal fun createGraphicsLayerTexture("
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start)
        require(start != -1 && end != -1) { "LegacyImpl class boundaries not found" }
        return source.substring(start, end).trimEnd()
    }

    private fun extractFunctionBody(source: String, signature: String): String {
        val funStart = source.indexOf(signature)
        require(funStart != -1) { "function '$signature' not found in source" }
        val openBrace = source.indexOf('{', funStart)
        require(openBrace != -1) { "no open brace after '$signature'" }
        return source.substring(funStart, matchingCloseBrace(source, openBrace) + 1)
    }

    private fun extractFunctionBodyFromClass(
        classBody: String,
        signature: String
    ): String = extractFunctionBody(classBody, signature)

    private fun matchingCloseBrace(source: String, openBraceIdx: Int): Int {
        var depth = 0
        for (i in openBraceIdx until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        error("unmatched brace starting at $openBraceIdx")
    }

    private class FakeTexture(
        size: IntSize,
        override val coordinateOrigin: CoordinateOrigin
    ) : Texture2D() {
        override val id: Int = 1
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = size.width
        override val height: Int = size.height
        override val specification: Texture.Specification = Texture.Specification(
            size = size,
            coordinateOrigin = coordinateOrigin
        )
        var destroyed: Boolean = false

        override fun bind(slot: Int) = Unit
        override fun setData(data: Buffer) = Unit
        override fun isLoaded(): Boolean = true
        override fun destroy() {
            destroyed = true
        }
        override fun generateMipMaps() = Unit
    }
}
