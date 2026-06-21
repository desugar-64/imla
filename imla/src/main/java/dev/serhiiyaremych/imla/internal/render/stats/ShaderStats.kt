/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.stats

import android.os.Build
import android.os.Trace
import android.util.Log

public data class ShaderStatsSnapshot(
    val fbo: ResourceLifetimeSnapshot = ResourceLifetimeSnapshot(),
    val texture: ResourceLifetimeSnapshot = ResourceLifetimeSnapshot(),
    val hardwareBufferTexture: ResourceLifetimeSnapshot = ResourceLifetimeSnapshot(),
    val hardwareBufferRootTexture: ResourceLifetimeSnapshot = ResourceLifetimeSnapshot(),
    val hardwareBufferContentTexture: ResourceLifetimeSnapshot = ResourceLifetimeSnapshot(),
    val hardwareBufferMaskTexture: ResourceLifetimeSnapshot = ResourceLifetimeSnapshot(),
    val hardwareBufferClipTexture: ResourceLifetimeSnapshot = ResourceLifetimeSnapshot(),
    val hardwareBufferOtherTexture: ResourceLifetimeSnapshot = ResourceLifetimeSnapshot(),
    val hardwareBufferImportedPixels: Long = 0L,
    val hardwareBufferImportedBytes: Long = 0L,
    val hardwareBufferRootTextureRecreates: Int = 0,
    val hardwareBufferRootTextureReuses: Int = 0,
    val hardwareBufferSlotTextureRecreates: Int = 0,
    val hardwareBufferSlotTextureReuses: Int = 0,
    val shader: ResourceLifetimeSnapshot = ResourceLifetimeSnapshot(),
    val shaderBinds: Int = 0,
    val shaderBindUniformBlock: Int = 0,
    val shaderUploads: Int = 0
) {
    public val fboInstances: Int get() = fbo.created
    public val shaderInstances: Int get() = shader.created
}

public data class ResourceLifetimeSnapshot(
    val created: Int = 0,
    val destroyed: Int = 0,
    val active: Int = 0,
    val peakActive: Int = 0
)

public enum class HardwareBufferTextureSource {
    Root,
    Content,
    Mask,
    Clip,
    Other
}

public object ShaderStats {
    private const val TAG = "ShaderStats"
    private val lock = Any()

    private val fbo = ResourceLifetimeCounter()
    private val texture = ResourceLifetimeCounter()
    private val hardwareBufferTexture = ResourceLifetimeCounter()
    private val hardwareBufferTexturesBySource = HardwareBufferTextureSource.entries
        .associateWith { ResourceLifetimeCounter() }
    private val shader = ResourceLifetimeCounter()
    private var hardwareBufferImportedPixels: Long = 0L
    private var hardwareBufferImportedBytes: Long = 0L
    private var hardwareBufferRootTextureRecreates: Int = 0
    private var hardwareBufferRootTextureReuses: Int = 0
    private var hardwareBufferSlotTextureRecreates: Int = 0
    private var hardwareBufferSlotTextureReuses: Int = 0
    private var shaderBinds: Int = 0
    private var shaderBindUniformBlock: Int = 0
    private var shaderUploads: Int = 0

    public fun printStats() {
        val snapshot = snapshot()
        Log.d(TAG, "--------ShaderStats--------")
        Log.d(
            TAG, """
            fbo                    = ${snapshot.fbo}
            texture                = ${snapshot.texture}
            hardwareBufferTexture  = ${snapshot.hardwareBufferTexture}
            hardwareBufferRoot     = ${snapshot.hardwareBufferRootTexture}
            hardwareBufferContent  = ${snapshot.hardwareBufferContentTexture}
            hardwareBufferMask     = ${snapshot.hardwareBufferMaskTexture}
            hardwareBufferClip     = ${snapshot.hardwareBufferClipTexture}
            hardwareBufferOther    = ${snapshot.hardwareBufferOtherTexture}
            hardwareBufferPixels   = ${snapshot.hardwareBufferImportedPixels}
            hardwareBufferBytes    = ${snapshot.hardwareBufferImportedBytes}
            hardwareBufferRootImport recreates/reuses = ${snapshot.hardwareBufferRootTextureRecreates}/${snapshot.hardwareBufferRootTextureReuses}
            hardwareBufferSlotImport recreates/reuses = ${snapshot.hardwareBufferSlotTextureRecreates}/${snapshot.hardwareBufferSlotTextureReuses}
            shader                 = ${snapshot.shader}
            shaderBinds            = ${snapshot.shaderBinds}
            shaderUploads          = ${snapshot.shaderUploads}
            shaderBindUniformBlock = ${snapshot.shaderBindUniformBlock}
        """.trimIndent()
        )
        Log.d(TAG, "---------------------------")
    }

