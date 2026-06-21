/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

internal class SceneRenderScheduler(
    private val renderGate: SceneRenderGate = SceneRenderGate.alwaysEnabled(),
    private val onRenderRequested: () -> Boolean
) {
    private val lock = Any()
    private val pendingReasons = LinkedHashSet<RenderReason>()
    private var renderRequested = false
    private var renderInFlight = false
    private var renderGateBlocked = false

    fun request(reason: RenderReason) {
        var blocked = false
        val shouldRequest = synchronized(lock) {
            pendingReasons.add(reason)
            if (!renderGate.canRenderScene()) {
                renderGateBlocked = true
                blocked = true
                false
            } else if (renderRequested || renderInFlight) {
                false
            } else {
                renderGateBlocked = false
                renderRequested = true
                true
            }
        }
        SceneTraceCounters.renderRequested(reason)
        if (blocked) {
            SceneTraceCounters.renderGateBlocked(reason)
        }
        if (shouldRequest && !onRenderRequested()) {
            synchronized(lock) {
                renderRequested = false
            }
        }
    }

    fun drainPendingReasonsIfGateAllows(): Boolean {
        var drainedCount = 0
        val shouldRequest = synchronized(lock) {
            when {
                !renderGate.canRenderScene() -> {
                    renderGateBlocked = true
                    false
                }
                pendingReasons.isEmpty() -> false
                renderRequested || renderInFlight -> false
                else -> {
                    renderGateBlocked = false
                    renderRequested = true
                    drainedCount = pendingReasons.size
                    true
                }
            }
        }
        if (shouldRequest && !onRenderRequested()) {
            synchronized(lock) {
                renderRequested = false
            }
            return false
        }
        if (drainedCount > 0) {
            SceneTraceCounters.renderGateDrained(drainedCount)
        }
        return shouldRequest
    }

    fun consumePendingReasons(): Set<RenderReason> {
        return synchronized(lock) {
            if (pendingReasons.isEmpty()) {
                return@synchronized emptySet()
            }
            val reasons = pendingReasons.toSet()
            pendingReasons.clear()
            reasons
        }
    }

    fun onRenderStarted() {
        synchronized(lock) {
            renderRequested = false
            renderInFlight = true
        }
    }

    fun onRenderFinished() {
        var blockedReasons: Set<RenderReason>? = null
        val shouldRequest = synchronized(lock) {
            renderInFlight = false
            if (pendingReasons.isEmpty() || renderRequested) {
                false
            } else if (!renderGate.canRenderScene()) {
                val wasBlocked = renderGateBlocked
                renderGateBlocked = true
                if (!wasBlocked) {
                    blockedReasons = pendingReasons.toSet()
                }
                false
            } else {
                renderGateBlocked = false
                renderRequested = true
                true
            }
        }
        if (blockedReasons != null) {
            blockedReasons.forEach { SceneTraceCounters.renderGateBlocked(it) }
        }
        if (shouldRequest && !onRenderRequested()) {
            synchronized(lock) {
                renderRequested = false
            }
        }
    }

    fun cancelRenderRequest() {
        synchronized(lock) {
            renderRequested = false
            renderInFlight = false
        }
    }
}
