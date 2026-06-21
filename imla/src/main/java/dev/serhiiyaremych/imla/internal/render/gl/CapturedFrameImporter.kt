package dev.serhiiyaremych.imla.internal.render.gl

import android.os.Build
import android.opengl.GLES30
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.capture.BufferLease
import dev.serhiiyaremych.imla.internal.capture.CapturedHardwareBufferFrame
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderMetricsLog
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.GraphicsContext
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.opengl.OpenGLHardwareBufferTexture2D
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import dev.serhiiyaremych.imla.internal.render.stats.HardwareBufferTextureSource
import dev.serhiiyaremych.imla.internal.render.stats.ShaderStats
import java.nio.Buffer
import java.util.LinkedHashMap

@RequiresApi(Build.VERSION_CODES.Q)
internal class CapturedFrameImporter(
    private val threadGuard: SceneGlThreadGuard,
    private val releaseQueue: HardwareBufferReleaseQueue,
    private val graphicsContextProvider: () -> GraphicsContext,
    private val simpleQuadRendererProvider: () -> SimpleQuadRenderer
) : AutoCloseable {
    private var ownedRootTransferFramebuffer: Framebuffer? = null
    // Per-(slot, source) transfer framebuffers reused across frames. The HardwareBuffer
    // lease must be returned to the capture pool after import, so its content is copied
    // into an importer-owned texture; caching that target avoids allocating a fresh
    // framebuffer + texture every slot every frame (the rotating-cards churn). Mirrors
    // ownedRootTransferFramebuffer. GlStore borrows these; the importer owns/destroys them.
    private val cachedSlotTransferFramebuffers = mutableMapOf<SlotTransferKey, Framebuffer>()
    private var cachedLegacyRootHardwareBufferTexture: OpenGLHardwareBufferTexture2D? = null
    private val cachedRootHardwareBufferTextures =
        object : LinkedHashMap<Long, CachedHardwareBufferTexture>(
            MAX_ROOT_HARDWARE_BUFFER_TEXTURE_CACHE_SIZE,
            0.75f,
            true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, CachedHardwareBufferTexture>?
            ): Boolean {
                val shouldEvict = size > MAX_ROOT_HARDWARE_BUFFER_TEXTURE_CACHE_SIZE
                if (shouldEvict) {
                    eldest?.value?.texture?.destroy()
                }
                return shouldEvict
            }
        }
    private val cachedSlotHardwareBufferTextures =
        object : LinkedHashMap<Long, CachedHardwareBufferTexture>(
            MAX_SLOT_HARDWARE_BUFFER_TEXTURE_CACHE_SIZE,
            0.75f,
            true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, CachedHardwareBufferTexture>?
            ): Boolean {
                val shouldEvict = size > MAX_SLOT_HARDWARE_BUFFER_TEXTURE_CACHE_SIZE
                if (shouldEvict) {
                    eldest?.value?.texture?.destroy()
                }
                return shouldEvict
            }
        }

    @OnGlThread
    fun importRoot(frame: CapturedHardwareBufferFrame): Texture2D? {
        threadGuard.checkCurrentThread()
        return withWrappedBuffer(
            frame = frame,
            source = HardwareBufferTextureSource.Root
        ) { sourceTexture ->
            copyToOwnedRootFramebuffer(
                sourceTexture = sourceTexture,
                size = frame.size,
                label = HardwareBufferTextureSource.Root.name
            ).colorAttachmentTexture
        }
    }

    @OnGlThread
    fun importOwned(
        slotId: SceneSlotId,
        frame: CapturedHardwareBufferFrame,
        source: HardwareBufferTextureSource
    ): Texture2D? {
        threadGuard.checkCurrentThread()
        return withWrappedBuffer(
            frame = frame,
            source = source
        ) { sourceTexture ->
            copyToOwnedSlotTexture(
                slotId = slotId,
                sourceTexture = sourceTexture,
                size = frame.size,
                source = source
            )
        }
    }

    @OnGlThread
    override fun close() {
        threadGuard.checkCurrentThread()
        cachedLegacyRootHardwareBufferTexture?.destroy()
        cachedLegacyRootHardwareBufferTexture = null
        destroyCachedRootHardwareBufferTextures()
        destroyCachedSlotHardwareBufferTextures()
        cachedSlotTransferFramebuffers.values.forEach { it.destroy() }
        cachedSlotTransferFramebuffers.clear()
        ownedRootTransferFramebuffer?.destroy()
        ownedRootTransferFramebuffer = null
    }

    private inline fun <T> withWrappedBuffer(
        frame: CapturedHardwareBufferFrame,
        source: HardwareBufferTextureSource,
        releaseResultOnFailure: (T) -> Unit = {},
        block: (Texture2D) -> T
    ): T? {
        val lease = frame.takeLease()
        var openLease: BufferLease? = lease
        var temporaryTexture: OpenGLHardwareBufferTexture2D? = null
        var result: T? = null
        var hasResult = false
        try {
            SceneRenderMetricsLog.time(
                phase = "graphicsLayer.captureCanvas.awaitFence",
                details = source.name
            ) {
                lease.awaitReady()
            }
            val sourceTexture = SceneRenderMetricsLog.time(
                phase = "graphicsLayer.captureCanvas.syncImport",
                details = "${source.name} ${frame.size.width}x${frame.size.height}"
            ) {
                val importedTexture = if (source == HardwareBufferTextureSource.Root) {
                    rootHardwareBufferTextureFor(frame, lease)
                } else {
                    slotHardwareBufferTextureFor(frame, lease, source)
                }
                importedTexture.also {
                    if (
                        source != HardwareBufferTextureSource.Root &&
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ) {
                        temporaryTexture = importedTexture
                    }
                }
            } ?: return null

            result = block(sourceTexture)
            hasResult = true
            SceneRenderMetricsLog.time(
                phase = "graphicsLayer.captureCanvas.releaseBuffer",
                details = "${source.name} ${frame.size.width}x${frame.size.height}"
            ) {
                releaseQueue.releaseAfterSubmittedGlWork(lease)
            }
            openLease = null
            return result
        } catch (throwable: Throwable) {
            if (hasResult) {
                @Suppress("UNCHECKED_CAST")
                releaseResultOnFailure(result as T)
            }
            throw throwable
        } finally {
            openLease?.close()
            temporaryTexture?.destroy()
            frame.close()
        }
    }

    private fun slotHardwareBufferTextureFor(
        frame: CapturedHardwareBufferFrame,
        lease: BufferLease,
        source: HardwareBufferTextureSource
    ): OpenGLHardwareBufferTexture2D? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return OpenGLHardwareBufferTexture2D.createFromBuffer(
                hardwareBuffer = lease.buffer,
                sizePx = frame.size,
                coordinateOrigin = CoordinateOrigin.TOP_LEFT,
                source = source
            )
        }

        val bufferId = lease.buffer.id
        val cachedTexture = cachedSlotHardwareBufferTextures[bufferId]
        if (cachedTexture != null) {
            if (cachedTexture.size == frame.size) {
                ShaderStats.recordHardwareBufferSlotTextureReused()
                return cachedTexture.texture
            }
            cachedTexture.texture.destroy()
            cachedSlotHardwareBufferTextures.remove(bufferId)
        }

        return OpenGLHardwareBufferTexture2D.createFromBuffer(
            hardwareBuffer = lease.buffer,
            sizePx = frame.size,
            coordinateOrigin = CoordinateOrigin.TOP_LEFT,
            source = source
        )?.also { importedTexture ->
            cachedSlotHardwareBufferTextures[bufferId] = CachedHardwareBufferTexture(
                texture = importedTexture,
                size = frame.size
            )
            ShaderStats.recordHardwareBufferSlotTextureRecreated()
        }
    }

    private fun rootHardwareBufferTextureFor(
        frame: CapturedHardwareBufferFrame,
        lease: BufferLease
    ): OpenGLHardwareBufferTexture2D? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cachedLegacyRootHardwareBufferTexture?.destroy()
            cachedLegacyRootHardwareBufferTexture = null
            return OpenGLHardwareBufferTexture2D.createFromBuffer(
                hardwareBuffer = lease.buffer,
                sizePx = frame.size,
                coordinateOrigin = CoordinateOrigin.TOP_LEFT,
                source = HardwareBufferTextureSource.Root
            )?.also { importedTexture ->
                cachedLegacyRootHardwareBufferTexture = importedTexture
                ShaderStats.recordHardwareBufferRootTextureRecreated()
            }
        }

        val rootTextureIterator = cachedRootHardwareBufferTextures.entries.iterator()
        while (rootTextureIterator.hasNext()) {
            val cachedTexture = rootTextureIterator.next().value
            if (cachedTexture.size != frame.size) {
                cachedTexture.texture.destroy()
                rootTextureIterator.remove()
            }
        }

        val bufferId = lease.buffer.id
        val cachedTexture = cachedRootHardwareBufferTextures[bufferId]
        if (cachedTexture != null) {
            if (cachedTexture.size == frame.size) {
                ShaderStats.recordHardwareBufferRootTextureReused()
                return cachedTexture.texture
            }
            cachedTexture.texture.destroy()
            cachedRootHardwareBufferTextures.remove(bufferId)
        }

        return OpenGLHardwareBufferTexture2D.createFromBuffer(
            hardwareBuffer = lease.buffer,
            sizePx = frame.size,
            coordinateOrigin = CoordinateOrigin.TOP_LEFT,
            source = HardwareBufferTextureSource.Root
        )?.also { importedTexture ->
            cachedRootHardwareBufferTextures[bufferId] = CachedHardwareBufferTexture(
                texture = importedTexture,
                size = frame.size
            )
            ShaderStats.recordHardwareBufferRootTextureRecreated()
        }
    }

    private fun copyToOwnedRootFramebuffer(
        sourceTexture: Texture2D,
        size: IntSize,
        label: String
    ): Framebuffer {
        val framebuffer = ensureOwnedRootTransferFramebuffer(size)
        copyToOwnedTexture(sourceTexture, framebuffer, size, label)
        return framebuffer
    }

    private fun copyToOwnedSlotTexture(
        slotId: SceneSlotId,
        sourceTexture: Texture2D,
        size: IntSize,
        source: HardwareBufferTextureSource
    ): Texture2D {
        val framebuffer = ensureSlotTransferFramebuffer(slotId, source, size)
        copyToOwnedTexture(sourceTexture, framebuffer, size, source.name)
        return BorrowedFramebufferTexture2D(framebuffer)
    }

    private fun ensureSlotTransferFramebuffer(
        slotId: SceneSlotId,
        source: HardwareBufferTextureSource,
        size: IntSize
    ): Framebuffer {
        val key = SlotTransferKey(slotId, source)
        val current = cachedSlotTransferFramebuffers[key]
        if (current?.specification?.size == size) {
            return current
        }
        current?.destroy()
        return createTransferFramebuffer(size).also { cachedSlotTransferFramebuffers[key] = it }
    }

    private fun ensureOwnedRootTransferFramebuffer(size: IntSize): Framebuffer {
        val currentFramebuffer = ownedRootTransferFramebuffer
        if (currentFramebuffer?.specification?.size == size) {
            return currentFramebuffer
        }
        currentFramebuffer?.destroy()
        return createTransferFramebuffer(size).also { framebuffer ->
            ownedRootTransferFramebuffer = framebuffer
        }
    }

    private fun createTransferFramebuffer(size: IntSize): Framebuffer {
        return Framebuffer.create(
            spec = FramebufferSpecification(
                size = size,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                    format = FramebufferTextureFormat.RGBA8,
                    coordinateOrigin = CoordinateOrigin.TOP_LEFT
                )
            ),
            commands = graphicsContextProvider().commands
        )
    }

    /**
     * Copies [sourceTexture] into [framebuffer]'s color attachment. The draw fallback
     * leaves [framebuffer] bound as [Bind.DRAW] with the viewport set to [size]; the
     * copyImage path leaves draw state untouched. Callers must bind their own target
     * before the next pass rather than rely on the GL state on return.
     */
    private fun copyToOwnedTexture(
        sourceTexture: Texture2D,
        framebuffer: Framebuffer,
        size: IntSize,
        label: String
    ) {
        if (graphicsContextProvider().supportsFastTextureOps()) {
            copyToOwnedTextureWithCopyImage(sourceTexture, framebuffer, size, label)
        } else {
            copyToOwnedTextureWithDraw(sourceTexture, framebuffer, size, label)
        }
    }

    private fun copyToOwnedTextureWithCopyImage(
        sourceTexture: Texture2D,
        framebuffer: Framebuffer,
        size: IntSize,
        label: String
    ) {
        SceneRenderMetricsLog.time(
            phase = "graphicsLayer.captureCanvas.copyToOwnedTexture.copyImage",
            details = "$label ${size.width}x${size.height}"
        ) {
            graphicsContextProvider().commands.copyImageSubData(
                srcName = sourceTexture.id,
                srcTarget = GLES30.GL_TEXTURE_2D,
                srcLevel = 0,
                srcX = 0,
                srcY = 0,
                srcZ = 0,
                dstName = framebuffer.colorAttachmentTexture.id,
                dstTarget = GLES30.GL_TEXTURE_2D,
                dstLevel = 0,
                dstX = 0,
                dstY = 0,
                dstZ = 0,
                srcWidth = size.width,
                srcHeight = size.height,
                srcDepth = 1
            )
        }
    }

    private fun copyToOwnedTextureWithDraw(
        sourceTexture: Texture2D,
        framebuffer: Framebuffer,
        size: IntSize,
        label: String
    ) {
        SceneRenderMetricsLog.time(
            phase = "graphicsLayer.captureCanvas.copyToOwnedTexture.draw",
            details = "$label ${size.width}x${size.height}"
        ) {
            val commands = graphicsContextProvider().commands
            framebuffer.bind(commands, Bind.DRAW, updateViewport = true)
            commands.clear()
            simpleQuadRendererProvider().draw(
                texture = sourceTexture,
                textureCoordinatesFlat = null,
                flipY = false
            )
        }
    }

    private fun destroyCachedSlotHardwareBufferTextures() {
        cachedSlotHardwareBufferTextures.values.forEach { cachedTexture ->
            cachedTexture.texture.destroy()
        }
        cachedSlotHardwareBufferTextures.clear()
    }

    private fun destroyCachedRootHardwareBufferTextures() {
        cachedRootHardwareBufferTextures.values.forEach { cachedTexture ->
            cachedTexture.texture.destroy()
        }
        cachedRootHardwareBufferTextures.clear()
    }
}

