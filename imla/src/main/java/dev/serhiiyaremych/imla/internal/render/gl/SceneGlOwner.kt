package dev.serhiiyaremych.imla.internal.render.gl

import android.content.res.AssetManager
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.graphics.opengl.GLRenderer
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.capture.CapturedHardwareBufferFrame
import dev.serhiiyaremych.imla.internal.capture.CapturedLayerFrame
import dev.serhiiyaremych.imla.internal.render.GraphicsContext
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Renderer2D
import dev.serhiiyaremych.imla.internal.render.SimpleRenderer
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.objects.QuadShaderProgram
import dev.serhiiyaremych.imla.internal.render.opengl.OpenGLRendererAPI
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.shader.ShaderProgram
import dev.serhiiyaremych.imla.internal.render.stats.HardwareBufferTextureSource
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.HardwareBitmapPresenter
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.SceneBackdropEffectPass
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.SceneRenderPipeline
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.SceneRootDrawPass
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.SceneSlotContentPass
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.SceneStencilClipPass
import dev.serhiiyaremych.imla.internal.metrics.SceneMetrics
import dev.serhiiyaremych.imla.internal.metrics.SceneRenderMetricsLog
import dev.serhiiyaremych.imla.internal.metrics.SceneMetricsFrame
import dev.serhiiyaremych.imla.internal.layer.model.SceneClipShapeKey
import dev.serhiiyaremych.imla.internal.layer.model.SceneProgressiveMaskKey
import dev.serhiiyaremych.imla.internal.layer.model.UiSceneCapture
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotDeclaration
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import dev.serhiiyaremych.imla.internal.render.scheduler.SceneFrameDropReason
import dev.serhiiyaremych.imla.internal.render.scheduler.SceneFrameRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class SceneGlOwner(
    assetManager: AssetManager,
    private val metrics: SceneMetrics,
    private val afterRenderComplete: () -> Unit = {},
    private val afterContentImport: (Set<SceneSlotId>) -> Unit = {},
    private val afterProgressiveMaskImport: (Map<SceneSlotId, SceneProgressiveMaskKey>) -> Unit = {},
    private val afterClipImport: (Map<SceneSlotId, SceneClipShapeKey>) -> Unit = {},
    private val onHardwareBitmapPresented: (SceneSlotId, ImageBitmap) -> Unit = { _, _ -> }
) : AutoCloseable {
    private val shaderLibrary = ShaderLibrary(assetManager)
    private val shaderBinder = ShaderBinder()
    private val quadRenderer = Renderer2D()
    private val simpleRenderer = SimpleRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer(
        shaderLibrary = shaderLibrary,
        renderer = simpleRenderer,
        shaderBinder = shaderBinder
    )
    private val quadBatchRenderer = QuadBatchRenderer(
        renderer2D = quadRenderer,
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder,
        commandsProvider = { requireNotNull(graphicsContext).commands }
    )
    private val threadGuard = SceneGlThreadGuard("SceneGlOwner")
    private val hardwareBufferReleaseQueue = HardwareBufferReleaseQueue(threadGuard)
    private var capturedFrameImporter: CapturedFrameImporter? = null
    private val glStore = GlStore(threadGuard)
    private val glRenderer = GLRenderer().apply { start("ImlaSceneGL") }
    private val closed = AtomicBoolean(false)
    private val pendingBySlot = ConcurrentHashMap<SceneSlotId, SceneFrameRequest>()
    private val presentScheduled = AtomicBoolean(false)

    private var graphicsContext: GraphicsContext? = null
    private var pendingRenderCompleteFrame: SceneMetricsFrame? = null
    private var renderPipeline: SceneRenderPipeline? = null
    @android.annotation.SuppressLint("NewApi")
    private val hardwareBitmapPresenter: HardwareBitmapPresenter? =
        if (Build.VERSION.SDK_INT >= 29) {
            HardwareBitmapPresenter(onPresented = onHardwareBitmapPresented)
        } else {
            null
        }

    @MainThread
    @RequiresApi(29)
    fun attachOffscreenRenderTarget(): SceneRenderTarget {
        check(!closed.get()) { "SceneGlOwner is closed" }
        return SceneRenderTarget(
            glOwner = this,
            glRenderer = glRenderer
        )
    }

    @MainThread
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingBySlot.values.forEach { it.close() }
        pendingBySlot.clear()
        presentScheduled.set(false)
        glRenderer.execute {
            threadGuard.bindCurrentThread()
            if (Build.VERSION.SDK_INT >= 29) {
                renderPipeline?.release()
            }
            glStore.destroy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                capturedFrameImporter?.close()
            }
            if (graphicsContext != null) {
                quadRenderer.shutdown()
                simpleRenderer.shutdown()
            }
            renderPipeline = null
            graphicsContext?.destroy()
            graphicsContext = null
        }
        glRenderer.stop(cancelPending = false)
    }

    @MainThread
    internal fun submit(target: SceneRenderTarget, request: SceneFrameRequest) {
        if (closed.get() || target.isDetached()) {
            metrics.recordDropped(SceneFrameDropReason.Closed)
            request.close()
            return
        }
        request.uiCapture.metricsFrame?.let { frame ->
            metrics.recordSubmitted(frame, request.readyAtNanos)
        }
        val slotId = request.uiCapture.slot.id
        val displaced = pendingBySlot.put(slotId, request)
        displaced?.close()
        if (presentScheduled.compareAndSet(false, true) &&
            !target.scheduleDraw(::onRenderComplete)
        ) {
            presentScheduled.set(false)
        }
    }

    @MainThread
    internal fun onTargetDetached() {
        pendingBySlot.values.forEach { it.close() }
        pendingBySlot.clear()
        presentScheduled.set(false)
    }

    @OnGlThread
    internal fun draw() {
        val requests = drainPendingRequests()
        if (requests.isEmpty()) return
        ensureGlResources()
        requests.forEach { request ->
            consumeRequest(request)
            val rootSize = glStore.currentRootSize() ?: return@forEach
            val frame = glStore.frameForDraw(rootSize) ?: return@forEach
            metrics.recordTargetSize(frame.targetSize)
            drawGlFrame(frame)
        }
    }

    private fun drawGlFrame(frame: SceneGlRenderFrame) {
        val startedNanos = System.nanoTime()
        // Pacing probe: one instant per presented GL frame. Output-side cadence
        // counterpart to SceneCaptureKick. See CapturePacingMetric.
        trace("SceneGlPresent") {
            SceneRenderMetricsLog.time(
                phase = "gl.onDrawFrame.total",
                details = "slot=${frame.slot.id.value}"
            ) {
                if (Build.VERSION.SDK_INT >= 29) {
                    requireNotNull(renderPipeline).render(frame)
                }
            }
        }
        val finishedNanos = System.nanoTime()
        metrics.recordGlRender(frame.metricsFrame, startedNanos, finishedNanos)
        pendingRenderCompleteFrame = frame.metricsFrame
    }

    @OnGlThread
    private fun drainPendingRequests(): List<SceneFrameRequest> {
        presentScheduled.set(false)
        if (pendingBySlot.isEmpty()) return emptyList()
        val drained = ArrayList<SceneFrameRequest>(pendingBySlot.size)
        pendingBySlot.keys.toList().forEach { slotId ->
            pendingBySlot.remove(slotId)?.let(drained::add)
        }
        return drained
    }

    @OnGlThread
    private fun consumeRequest(request: SceneFrameRequest) {
        SceneRenderMetricsLog.time(
            phase = "gl.consumeSnapshot.total",
            details = "slot=${request.uiCapture.slot.id.value}"
        ) {
            trace("SceneGlOwner#ConsumeSnapshot") {
                if (closed.get()) {
                    metrics.recordDropped(SceneFrameDropReason.Closed)
                    request.close()
                    return@trace
                }
                ensureGlResources()
                val glFrame = request.use {
                    buildGlFrame(request.uiCapture)
                }
                if (glFrame != null) {
                    installGlFrame(glFrame)
                }
            }
        }
    }

    @OnGlThread
    internal fun onRenderComplete() {
        threadGuard.bindCurrentThread()
        metrics.recordRenderComplete(pendingRenderCompleteFrame)
        pendingRenderCompleteFrame = null
        afterRenderComplete()
    }

    private fun ensureGlResources() {
        threadGuard.bindCurrentThread()
        if (graphicsContext != null) return

        val context = createGraphicsContext()
        context.initialize()
        initializeGlRenderers(context)

        val shaderPrograms = createSceneShaderPrograms(context)
        val pipeline = if (Build.VERSION.SDK_INT >= 29) {
            createRenderPipeline(
                context = context,
                shaderPrograms = shaderPrograms
            )
        } else {
            error("GL present pipeline requires API 29")
        }

        graphicsContext = context
        renderPipeline = pipeline
    }

    private fun createGraphicsContext(): GraphicsContext {
        val commands = RenderCommands(OpenGLRendererAPI())
        return GraphicsContext(
            rendererApi = commands.rendererApi,
            shaderLibrary = shaderLibrary,
            shaderBinder = shaderBinder,
            fboPool = FramebufferLendingPool(commands),
            commands = commands
        )
    }

    private fun initializeGlRenderers(context: GraphicsContext) {
        simpleRenderer.init(context.commands)
        quadRenderer.init(context, shaderLibrary, shaderBinder)
    }

    private fun createSceneShaderPrograms(context: GraphicsContext): SceneShaderPrograms {
        return SceneShaderPrograms(
            noise = createNoiseShaderProgram(),
            backdropPrepare = createBackdropPrepareShaderProgram(context),
            backdropBlur = createBackdropBlurShaderProgram(context),
            backdropBlurNoMask = createBackdropBlurNoMaskShaderProgram(context),
            backdropComposite = createBackdropCompositeShaderProgram(context),
            stencilClip = createStencilClipShaderProgram(context)
        )
    }

    @RequiresApi(29)
    private fun createRenderPipeline(
        context: GraphicsContext,
        shaderPrograms: SceneShaderPrograms
    ): SceneRenderPipeline {
        val graphicsContext = context
        return SceneRenderPipeline(
            presenter = createPresenter(),
            noisePass = SceneNoisePass(
                commandsProvider = { graphicsContext.commands },
                quadBatchRenderer = quadBatchRenderer,
                shaderProgram = shaderPrograms.noise
            ),
            rootPass = SceneRootDrawPass(
                commandsProvider = { graphicsContext.commands }
            ),
            backdropPass = createBackdropPass(context, shaderPrograms),
            contentPass = SceneSlotContentPass(
                commandsProvider = { graphicsContext.commands },
                quadBatchRenderer = quadBatchRenderer
            ),
            stencilClipPass = SceneStencilClipPass(
                commandsProvider = { graphicsContext.commands },
                quadBatchRenderer = quadBatchRenderer,
                shaderProgram = shaderPrograms.stencilClip
            )
        )
    }

    @android.annotation.SuppressLint("NewApi")
    private fun createPresenter(): HardwareBitmapPresenter {
        check(Build.VERSION.SDK_INT >= 29) { "Hardware bitmap present requires API 29" }
        return requireNotNull(hardwareBitmapPresenter)
    }

    private fun createBackdropPass(
        context: GraphicsContext,
        shaderPrograms: SceneShaderPrograms
    ): SceneBackdropEffectPass {
        val graphicsContext = context
        return SceneBackdropEffectPass(
            preparePass = SceneBackdropPreparePass(
                commandsProvider = { graphicsContext.commands },
                framebufferPool = context.fboPool,
                quadBatchRenderer = quadBatchRenderer,
                shaderProgram = shaderPrograms.backdropPrepare
            ),
            blurPass = SceneBackdropBlurPass(
                commandsProvider = { graphicsContext.commands },
                framebufferPool = context.fboPool,
                quadBatchRenderer = quadBatchRenderer,
                maskedShaderProgram = shaderPrograms.backdropBlur,
                noMaskShaderProgram = shaderPrograms.backdropBlurNoMask
            ),
            compositePass = SceneBackdropCompositePass(
                commandsProvider = { graphicsContext.commands },
                quadBatchRenderer = quadBatchRenderer,
                shaderProgram = shaderPrograms.backdropComposite
            )
        )
    }

    private fun createStencilClipShaderProgram(context: GraphicsContext): ShaderProgram {
        val textureSlots = context.getCapabilities().maxTextureImageUnits
        return QuadShaderProgram(
            shaderBinder = shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "stencil_clip_batch",
                preprocessorDefines = mapOf(
                    "MAX_TEXTURE_SLOTS" to textureSlots.toString(),
                    "TEXTURE_SWITCH_CASES" to textureSwitchCases(
                        textureSlots = textureSlots,
                        coordinateVariable = "texCoord"
                    )
                )
            ),
            textureSlots = textureSlots
        )
    }

    private fun createBackdropPrepareShaderProgram(context: GraphicsContext): ShaderProgram {
        val textureSlots = context.getCapabilities().maxTextureImageUnits
        return QuadShaderProgram(
            shaderBinder = shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "scene_backdrop_prepare",
                preprocessorDefines = mapOf(
                    "MAX_TEXTURE_SLOTS" to textureSlots.toString(),
                    "TEXTURE_SWITCH_CASES" to textureSwitchCases(
                        textureSlots = textureSlots,
                        coordinateVariable = "uv"
                    )
                )
            ),
            textureSlots = textureSlots
        )
    }

    private fun createBackdropBlurShaderProgram(context: GraphicsContext): ShaderProgram {
        return SceneBackdropBlurShaderProgram(
            shaderBinder = shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "scene_backdrop_separable_blur_direct",
                preprocessorDefines = mapOf(
                    "MAX_BLUR_RADIUS_PX" to SCENE_BACKDROP_BLUR_MAX_RADIUS_PX.toString()
                )
            )
        )
    }

    private fun createBackdropBlurNoMaskShaderProgram(context: GraphicsContext): ShaderProgram {
        return SceneBackdropBlurShaderProgram(
            shaderBinder = shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "scene_backdrop_separable_blur_direct_nomask",
                preprocessorDefines = mapOf(
                    "MAX_KERNEL_SAMPLE_COUNT" to SCENE_BACKDROP_BLUR_MAX_KERNEL_SAMPLE_COUNT.toString()
                )
            )
        )
    }

    private fun createBackdropCompositeShaderProgram(context: GraphicsContext): ShaderProgram {
        val textureSlots = context.getCapabilities().maxTextureImageUnits
        return QuadShaderProgram(
            shaderBinder = shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "scene_backdrop_composite",
                preprocessorDefines = mapOf(
                    "MAX_TEXTURE_SLOTS" to textureSlots.toString(),
                    "TEXTURE_SWITCH_CASES" to textureSwitchCases(
                        textureSlots = textureSlots,
                        coordinateVariable = "texCoordAdjusted"
                    ),
                    "NOISE_SWITCH_CASES" to textureSwitchCases(
                        textureSlots = textureSlots,
                        coordinateVariable = "noiseUv"
                    ),
                    "CRISP_SWITCH_CASES" to textureSwitchCases(
                        textureSlots = textureSlots,
                        coordinateVariable = "uv"
                    ),
                    "MASK_SWITCH_CASES" to textureSwitchCases(
                        textureSlots = textureSlots,
                        coordinateVariable = "uv"
                    )
                )
            ),
            textureSlots = textureSlots
        )
    }

    private fun createNoiseShaderProgram(): ShaderProgram {
        return QuadShaderProgram(
            shaderBinder = shaderBinder,
            shader = shaderLibrary.loadShaderFromFile(
                vertFileName = "default_quad",
                fragFileName = "noise_flat"
            )
        )
    }

    private fun textureSwitchCases(
        textureSlots: Int,
        coordinateVariable: String
    ): String {
        return buildString {
            for (i in 0 until textureSlots) {
                appendLine("        case $i:")
                appendLine("            baseColor = texture(u_Textures[$i], $coordinateVariable);break;")
            }
        }
    }

    private data class SceneShaderPrograms(
        val noise: ShaderProgram,
        val backdropPrepare: ShaderProgram,
        val backdropBlur: ShaderProgram,
        val backdropBlurNoMask: ShaderProgram,
        val backdropComposite: ShaderProgram,
        val stencilClip: ShaderProgram
    )

    private fun importCapturedFrame(
        slotId: SceneSlotId,
        frame: CapturedLayerFrame,
        source: HardwareBufferTextureSource
    ): Texture2D? {
        return importHardwareBufferFrame(slotId, frame as CapturedHardwareBufferFrame, source)
    }

    private fun importHardwareBufferFrame(
        slotId: SceneSlotId,
        frame: CapturedHardwareBufferFrame,
        source: HardwareBufferTextureSource
    ): Texture2D? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            frame.close()
            return null
        }
        return hardwareBufferFrameImporter().importOwned(slotId, frame, source)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hardwareBufferFrameImporter(): CapturedFrameImporter {
        return capturedFrameImporter ?: CapturedFrameImporter(
            threadGuard = threadGuard,
            releaseQueue = hardwareBufferReleaseQueue,
            graphicsContextProvider = { requireNotNull(graphicsContext) },
            simpleQuadRendererProvider = { simpleQuadRenderer }
        ).also { capturedFrameImporter = it }
    }

    /**
     * Builds GL-owned frame state from UI-side captures; root texture creation gates slot textures.
     */
    private fun buildGlFrame(uiCapture: UiSceneCapture): GlFrame? {
        val root = prepareRootFrame(uiCapture) ?: return null
        val textures = buildGlTextures(uiCapture)
        return GlFrame(
            root = root,
            textures = textures,
            liveSlotIds = uiCapture.liveSlotIds.ifEmpty {
                setOf(uiCapture.slot.id)
            },
            clippedSlotIds = uiCapture.liveSlotIds.ifEmpty {
                setOf(uiCapture.slot.id)
            }
        )
    }

    /**
     * Publishes a GL frame into the GL store and trims stale slot resources.
     */
    private fun installGlFrame(frame: GlFrame) = trace("SceneGlOwner#InstallFrame") {
        trace("SceneGlOwner#InstallRoot") {
            frame.root.applyTo(glStore)
        }
        installGlContent(frame)
        installGlMasks(frame)
        installGlClips(frame)
        trace("SceneGlOwner#PruneSlots live=${frame.liveSlotIds.size}") {
            glStore.pruneSlotsTo(frame.liveSlotIds)
        }
        trace("SceneGlOwner#PruneClipSlots clipped=${frame.clippedSlotIds.size}") {
            glStore.pruneClipSlotsTo(frame.clippedSlotIds)
        }
    }

    /**
     * Installs GL slot content textures and reports slots that became GL-resident.
     */
    private fun installGlContent(frame: GlFrame) {
        trace("SceneGlOwner#InstallContent count=${frame.textures.contentTextures.size}") {
            frame.textures.contentTextures.forEach { (slotId, texture) ->
                glStore.replaceContent(slotId, texture)
            }
        }
        if (frame.textures.contentTextures.isNotEmpty()) {
            afterContentImport(frame.textures.contentTextures.keys.toSet())
        }
    }

    /**
     * Installs GL progressive-mask textures and reports keys that became GL-visible.
     */
    private fun installGlMasks(frame: GlFrame) {
        trace("SceneGlOwner#InstallMasks count=${frame.textures.masks.size}") {
            frame.textures.masks.forEach { (slotId, imported) ->
                glStore.replaceProgressiveMask(slotId, imported.texture)
            }
        }
        if (frame.textures.masks.isNotEmpty()) {
            afterProgressiveMaskImport(frame.textures.masks.mapValues { it.value.key })
        }
    }

    /**
     * Installs GL clip textures and reports keys that became GL-visible.
     */
    private fun installGlClips(frame: GlFrame) {
        trace("SceneGlOwner#InstallClips count=${frame.textures.clips.size}") {
            frame.textures.clips.forEach { (slotId, imported) ->
                glStore.replaceClip(slotId, imported.texture)
            }
        }
        if (frame.textures.clips.isNotEmpty()) {
            afterClipImport(frame.textures.clips.mapValues { it.value.key })
        }
    }

    private fun prepareRootFrame(uiCapture: UiSceneCapture): PreparedSceneRoot? {
        return SceneRenderMetricsLog.time(
            phase = "gl.prepareRootFrame",
            details = "${uiCapture.rootFrame.logicalSize.width}x${uiCapture.rootFrame.logicalSize.height}"
        ) {
            val startedNanos = System.nanoTime()
            val importedRoot = importHardwareBufferRootFrame(uiCapture.rootFrame as CapturedHardwareBufferFrame)
            metrics.recordRootImport(uiCapture.metricsFrame, startedNanos, System.nanoTime())
            importedRoot?.toPreparedSceneRoot(uiCapture)
        }
    }

    private fun importHardwareBufferRootFrame(frame: CapturedHardwareBufferFrame): OwnedGlTexture? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            frame.close()
            return null
        }
        return hardwareBufferFrameImporter().importRoot(frame)?.let { texture ->
            OwnedGlTexture(
                texture = texture,
                releaseTexture = { }
            )
        }
    }

    private fun buildGlTextures(uiCapture: UiSceneCapture): GlTextures {
        return SceneRenderMetricsLog.time(
            phase = "gl.importResources.total",
            details = "content=${uiCapture.uiCaptures.contentFrames.size} mask=${uiCapture.uiCaptures.maskFrames.size} clip=${uiCapture.uiCaptures.clipFrames.size}"
        ) {
            GlTextures(
                contentTextures = buildGlContentTextures(uiCapture),
                masks = buildGlMaskTextures(uiCapture),
                clips = buildGlClipTextures(uiCapture)
            )
        }
    }

    private fun buildGlContentTextures(
        uiCapture: UiSceneCapture
    ): Map<SceneSlotId, SceneSampledTexture> {
        val startedNanos = System.nanoTime()
        return uiCapture.uiCaptures.contentFrames.mapNotNull { (slotId, frame) ->
            val texture = importCapturedFrame(slotId, frame, HardwareBufferTextureSource.Content)
                ?: return@mapNotNull null
            slotId to SceneSampledTexture(
                texture = texture,
                contentSize = frame.contentSize
            )
        }.toMap().also {
            metrics.recordSlotImport(uiCapture.metricsFrame, startedNanos, System.nanoTime())
        }
    }

    private fun buildGlMaskTextures(
        uiCapture: UiSceneCapture
    ): Map<SceneSlotId, ImportedMask> {
        val startedNanos = System.nanoTime()
        return uiCapture.uiCaptures.maskFrames.mapNotNull { (slotId, frame) ->
            val texture = importCapturedFrame(slotId, frame, HardwareBufferTextureSource.Mask)
                ?: return@mapNotNull null
            val key = uiCapture.uiCaptures.maskKeys.getValue(slotId)
            slotId to ImportedMask(texture = texture, key = key)
        }.toMap().also {
            metrics.recordMaskImport(uiCapture.metricsFrame, startedNanos, System.nanoTime())
        }
    }

    private fun buildGlClipTextures(
        uiCapture: UiSceneCapture
    ): Map<SceneSlotId, ImportedClip> {
        val startedNanos = System.nanoTime()
        return uiCapture.uiCaptures.clipFrames.map { (slotId, frame) ->
            val texture =
                requireNotNull(importCapturedFrame(slotId, frame, HardwareBufferTextureSource.Clip)) {
                    "Scene failed to import required clip texture for slot ${slotId.value}"
                }
            val key = uiCapture.uiCaptures.clipKeys.getValue(slotId)
            slotId to ImportedClip(
                texture = SceneSampledTexture(texture = texture, contentSize = frame.contentSize),
                key = key
            )
        }.toMap().also {
            metrics.recordClipImport(uiCapture.metricsFrame, startedNanos, System.nanoTime())
        }
    }
}

