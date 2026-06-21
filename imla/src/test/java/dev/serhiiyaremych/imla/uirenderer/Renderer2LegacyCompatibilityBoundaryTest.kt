/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Renderer2LegacyCompatibilityBoundaryTest {
    @Test
    fun publicApiIsRootEffectApiOnly() {
        val effectApi = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/EffectLayer.kt"
        )
        val hostApi = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/ImlaHost.kt"
        )

        assertContains(effectApi, "public fun Modifier.effectGroup()")
        assertContains(effectApi, "public fun Modifier.effectLayer(")
        assertContains(effectApi, "public fun interface EffectLayerBoundsProvider")
        assertContains(effectApi, "public class EffectLayerScope internal constructor()")
        assertContains(hostApi, "public fun ImlaHost(")

        val forbiddenPublicNames = listOf(
            "public fun Modifier.sceneSource",
            "public fun Modifier.sceneSlot",
            "public class SceneSlotScope",
            "public annotation class SceneSlotDsl",
            "public fun interface SceneVisualBoundsProvider",
            "public fun ImlaSceneHost",
            "public class ImlaSceneRenderer",
            "public fun rememberImlaSceneRenderer",
            "public fun Modifier.blurSource",
            "public fun Modifier.backdropBlur",
            "public data class Style",
            "public enum class BlurAlgorithm"
        )
        val productionSource = productionSources()
        val offenders = forbiddenPublicNames.filter { name ->
            productionSource.any { (_, source) -> name in source }
        }

        assertTrue(
            "Old public API names must not be exposed. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun implementationPackagesDoNotUseOldPublicScene2OrUirendererPackages() {
        val packageOffenders = productionSources().flatMap { (path, source) ->
            source.lineSequence()
                .filter { line ->
                    line.startsWith("package ") &&
                        (".scene2" in line || ".uirenderer" in line || ".renderer" in line)
                }
                .map { line -> "$path declares $line" }
        }
        val importOffenders = productionSources().flatMap { (path, source) ->
            source.lineSequence()
                .filter { line ->
                    line.startsWith("import dev.serhiiyaremych.imla.uirenderer") ||
                        line.startsWith("import dev.serhiiyaremych.imla.renderer") ||
                        line.contains(".scene2.")
                }
                .map { line -> "$path imports $line" }
        }

        assertTrue(
            "Implementation packages must use dev.serhiiyaremych.imla.internal.*. " +
                "Offenders: ${packageOffenders + importOffenders}",
            packageOffenders.isEmpty() && importOffenders.isEmpty()
        )
    }

    @Test
    fun publicPackageContainsOnlyComposeEntryPoints() {
        val productionRoot = sourcePath("imla/src/main/java/dev/serhiiyaremych/imla")
        val offenders = productionSources().flatMap { (path, source) ->
            val packageLine = source.lineSequence().firstOrNull { it.startsWith("package ") }
            val relativePath = productionRoot.relativize(path).toString()
            if (packageLine == "package dev.serhiiyaremych.imla" || packageLine?.contains(".internal") == true) {
                emptyList()
            } else {
                listOf("$relativePath declares $packageLine")
            }
        }

        assertTrue(
            "Library public API must live in the root package and implementation under internal. " +
                "Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun retiredRendererOneBridgeFilesStayDeleted() {
        listOf(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/UiLayerRenderer.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaRenderPipeline.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/modifier/ImlaBlurSourceModifier.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/CopyLessRenderingPipeline.kt",
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/UiRenderingPipeline.kt"
        ).forEach(::assertMissing)
    }

    @Test
    fun legacyRenderObjectMutationApisStayDeleted() {
        val sceneRenderer = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt"
        )
        val legacyMutationApis = listOf(
            "updateRenderObject(",
            "detachRenderObject(",
            "updateOffset(",
            "updateStyle(",
            "updateMask(",
            "updateContentLayer(",
            "ImlaRenderPipeline"
        )
        val offenders = legacyMutationApis.filter { it in sceneRenderer }

        assertTrue(
            "Internal legacy renderer must not regrow render-object compatibility APIs. " +
                "Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun legacyBaseTextureCompositeModeIsDeletedWithOldPipeline() {
        val sceneCompositeEffect = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/composite/" +
                "BackdropCompositeShaderEffect.kt"
        )
        val sceneAdapter = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/BackdropCompositeEffect.kt"
        )

        listOf("baseTexture:", "baseUv:", "baseFlip:").forEach { forbidden ->
            assertFalse(
                "Renderer 2 composite shader effect must not accept legacy $forbidden input.",
                forbidden in sceneCompositeEffect
            )
        }
        assertFalse(
            "Renderer 2 scene adapter must not pass legacy base inputs.",
            "baseTexture" in sceneAdapter || "baseUv" in sceneAdapter || "baseFlip" in sceneAdapter
        )
    }

    @Test
    fun effectLayerDrawOrderFollowsRootDrawTraversal() {
        val registrySource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/layer/registry/SceneRegistry.kt"
        )
        val sourceModifier = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/modifier/SceneSourceModifier.kt"
        )
        val layerModifier = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/modifier/SceneSlotModifier.kt"
        )

        assertContains(registrySource, "fun resetDrawOrder()")
        assertContains(registrySource, "fun recordSlotDraw(id: SceneSlotId)")
        assertContains(registrySource, "drawOrder = drawOrder,")
        assertContains(sourceModifier, "sceneRegistry?.resetDrawOrder()\n        layer.record")
        assertContains(layerModifier, "registry?.recordSlotDraw(slotId)")
        assertFalse(
            "Effect layers must not use slot creation id as committed draw order.",
            "drawOrder = id.value.toInt()," in registrySource
        )
    }

    private fun productionSources(): List<Pair<Path, String>> {
        val productionRoot = sourcePath("imla/src/main/java/dev/serhiiyaremych/imla")
        return Files.walk(productionRoot).use { paths ->
            paths
                .filter { path -> path.toString().endsWith(".kt") }
                .map { path -> path to String(Files.readAllBytes(path), Charsets.UTF_8) }
                .toList()
        }
    }

    private fun assertContains(source: String, expected: String) {
        assertTrue("Expected source to contain:\n$expected", source.contains(expected))
    }

    private fun assertMissing(relativePath: String) {
        assertFalse("$relativePath must be deleted.", Files.exists(sourcePath(relativePath)))
    }

    private fun sourceFile(relativePath: String): String {
        return String(Files.readAllBytes(sourcePath(relativePath)), Charsets.UTF_8)
    }

    private fun sourcePath(relativePath: String): Path {
        val path = Paths.get(relativePath)
        val cwd = Paths.get("").toAbsolutePath()
        return listOfNotNull(
            cwd.resolve(path),
            cwd.parent?.resolve(path)
        ).firstOrNull { candidate -> Files.exists(candidate) } ?: path
    }
}
