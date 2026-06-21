/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class Renderer2ActiveShaderAssetGuardTest {
    @Test
    fun activeRenderer2ShaderAssetNamesDoNotIncludeLegacyMaskOrNoiseShaders() {
        val loadedFragmentAssets = activeRenderer2SourceFiles
            .flatMap { relativePath -> loadedFragmentAssetNames(sourceFile(relativePath)) }
            .toSet()

        assertTrue(
            loadedFragmentAssets.containsAll(
                setOf(
                    "screen_space_quad",
                    "noise_blend_quad",
                    "noise_flat",
                    "stencil_clip_quad",
                    "gaussian_blur_kernel"
                )
            )
        )
        assertEquals(emptySet<String>(), loadedFragmentAssets.intersect(legacyMaskNoiseFragmentAssets))
    }

    private fun loadedFragmentAssetNames(source: String): List<String> {
        val namedLoadShaderFragments = fragmentFileArgumentRegex
            .findAll(source)
            .map { match -> match.groupValues[1] }
        val directFragmentAssets = fragmentAssetPathRegex
            .findAll(source)
            .map { match -> match.groupValues[1] }
        return (namedLoadShaderFragments + directFragmentAssets).toList()
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

    private companion object {
        val legacyMaskNoiseFragmentAssets = setOf(
            "simple_mask",
            "mask",
            "noise",
            "noise_blend"
        )
        val fragmentFileArgumentRegex = Regex("""fragFileName\s*=\s*"([^"]+)"""")
        val fragmentAssetPathRegex = Regex("""fragmentAsset\s*=\s*"shader/([^"]+)\.frag"""")

        val activeRenderer2SourceFiles = listOf(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/Renderer2D.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/GraphicsLayerRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneSession.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/MaskTextureRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/StencilClipRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/SurfaceTextureRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/QuadBatchRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/SimpleQuadRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/SinglePassQuadExecutor.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/EffectPipeline.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianBlurEffect.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianBlurKernelShaderProgram.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/PreProcessEffect.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/noise/BackdropCompositeSamplingOrigins.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/composite/BackdropCompositeShaderEffect.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/BackdropCompositeEffect.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBackdropPasses.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasBackdropCompositePass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasBlurPass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasCompositeLookupAdapter.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasCopyPass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasPipelineRunner.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneContentCompositePass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneLayerRepository.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneMaskRepository.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneNoisePass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/ScenePresentPass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneRootSeedPass.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneStencilClipPass.kt"
        )
    }
}
