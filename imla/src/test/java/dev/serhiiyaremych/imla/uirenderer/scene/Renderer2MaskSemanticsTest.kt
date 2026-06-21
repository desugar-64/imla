/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class Renderer2MaskSemanticsTest {
    @Test
    fun activeScenePathNamesBlurRadiusAndCompositeCoverageMasks() {
        val slotRunnerSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt"
        )
        val backdropSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBackdropPasses.kt"
        )

        assertTrue(
            "Per-slot renderer must read the blur-radius mask from SceneSlotPlan",
            slotRunnerSource.contains("val blurRadiusMask = slot.blurRadiusMask")
        )
        assertTrue(
            "Per-slot renderer must read the composite coverage mask from SceneSlotPlan",
            slotRunnerSource.contains("val compositeCoverageMask = slot.compositeCoverageMask")
        )
        assertTrue(
            "Per-slot renderer must pass the blur-radius mask into the blur stage",
            slotRunnerSource.contains("val blurOutput = blurPass.execute(preOutput, style.sigma, blurRadiusMask)")
        )
        assertTrue(
            "Per-slot renderer must pass the composite coverage mask into the backdrop composite stage",
            Regex("""backdropCompositePass\.execute\([\s\S]*compositeCoverageMask = compositeCoverageMask""")
                .containsMatchIn(slotRunnerSource)
        )
        assertTrue(
            "Backdrop composite must route masked slots through the noise/mask shader path",
            backdropSource.contains("if (noiseTexture != null || compositeCoverageMask != null)")
        )
        assertTrue(
            "Backdrop composite must forward the mask through the scene backdrop composite boundary",
            backdropSource.contains("compositeCoverageMask = compositeCoverageMask")
        )
    }

    @Test
    fun blurStageSamplesMaskInSampleCropSpaceAndOnlyChangesKernelRadius() {
        val blurSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianBlurEffect.kt"
        )
        val kernelShader = sourceFile("imla/src/main/assets/shader/gaussian_blur_kernel.frag")

        assertTrue(
            "Renderer 2 blur masks must map through sampleCrop",
            blurSource.contains("val maskUv = input.fbo.toUvRect(input.sampleCrop)")
        )
        assertFalse(
            "Renderer 2 blur masks must not map through contentCrop",
            blurSource.contains("val maskUv = input.fbo.toUvRect(input.contentCrop)")
        )
        assertTrue(
            "Blur shader must convert blur texture UVs into local mask coordinates",
            kernelShader.contains("vec2 local = (v_UV0 - u_MaskUvMin) / maskSize;")
        )
        assertTrue(
            "Blur shader must treat mask out-of-bounds as fully blurred padding",
            kernelShader.contains("maskValue = mix(1.0, sampled, inBounds);")
        )
        assertTrue(
            "Blur shader must use the mask to scale kernel offsets",
            kernelShader.contains("vec2 offset = sampleData.xy * maskValue;")
        )
        assertFalse(
            "Blur shader must not gate final fragment alpha directly",
            kernelShader.contains("color.a *= maskValue") || kernelShader.contains("color *= maskValue")
        )
    }

    @Test
    fun compositeStageSamplesMaskInQuadSpaceAndGatesFinalAlpha() {
        val compositeEffectSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/composite/BackdropCompositeShaderEffect.kt"
        )
        val compositeShader = sourceFile("imla/src/main/assets/shader/noise_blend_quad.frag")

        assertTrue(
            "Composite stage must use quad-local mask coordinates",
            compositeShader.contains("vec2 maskUv = maskCoord;")
        )
        assertFalse(
            "Composite coverage masks must not use sampleCrop uniforms",
            compositeShader.contains("u_MaskUvMin") || compositeShader.contains("u_MaskUvMax")
        )
        assertTrue(
            "Composite stage must gate final source-over alpha with the mask",
            compositeShader.contains("outAlpha *= maskValue;")
        )
        assertTrue(
            "Composite stage must keep mask origin handling separate from blur origin handling",
            compositeEffectSource.contains("samplingOrigins.compositeCoverageMaskFlip")
        )
    }

    @Test
    fun diagnosticSceneCoversSimplePaddedAndRotatedMaskedSlots() {
        val sceneSource = sourceFile("app/src/main/java/dev/serhiiyaremych/imla/MaskSemanticsScene.kt")
        val activitySource = sourceFile("app/src/main/java/dev/serhiiyaremych/imla/MainActivity.kt")

        assertTrue(
            "Mask diagnostic scene must stay behind a manual log-tag switch",
            activitySource.contains("Log.isLoggable(MASK_SEMANTICS_SCENE_TAG, Log.DEBUG)")
        )
        listOf("mask-simple", "mask-padded", "mask-rotated").forEach { debugName ->
            assertTrue(
                "Diagnostic scene must include $debugName",
                sceneSource.contains("debugName = \"$debugName\"")
            )
        }
        assertTrue(
            "Padded diagnostic slot must use a larger visible area than its layout box",
            sceneSource.contains("visibleAreaProvider = paddedEffectLayerBoundsProvider")
        )
        assertTrue(
            "Rotated diagnostic slot must exercise transformed quad-space mask coverage",
            sceneSource.contains("rotationZ = -16f")
        )
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