    public fun recordFboCreated() {
        synchronized(lock) {
            fbo.created()
        }
    }

    public fun recordFboDestroyed() {
        synchronized(lock) {
            fbo.destroyed()
        }
    }

    public fun recordTextureCreated() {
        synchronized(lock) {
            texture.created()
        }
    }

    public fun recordTextureDestroyed(count: Int = 1) {
        synchronized(lock) {
            texture.destroyed(count)
        }
    }

    public fun recordHardwareBufferTextureCreated(
        source: HardwareBufferTextureSource = HardwareBufferTextureSource.Other,
        pixels: Long = 0L,
        bytes: Long = 0L
    ) {
        synchronized(lock) {
            hardwareBufferTexture.created()
            hardwareBufferTexturesBySource.getValue(source).created()
            hardwareBufferImportedPixels += pixels.coerceAtLeast(0L)
            hardwareBufferImportedBytes += bytes.coerceAtLeast(0L)
        }
    }

    public fun recordHardwareBufferTextureDestroyed(
        source: HardwareBufferTextureSource = HardwareBufferTextureSource.Other
    ) {
        synchronized(lock) {
            hardwareBufferTexture.destroyed()
            hardwareBufferTexturesBySource.getValue(source).destroyed()
        }
    }

    public fun recordHardwareBufferRootTextureRecreated() {
        synchronized(lock) {
            hardwareBufferRootTextureRecreates++
        }
    }

    public fun recordHardwareBufferRootTextureReused() {
        synchronized(lock) {
            hardwareBufferRootTextureReuses++
        }
    }

    public fun recordHardwareBufferSlotTextureRecreated() {
        synchronized(lock) {
            hardwareBufferSlotTextureRecreates++
        }
    }

    public fun recordHardwareBufferSlotTextureReused() {
        synchronized(lock) {
            hardwareBufferSlotTextureReuses++
        }
    }

    public fun recordShaderCreated() {
        synchronized(lock) {
            shader.created()
        }
    }

    public fun recordShaderDestroyed() {
        synchronized(lock) {
            shader.destroyed()
        }
    }

    public fun recordShaderBind() {
        synchronized(lock) {
            shaderBinds++
        }
    }

    public fun recordShaderBindUniformBlock() {
        synchronized(lock) {
            shaderBindUniformBlock++
        }
    }

    public fun recordShaderUpload() {
        synchronized(lock) {
            shaderUploads++
        }
    }

    public fun reset() {
        synchronized(lock) {
            shaderBinds = 0
            shaderUploads = 0
            shaderBindUniformBlock = 0
        }
    }