private data class GlFrame(
    val root: PreparedSceneRoot,
    val textures: GlTextures,
    val liveSlotIds: Set<SceneSlotId>,
    val clippedSlotIds: Set<SceneSlotId>
)

private data class GlTextures(
    val contentTextures: Map<SceneSlotId, SceneSampledTexture>,
    val masks: Map<SceneSlotId, ImportedMask>,
    val clips: Map<SceneSlotId, ImportedClip>
)

private data class ImportedMask(
    val texture: Texture2D,
    val key: SceneProgressiveMaskKey
)

private data class ImportedClip(
    val texture: SceneSampledTexture,
    val key: SceneClipShapeKey
)

private data class OwnedGlTexture(
    val texture: Texture2D,
    val releaseTexture: (Texture2D) -> Unit
)

private fun OwnedGlTexture.toPreparedSceneRoot(
    uiCapture: UiSceneCapture
): PreparedSceneRoot {
    return PreparedSceneRoot(
        texture = texture,
        rootSize = uiCapture.rootFrame.logicalSize,
        rootCaptureDurationNanos = uiCapture.rootCaptureDurationNanos,
        frameBudgetNanos = uiCapture.frameBudgetNanos,
        metricsFrame = uiCapture.metricsFrame,
        slot = uiCapture.slot,
        releaseTexture = releaseTexture
    )
}

private data class PreparedSceneRoot(
    val texture: Texture2D,
    val rootSize: IntSize,
    val rootCaptureDurationNanos: Long,
    val frameBudgetNanos: Long,
    val metricsFrame: SceneMetricsFrame?,
    val slot: SceneSlotDeclaration,
    val releaseTexture: (Texture2D) -> Unit
) {
    fun applyTo(
        glStore: GlStore
    ) {
        glStore.replaceRoot(
            texture = texture,
            rootSize = rootSize,
            rootCaptureDurationNanos = rootCaptureDurationNanos,
            frameBudgetNanos = frameBudgetNanos,
            metricsFrame = metricsFrame,
            slot = slot,
            releaseTexture = releaseTexture
        )
    }
}
