package dev.serhiiyaremych.imla.internal.render.gl

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.graphics.opengl.GLRenderer
import androidx.graphics.opengl.egl.EGLManager
import androidx.graphics.surface.SurfaceControlCompat
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.capture.CanvasTextureCapture
import dev.serhiiyaremych.imla.internal.capture.CapturedLayerFrame
import dev.serhiiyaremych.imla.internal.capture.CapturedHardwareBufferFrame
import dev.serhiiyaremych.imla.internal.capture.CapturedTextureFrame
import dev.serhiiyaremych.imla.internal.render.GraphicsContext
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Renderer2D
import dev.serhiiyaremych.imla.internal.render.SimpleRenderer
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.objects.QuadShaderProgram
import dev.serhiiyaremych.imla.internal.render.opengl.OpenGLRendererAPI
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.render.shader.ShaderProgram
import dev.serhiiyaremych.imla.internal.render.stats.HardwareBufferTextureSource
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.BlitPresenter
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.HardwareBufferPresenter
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.SceneBackdropEffectPass
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.SceneFinalPresentPass
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.ScenePresenter
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class SceneGlOwner(
    assetManager: AssetManager,
    private val metrics: SceneMetrics,
    private val afterRenderComplete: () -> Unit = {},
    private val afterContentImport: (Set<SceneSlotId>) -> Unit = {},
    private val afterProgressiveMaskImport: (Map<SceneSlotId, SceneProgressiveMaskKey>) -> Unit = {},
    private val afterClipImport: (Map<SceneSlotId, SceneClipShapeKey>) -> Unit = {}
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
    private val glRenderer = GLRenderer().apply { start("ImlaScene2GL") }
    private val closed = AtomicBoolean(false)
    private val pendingRequest = AtomicReference<SceneFrameRequest?>(null)
    private val presentScheduled = AtomicBoolean(false)
    // Snapshotted once so the surface-attach and pipeline-build decisions stay consistent.
    private val hwPresentEnabled = SceneRenderFlags.hwPresentEnabled()

    private var graphicsContext: GraphicsContext? = null
    private var pendingRenderCompleteFrame: SceneMetricsFrame? = null
    private var renderPipeline: SceneRenderPipeline? = null
    // Created at attach on the main thread when the HW present path is selected; borrowed by
    // HardwareBufferPresenter and released here at detach. Null = blit path.
    @RequiresApi(29)
    private var sceneSurfaceControl: SurfaceControlCompat? = null

    @MainThread
    fun attachRenderTarget(surface: Surface, size: IntSize): SceneRenderTarget {
        check(!closed.get()) { "SceneGlOwner is closed" }
        check(size != IntSize.Zero) { "Scene render target size must be non-zero" }
        val callback = SceneRenderCallback()
        val renderTarget = glRenderer.attach(
            surface = surface,
            width = size.width,
            height = size.height,
            renderer = callback
        )
        return SceneRenderTarget(
            glOwner = this,
            glRenderer = glRenderer,
            renderTarget = renderTarget,
            initialSize = size
        ).also {
            callback.target = it
        }
    }

    @MainThread
    @RequiresApi(29)
    fun attachRenderTargetWithSurfaceView(surfaceView: SurfaceView, size: IntSize): SceneRenderTarget {
        check(!closed.get()) { "SceneGlOwner is closed" }
        check(size != IntSize.Zero) { "Scene render target size must be non-zero" }
        Log.i("ImlaScenePresent", "present path: ${if (hwPresentEnabled) "HW" else "BLIT"}")
        if (!hwPresentEnabled) {
            // Blit on the SurfaceView's window surface (no zero-copy present).
            return attachRenderTarget(surfaceView.holder.surface, size)
        }
        // HW present: render offscreen into the HardwareBuffer ring and hand each buffer to
        // SurfaceFlinger via this SurfaceControl, so the GL target has no window surface.
        sceneSurfaceControl = SurfaceControlCompat.Builder()
            .setParent(surfaceView)
            .setName("ImlaScene")
            .build()
        val callback = SceneRenderCallback()
        val glTarget = glRenderer.createRenderTarget(size.width, size.height, callback)
        return SceneRenderTarget(
            glOwner = this,
            glRenderer = glRenderer,
            renderTarget = glTarget,
            initialSize = size
        ).also { callback.target = it }
    }

    @MainThread
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingRequest.getAndSet(null)?.close()
        presentScheduled.set(false)
        glRenderer.execute {
            threadGuard.bindCurrentThread()
            renderPipeline?.release()
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
            if (Build.VERSION.SDK_INT >= 29) {
                sceneSurfaceControl?.release()
                sceneSurfaceControl = null
            }
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
        val displaced = pendingRequest.getAndSet(request)
        displaced?.close()
        if (presentScheduled.compareAndSet(false, true) &&
            !target.requestRender(::onRenderComplete)
        ) {
            presentScheduled.set(false)
        }
    }

    @MainThread
    internal fun onTargetDetached() {
        pendingRequest.getAndSet(null)?.close()
        presentScheduled.set(false)
    }

    internal fun createCanvasTextureCapture(label: String): CanvasTextureCapture {
        return SceneSurfaceTextureCapture(
            label = label,
            shaderLibrary = shaderLibrary,
            shaderBinder = shaderBinder,
            simpleQuadRenderer = simpleQuadRenderer,
            commandsProvider = { requireNotNull(graphicsContext).commands },
            executeOnGlThread = ::executeWithGlResources,
            isOnGlThread = threadGuard::isCurrentThread
        )
    }

    @AnyThread
    private fun executeWithGlResources(block: () -> Unit) {
        if (threadGuard.isCurrentThread()) {
            if (!closed.get()) {
                ensureGlResources()
                block()
            }
        } else {
            glRenderer.execute {
                if (!closed.get()) {
                    ensureGlResources()
                    block()
                }
            }
        }
    }

    @OnGlThread
    internal fun draw(targetSize: IntSize) {
        consumePendingRequest()
        ensureGlResources()
        val frame = glStore.frameForDraw(targetSize) ?: return
        metrics.recordTargetSize(targetSize)
        drawGlFrame(frame)
    }

    private fun drawGlFrame(frame: SceneGlRenderFrame) {
        val startedNanos = System.nanoTime()
        // Pacing probe: one instant per presented GL frame. Output-side cadence
        // counterpart to SceneCaptureKick. See CapturePacingMetric.
        trace("SceneGlPresent") {
            SceneRenderMetricsLog.time(
                phase = "gl.onDrawFrame.total",
                details = "slots=${frame.slots.size}"
            ) {
                requireNotNull(renderPipeline).render(frame)
            }
        }
        drawDropDebugMarkerIfNeeded(frame.targetSize)
        val finishedNanos = System.nanoTime()
        metrics.recordGlRender(frame.metricsFrame, startedNanos, finishedNanos)
        pendingRenderCompleteFrame = frame.metricsFrame
    }

    private fun drawDropDebugMarkerIfNeeded(targetSize: IntSize) {
        if (!metrics.consumeDropDebugMarker()) return
        val markerSize = DROP_DEBUG_MARKER_SIZE_PX.coerceAtMost(
            minOf(targetSize.width, targetSize.height)
        )
        if (markerSize <= 0) return
        val commands = requireNotNull(graphicsContext).commands
        SceneRenderMetricsLog.time("gl.dropDebugMarker") {
            commands.bindDefaultFramebuffer(Bind.DRAW)
            commands.enableScissorTest()
            commands.setScissor(
                x = 0,
                y = targetSize.height - markerSize,
                width = markerSize,
                height = markerSize
            )
            commands.setClearColor(Color.Magenta)
            commands.clear(commands.colorBufferBit)
            commands.disableScissorTest()
        }
    }

    @OnGlThread
    private fun consumePendingRequest() {
        presentScheduled.set(false)
        val request = pendingRequest.getAndSet(null) ?: return
        SceneRenderMetricsLog.time(
            phase = "gl.consumeSnapshot.total",
            details = "slots=${request.uiCapture.slots.size}"
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
        val pipeline = createRenderPipeline(
            context = context,
            shaderPrograms = shaderPrograms
        )

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

    private fun createRenderPipeline(
        context: GraphicsContext,
        shaderPrograms: SceneShaderPrograms
    ): SceneRenderPipeline {
        val graphicsContext = context
        return SceneRenderPipeline(
            presenter = createPresenter(context),
            noisePass = SceneNoisePass(
                commandsProvider = { graphicsContext.commands },
                quadBatchRenderer = quadBatchRenderer,
                shaderProgram = shaderPrograms.noise
            ),
            rootPass = SceneRootDrawPass(
                commandsProvider = { graphicsContext.commands },
                quadBatchRenderer = quadBatchRenderer
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

    // Single source of truth for the present backend: a SurfaceControl is created at attach
    // only when the HW path is selected (hwPresentEnabled && API 29+), so its presence picks
    // the presenter. The SurfaceControl is borrowed; SceneGlOwner.close releases it.
    // @SuppressLint: the SDK_INT >= 29 guard and the @RequiresApi(29) HardwareBufferPresenter
    // call are separated by the nullable surfaceControl local, which lint cannot correlate.
    @SuppressLint("NewApi")
    private fun createPresenter(context: GraphicsContext): ScenePresenter {
        val surfaceControl = if (Build.VERSION.SDK_INT >= 29) sceneSurfaceControl else null
        return if (surfaceControl != null) {
            HardwareBufferPresenter(surfaceControl)
        } else {
            BlitPresenter(
                framebufferPool = context.fboPool,
                supportsStencil = context::supportsHardwareStencilClipping,
                presentPass = SceneFinalPresentPass(
                    commandsProvider = { context.commands },
                    quadBatchRenderer = quadBatchRenderer
                )
            )
        }
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
        return when (frame) {
            is CapturedHardwareBufferFrame -> importHardwareBufferFrame(slotId, frame, source)
            is CapturedTextureFrame -> frame.takeTexture().also {
                frame.close()
            }
        }
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
            liveSlotIds = uiCapture.slots.mapTo(mutableSetOf()) { it.id },
            clippedSlotIds = uiCapture.slots
                .filter { it.requiresClip }
                .mapTo(mutableSetOf()) { it.id }
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
            val importedRoot = when (val frame = uiCapture.rootFrame) {
                is CapturedHardwareBufferFrame -> importHardwareBufferRootFrame(frame)
                is CapturedTextureFrame -> {
                    val texture = frame.takeTexture()
                    frame.close()
                    texture?.let {
                        OwnedGlTexture(
                            texture = it,
                            releaseTexture = Texture2D::destroy
                        )
                    }
                }
            }
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

    // One callback for both present backends: draw() runs the pipeline, which acquires the
    // scene buffer, renders, and presents (blit pass or HW hand-off) via the ScenePresenter.
    private class SceneRenderCallback : GLRenderer.RenderCallback {
        @Volatile
        var target: SceneRenderTarget? = null

        @OnGlThread
        override fun onDrawFrame(eglManager: EGLManager) {
            target?.draw()
        }
    }
}

private const val DROP_DEBUG_MARKER_SIZE_PX = 32

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
        slots = uiCapture.slots,
        releaseTexture = releaseTexture
    )
}

private data class PreparedSceneRoot(
    val texture: Texture2D,
    val rootSize: IntSize,
    val rootCaptureDurationNanos: Long,
    val frameBudgetNanos: Long,
    val metricsFrame: SceneMetricsFrame?,
    val slots: List<SceneSlotDeclaration>,
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
            slots = slots,
            releaseTexture = releaseTexture
        )
    }
}
