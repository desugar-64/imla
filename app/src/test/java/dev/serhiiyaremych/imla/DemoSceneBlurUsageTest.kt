/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSceneBlurUsageTest {
    @Test
    fun demoUsesEffectGroupAndEffectLayerPublicApi() {
        val sourceRoot = projectRoot().resolve("app/src/main/java")
        val sources = sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associateWith { it.readText() }

        assertTrue(
            "Demo must use the public effect group modifier.",
            sources.values.any { ".effectGroup()" in it }
        )
        assertTrue(
            "Demo must use the public effect layer modifier.",
            sources.values.any { ".effectLayer(" in it || ".effectLayer {" in it }
        )

        val forbiddenCalls = listOf(
            ".sceneSource(",
            ".sceneSlot(",
            "Modifier.sceneSlot",
            ".blurSource(",
            ".backdropBlur("
        )
        val offenders = sources.flatMap { (file, source) ->
            forbiddenCalls
                .filter { call -> call in source }
                .map { call -> "${file.relativeTo(sourceRoot)}: $call" }
        }

        assertTrue(
            "Demo source must not use old renderer public calls. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun demoBottomSheetUsesMaterialModalBottomSheet() {
        val source = projectRoot()
            .resolve("app/src/main/java/dev/serhiiyaremych/imla/MainActivity.kt")
            .readText()
        val bottomSheetBody = source.functionBody("BlurSettingsBottomSheet")

        assertTrue(
            "Demo bottom sheet must use Material ModalBottomSheet.",
            "ModalBottomSheet(" in bottomSheetBody
        )
        assertTrue(
            "Demo bottom sheet must not be a custom AnimatedVisibility replacement.",
            "AnimatedVisibility(" !in bottomSheetBody
        )
    }

    @Test
    fun imlaHostOwnsRendererAndProvidesEffectGroupScope() {
        val root = projectRoot()
        val hostSource = root
            .resolve("imla/src/main/java/dev/serhiiyaremych/imla/ImlaHost.kt")
            .readText()
        val sourceModifier = root
            .resolve("imla/src/main/java/dev/serhiiyaremych/imla/internal/modifier/SceneSourceModifier.kt")
            .readText()

        assertTrue(
            "ImlaHost must create the scratch renderer internally.",
            "val renderer = rememberSceneRenderer()" in hostSource
        )
        assertTrue(
            "ImlaHost must provide the active renderer to effect group modifiers.",
            "LocalSceneRenderer provides renderer" in hostSource
        )
        assertTrue(
            "ImlaHost must provide the active registry to effect layer modifiers.",
            "LocalSceneRegistry provides registry" in hostSource
        )
        assertTrue(
            "effectGroup must resolve the renderer from host scope.",
            "currentValueOf(LocalSceneRenderer)" in sourceModifier
        )
        assertTrue(
            "effectGroup must resolve the registry from host scope.",
            "currentValueOf(LocalSceneRegistry)" in sourceModifier
        )
    }

    @Test
    fun publicEffectApiDoesNotRequireRendererParameters() {
        val source = projectRoot()
            .resolve("imla/src/main/java/dev/serhiiyaremych/imla/EffectLayer.kt")
            .readText()

        assertTrue(
            "The public API must expose effectGroup().",
            "public fun Modifier.effectGroup()" in source
        )
        assertTrue(
            "The public API must expose effectLayer DSL.",
            "public fun Modifier.effectLayer(\n    configure: EffectLayerScope.() -> Unit" in source
        )
        assertTrue(
            "The public API must expose effect layer bounds provider.",
            "public fun interface EffectLayerBoundsProvider" in source
        )
        assertFalse(
            "The public effect API must not require an ImlaSceneRenderer parameter.",
            "renderer: ImlaSceneRenderer" in source
        )
        assertFalse(
            "The public effect API must not require a SceneRenderer parameter.",
            "renderer: SceneRenderer" in source
        )
    }

    @Test
    fun demoUsesImlaHostAsTheOnlyRendererEntryPoint() {
        val source = projectRoot()
            .resolve("app/src/main/java/dev/serhiiyaremych/imla/MainActivity.kt")
            .readText()

        assertTrue(
            "Demo must host Imla content through ImlaHost.",
            "ImlaHost(" in source
        )
        assertTrue(
            "Demo source root must use effectGroup().",
            ".effectGroup()" in source
        )
        assertFalse(
            "Demo must not create the scratch renderer itself.",
            "rememberSceneRenderer(" in source
        )
        assertFalse(
            "Demo must not use the old scene renderer bridge.",
            "rememberImlaSceneRenderer(" in source || "ImlaSceneHost(" in source
        )
    }

    @Test
    fun multiInstancePagerUsesPageLocalImlaHosts() {
        val source = projectRoot()
            .resolve("app/src/main/java/dev/serhiiyaremych/imla/MultiInstanceScenePagerDemo.kt")
            .readText()
        val routeSource = projectRoot()
            .resolve("app/src/main/java/dev/serhiiyaremych/imla/MainActivity.kt")
            .readText()
        val hostBody = source.functionBody("SceneRendererPageHost")
        val cardBody = source.functionBody("GlassCard")

        assertTrue(
            "The multi-instance demo must be reachable from the existing bottom-nav route.",
            "MultiInstanceScenePagerDemo(" in routeSource
        )
        assertTrue(
            "The demo must use a horizontal pager or pager-like route.",
            "HorizontalPager(" in source
        )
        assertTrue(
            "Pager pages should not keep hidden renderer hosts alive after they settle.",
            "beyondViewportPageCount = 0" in source && "isPageVisible" in source
        )
        assertTrue(
            "Each visible page host must create its own ImlaHost scope.",
            "ImlaHost(" in hostBody
        )
        assertTrue(
            "Each visible page host must own the effect group capture scope.",
            ".effectGroup()" in hostBody
        )
        assertTrue(
            "Pager blur cards must use renderer-free effectLayer.",
            ".effectLayer(" in cardBody && "renderer =" !in cardBody
        )
    }

    @Test
    fun internalLegacyRendererDoesNotExposeRenderObjectApis() {
        val source = projectRoot()
            .resolve("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt")
            .readText()

        val forbiddenApis = listOf(
            "updateRenderObject(",
            "detachRenderObject(",
            "updateOffset(",
            "updateStyle(",
            "updateMask(",
            "updateContentLayer(",
            "ImlaRenderPipeline"
        )

        val offenders = forbiddenApis.filter { it in source }
        assertTrue(
            "ImlaSceneRenderer must not expose legacy render-object compatibility APIs. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    private fun String.functionBody(name: String): String {
        val functionStart = indexOf("private fun $name")
        require(functionStart >= 0) { "Could not find $name" }

        val bodyStart = indexOf('{', functionStart)
        require(bodyStart >= 0) { "Could not find $name body" }

        var depth = 0
        for (index in bodyStart until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return substring(bodyStart, index + 1)
                }
            }
        }
        error("Could not find $name body end")
    }

    private fun projectRoot(): File {
        return generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
    }
}
