/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImlaSceneRendererLifetimeTest {
    @Test
    fun observerDestroysRememberedRendererWhenForgottenOrAbandoned() {
        val renderer = FakeRenderer()
        val observer = ImlaSceneRendererObserver(
            renderer = renderer,
            destroyRenderer = FakeRenderer::destroy
        )

        observer.onForgotten()
        observer.onAbandoned()

        assertEquals(2, renderer.destroyCalls)
    }

    @Test
    fun rememberSceneRendererStoresRememberObserver() {
        val source = projectRoot()
            .resolve("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt")
            .readText()
        val body = source.functionBody("rememberImlaSceneRenderer")

        assertTrue(
            "rememberImlaSceneRenderer must remember the observer, not only observer.renderer.",
            "val observer = remember(" in body
        )
        assertTrue(
            "rememberImlaSceneRenderer must return the renderer from the remembered observer.",
            "return observer.renderer" in body
        )
    }

    @Test
    fun sceneRendererDelegatesSurfaceLifecycleToSession() {
        val source = projectRoot()
            .resolve("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt")
            .readText()

        assertTrue(
            "ImlaSceneRenderer should own one scene session for surface lifecycle.",
            "private val sceneSession: ImlaSceneSession" in source
        )
        assertTrue(
            "ImlaSceneRenderer attach must delegate to the scene session.",
            "sceneSession.attachSurface(surface, size)" in source
        )
        assertTrue(
            "ImlaSceneRenderer detach must delegate to the scene session.",
            "sceneSession.detachSurface()" in source
        )
        assertTrue(
            "ImlaSceneRenderer render requests must be gated through the scene session.",
            "return sceneSession.requestRender()" in source
        )

        val rendererOwnedState = listOf(
            "AtomicBoolean",
            "isSurfaceAttached",
            "isRendering",
            "mainRenderTarget",
            "renderTargetSize",
            "mainDrawCallback"
        )
        val offenders = rendererOwnedState.filter { it in source }
        assertTrue(
            "ImlaSceneRenderer must not directly own surface lifecycle state. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    private class FakeRenderer {
        var destroyCalls: Int = 0
            private set

        fun destroy() {
            destroyCalls += 1
        }
    }

    private fun String.functionBody(name: String): String {
        val functionStart = indexOf("fun $name")
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
