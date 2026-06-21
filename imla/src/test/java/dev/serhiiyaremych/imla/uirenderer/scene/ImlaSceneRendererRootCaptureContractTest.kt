/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class ImlaSceneRendererRootCaptureContractTest {

    @Test
    fun captureLayerReturnsBoolean() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/RenderableRootLayer.kt")
        val captureFun = extractFunctionBody(source, "fun captureLayer(")

        assertTrue(captureFun.contains("): Boolean ="))
    }

    @Test
    fun captureLayerReturnsFalseOnNullGraphicsLayerTextureFrame() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/RenderableRootLayer.kt")
        val captureBody = extractFunctionBody(source, "fun captureLayer(")

        assertTrue(captureBody.contains("?: return@trace false"))
    }

    @Test
    fun captureLayerReturnsFalseOnZeroSize() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/RenderableRootLayer.kt")
        val captureBody = extractFunctionBody(source, "fun captureLayer(")

        assertTrue(captureBody.contains("if (size == IntSize.Zero) return@trace false"))
    }

    @Test
    fun onRootLayerUpdatedGuardsCommitOnCaptureLayerResult() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt")
        val rootUpdateBody = extractFunctionBody(source, "fun onRootLayerUpdated(")

        val captureIdx = rootUpdateBody.indexOf("!renderableLayer.captureLayer(")
        val failureReturnIdx = rootUpdateBody.indexOf("return@trace", captureIdx)
        val commitIdx = rootUpdateBody.indexOf("sceneCoordinator.commitRootCapture(size)", failureReturnIdx)

        assertTrue("onRootLayerUpdated must check captureLayer result", captureIdx >= 0)
        assertTrue("failure path must return before commit", failureReturnIdx > captureIdx)
        assertTrue("commitRootCapture must follow the failure return", commitIdx > failureReturnIdx)
    }

    @Test
    fun onRootLayerUpdatedFailurePathRecordsFailedCounter() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt")
        val rootUpdateBody = extractFunctionBody(source, "fun onRootLayerUpdated(")

        val captureIdx = rootUpdateBody.indexOf("!renderableLayer.captureLayer(")
        val failedCounterIdx = rootUpdateBody.indexOf("SceneTraceCounters.rootCaptureFailed()", captureIdx)

        assertTrue("failure path must increment rootCaptureFailed counter", failedCounterIdx > captureIdx)
    }

    @Test
    fun onRootLayerUpdatedFailurePathRequestsRender() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt")
        val rootUpdateBody = extractFunctionBody(source, "fun onRootLayerUpdated(")

        val captureIdx = rootUpdateBody.indexOf("!renderableLayer.captureLayer(")
        val requestIdx = rootUpdateBody.indexOf("sceneCoordinator.requestRender(", captureIdx)

        assertTrue("failure path must request a render", requestIdx > captureIdx)
        val requestWithReasonIdx = rootUpdateBody.indexOf("sceneCoordinator.requestRender(RenderReason.RootCaptured)", captureIdx)
        assertTrue("requestRender must use RootCaptured reason", requestWithReasonIdx > captureIdx)
    }

    @Test
    fun coordinatorCommitRootCaptureCreatesFrameSnapshot() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/ImlaSceneCoordinator.kt")
        val commitRootCaptureBody = extractFunctionBody(source, "fun commitRootCapture(")

        assertTrue(commitRootCaptureBody.contains("commitRegistrySnapshot(rootSize, reasons)"))
        assertTrue(commitRootCaptureBody.contains("onFrameCommitted(frame)"))
        assertTrue(commitRootCaptureBody.contains("scheduler.request(RenderReason.RootCaptured)"))
    }

    @Test
    fun coordinatorCommitRootCaptureConsumesPendingReasonsAndAddsRootCaptured() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/ImlaSceneCoordinator.kt")
        val commitBody = extractFunctionBody(source, "fun commitRootCapture(")

        assertTrue(commitBody.contains("scheduler.consumePendingReasons() + RenderReason.RootCaptured"))
    }

    @Test
    fun coordinatorWithoutCommitReturnsNullFromConsumeFrameForRender() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/ImlaSceneCoordinator.kt")
        val consumeBody = extractFunctionBody(source, "fun consumeFrameForRender()")

        assertTrue(consumeBody.contains("val frame = committedFrame ?: return null"))
    }

    @Test
    fun coordinatorExistingFramePreservedWhenNoNewCommit() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/ImlaSceneCoordinator.kt")
        val commitBody = extractFunctionBody(source, "fun commitRegistrySnapshot(")

        assertTrue(commitBody.contains("committedFrame = frame"))
        assertTrue(commitBody.contains("nextGeneration += 1L"))
    }

    @Test
    fun traceCountersRootCaptureFailedExists() {
        val source = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneTraceCounters.kt")

        assertTrue(source.contains("rootCaptureFailures"))
        assertTrue(source.contains("fun rootCaptureFailed()"))
        assertTrue(source.contains("rootCaptureFailures.increment()"))
        assertTrue(source.contains("\"root.capture.failed\""))
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

    private fun extractFunctionBody(source: String, signature: String): String {
        val funStart = source.indexOf(signature)
        require(funStart != -1) { "function '$signature' not found in source" }
        val openBrace = source.indexOf('{', funStart)
        require(openBrace != -1) { "no open brace after '$signature'" }
        return source.substring(funStart, matchingCloseBrace(source, openBrace) + 1)
    }

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
}
