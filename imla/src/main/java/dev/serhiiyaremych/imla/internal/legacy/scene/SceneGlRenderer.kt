/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.GraphicsContext
import dev.serhiiyaremych.imla.internal.render.Renderer2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.shader.ShaderBinder
import dev.serhiiyaremych.imla.internal.render.shader.ShaderLibrary
import dev.serhiiyaremych.imla.internal.legacy.StencilClipRenderer
import dev.serhiiyaremych.imla.internal.render.processing.effects.EffectPipeline
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.SimpleQuadRenderer
import dev.serhiiyaremych.imla.internal.render.processing.SinglePassQuadExecutor

internal class SceneGlRenderer(
    private val resourceStore: SceneResourceStore,
    private val simpleRenderer: SimpleQuadRenderer,
    private val renderer2D: Renderer2D,
    shaderLibrary: ShaderLibrary,
    private val shaderBinder: ShaderBinder,
    private val graphicsContextProvider: () -> GraphicsContext,
    private val coordinator: ImlaSceneCoordinator,
    private val atlasRenderConfig: SceneBlurAtlasRenderConfig =
        SceneBlurAtlasDiagnosticMode.renderConfig()
) {
    private val commandsProvider = { graphicsContextProvider().commands }
    private val singlePassQuadExecutor = SinglePassQuadExecutor(simpleRenderer)
    private val rootSeedPass = SceneRootSeedPass(commandsProvider, singlePassQuadExecutor)
    private val presentPass = ScenePresentPass(commandsProvider, singlePassQuadExecutor)
    private val quadBatchRenderer = QuadBatchRenderer(
        renderer2D = renderer2D,
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder,
        commandsProvider = commandsProvider
    )
    private val effectPipelineDelegate = lazy(LazyThreadSafetyMode.NONE) {
        val graphicsContext = graphicsContextProvider()
        EffectPipeline(
            pool = graphicsContext.fboPool,
            commands = graphicsContext.commands,
            shaderLibrary = shaderLibrary,
            shaderBinder = shaderBinder,
            singlePassQuadExecutor = singlePassQuadExecutor
        )
    }
    private val effectPipeline: EffectPipeline by effectPipelineDelegate
    private val backdropPreprocessPass = SceneBackdropPreprocessPass { effectPipeline }
    private val blurPass = SceneBlurPass { effectPipeline }
    private val backdropCompositePass = SceneBackdropCompositePass(
        quadBatchRenderer = quadBatchRenderer,
        renderer2D = renderer2D,
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder
    )
    private val atlasBackdropCompositePass = SceneBlurAtlasBackdropCompositePass(
        quadBatchRenderer = quadBatchRenderer,
        renderer2D = renderer2D,
        shaderLibrary = shaderLibrary,
        shaderBinder = shaderBinder
    )
    private val contentCompositePass = SceneContentCompositePass(quadBatchRenderer)
    private val stencilClipPass = SceneStencilClipPass(commandsProvider) {
        StencilClipRenderer(graphicsContextProvider(), shaderLibrary, shaderBinder)
    }
    private val noisePass = SceneNoisePass(commandsProvider, singlePassQuadExecutor, shaderLibrary)

    /**
     * Prepared atlas ownership for the future enabled branch. The delegate stays cold while
     * [SceneBlurAtlasRenderConfig.enabled] is false, so default rendering does not construct or run
     * atlas copy/blur work.
     */
    private val atlasPipelineRunnerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        val graphicsContext = graphicsContextProvider()
        SceneBlurAtlasPipelineRunner(
            copyPass = SceneBlurAtlasCopyPass(
                commands = graphicsContext.commands,
                fboPool = graphicsContext.fboPool
            ),
            blurPass = SceneBlurAtlasBlurPass(
                blurOutputPool = graphicsContext.fboPool,
                effectPipeline = { effectPipeline }
            )
        )
    }
    private val slotPassRunner = SceneSlotPassRunner(
        commandsProvider = commandsProvider,
        perSlotBackdropRenderer = ScenePerSlotBackdropRenderer(
            commandsProvider = commandsProvider,
            backdropPreprocessPass = backdropPreprocessPass,
            blurPass = blurPass,
            backdropCompositePass = backdropCompositePass,
            effectFrameReleaser = SceneEffectFrameReleaser { graphicsContextProvider().fboPool }
        ),
        contentCompositePass = contentCompositePass,
        stencilClipPass = stencilClipPass,
        atlasRenderConfig = atlasRenderConfig,
        atlasPipelineProvider = { atlasPipelineRunnerDelegate.value },
        atlasBackdropCompositePass = atlasBackdropCompositePass
    )

    private var sceneSpec: FramebufferSpecification? = null
    private var sceneFbo: Framebuffer? = null

    fun render(targetSize: IntSize) = trace("SceneGlRenderer#render") {
        if (targetSize == IntSize.Zero) return@trace

        resourceStore.releasePendingFrameResources()

        val rootTexture = resourceStore.consumePendingRootTexture()
        if (rootTexture == null) {
            SceneTraceCounters.rootTextureMissingRender()
            commandsProvider().clear(Color.Black)
            return@trace
        }

        val sceneFrame = coordinator.consumeFrameForRender()
        if (sceneFrame == null) {
            SceneTraceCounters.sceneFrameMissingRender()
            presentPass.execute(rootTexture, targetSize, rootTexture.flipTexture)
            return@trace
        }

        val renderFrame = SceneFramePlanner.plan(
            frame = sceneFrame,
            resources = resourceStore.resolveResources(sceneFrame, rootTexture)
        )

        SceneTraceCounters.renderStarted(renderFrame.committedSlotCount)
        val scene = ensureScene(targetSize) ?: return@trace
        rootSeedPass.execute(renderFrame.rootTexture, scene)
        val noiseTexture = noisePass.prepare(renderFrame, targetSize)
        slotPassRunner.execute(
            scene = scene,
            frame = renderFrame,
            targetSize = targetSize,
            noiseTexture = noiseTexture,
            useAtlasCopyImage = atlasRenderConfig.enabled && graphicsContextProvider().supportsFastTextureOps()
        )
        presentPass.execute(scene.colorAttachmentTexture, targetSize, flipY = false)
    }

    fun destroy() {
        clearCache()
        if (effectPipelineDelegate.isInitialized()) {
            effectPipeline.destroy()
        }
    }

    fun clearCache() {
        sceneFbo?.destroy()
        noisePass.destroy()
        sceneFbo = null
        sceneSpec = null
        if (effectPipelineDelegate.isInitialized()) {
            effectPipeline.resetEffects()
        }
        graphicsContextProvider().shaderLibrary.resetUniformCaches()
    }

    private fun ensureScene(size: IntSize): Framebuffer? = trace("SceneGlRenderer#ensureScene") {
        val cachedSpec = sceneSpec
        if (cachedSpec != null && cachedSpec.size == size && sceneFbo != null) return@trace sceneFbo

        sceneFbo?.destroy()
        sceneSpec = FramebufferSpecification(
            size = size,
            attachmentsSpec = FramebufferAttachmentSpecification.withStencil(
                colorFormat = FramebufferTextureFormat.RGBA8,
                coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
            )
        )
        sceneFbo = Framebuffer.create(requireNotNull(sceneSpec), commandsProvider())
        sceneFbo
    }

}
