/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.StencilClipRenderer
import dev.serhiiyaremych.imla.internal.legacy.StencilClipWriteResult
import dev.serhiiyaremych.imla.internal.legacy.StencilClipWriter

/**
 * Executes stencil clip setup for one clipped scene slot on the GL thread.
 *
 * The scene renderer calls [execute] before drawing that slot's backdrop and content,
 * then calls [disable] immediately afterward when setup was applied. If hardware stencil
 * clipping is unsupported, setup returns [SceneStencilClipSetupResult.FallbackUnclipped]
 * so the caller draws the slot without clipping rather than testing against an empty
 * stencil buffer. Command state comes from the owning GraphicsContext and stays local
 * to that renderer. This pass owns only transient stencil state for the bound target
 * and delegates clip texture drawing and stencil-test toggles to [StencilClipRenderer].
 * It does not own scene commits, captured layer resources, callbacks, surface lifecycle,
 * or texture lifetimes.
 */
internal class SceneStencilClipPass(
    private val commandsProvider: () -> RenderCommands,
    stencilClipRendererFactory: () -> StencilClipWriter
) : SceneStencilClipExecutor {
    private val stencilClipRenderer: StencilClipWriter by lazy(
        LazyThreadSafetyMode.NONE,
        stencilClipRendererFactory
    )

    override fun execute(
        clipTexture: Texture2D,
        transform: Mat4,
        targetSize: IntSize
    ): SceneStencilClipSetupResult = trace("SceneStencilClipPass#execute") {
        executeSetup(clipTexture, transform, targetSize)
    }

    internal fun executeSetup(
        clipTexture: Texture2D,
        transform: Mat4,
        targetSize: IntSize
    ): SceneStencilClipSetupResult {
        SceneStencilClipTextureContract.requireShaderInput(clipTexture)
        SceneTraceCounters.stencilSetup()
        val commands = commandsProvider()
        return try {
            commands.disableStencilTest()
            commands.clearStencil(0)
            commands.stencilMask(0xFF)
            commands.clear(commands.stencilBufferBit)
            val writeResult = stencilClipRenderer.writeTextureToStencil(
                clipTexture = clipTexture,
                transform = transform,
                targetSize = targetSize,
                stencilRef = 1
            )
            if (writeResult == StencilClipWriteResult.Unsupported) {
                return SceneStencilClipSetupResult.FallbackUnclipped
            }
            stencilClipRenderer.enableStencilTest(stencilRef = 1)
            SceneStencilClipSetupResult.Applied
        } catch (failure: Throwable) {
            try {
                stencilClipRenderer.disableStencilTest()
            } catch (suppressed: Throwable) {
                failure.addSuppressed(suppressed)
            }
            throw failure
        }
    }

    override fun disable() = trace("SceneStencilClipPass#disable") {
        stencilClipRenderer.disableStencilTest()
    }
}

internal object SceneStencilClipTextureContract {
    val shaderInputOrigin: CoordinateOrigin = CoordinateOrigin.TOP_LEFT

    fun requireShaderInput(clipTexture: Texture2D) {
        require(clipTexture.coordinateOrigin == shaderInputOrigin) {
            "Stencil clip shader expects slot-local $shaderInputOrigin clip textures; " +
                "got ${clipTexture.coordinateOrigin}"
        }
    }
}
