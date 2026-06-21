/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import dev.serhiiyaremych.imla.internal.render.processing.composite.BackdropCompositeShaderEffect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class ScenePassTest {
    @Test
    fun sceneGlRenderer_usesExplicitScenePasses() {
        val rendererFields = SceneGlRenderer::class.java.declaredFields
            .filterNot { it.isSynthetic }

        assertTrue(
            "SceneGlRenderer must orchestrate SceneRootSeedPass",
            rendererFields.any { field -> field.type == SceneRootSeedPass::class.java }
        )
        assertTrue(
            "SceneGlRenderer must orchestrate ScenePresentPass",
            rendererFields.any { field -> field.type == ScenePresentPass::class.java }
        )
        assertTrue(
            "SceneGlRenderer must orchestrate SceneBackdropPreprocessPass",
            rendererFields.any { field -> field.type == SceneBackdropPreprocessPass::class.java }
        )
        assertTrue(
            "SceneGlRenderer must orchestrate SceneBlurPass",
            rendererFields.any { field -> field.type == SceneBlurPass::class.java }
        )
        assertTrue(
            "SceneGlRenderer must orchestrate SceneBackdropCompositePass",
            rendererFields.any { field -> field.type == SceneBackdropCompositePass::class.java }
        )
        assertTrue(
            "SceneGlRenderer must orchestrate SceneContentCompositePass",
            rendererFields.any { field -> field.type == SceneContentCompositePass::class.java }
        )
        assertTrue(
            "SceneGlRenderer must orchestrate SceneStencilClipPass",
            rendererFields.any { field -> field.type == SceneStencilClipPass::class.java }
        )
        assertTrue(
            "SceneGlRenderer must orchestrate SceneNoisePass",
            rendererFields.any { field -> field.type == SceneNoisePass::class.java }
        )
        assertTrue(
            "SceneGlRenderer must orchestrate SceneSlotPassRunner",
            rendererFields.any { field -> field.type == SceneSlotPassRunner::class.java }
        )
    }

    @Test
    fun sceneGlRenderer_doesNotOwnExtractedPassHelpers() {
        val rendererMethods = SceneGlRenderer::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { method -> method.name }

        assertFalse(
            "SceneGlRenderer must delegate root seeding to SceneRootSeedPass",
            rendererMethods.any { methodName -> methodName.contains("seedScene") }
        )
        assertFalse(
            "SceneGlRenderer must delegate final presentation to ScenePresentPass",
            rendererMethods.any { methodName -> methodName.contains("presentTexture") }
        )
        assertFalse(
            "SceneGlRenderer must delegate backdrop composite execution to SceneBackdropCompositePass",
            rendererMethods.any { methodName -> methodName.contains("compositeEffect") }
        )
        assertFalse(
            "SceneGlRenderer must delegate content composite execution to SceneContentCompositePass",
            rendererMethods.any { methodName -> methodName.contains("compositeSlotContent") }
        )
        assertFalse(
            "SceneGlRenderer must delegate stencil setup to SceneStencilClipPass",
            rendererMethods.any { methodName -> methodName.contains("setupStencilClipping") }
        )
        assertFalse(
            "SceneGlRenderer must delegate ordered slot processing to SceneSlotPassRunner",
            rendererMethods.any { methodName -> methodName.contains("compositeSlots") }
        )
        assertFalse(
            "SceneGlRenderer must delegate per-slot backdrop composition to SceneSlotPassRunner",
            rendererMethods.any { methodName -> methodName.contains("compositeBlurredBackdrop") }
        )
        assertFalse(
            "SceneGlRenderer must delegate transient effect-frame release to the slot runner helper",
            rendererMethods.any { methodName -> methodName.contains("releaseEffectFrames") }
        )
        listOf(
            "prepareNoiseTexture",
            "ensureNoiseFbo",
            "drawNoiseIfNeeded"
        ).forEach { methodName ->
            assertFalse(
                "SceneGlRenderer must delegate noise texture preparation to SceneNoisePass",
                rendererMethods.any { rendererMethod -> rendererMethod.contains(methodName) }
            )
        }
    }

    @Test
    fun sceneGlRenderer_delegatesSlotPassSequencingToRunner() {
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt"
        )

        assertFalse(
            "SceneGlRenderer must delegate preprocess execution to SceneBackdropPreprocessPass",
            rendererSource.contains("effectPipeline.preProcess(")
        )
        assertFalse(
            "SceneGlRenderer must delegate blur execution to SceneBlurPass",
            rendererSource.contains("effectPipeline.gaussianBlur(")
        )
        assertTrue(
            "SceneGlRenderer must call SceneSlotPassRunner for ordered slot work",
            rendererSource.contains("slotPassRunner.execute(") &&
                rendererSource.contains("scene = scene") &&
                rendererSource.contains("frame = renderFrame") &&
                rendererSource.contains("noiseTexture = noiseTexture")
        )
        assertTrue(
            "SceneGlRenderer must call SceneNoisePass for frame-level noise preparation",
            rendererSource.contains("val noiseTexture = noisePass.prepare(renderFrame, targetSize)")
        )
        assertFalse(
            "SceneGlRenderer must not composite slot backdrops directly",
            rendererSource.contains("backdropCompositePass.execute(")
        )
        assertFalse(
            "SceneGlRenderer must not composite slot content directly",
            rendererSource.contains("contentCompositePass.execute(")
        )
    }

    @Test
    fun sceneGlRenderer_doesNotOwnNoisePreparationState() {
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt"
        )

        listOf(
            "prepareNoiseTexture",
            "ensureNoiseFbo",
            "drawNoiseIfNeeded",
            "noiseFbo",
            "noiseSize",
            "noiseFlip",
            "noiseReady",
            "noiseGenShader"
        ).forEach { forbiddenName ->
            assertFalse(
                "SceneGlRenderer must not own $forbiddenName",
                rendererSource.contains(forbiddenName)
            )
        }
    }

    @Test
    fun sceneNoisePass_preservesNoiseTexturePreparationContract() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneNoisePass.kt"
        )

        assertTrue(
            "SceneNoisePass must no-op when no slot requires noise",
            source.contains("if (!frame.requiresNoiseTexture) return null")
        )
        assertTrue(
            "SceneNoisePass must preserve the R8 noise framebuffer format",
            source.contains("format = FramebufferTextureFormat.R8")
        )
        assertTrue(
            "SceneNoisePass must preserve root-texture flip driven coordinate origin",
            source.contains("if (desiredFlip) CoordinateOrigin.TOP_LEFT else CoordinateOrigin.BOTTOM_LEFT")
        )
        assertTrue(
            "SceneNoisePass must preserve the flat noise shader source",
            source.contains("fragFileName = \"noise_flat\"")
        )
        assertTrue(
            "SceneNoisePass must preserve texture uniform block binding",
            source.contains("bindUniformBlock(SimpleRenderer.TEXTURE_DATA_UBO_BLOCK, SimpleRenderer.TEXTURE_DATA_UBO_BINDING_POINT)")
        )
        assertTrue(
            "SceneNoisePass must draw noise only after ensuring the framebuffer",
            Regex("""ensureNoiseFbo\(targetSize, frame\.rootTexture\.flipTexture\)\s*drawNoiseIfNeeded\(\)""")
                .containsMatchIn(source)
        )
        assertTrue(
            "SceneNoisePass must use the single-pass quad executor for its first migration slice",
            source.contains("singlePassQuadExecutor.draw(shader = noiseGenShader)")
        )
        assertFalse(
            "SceneNoisePass must not depend directly on SimpleQuadRenderer after the first migration slice",
            source.contains("SimpleQuadRenderer")
        )
        assertTrue(
            "SceneNoisePass must cache the generated noise texture until size or origin changes",
            Regex("""if \(noiseSize == size && noiseFlip == desiredFlip && noiseFbo != null\) return@trace""")
                .containsMatchIn(source)
        )
        assertTrue(
            "SceneNoisePass must destroy its owned framebuffer",
            source.contains("noiseFbo?.destroy()")
        )
    }

    @Test
    fun sceneRootSeedAndPresentPassesUseSinglePassExecutor() {
        val rootSeedSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneRootSeedPass.kt"
        )
        val presentSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/ScenePresentPass.kt"
        )
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt"
        )

        assertTrue(
            "SceneRootSeedPass must receive the named single-pass executor",
            rootSeedSource.contains("singlePassQuadExecutor: SinglePassQuadExecutor")
        )
        assertTrue(
            "ScenePresentPass must receive the named single-pass executor",
            presentSource.contains("singlePassQuadExecutor: SinglePassQuadExecutor")
        )
        assertTrue(
            "SceneGlRenderer must share its executor with root seed and present passes",
            rendererSource.contains("SceneRootSeedPass(commandsProvider, singlePassQuadExecutor)") &&
                rendererSource.contains("ScenePresentPass(commandsProvider, singlePassQuadExecutor)")
        )
        assertTrue(
            "SceneRootSeedPass must preserve root texture flip handling",
            rootSeedSource.contains("flipY = rootTexture.flipTexture")
        )
        assertTrue(
            "SceneRootSeedPass must preserve blending-disabled seed behavior",
            rootSeedSource.contains("commands.disableBlending()")
        )
        assertTrue(
            "SceneRootSeedPass must preserve scene FBO draw binding",
            rootSeedSource.contains("scene.bindForOverwrite(commands, Bind.DRAW)")
        )
        assertTrue(
            "ScenePresentPass must preserve default framebuffer binding and viewport ownership",
            presentSource.contains("commands.bindDefaultFramebuffer()") &&
                presentSource.contains("commands.setViewPort(width = targetSize.width, height = targetSize.height)")
        )
        assertTrue(
            "ScenePresentPass must preserve caller-owned flip handling",
            presentSource.contains("flipY = flipY")
        )
        assertTrue(
            "SceneGlRenderer must preserve root-only fallback present flip",
            rendererSource.contains("presentPass.execute(rootTexture, targetSize, rootTexture.flipTexture)")
        )
        assertTrue(
            "SceneGlRenderer must preserve composed scene present without Y flip",
            rendererSource.contains("presentPass.execute(scene.colorAttachmentTexture, targetSize, flipY = false)")
        )
        assertTrue(
            "Root seed and present must preserve full texture UVs through the executor default",
            !rootSeedSource.contains("textureCoordinatesFlat =") &&
                !presentSource.contains("textureCoordinatesFlat =")
        )
        listOf(rootSeedSource, presentSource).forEach { source ->
            assertFalse(
                "Root seed and present passes must not depend directly on SimpleQuadRenderer after migration",
                source.contains("SimpleQuadRenderer") ||
                    source.contains("private val simpleRenderer") ||
                    source.contains("simpleRenderer.draw(")
            )
        }
    }

    @Test
    fun sceneNoisePass_doesNotReferenceSceneStateOwners() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneNoisePass.kt"
        )

        listOf(
            "RenderObject",
            "Repository",
            "repository",
            "GraphicsLayer",
            "GLRenderer",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "SceneResourceStore",
            "renderer/session lifecycle"
        ).forEach { forbiddenName ->
            assertFalse(
                "SceneNoisePass must not reference $forbiddenName",
                source.contains(forbiddenName)
            )
        }
    }

    @Test
    fun sceneNoisePass_doesNotIntroduceRotationOrManualUvFlipSemantics() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneNoisePass.kt"
        )

        assertFalse(
            "SceneNoisePass must not introduce rotation-specific UV state",
            source.contains("uvRotation")
        )
        assertFalse(
            "SceneNoisePass must not use legacy render-quad rotation",
            source.contains("rotationZ")
        )
        assertFalse(
            "SceneNoisePass must not introduce counter-rotation",
            source.contains("counterRotation") || source.contains("counter-rotation")
        )
        assertFalse(
            "SceneNoisePass must not introduce manual UV origin handling",
            source.contains("needsFlipForOpenGL")
        )
        assertFalse(
            "SceneNoisePass must not manually invert UV coordinates",
            source.contains("1.0 -")
        )
    }

    @Test
    fun singlePassQuadExecutor_documentsAndDelegatesSingleDrawContract() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/SinglePassQuadExecutor.kt"
        )

        listOf(
            "destination FBO binding",
            "viewport/scissor setup",
            "ShaderBinder",
            "default texture shader",
            "origin drives the inherited flip value",
            "enable blending",
            "change stencil state",
            "batch multiple draws"
        ).forEach { contractText ->
            assertTrue(
                "SinglePassQuadExecutor must document $contractText",
                source.contains(contractText)
            )
        }
        assertTrue(
            "SinglePassQuadExecutor must delegate shader, texture, origin/flip, alpha, and tint inputs unchanged",
            Regex(
                """simpleQuadRenderer\.draw\(\s*shader = shader,\s*texture = texture,\s*textureCoordinatesFlat = textureCoordinatesFlat,\s*alpha = alpha,\s*flipY = flipY,\s*tint = tint\s*\)"""
            ).containsMatchIn(source)
        )
        assertTrue(
            "SinglePassQuadExecutor must delegate default-shader texture draws unchanged",
            Regex(
                """simpleQuadRenderer\.draw\(\s*texture = texture,\s*textureCoordinatesFlat = textureCoordinatesFlat,\s*alpha = alpha,\s*flipY = flipY,\s*tint = tint\s*\)"""
            ).containsMatchIn(source)
        )
        listOf("bindForOverwrite", "viewport(", ".viewport", "enableBlend", "enableStencil").forEach { stateChange ->
            assertFalse(
                "SinglePassQuadExecutor must not own $stateChange",
                source.contains(stateChange)
            )
        }
    }

    @Test
    fun activePreprocessAndBlurUseSinglePassExecutor() {
        val effectPipelineSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/EffectPipeline.kt"
        )
        val effectContextSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/EffectContext.kt"
        )
        val preProcessSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/PreProcessEffect.kt"
        )
        val gaussianBlurSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianBlurEffect.kt"
        )
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt"
        )

        assertTrue(
            "EffectPipeline must receive the active offscreen single-pass executor",
            effectPipelineSource.contains("singlePassQuadExecutor: SinglePassQuadExecutor")
        )
        assertTrue(
            "EffectContext must expose the executor to active effects",
            effectContextSource.contains("val singlePassQuadExecutor: SinglePassQuadExecutor")
        )
        assertTrue(
            "SceneGlRenderer must thread its executor into EffectPipeline",
            rendererSource.contains("singlePassQuadExecutor = singlePassQuadExecutor")
        )
        assertTrue(
            "PreProcessEffect must submit blit and mip downsample through the executor",
            preProcessSource.split("context.singlePassQuadExecutor.draw").size - 1 == 2
        )
        assertTrue(
            "GaussianBlurEffect must submit blur passes through the executor",
            gaussianBlurSource.contains("context.singlePassQuadExecutor.draw(")
        )
        listOf(effectPipelineSource, effectContextSource, preProcessSource, gaussianBlurSource)
            .forEach { source ->
                assertFalse(
                    "Active preprocess/blur code must not depend directly on SimpleQuadRenderer",
                    source.contains("SimpleQuadRenderer")
                )
                assertFalse(
                    "Active preprocess/blur code must not use the old quad renderer context field",
                    source.contains("quadRenderer")
                )
            }
    }

    @Test
    fun sceneSlotPassRunner_delegatesBackdropEffectPipelineCallsToPasses() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt"
        )

        assertTrue(
            "SceneSlotPassRunner must delegate backdrop composite execution to SceneBackdropCompositePass",
            source.contains("backdropCompositePass.execute(")
        )
        assertTrue(
            "SceneSlotPassRunner must delegate content composite execution to SceneContentCompositePass",
            source.contains("contentCompositePass.execute(")
        )
    }

    @Test
    fun sceneSlotPassRunner_orchestratesStencilClipPassPerClippedSlot() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt"
        )

        assertTrue(
            "SceneSlotPassRunner must process slots in committed order",
            source.contains("frame.slots.forEach")
        )
        assertTrue(
            "SceneSlotPassRunner must count one composite pass per slot",
            source.contains("SceneTraceCounters.slotCompositePass()")
        )
        assertTrue(
            "SceneSlotPassRunner must keep the slot transform snapshot explicit",
            source.contains("val transform = slot.transform")
        )
        assertTrue(
            "SceneSlotPassRunner must keep the slot clip texture null check",
            source.contains("val clipTexture = slot.clipTexture")
        )
        assertTrue(
            "SceneSlotPassRunner must apply stencil setup only for clipped slots",
            Regex(
                """scene\.bind\(commandsProvider\(\), Bind\.DRAW, updateViewport = true\)\s*if \(clipTexture != null && !stencilSetupAttempted\) \{\s*stencilSetupAttempted = true\s*val stencilResult = stencilClipPass\.execute\(clipTexture, transform, targetSize\)\s*stencilActive = stencilResult == SceneStencilClipSetupResult\.Applied\s*}"""
            ).containsMatchIn(source)
        )
        assertTrue(
            "SceneSlotPassRunner must pass final scene-composite stencil setup into backdrop rendering before content",
            Regex(
                """try \{\s*compositeBackdrop\(\s*scene = scene,\s*slot = slot,\s*transform = transform,\s*targetSize = targetSize,\s*noiseTexture = noiseTexture,\s*atlasLookups = atlasLookups,\s*atlasEnabled = atlasFrameState != null,\s*beforeSceneComposite = beginSceneComposite\s*\)\s*beginSceneComposite\(\)\s*contentCompositePass\.execute\(slot, transform, targetSize\)"""
            ).containsMatchIn(source)
        )
        assertTrue(
            "SceneSlotPassRunner must disable stencil after clipped slot drawing",
            Regex("""if \(stencilActive\) \{\s*stencilClipPass\.disable\(\)\s*}""").containsMatchIn(source)
        )
        assertTrue(
            "ScenePerSlotBackdropRenderer must reset scene composite state before offscreen effect passes",
            Regex(
                """prepareOffscreenEffectState\(\)\s*val preOutput = backdropPreprocessPass\.execute[\s\S]*prepareOffscreenEffectState\(\)\s*val blurOutput = blurPass\.execute"""
            ).containsMatchIn(source)
        )
    }

    @Test
    fun sceneSlotPassRunner_releasesTransientEffectFramesOnceAfterBackdropProcessing() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt"
        )

        assertTrue(
            "ScenePerSlotBackdropRenderer must release effect frames from the backdrop processing finally block",
            Regex(
                """finally \{\s*effectFrameReleaser\.release\(preOutput, blurOutput\)\s*}"""
            ).containsMatchIn(source)
        )
        assertTrue(
            "SceneSlotPassRunner must expose release through a small helper boundary",
            source.contains("fun interface EffectFrameReleaser")
        )
    }

    @Test
    fun contentCompositePass_preservesContentQuadContract() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneContentCompositePass.kt"
        )

        assertTrue(
            "SceneContentCompositePass must return early without a content texture",
            source.contains("slot.contentTexture ?: return@trace")
        )
        assertTrue(
            "SceneContentCompositePass must count content composite passes",
            source.contains("SceneTraceCounters.contentCompositePass()")
        )
        assertTrue(
            "SceneContentCompositePass must keep the content quad id suffix",
            source.contains("id = \"\${slot.id.value}_content\"")
        )
        assertTrue(
            "SceneContentCompositePass must use the slot area as the quad source",
            source.contains("val area = slot.area")
        )
        assertTrue(
            "SceneContentCompositePass must use the full content UV rect",
            source.contains("uv = Rect(0f, 0f, 1f, 1f)")
        )
        assertTrue(
            "SceneContentCompositePass must keep content alpha opaque",
            source.contains("alpha = 1f")
        )
        assertTrue(
            "SceneContentCompositePass must keep content mask value disabled",
            source.contains("maskValue = 0f")
        )
        assertTrue(
            "SceneContentCompositePass must preserve texture flip state",
            source.contains("flipTexture = texture.flipTexture")
        )
        assertTrue(
            "SceneContentCompositePass must preserve slot visual transform",
            source.contains("transform = transform")
        )
        assertTrue(
            "SceneContentCompositePass must preserve content blend state",
            source.contains("enableBlending = true")
        )
    }

    @Test
    fun backdropCompositePass_usesSampleCropForBlurUv() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBackdropPasses.kt"
        )

        assertTrue(
            "SceneBackdropCompositePass must derive blur UVs from sampleCrop",
            source.contains("output.fbo.toUvRect(output.sampleCrop)")
        )
        assertFalse(
            "SceneBackdropCompositePass must not derive blur UVs from contentCrop",
            source.contains("output.fbo.toUvRect(output.contentCrop)")
        )
    }

    @Test
    fun renderer2BackdropCompositeNamesSceneFacingBoundary() {
        val passSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBackdropPasses.kt"
        )
        val atlasPassSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasBackdropCompositePass.kt"
        )
        val inputFields = BackdropCompositeInput::class.java.declaredFields
            .filterNot { field -> field.isSynthetic || field.name == "\$stable" }
            .map { field -> field.name }
            .toSet()

        assertTrue(passSource.contains("BackdropCompositeEffect"))
        assertTrue(passSource.contains("BackdropCompositeInput("))
        assertFalse(passSource.contains("NoiseBlendQuadEffect"))
        assertTrue(atlasPassSource.contains("BackdropCompositeEffect"))
        assertFalse(atlasPassSource.contains("NoiseBlendQuadEffect"))
        listOf(
            "blurTexture",
            "samplingOrigins",
            "sampleRect",
            "sampleUv",
            "noiseTexture",
            "compositeCoverageMask",
            "tint",
            "opacity",
            "targetSize"
        ).forEach { fieldName ->
            assertTrue(
                "BackdropCompositeInput must expose $fieldName",
                inputFields.contains(fieldName)
            )
        }
        assertTrue(
            "BackdropCompositeEffect must keep the shared shader behind a backdrop composite implementation name",
            BackdropCompositeEffect::class.java.declaredFields
                .filterNot { field -> field.isSynthetic }
                .any { field -> field.type == BackdropCompositeShaderEffect::class.java }
        )
        listOf("baseTexture", "baseUv", "baseFlip").forEach { legacyInput ->
            assertFalse(
                "BackdropCompositeInput must not expose legacy $legacyInput",
                inputFields.contains(legacyInput)
            )
            assertFalse(
                "Renderer 2 BackdropCompositeEffect must not pass legacy $legacyInput",
                sourceFile(
                    "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/BackdropCompositeEffect.kt"
                ).contains(legacyInput)
            )
            assertFalse(
                "Renderer 2 BackdropCompositeShaderEffect must not accept legacy $legacyInput",
                sourceFile(
                    "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/composite/BackdropCompositeShaderEffect.kt"
                ).contains("$legacyInput:")
            )
        }
    }

    @Test
    fun backdropPasses_doNotIntroduceRotationOrManualUvFlipSemantics() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBackdropPasses.kt"
        )

        assertFalse(
            "Backdrop passes must not introduce rotation-specific UV state",
            source.contains("uvRotation")
        )
        assertFalse(
            "Backdrop passes must not use legacy render-quad rotation",
            source.contains("rotationZ")
        )
        assertFalse(
            "Backdrop passes must not introduce manual UV origin handling",
            source.contains("needsFlipForOpenGL")
        )
        assertFalse(
            "Backdrop passes must not introduce manual UV origin handling",
            source.contains("CoordinateOrigin")
        )
        assertFalse(
            "Backdrop passes must not manually invert UV coordinates",
            source.contains("1.0 -")
        )
    }

    @Test
    fun contentCompositePass_doesNotIntroduceRotationOrManualUvFlipSemantics() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneContentCompositePass.kt"
        )

        assertFalse(
            "SceneContentCompositePass must not introduce rotation-specific UV state",
            source.contains("uvRotation")
        )
        assertFalse(
            "SceneContentCompositePass must not use legacy render-quad rotation",
            source.contains("rotationZ")
        )
        assertFalse(
            "SceneContentCompositePass must not introduce counter-rotation",
            source.contains("counterRotation") || source.contains("counter-rotation")
        )
        assertFalse(
            "SceneContentCompositePass must not introduce manual UV origin handling",
            source.contains("needsFlipForOpenGL")
        )
        assertFalse(
            "SceneContentCompositePass must not introduce manual UV origin handling",
            source.contains("CoordinateOrigin")
        )
        assertFalse(
            "SceneContentCompositePass must not manually invert UV coordinates",
            source.contains("1.0 -")
        )
    }

    @Test
    fun sceneSlotPassRunner_doesNotIntroduceRotationOrManualUvFlipSemantics() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt"
        )

        assertFalse(
            "SceneSlotPassRunner must not introduce rotation-specific UV state",
            source.contains("uvRotation")
        )
        assertFalse(
            "SceneSlotPassRunner must not use legacy render-quad rotation",
            source.contains("rotationZ")
        )
        assertFalse(
            "SceneSlotPassRunner must not introduce counter-rotation",
            source.contains("counterRotation") || source.contains("counter-rotation")
        )
        assertFalse(
            "SceneSlotPassRunner must not introduce manual UV origin handling",
            source.contains("needsFlipForOpenGL")
        )
        assertFalse(
            "SceneSlotPassRunner must not introduce manual UV origin handling",
            source.contains("CoordinateOrigin")
        )
        assertFalse(
            "SceneSlotPassRunner must not manually invert UV coordinates",
            source.contains("1.0 -")
        )
    }

    @Test
    fun stencilClipPass_keepsStencilSetupLocalAndExplicitlyDisables() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneStencilClipPass.kt"
        )

        assertTrue(
            "SceneStencilClipPass must count stencil setup",
            source.contains("SceneTraceCounters.stencilSetup()")
        )
        assertTrue(
            "SceneStencilClipPass must disable stencil before setup",
            source.contains("commands.disableStencilTest()")
        )
        assertTrue(
            "SceneStencilClipPass must clear stencil to zero",
            source.contains("commands.clearStencil(0)")
        )
        assertTrue(
            "SceneStencilClipPass must make stencil writes available during setup",
            source.contains("commands.stencilMask(0xFF)")
        )
        assertTrue(
            "SceneStencilClipPass must clear the stencil buffer",
            source.contains("commands.clear(commands.stencilBufferBit)")
        )
        assertTrue(
            "SceneStencilClipPass must write clip textures into stencil",
            source.contains("stencilClipRenderer.writeTextureToStencil(")
        )
        assertTrue(
            "SceneStencilClipPass must enable stencil testing for clipped drawing",
            source.contains("stencilClipRenderer.enableStencilTest(stencilRef = 1)")
        )
        assertTrue(
            "SceneStencilClipPass must expose explicit cleanup behavior",
            source.contains("fun disable()")
        )
        assertTrue(
            "SceneStencilClipPass cleanup must delegate to StencilClipRenderer",
            source.contains("stencilClipRenderer.disableStencilTest()")
        )
    }

    @Test
    fun scenePath_usesContextOwnedRenderCommands() {
        val sceneSources = listOf(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneRootSeedPass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/ScenePresentPass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneStencilClipPass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneNoisePass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/EffectPipeline.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/EffectContext.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/PreProcessEffect.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianBlurEffect.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/StencilClipRenderer.kt"
        ).joinToString(separator = "\n") { path -> sourceFile(path) }

        assertFalse(
            "Scene renderer path must not call the legacy RenderCommand singleton",
            sceneSources.contains("RenderCommand.")
        )
        assertFalse(
            "Scene renderer path must not import the legacy RenderCommand singleton",
            Regex("""(?m)^import dev\.serhiiyaremych\.imla\.renderer\.RenderCommand$""")
                .containsMatchIn(sceneSources)
        )
        assertFalse(
            "Scene renderer path must not use ThreadLocal command ownership",
            sceneSources.contains("ThreadLocal")
        )

        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt"
        )
        assertTrue(
            "SceneGlRenderer must resolve commands from its GraphicsContext",
            rendererSource.contains("private val commandsProvider = { graphicsContextProvider().commands }")
        )
        assertTrue(
            "Scene passes must receive context-owned commands explicitly",
            rendererSource.contains("SceneRootSeedPass(commandsProvider, singlePassQuadExecutor)") &&
                rendererSource.contains("ScenePresentPass(commandsProvider, singlePassQuadExecutor)") &&
                rendererSource.contains("SceneStencilClipPass(commandsProvider)") &&
                rendererSource.contains("SceneNoisePass(commandsProvider, singlePassQuadExecutor, shaderLibrary)") &&
                rendererSource.contains("commandsProvider = commandsProvider")
        )

        val sceneRendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt"
        )
        assertTrue(
            "ImlaSceneRenderer must create a renderer-local command facade",
            sceneRendererSource.contains("val commands = RenderCommands(OpenGLRendererAPI())")
        )
        assertTrue(
            "ImlaSceneRenderer must install commands on its GraphicsContext and FBO pool",
            sceneRendererSource.contains("fboPool = FramebufferLendingPool(commands)") &&
                sceneRendererSource.contains("commands = commands")
        )
    }

    @Test
    fun framebufferBindings_useContextCommandCache() {
        val framebufferSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/opengl/buffer/OpenGLFrameBuffer.kt"
        )
        val commandsSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/RenderCommands.kt"
        )
        val rendererApiSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/opengl/OpenGLRendererApi.kt"
        )

        assertFalse(
            "OpenGLFramebuffer must not reach through RenderCommand for FBO bind caching",
            framebufferSource.contains("RenderCommand.checkAndCacheFBOBind")
        )
        assertTrue(
            "OpenGLFramebuffer construction must update the context command FBO cache",
            framebufferSource.contains("commands.bindFramebuffer(BOTH, rendererId, force = true)") &&
                framebufferSource.contains("commands.bindDefaultFramebuffer(force = true)")
        )
        assertTrue(
            "OpenGLFramebuffer direct binds must update the same context command cache",
            framebufferSource.contains("commands.bindFramebuffer(bind, rendererId)") &&
                framebufferSource.contains("commands.setViewPort(width = sampledWidth, height = sampledHeight)") &&
                framebufferSource.contains("commands.bindDefaultFramebuffer()")
        )
        assertFalse(
            "RenderCommands must not use ThreadLocal ownership",
            commandsSource.contains("ThreadLocal")
        )
        assertFalse(
            "OpenGLRendererAPI must not own the mutable FBO cache",
            rendererApiSource.contains("cachedDrawFBO") || rendererApiSource.contains("cachedReadFBO")
        )
    }

    @Test
    fun stencilClipPass_doesNotReferenceSceneStateOwners() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneStencilClipPass.kt"
        )

        listOf(
            "RenderObject",
            "Repository",
            "repository",
            "GraphicsLayer",
            "GLRenderer",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "SceneResourceStore"
        ).forEach { forbiddenName ->
            assertFalse(
                "SceneStencilClipPass must not reference $forbiddenName",
                source.contains(forbiddenName)
            )
        }
    }

    @Test
    fun stencilClipPass_doesNotIntroduceRotationOrManualUvFlipSemantics() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneStencilClipPass.kt"
        )

        assertFalse(
            "SceneStencilClipPass must not introduce rotation-specific UV state",
            source.contains("uvRotation")
        )
        assertFalse(
            "SceneStencilClipPass must not use legacy render-quad rotation",
            source.contains("rotationZ")
        )
        assertFalse(
            "SceneStencilClipPass must not introduce counter-rotation",
            source.contains("counterRotation") || source.contains("counter-rotation")
        )
        assertFalse(
            "SceneStencilClipPass must not introduce manual UV origin handling",
            source.contains("needsFlipForOpenGL")
        )
        assertTrue(
            "SceneStencilClipPass must keep clip origin as an explicit top-left contract",
            source.contains("CoordinateOrigin.TOP_LEFT")
        )
        assertFalse(
            "SceneStencilClipPass must not manually invert UV coordinates",
            source.contains("1.0 -")
        )
    }

    @Test
    fun contentCompositePass_doesNotReferenceSceneStateOwners() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneContentCompositePass.kt"
        )

        listOf(
            "RenderObject",
            "Repository",
            "GraphicsLayer",
            "GLRenderer",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "SceneResourceStore"
        ).forEach { forbiddenName ->
            assertFalse(
                "SceneContentCompositePass must not reference $forbiddenName",
                source.contains(forbiddenName)
            )
        }
    }

    @Test
    fun sceneSlotPassRunner_doesNotReferenceSceneStateOwners() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt"
        )

        listOf(
            "RenderObject",
            "Repository",
            "repository",
            "GraphicsLayer",
            "GLRenderer",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession"
        ).forEach { forbiddenName ->
            assertFalse(
                "SceneSlotPassRunner must not reference $forbiddenName",
                source.contains(forbiddenName)
            )
        }
    }

    @Test
    fun sceneSlotPassRunner_dependsOnlyOnPassesReleaseHelperAndFramePlan() {
        val ignoredGeneratedFields = setOf("\$stable", "Companion", "MIN_ATLAS_NOISE_ALPHA")
        val runnerFields = SceneSlotPassRunner::class.java.declaredFields
            .filterNot { field -> field.isSynthetic || field.name in ignoredGeneratedFields }
            .map { field -> field.type }
        val allowedFieldTypes = setOf(
            Function0::class.java,
            SceneSlotBackdropRenderer::class.java,
            SceneContentCompositeExecutor::class.java,
            SceneStencilClipExecutor::class.java,
            SceneBlurAtlasRenderConfig::class.java,
            SceneBlurAtlasPipelinePreflightPlanner::class.java,
            SceneBlurAtlasBackdropCompositeExecutor::class.java
        )

        assertTrue(
            "SceneSlotPassRunner fields must be pass executors plus the internal atlas gate/preflight",
            runnerFields.all { fieldType -> fieldType in allowedFieldTypes }
        )

        listOf(SceneSlotPassRunner::class.java).assertNoForbiddenType(
            "RenderObject",
            "Repository",
            "GraphicsLayer",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "GLRenderer"
        )
    }

    @Test
    fun scenePasses_doNotDependOnSceneStateOwners() {
        listOf(
            SceneRootSeedPass::class.java,
            ScenePresentPass::class.java,
            SceneBackdropPreprocessPass::class.java,
            SceneBlurPass::class.java,
            SceneBackdropCompositePass::class.java,
            SceneContentCompositePass::class.java,
            SceneStencilClipPass::class.java,
            SceneNoisePass::class.java,
            SceneSlotPassRunner::class.java,
            ScenePerSlotBackdropRenderer::class.java
        ).assertNoForbiddenType(
            "RenderObject",
            "Repository",
            "GraphicsLayer",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "GLRenderer"
        )
    }

    private fun List<Class<*>>.assertNoForbiddenType(vararg forbiddenNames: String) {
        forEach { type ->
            val fieldTypes = type.declaredFields
                .filterNot { it.isSynthetic }
                .map { field -> field.genericType.typeName }
            val constructorTypes = type.declaredConstructors
                .flatMap { constructor -> constructor.genericParameterTypes.asList() }
                .map { parameterType -> parameterType.typeName }
            val methodTypes = type.declaredMethods
                .filterNot { it.isSynthetic }
                .flatMap { method ->
                    method.genericParameterTypes.asList() + method.genericReturnType
                }
                .map { parameterType -> parameterType.typeName }

            val referencedTypes = fieldTypes + constructorTypes + methodTypes
            forbiddenNames.forEach { forbiddenName ->
                assertFalse(
                    "${type.simpleName} must not reference $forbiddenName",
                    referencedTypes.any { referencedType -> referencedType.contains(forbiddenName) }
                )
            }
        }
    }

    private fun sourceFile(relativePath: String): String {
        val path = Paths.get(relativePath)
        val cwd = Paths.get("").toAbsolutePath()
        val sourcePath = listOfNotNull(
            cwd.resolve(path),
            cwd.parent?.resolve(path)
        ).firstOrNull { candidate -> Files.exists(candidate) } ?: path

        return String(Files.readAllBytes(sourcePath), Charsets.UTF_8)
    }
}
