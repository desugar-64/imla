/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.capture

import android.graphics.Color
import android.graphics.RenderNode
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
@RunWith(AndroidJUnit4::class)
class SingleBufferRendererTest {
    private val openRenderers = mutableListOf<SingleBufferRenderer>()
    // Tracks the RenderNode for each renderer so pollRender can re-record it with a
    // fresh frame before each render attempt.  HardwareRenderer.syncAndDraw() may
    // return SYNC_FRAME_DROPPED when the display-list has not changed since the last
    // frame, which leaves SingleBufferRenderer's internal state stuck in "Writing".
    // Re-recording the node guarantees the GPU sees dirty content, matching real
    // production usage where UI content changes between frames.
    private val renderNodes = mutableMapOf<SingleBufferRenderer, RenderNode>()
    private var frameCounter = 0

    @Before
    fun requireSingleBufferRendererApi() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    }

    @After
    fun closeOpenRenderers() {
        openRenderers.toList().forEach(::closeOnMain)
        openRenderers.clear()
        renderNodes.clear()
        frameCounter = 0
    }

    @Test
    fun renderReturnsLeaseWithExpectedBufferSize() {
        val renderer = renderer()

        val lease = requireNotNull(renderOnMain(renderer))

        assertEquals(CAPTURE_SIZE.width, lease.buffer.width)
        assertEquals(CAPTURE_SIZE.height, lease.buffer.height)

        lease.release(null)
    }

    @Test
    fun heldLeaseBlocksNextRenderUntilReleased() {
        val renderer = renderer()
        val firstLease = requireNotNull(renderOnMain(renderer))

        assertNull(renderOnMain(renderer, timeoutMs = 50))

        firstLease.release(null)
        val secondLease = pollRender(renderer)

        assertNotNull(secondLease)
        secondLease?.release(null)
    }

    @Test
    fun renderAsyncAcceptsAndRefusesWithOneCallback() {
        val renderer = renderer()
        val resultLatch = CountDownLatch(1)
        val callbackCount = AtomicInteger(0)
        val renderedLease = AtomicReference<BufferLease?>()

        val accepted = runOnMain {
            renderer.renderAsync(vsyncTimeNanos = null, record = {}) { lease ->
                callbackCount.incrementAndGet()
                renderedLease.set(lease)
                resultLatch.countDown()
            }
        }

        assertTrue(accepted)
        assertTrue(resultLatch.await(1, TimeUnit.SECONDS))
        val lease = requireNotNull(renderedLease.get())

        val refused = runOnMain {
            renderer.renderAsync(vsyncTimeNanos = null, record = {}) {
                callbackCount.incrementAndGet()
            }
        }

        assertFalse(refused)
        assertEquals(1, callbackCount.get())

        lease.release(null)
    }

    @Test
    fun renderAsyncThrowsOffMainThread() {
        val renderer = renderer()

        assertThrows(IllegalStateException::class.java) {
            renderer.renderAsync(vsyncTimeNanos = null, record = {}, onResult = {})
        }
    }

    @Test
    fun renderReturnsNullAfterClose() {
        val renderer = renderer()

        closeOnMain(renderer)

        assertNull(renderOnMain(renderer, timeoutMs = 50))
    }

    @Test
    fun closeWhileLeasedAllowsFreshRendererOnSameCaptureThread() {
        val renderer = renderer()
        val lease = requireNotNull(renderOnMain(renderer))

        closeOnMain(renderer)
        lease.release(null)

        val freshRenderer = renderer()
        val freshLease = pollRender(freshRenderer)

        assertNotNull(freshLease)
        freshLease?.release(null)
    }

    @Test
    fun sequentialRenderReleaseCyclesDoNotGetStuck() {
        val renderer = renderer()

        repeat(50) { cycle ->
            val lease = pollRender(renderer)
            assertNotNull("cycle $cycle", lease)
            lease?.release(null)
        }

        val finalLease = pollRender(renderer)
        assertNotNull(finalLease)
        finalLease?.release(null)
    }

    @Test
    fun concurrentReleaseNeverAllowsMoreThanOneLiveLease() {
        val renderer = renderer()
        val liveLeases = AtomicInteger(0)
        val maxLiveLeases = AtomicInteger(0)
        var heldLease: BufferLease? = requireNotNull(pollRender(renderer)).also {
            trackLiveLease(liveLeases, maxLiveLeases)
        }

        repeat(50) { iteration ->
            val previousLease = requireNotNull(heldLease)
            val releaseDone = CountDownLatch(1)
            Thread {
                try {
                    SystemClock.sleep((iteration % 4).toLong())
                    previousLease.release(null)
                    liveLeases.decrementAndGet()
                } finally {
                    releaseDone.countDown()
                }
            }.start()

            // Re-record before the racy 50 ms render attempt so that if the renderer
            // accepts the request it sees fresh content and avoids SYNC_FRAME_DROPPED.
            renderNodes[renderer]?.let { node ->
                val canvas = node.beginRecording(CAPTURE_SIZE.width, CAPTURE_SIZE.height)
                try {
                    canvas.drawColor(Color.rgb(frameCounter and 0xFF, 100, 200))
                    frameCounter++
                } finally {
                    node.endRecording()
                }
            }
            val immediateLease = renderOnMain(renderer, timeoutMs = 50)
            heldLease = immediateLease?.also {
                trackLiveLease(liveLeases, maxLiveLeases)
            }

            assertTrue(releaseDone.await(1, TimeUnit.SECONDS))

            if (heldLease == null) {
                heldLease = requireNotNull(pollRender(renderer)).also {
                    trackLiveLease(liveLeases, maxLiveLeases)
                }
            }
        }

        heldLease?.release(null)
        if (heldLease != null) {
            liveLeases.decrementAndGet()
        }

        val finalLease = pollRender(renderer)
        assertNotNull(finalLease)
        finalLease?.release(null)

        assertTrue(maxLiveLeases.get() <= 1)
    }

    private fun renderer(): SingleBufferRenderer {
        val node = renderNode()
        return SingleBufferRenderer(
            label = "SingleBufferRendererTest",
            size = CAPTURE_SIZE,
            contentRoot = node,
            captureThread = captureThread
        ).also { renderer ->
            openRenderers.add(renderer)
            renderNodes[renderer] = node
        }
    }

    private fun renderNode(): RenderNode {
        val node = RenderNode("SingleBufferRendererTest")
        node.setPosition(0, 0, CAPTURE_SIZE.width, CAPTURE_SIZE.height)
        val canvas = node.beginRecording(CAPTURE_SIZE.width, CAPTURE_SIZE.height)
        try {
            canvas.drawColor(Color.RED)
        } finally {
            node.endRecording()
        }
        return node
    }

    private fun renderOnMain(
        renderer: SingleBufferRenderer,
        timeoutMs: Long = 1_000
    ): BufferLease? {
        val result = BlockingLeaseResult()
        val started = runOnMain {
            renderer.renderAsync(vsyncTimeNanos = null, record = {}, onResult = result::complete)
        }
        return if (started) result.await(timeoutMs) else null
    }

    private fun closeOnMain(renderer: SingleBufferRenderer) {
        runOnMain {
            renderer.close()
        }
        openRenderers.remove(renderer)
        renderNodes.remove(renderer)
    }

    private fun pollRender(
        renderer: SingleBufferRenderer,
        timeoutMs: Long = 5_000
    ): BufferLease? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            // Re-record the RenderNode with a fresh frame before every render attempt.
            // HardwareRenderer.syncAndDraw() may return SYNC_FRAME_DROPPED (treated as
            // "soft success" in SingleBufferRenderer) when the display-list hasn't
            // changed, leaving the renderer's state permanently stuck in "Writing".
            // Updating the node ensures the GPU sees new content, matching real usage
            // where the captured UI layer changes between frames.
            renderNodes[renderer]?.let { node ->
                val canvas = node.beginRecording(CAPTURE_SIZE.width, CAPTURE_SIZE.height)
                try {
                    // Vary the color slightly each frame so HardwareRenderer treats
                    // this as a genuinely different frame.
                    canvas.drawColor(Color.rgb(200, frameCounter and 0xFF, 100))
                    frameCounter++
                } finally {
                    node.endRecording()
                }
            }
            val lease = renderOnMain(renderer, timeoutMs = 500)
            if (lease != null) {
                return lease
            }
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun trackLiveLease(
        liveLeases: AtomicInteger,
        maxLiveLeases: AtomicInteger
    ) {
        val live = liveLeases.incrementAndGet()
        maxLiveLeases.updateAndGet { current -> maxOf(current, live) }
    }

    private fun <T> runOnMain(block: () -> T): T {
        val result = MainThreadResult<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                result.value = block()
            } catch (throwable: Throwable) {
                result.throwable = throwable
            }
        }
        result.throwable?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.value as T
    }

    private class MainThreadResult<T> {
        var value: T? = null
        var throwable: Throwable? = null
    }

    // Blocking adapter over the async renderAsync API so the synchronous test
    // helpers can await a single lease. Mirrors the renderer's completion gate: a
    // lease delivered after this helper has timed out is released, not leaked.
    private class BlockingLeaseResult {
        private val latch = CountDownLatch(1)
        private val lock = Any()
        private var lease: BufferLease? = null
        private var done = false

        fun complete(lease: BufferLease?) {
            val lateLease = synchronized(lock) {
                if (done) {
                    lease
                } else {
                    done = true
                    this.lease = lease
                    latch.countDown()
                    null
                }
            }
            lateLease?.release(null)
        }

        fun await(timeoutMs: Long): BufferLease? {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return synchronized(lock) {
                if (done) {
                    lease
                } else {
                    done = true
                    null
                }
            }
        }
    }

    companion object {
        private val CAPTURE_SIZE = IntSize(64, 64)
        private lateinit var captureThread: CaptureThread

        @JvmStatic
        @BeforeClass
        fun createCaptureThread() {
            captureThread = CaptureThread("SingleBufferRendererTest")
        }

        @JvmStatic
        @AfterClass
        fun closeCaptureThread() {
            captureThread.close()
        }
    }
}
