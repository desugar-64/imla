/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import android.view.Surface
import androidx.compose.ui.unit.IntSize
import androidx.graphics.opengl.GLRenderer
import androidx.graphics.opengl.egl.EGLManager
import dev.serhiiyaremych.imla.internal.ext.logd
import dev.serhiiyaremych.imla.internal.ext.threadTag
import java.util.concurrent.atomic.AtomicBoolean

internal class ImlaSceneSession(
    private val glSession: GlSession,
    private val onSurfaceAttached: (IntSize) -> Unit,
    private val onSurfaceDetached: () -> Unit,
    private val onSurfaceChanged: () -> Unit,
    private val onRenderStarted: () -> Unit,
    private val onRenderFinished: () -> Unit,
    private val onRender: (IntSize) -> Unit,
    private val onRenderCancelled: () -> Unit,
    private val renderGate: () -> Boolean = { true }
) {
    private val lock = Any()
    private var lifecycleState: LifecycleState = LifecycleState.Created
    private var activeGeneration: Long = 0L
    private var targetSize: IntSize = IntSize.Zero
    private var needsFreshRootCapture: Boolean = true

    val isDestroyed: Boolean
        get() = synchronized(lock) { lifecycleState == LifecycleState.Destroyed }

    val currentRenderTargetSize: IntSize
        get() = synchronized(lock) { targetSize }

    fun initializeIfNeeded(size: IntSize): Boolean {
        if (size == IntSize.Zero || isDestroyed) return false
        glSession.initializeIfNeeded(size)
        return true
    }

    fun canCaptureFreshRoot(size: IntSize): Boolean {
        if (size == IntSize.Zero || !glSession.hasInitializedResources) return false
        return synchronized(lock) {
            lifecycleState == LifecycleState.Attached || lifecycleState == LifecycleState.Ready
        }
    }

    fun acceptFreshRootCapture(size: IntSize): Boolean {
        if (size == IntSize.Zero || !glSession.hasInitializedResources) return false
        return synchronized(lock) {
            if (lifecycleState != LifecycleState.Attached && lifecycleState != LifecycleState.Ready) {
                return@synchronized false
            }
            if (targetSize == IntSize.Zero) return@synchronized false

            needsFreshRootCapture = false
            lifecycleState = LifecycleState.Ready
            true
        }
    }

    fun attachSurface(surface: Surface?, size: IntSize) {
        if (surface == null || size == IntSize.Zero) return
        val generation = synchronized(lock) {
            if (lifecycleState == LifecycleState.Destroyed) return

            activeGeneration += 1L
            targetSize = size
            needsFreshRootCapture = true
            lifecycleState = LifecycleState.Attached
            activeGeneration
        }

        onRenderCancelled()
        glSession.attachSurface(
            surface = surface,
            size = size,
            generation = generation,
            isCurrentGeneration = ::isCurrentGeneration,
            onAttached = {
                onSurfaceAttached(size)
                onSurfaceChanged()
            }
        )
    }

    fun detachSurface() {
        val generation = synchronized(lock) {
            if (lifecycleState == LifecycleState.Destroyed) return

            activeGeneration += 1L
            targetSize = IntSize.Zero
            needsFreshRootCapture = true
            lifecycleState = LifecycleState.Suspended
            activeGeneration
        }

        onRenderCancelled()
        glSession.detachSurface(
            generation = generation,
            shouldDetach = ::isCurrentGeneration,
            onDetached = onSurfaceDetached
        )
    }

    fun destroy(destroyGlResources: () -> Unit, stopGlRenderer: () -> Unit) {
        synchronized(lock) {
            if (lifecycleState == LifecycleState.Destroyed) return

            activeGeneration += 1L
            targetSize = IntSize.Zero
            needsFreshRootCapture = true
            lifecycleState = LifecycleState.Destroyed
        }

        onRenderCancelled()
        onSurfaceDetached()
        glSession.destroy(destroyGlResources = destroyGlResources)
        stopGlRenderer()
    }

    fun requestRender(): Boolean {
        if (!renderGate()) return false
        val generation = synchronized(lock) {
            if (lifecycleState != LifecycleState.Ready) return false
            if (needsFreshRootCapture || targetSize == IntSize.Zero) return false
            activeGeneration
        }
        return glSession.requestRender(
            generation = generation,
            isCurrentAndReady = ::isCurrentReady
        )
    }

    private fun drawFrame(generation: Long) {
        val size = synchronized(lock) {
            if (lifecycleState != LifecycleState.Ready) return@synchronized null
            if (activeGeneration != generation || needsFreshRootCapture) return@synchronized null
            targetSize.takeIf { it != IntSize.Zero }
        }

        if (size == null) {
            onRenderStarted()
            try {
                onRenderCancelled()
            } finally {
                onRenderFinished()
            }
            return
        }

        onRenderStarted()
        try {
            onRender(size)
        } finally {
            onRenderFinished()
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean {
        return synchronized(lock) {
            lifecycleState != LifecycleState.Destroyed && activeGeneration == generation
        }
    }

    private fun isCurrentReady(generation: Long): Boolean {
        return synchronized(lock) {
            lifecycleState == LifecycleState.Ready &&
                activeGeneration == generation &&
                !needsFreshRootCapture &&
                targetSize != IntSize.Zero
        }
    }

    internal interface GlSession {
        val hasInitializedResources: Boolean

        fun initializeIfNeeded(size: IntSize)

        fun attachSurface(
            surface: Surface,
            size: IntSize,
            generation: Long,
            isCurrentGeneration: (Long) -> Boolean,
            onAttached: () -> Unit
        )

        fun detachSurface(
            generation: Long,
            shouldDetach: (Long) -> Boolean,
            onDetached: () -> Unit
        )

        fun requestRender(
            generation: Long,
            isCurrentAndReady: (Long) -> Boolean
        ): Boolean

        fun destroy(destroyGlResources: () -> Unit)
    }

    private enum class LifecycleState {
        Created,
        Attached,
        Ready,
        Suspended,
        Destroyed
    }

    internal companion object {
        fun create(
            glRenderer: GLRenderer,
            initializeGlResources: (IntSize) -> Unit,
            releaseSharedSurfaceResources: () -> Unit,
            onSurfaceAttached: (IntSize) -> Unit,
            onSurfaceDetached: () -> Unit,
            onSurfaceChanged: () -> Unit,
            onRenderStarted: () -> Unit,
            onRenderFinished: () -> Unit,
            onRender: (IntSize) -> Unit,
            onRenderCancelled: () -> Unit,
            renderGate: () -> Boolean = { true }
        ): ImlaSceneSession {
            lateinit var session: ImlaSceneSession
            val glSession = ImlaGlSession(
                glRenderer = glRenderer,
                initializeGlResources = initializeGlResources,
                releaseSharedSurfaceResources = releaseSharedSurfaceResources,
                onDrawFrame = { generation -> session.drawFrame(generation) }
            )
            session = ImlaSceneSession(
                glSession = glSession,
                onSurfaceAttached = onSurfaceAttached,
                onSurfaceDetached = onSurfaceDetached,
                onSurfaceChanged = onSurfaceChanged,
                onRenderStarted = onRenderStarted,
                onRenderFinished = onRenderFinished,
                onRender = onRender,
                onRenderCancelled = onRenderCancelled,
                renderGate = renderGate
            )
            return session
        }
    }
}

internal class ImlaGlSession(
    private val glRenderer: GLRenderer,
    private val initializeGlResources: (IntSize) -> Unit,
    private val releaseSharedSurfaceResources: () -> Unit,
    private val onDrawFrame: (Long) -> Unit
) : ImlaSceneSession.GlSession {
    private val resourcesInitialized = AtomicBoolean(false)
    private val renderInFlight = AtomicBoolean(false)

    @Volatile
    private var renderTarget: GLRenderer.RenderTarget? = null

    override val hasInitializedResources: Boolean
        get() = resourcesInitialized.get()

    override fun initializeIfNeeded(size: IntSize) {
        if (size == IntSize.Zero || resourcesInitialized.get()) return
        glRenderer.execute {
            ensureResources(size)
        }
    }

    override fun attachSurface(
        surface: Surface,
        size: IntSize,
        generation: Long,
        isCurrentGeneration: (Long) -> Boolean,
        onAttached: () -> Unit
    ) {
        glRenderer.execute {
            if (!isCurrentGeneration(generation)) return@execute

            logd(TAG, "attachRootSurface size=$size ${threadTag()}")
            renderTarget?.detach(true)
            renderTarget = null

            ensureResources(size)
            renderTarget = glRenderer.attach(
                surface = surface,
                width = size.width,
                height = size.height,
                renderer = callbackFor(generation)
            )
            onAttached()

            logd(TAG, "attachRootSurface complete ${threadTag()}")
        }
    }

    override fun detachSurface(
        generation: Long,
        shouldDetach: (Long) -> Boolean,
        onDetached: () -> Unit
    ) {
        renderInFlight.set(false)
        glRenderer.execute {
            if (!shouldDetach(generation)) return@execute

            logd(TAG, "detachRootSurface ${threadTag()}")
            renderTarget?.detach(true)
            renderTarget = null
            onDetached()
        }
    }

    override fun requestRender(
        generation: Long,
        isCurrentAndReady: (Long) -> Boolean
    ): Boolean {
        val target = renderTarget ?: return false
        if (!resourcesInitialized.get() || !isCurrentAndReady(generation)) return false
        if (!renderInFlight.compareAndSet(false, true)) return false

        logd(TAG, "requestRender ${threadTag()}")
        target.requestRender()
        return true
    }

    override fun destroy(destroyGlResources: () -> Unit) {
        renderInFlight.set(false)
        glRenderer.execute {
            renderTarget?.detach(true)
            renderTarget = null
            if (resourcesInitialized.compareAndSet(true, false)) {
                destroyGlResources()
            }
        }
    }

    private fun callbackFor(generation: Long): GLRenderer.RenderCallback {
        return object : GLRenderer.RenderCallback {
            override fun onDrawFrame(eglManager: EGLManager) {
                if (!renderInFlight.get()) return
                try {
                    onDrawFrame(generation)
                } finally {
                    renderInFlight.set(false)
                }
            }
        }
    }

    private fun ensureResources(size: IntSize) {
        if (!resourcesInitialized.compareAndSet(false, true)) return
        initializeGlResources(size)
        releaseSharedSurfaceResources()
    }

    private companion object {
        private const val TAG = "ImlaGlSession"
    }
}
