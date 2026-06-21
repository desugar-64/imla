/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Renderer2BackdropCompositeAlphaContractTest {
    @Test
    fun plainCompositeOutputsUnassociatedRgbWithSourceOverAlpha() {
        val shader = sourceFile("imla/src/main/assets/shader/screen_space_quad.frag")
        val quadRenderer = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/QuadBatchRenderer.kt"
        )
        val glApi = sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/render/opengl/OpenGLRendererApi.kt")

        assertTrue(shader.contains("baseColor = mix(baseColor, data.tint, data.tint.a * data.tint.a);"))
        assertTrue(shader.contains("baseColor.a = data.alpha;"))
        assertTrue(shader.contains("baseColor.a *= data.alpha;"))
        assertTrue(shader.contains("color = baseColor;"))
        assertFalse(shader.contains("baseColor.rgb *= baseColor.a"))
        assertTrue(quadRenderer.contains("commands.withBlendingModeEnabled"))
        assertTrue(glApi.contains("glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)"))
    }

    @Test
    fun noiseAndMaskCompositeOutputsUnassociatedRgbWithCoverageGatedAlpha() {
        val shader = sourceFile("imla/src/main/assets/shader/noise_blend_quad.frag")
        val effect = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/BackdropCompositeEffect.kt"
        )

        assertTrue(shader.contains("blurColor = mix(blurColor, data.tint, data.tint.a * data.tint.a);"))
        assertTrue(shader.contains("vec3 grain = (vec3(noiseValue) - vec3(0.5)) * u_NoiseAlpha * weight;"))
        assertTrue(shader.contains("vec3 blended = clamp(blurColor.rgb + grain, 0.0, 1.0);"))
        assertTrue(shader.contains("outAlpha = data.alpha;"))
        assertTrue(shader.contains("outAlpha *= maskValue;"))
        assertTrue(shader.contains("color = vec4(blended, outAlpha);"))
        assertFalse(shader.contains("blended *= outAlpha"))
        assertTrue(effect.contains("val opacity: Float"))
        assertTrue(effect.contains("val compositeCoverageMask: Texture2D?"))
        assertTrue(effect.contains("val noiseAlpha: Float"))
    }

    @Test
    fun alphaDiagnosticSceneCoversCompositeRiskCasesBehindManualSwitch() {
        val sceneSource = sourceFile("app/src/main/java/dev/serhiiyaremych/imla/AlphaCompositeScene.kt")
        val activitySource = sourceFile("app/src/main/java/dev/serhiiyaremych/imla/MainActivity.kt")

        assertTrue(
            "Alpha diagnostic scene must stay behind a manual log-tag switch",
            activitySource.contains("Log.isLoggable(ALPHA_COMPOSITE_SCENE_TAG, Log.DEBUG)")
        )
        listOf(
            "alpha-low-opacity",
            "alpha-tint",
            "alpha-mask-gradient",
            "alpha-noise",
            "alpha-edge-source"
        ).forEach { debugName ->
            assertTrue("Diagnostic scene must include $debugName", sceneSource.contains("debugName = \"$debugName\""))
        }
        assertTrue("Low-opacity case must keep blur opacity partial", sceneSource.contains("blurOpacity = 0.28f"))
        assertTrue("Tint case must use partial tint opacity", sceneSource.contains("copy(alpha = 0.48f)"))
        assertTrue("Mask case must use a coverage gradient", sceneSource.contains("val coverageGradient"))
        assertTrue("Noise case must use partial noise", sceneSource.contains("noiseAlpha = 0.24f"))
        assertTrue("Edge-source case must include translucent foreground detail", sceneSource.contains("edgeContent = true"))
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