    public fun snapshot(): ShaderStatsSnapshot = synchronized(lock) {
        val snapshot = ShaderStatsSnapshot(
            fbo = fbo.snapshot(),
            texture = texture.snapshot(),
            hardwareBufferTexture = hardwareBufferTexture.snapshot(),
            hardwareBufferRootTexture = hardwareBufferTexturesBySource.getValue(HardwareBufferTextureSource.Root).snapshot(),
            hardwareBufferContentTexture = hardwareBufferTexturesBySource.getValue(HardwareBufferTextureSource.Content).snapshot(),
            hardwareBufferMaskTexture = hardwareBufferTexturesBySource.getValue(HardwareBufferTextureSource.Mask).snapshot(),
            hardwareBufferClipTexture = hardwareBufferTexturesBySource.getValue(HardwareBufferTextureSource.Clip).snapshot(),
            hardwareBufferOtherTexture = hardwareBufferTexturesBySource.getValue(HardwareBufferTextureSource.Other).snapshot(),
            hardwareBufferImportedPixels = hardwareBufferImportedPixels,
            hardwareBufferImportedBytes = hardwareBufferImportedBytes,
            hardwareBufferRootTextureRecreates = hardwareBufferRootTextureRecreates,
            hardwareBufferRootTextureReuses = hardwareBufferRootTextureReuses,
            hardwareBufferSlotTextureRecreates = hardwareBufferSlotTextureRecreates,
            hardwareBufferSlotTextureReuses = hardwareBufferSlotTextureReuses,
            shader = shader.snapshot(),
            shaderBinds = shaderBinds,
            shaderBindUniformBlock = shaderBindUniformBlock,
            shaderUploads = shaderUploads
        )
        snapshot.emitTraceCounters()
        snapshot
    }

    private class ResourceLifetimeCounter {
        private var created = 0
        private var destroyed = 0
        private var active = 0
        private var peakActive = 0

        fun created() {
            created++
            active++
            peakActive = peakActive.coerceAtLeast(active)
        }

        fun destroyed(count: Int = 1) {
            val safeCount = count.coerceAtLeast(0)
            destroyed += safeCount
            active = (active - safeCount).coerceAtLeast(0)
        }

        fun snapshot(): ResourceLifetimeSnapshot {
            return ResourceLifetimeSnapshot(
                created = created,
                destroyed = destroyed,
                active = active,
                peakActive = peakActive
            )
        }
    }

    private fun ShaderStatsSnapshot.emitTraceCounters() {
        fbo.emitTraceCounters("fbo")
        texture.emitTraceCounters("texture")
        hardwareBufferTexture.emitTraceCounters("hardwareBufferTexture")
        hardwareBufferRootTexture.emitTraceCounters("hardwareBufferTexture.root")
        hardwareBufferContentTexture.emitTraceCounters("hardwareBufferTexture.content")
        hardwareBufferMaskTexture.emitTraceCounters("hardwareBufferTexture.mask")
        hardwareBufferClipTexture.emitTraceCounters("hardwareBufferTexture.clip")
        hardwareBufferOtherTexture.emitTraceCounters("hardwareBufferTexture.other")
        setTraceCounter("hardwareBufferTexture.importedPixels", hardwareBufferImportedPixels)
        setTraceCounter("hardwareBufferTexture.importedBytes", hardwareBufferImportedBytes)
        setTraceCounter("hardwareBufferTexture.root.recreates", hardwareBufferRootTextureRecreates)
        setTraceCounter("hardwareBufferTexture.root.reuses", hardwareBufferRootTextureReuses)
        setTraceCounter("hardwareBufferTexture.slot.recreates", hardwareBufferSlotTextureRecreates)
        setTraceCounter("hardwareBufferTexture.slot.reuses", hardwareBufferSlotTextureReuses)
        shader.emitTraceCounters("shader")
        setTraceCounter("shader.binds", shaderBinds)
        setTraceCounter("shader.uploads", shaderUploads)
        setTraceCounter("shader.uniformBlocks", shaderBindUniformBlock)
    }

    private fun ResourceLifetimeSnapshot.emitTraceCounters(name: String) {
        setTraceCounter("$name.created", created)
        setTraceCounter("$name.destroyed", destroyed)
        setTraceCounter("$name.active", active)
        setTraceCounter("$name.peakActive", peakActive)
    }

    private fun setTraceCounter(name: String, value: Int) {
        setTraceCounter(name, value.toLong())
    }

    private fun setTraceCounter(name: String, value: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.setCounter("$TRACE_PREFIX.$name", value)
        }
    }

    private const val TRACE_PREFIX = "ImlaSceneResource"
}
