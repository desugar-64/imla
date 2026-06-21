/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl.pipeline

import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import androidx.graphics.surface.SurfaceControlCompat
import androidx.hardware.SyncFenceCompat
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.gl.SceneGlRenderFrame

/**
 * Owns how a finished scene buffer reaches the display, via one of two API-gated backends:
 *  • [HardwareBufferPresenter] (API 29+): zero-copy — hand the scene's HardwareBuffer to
 *    SurfaceFlinger via [SurfaceControlCompat]; no present pass, no `eglSwapBuffers`.
 *  • [BlitPresenter] (all APIs): blit the scene FBO into the EGL window surface.
 *
 * Each frame is `acquire → render → present → release`, driven from the GL thread.
 */
internal interface ScenePresenter {
    fun acquire(size: IntSize, requiresStencil: Boolean): SceneRenderBuffer
    fun present(frame: SceneGlRenderFrame, buffer: SceneRenderBuffer)
    fun release(buffer: SceneRenderBuffer)
    fun close()
}

/**
 * Offscreen FBO + final blit to the EGL window surface. The blit pass draws the scene
 * buffer into the default framebuffer during the frame; `GLRenderer` swaps afterward.
 */
internal class BlitPresenter(
    private val framebufferPool: FramebufferLendingPool,
    private val supportsStencil: () -> Boolean,
    private val presentPass: SceneFinalPresentPass
) : ScenePresenter {
    override fun acquire(size: IntSize, requiresStencil: Boolean): SceneRenderBuffer {
        check(!requiresStencil || supportsStencil()) {
            "Scene non-rectangle clipping requires a stencil-capable GL context"
        }
        val framebuffer = framebufferPool.acquire(
            FramebufferSpecification(
                size = size,
                attachmentsSpec = if (requiresStencil) {
                    FramebufferAttachmentSpecification.withStencil(
                        colorFormat = FramebufferTextureFormat.RGBA8,
                        coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
                    )
                } else {
                    FramebufferAttachmentSpecification.singleColor(
                        format = FramebufferTextureFormat.RGBA8,
                        coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
                    )
                }
            )
        )
        return SceneRenderBuffer(
            size = size,
            hasStencil = requiresStencil,
            backing = SceneRenderBuffer.Backing.Plain(framebuffer)
        )
    }

    override fun present(frame: SceneGlRenderFrame, buffer: SceneRenderBuffer) {
        presentPass.draw(frame = frame, buffer = buffer, viewportDivisor = 1)
    }

    override fun release(buffer: SceneRenderBuffer) {
        (buffer.backing as? SceneRenderBuffer.Backing.Plain)?.let {
            framebufferPool.release(it.framebuffer)
        }
    }

    override fun close() = Unit
}

/**
 * Zero-copy present: hand the scene's HardwareBuffer ([ring]) to SurfaceFlinger via a
 * [SurfaceControlCompat] transaction instead of blitting. The bottom-left GL origin becomes a free
 * vertical buffer transform; the compositor release fence gates reuse (see [SceneHwBufferRing]),
 * so [release] is a no-op.
 *
 * Some SurfaceFlingers (e.g. MI 9 / API 30 MIUI) ignore the acquire fence, so we block on GPU
 * completion ourselves before committing — on [presentThread], off the GL thread so it keeps
 * pipelining. [surfaceControl] is caller-owned (attach/detach); borrowed here.
 */
@RequiresApi(29)
internal class HardwareBufferPresenter(
    private val surfaceControl: SurfaceControlCompat,
    private val ring: SceneHwBufferRing = SceneHwBufferRing()
) : ScenePresenter {
    // [presentThread] exclusively owns transaction + opaqueConfigured; the GL thread only creates
    // the fence and posts.
    private val presentThread = HandlerThread("ImlaScenePresent").apply { start() }
    private val presentHandler = Handler(presentThread.looper)
    private val transaction = SurfaceControlCompat.Transaction()
    private var opaqueConfigured = false

    override fun acquire(size: IntSize, requiresStencil: Boolean): SceneRenderBuffer {
        // The HW FBO always carries a stencil renderbuffer, so requiresStencil is implied.
        val hwBuffer = ring.obtain(size)
        return SceneRenderBuffer(
            size = size,
            hasStencil = true,
            backing = SceneRenderBuffer.Backing.Hw(hwBuffer)
        )
    }

    override fun present(frame: SceneGlRenderFrame, buffer: SceneRenderBuffer) {
        val hwBuffer = (buffer.backing as SceneRenderBuffer.Backing.Hw).hwBuffer
        // Fence created on the GL thread (captures + flushes this frame's commands); mark in-flight
        // before posting so the ring won't reissue this buffer until the compositor releases it.
        val fence = SyncFenceCompat.createNativeSyncFence()
        ring.markPresentInFlight(hwBuffer)
        presentHandler.post {
            // Block on GPU completion ourselves (don't trust the acquire fence), then commit.
            fence.await(FENCE_AWAIT_TIMEOUT_NANOS)
            transaction
                .setVisibility(surfaceControl, true)
                .setBuffer(surfaceControl, hwBuffer.hardwareBuffer, fence) { releaseFence ->
                    ring.onCompositorRelease(hwBuffer, releaseFence)
                }
                .setBufferTransform(
                    surfaceControl,
                    SurfaceControlCompat.BUFFER_TRANSFORM_MIRROR_VERTICAL
                )
            if (!opaqueConfigured) {
                transaction.setOpaque(surfaceControl, true)
                opaqueConfigured = true
            }
            transaction.commit()
            fence.close()
        }
    }

    override fun release(buffer: SceneRenderBuffer) = Unit

    override fun close() {
        // Drain posted presents before freeing buffers, so none is closed while in use.
        presentThread.quitSafely()
        try {
            presentThread.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        transaction.close()
        ring.close()
    }

    private companion object {
        // Backstop only; the fence normally signals within a frame. Proceed on timeout so a lost
        // fence can't freeze present.
        const val FENCE_AWAIT_TIMEOUT_NANOS = 200_000_000L // 200 ms
    }
}
