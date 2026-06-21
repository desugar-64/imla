/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import android.view.Surface
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImlaSceneSessionRootCaptureContractTest {
    @Test
    fun requestRenderWaitsForAcceptedFreshRootCapture() {
        val glSession = FakeGlSession(hasInitializedResources = true)
        val session = session(glSession)
        val size = IntSize(200, 100)
        session.forceState(
            lifecycleState = "Attached",
            targetSize = size,
            needsFreshRootCapture = true,
            activeGeneration = 7L
        )

        assertTrue(session.canCaptureFreshRoot(size))
        assertFalse(session.requestRender())
        assertEquals(0, glSession.renderRequests)

        assertTrue(session.acceptFreshRootCapture(size))
        assertTrue(session.requestRender())

        assertEquals(1, glSession.renderRequests)
        assertEquals(7L, glSession.lastRenderGeneration)
    }

    @Test
    fun reattachInvalidatesPreviousReadyRootUntilFreshCaptureIsAccepted() {
        val glSession = FakeGlSession(hasInitializedResources = true)
        val session = session(glSession)
        val size = IntSize(200, 100)
        session.forceState(
            lifecycleState = "Ready",
            targetSize = size,
            needsFreshRootCapture = false,
            activeGeneration = 1L
        )

        assertTrue(session.requestRender())
        val staleGeneration = glSession.lastRenderGeneration ?: error("missing render generation")
        val staleReadyCheck = glSession.lastReadyCheck ?: error("missing ready check")

        session.forceState(
            lifecycleState = "Attached",
            targetSize = size,
            needsFreshRootCapture = true,
            activeGeneration = 2L
        )

        assertFalse(staleReadyCheck(staleGeneration))
        assertFalse(session.requestRender())
        assertEquals(1, glSession.renderRequests)

        assertTrue(session.acceptFreshRootCapture(size))
        assertTrue(session.requestRender())

        assertEquals(2, glSession.renderRequests)
        assertEquals(2L, glSession.lastRenderGeneration)
    }

    @Test
    fun rootCaptureIsRejectedUntilGlResourcesExist() {
        val glSession = FakeGlSession(hasInitializedResources = false)
        val session = session(glSession)
        val size = IntSize(200, 100)
        session.forceState(
            lifecycleState = "Attached",
            targetSize = size,
            needsFreshRootCapture = true,
            activeGeneration = 1L
        )

        assertFalse(session.canCaptureFreshRoot(size))
        assertFalse(session.acceptFreshRootCapture(size))
        assertFalse(session.requestRender())
        assertEquals(0, glSession.renderRequests)
    }

    @Test
    fun sessionBlockedRenderGateRejectsRequestRender() {
        val glSession = FakeGlSession(hasInitializedResources = true)
        val session = session(glSession, renderGate = { false })
        val size = IntSize(200, 100)
        session.forceState(
            lifecycleState = "Ready",
            targetSize = size,
            needsFreshRootCapture = false,
            activeGeneration = 1L
        )

        assertFalse(session.requestRender())
        assertEquals(0, glSession.renderRequests)
    }

    @Test
    fun sessionRenderGateOverrideAllowsWhenReady() {
        val glSession = FakeGlSession(hasInitializedResources = true)
        val session = session(glSession, renderGate = { true })
        val size = IntSize(200, 100)
        session.forceState(
            lifecycleState = "Ready",
            targetSize = size,
            needsFreshRootCapture = false,
            activeGeneration = 1L
        )

        assertTrue(session.requestRender())
        assertEquals(1, glSession.renderRequests)
    }

    private fun session(
        glSession: FakeGlSession,
        renderGate: () -> Boolean = { true }
    ): ImlaSceneSession {
        return ImlaSceneSession(
            glSession = glSession,
            onSurfaceAttached = {},
            onSurfaceDetached = {},
            onSurfaceChanged = {},
            onRenderStarted = {},
            onRenderFinished = {},
            onRender = {},
            onRenderCancelled = {},
            renderGate = renderGate
        )
    }

    private fun ImlaSceneSession.forceState(
        lifecycleState: String,
        targetSize: IntSize,
        needsFreshRootCapture: Boolean,
        activeGeneration: Long
    ) {
        val stateClass = Class.forName("${ImlaSceneSession::class.java.name}\$LifecycleState")
        val stateValue = stateClass.enumConstants
            .single { enumValue -> (enumValue as Enum<*>).name == lifecycleState }
        field("lifecycleState").set(this, stateValue)
        field("targetSize").setLong(this, targetSize.packForInlineStorage())
        field("needsFreshRootCapture").setBoolean(this, needsFreshRootCapture)
        field("activeGeneration").setLong(this, activeGeneration)
    }

    private fun ImlaSceneSession.field(name: String) =
        javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun IntSize.packForInlineStorage(): Long {
        return (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)
    }

    private class FakeGlSession(
        override var hasInitializedResources: Boolean
    ) : ImlaSceneSession.GlSession {
        var renderRequests: Int = 0
            private set
        var lastRenderGeneration: Long? = null
            private set
        var lastReadyCheck: ((Long) -> Boolean)? = null
            private set

        override fun initializeIfNeeded(size: IntSize) {
            hasInitializedResources = true
        }

        override fun attachSurface(
            surface: Surface,
            size: IntSize,
            generation: Long,
            isCurrentGeneration: (Long) -> Boolean,
            onAttached: () -> Unit
        ) {
            onAttached()
        }

        override fun detachSurface(
            generation: Long,
            shouldDetach: (Long) -> Boolean,
            onDetached: () -> Unit
        ) {
            onDetached()
        }

        override fun requestRender(
            generation: Long,
            isCurrentAndReady: (Long) -> Boolean
        ): Boolean {
            renderRequests += 1
            lastRenderGeneration = generation
            lastReadyCheck = isCurrentAndReady
            return isCurrentAndReady(generation)
        }

        override fun destroy(destroyGlResources: () -> Unit) {
            destroyGlResources()
        }
    }
}