private const val MAX_ROOT_HARDWARE_BUFFER_TEXTURE_CACHE_SIZE = 2
private const val MAX_SLOT_HARDWARE_BUFFER_TEXTURE_CACHE_SIZE = 8

private data class CachedHardwareBufferTexture(
    val texture: OpenGLHardwareBufferTexture2D,
    val size: IntSize
)

private data class SlotTransferKey(
    val slotId: SceneSlotId,
    val source: HardwareBufferTextureSource
)

// A non-owning view over an importer-owned, cached slot transfer framebuffer's color
// attachment. destroy() is a no-op: the importer owns the framebuffer lifecycle and
// reuses it across frames, so GlStore borrowing this must not free it.
private class BorrowedFramebufferTexture2D(
    framebuffer: Framebuffer
) : Texture2D() {
    private val texture: Texture2D = framebuffer.colorAttachmentTexture

    override val target: Texture.Target = texture.target
    override val specification: Texture.Specification = texture.specification
    override val width: Int get() = texture.width
    override val height: Int get() = texture.height
    override val id: Int get() = texture.id
    override val coordinateOrigin: CoordinateOrigin get() = texture.coordinateOrigin

    override fun generateMipMaps() = texture.generateMipMaps()

    override fun setData(data: Buffer) = texture.setData(data)

    override fun bind(slot: Int) = texture.bind(slot)

    override fun isLoaded(): Boolean = texture.isLoaded()

    override fun destroy() {
        // Borrowed: the importer owns and reuses the backing framebuffer.
    }
}

