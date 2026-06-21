/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.RendererApi
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.VertexArray
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.legacy.BlurAlgorithm
import dev.serhiiyaremych.imla.internal.legacy.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.Buffer
import java.nio.file.Files
import java.nio.file.Paths

class SceneBlurAtlasRenderOrchestrationTest {
    @Test
    fun atlasRenderConfig_defaultsOn() {
        assertTrue(SceneBlurAtlasRenderConfig().enabled)
        assertTrue(SceneBlurAtlasRenderConfig.Enabled.enabled)
        assertFalse(SceneBlurAtlasRenderConfig.Disabled.enabled)
    }

    @Test
    fun atlasOptOut_defaultEnablesAtlasAndOptOutDisables() {
        assertTrue(
            SceneBlurAtlasDiagnosticMode.renderConfig(
                overrideDisabled = { false }
            ).enabled
        )
        assertFalse(
            SceneBlurAtlasDiagnosticMode.renderConfig(
                overrideDisabled = { true }
            ).enabled
        )
    }

    @Test
    fun atlasOptOutEnabledByDefaultAndDisabledViaSystemProperty() {
        val diagnosticSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasDiagnosticMode.kt"
        )

        assertTrue(diagnosticSource.contains("ImlaAtlasDisabled"))
        assertTrue(diagnosticSource.contains("Log.isLoggable(ATLAS_DISABLED_TAG, Log.DEBUG)"))
        assertTrue(diagnosticSource.contains("overrideDisabled: () -> Boolean"))
        assertFalse(diagnosticSource.contains("BuildConfig.DEBUG"))
        assertFalse(diagnosticSource.contains("BuildConfig.BUILD_TYPE"))
    }

    @Test
    fun slotRunner_flagOffDoesNotRequestAtlasPipelineAndUsesPerSlotPath() {
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer()
        val atlasComposite = RecordingAtlasComposite()
        var atlasProviderRequested = false
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Disabled,
            atlasPreflightPlanner = SceneBlurAtlasPipelinePreflightPlanner {
                error("atlas preflight must stay cold when the flag is off")
            },
            atlasPipelineProvider = {
                atlasProviderRequested = true
                error("atlas pipeline must stay cold when the flag is off")
            }
        )
        val frame = renderFrame(slotPlan(id = "eligible"))

        withRecordingSceneTraceCounters { recorder ->
            runner.executeFrame(
                scene = sceneFramebuffer(),
                frame = frame,
                targetSize = frame.rootSize,
                noiseTexture = null,
                useAtlasCopyImage = true
            )

            assertTrue(recorder.atlasRecords().isEmpty())
        }

        assertFalse(atlasProviderRequested)
        assertTrue(atlasComposite.calls.isEmpty())
        assertEquals(listOf("eligible"), perSlotBackdrop.slotIds)
    }

    @Test
    fun slotRunner_enabledWithoutEligibleWorkDoesNotRequestAtlasPipeline() {
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer()
        val atlasComposite = RecordingAtlasComposite()
        val preflightPlanner = RecordingAtlasPreflightPlanner()
        var atlasProviderRequested = false
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPreflightPlanner = preflightPlanner,
            atlasPipelineProvider = {
                atlasProviderRequested = true
                error("atlas pipeline must run only when the frame has atlas-eligible work")
            }
        )
        val frame = renderFrame(
            slotPlan(
                id = "masked-fallback",
                maskTexture = FakeTexture(id = 55, size = IntSize(width = 12, height = 12))
            )
        )

        withRecordingSceneTraceCounters { recorder ->
            runner.executeFrame(
                scene = sceneFramebuffer(),
                frame = frame,
                targetSize = frame.rootSize,
                noiseTexture = null
            )

            assertEquals(1, recorder.count("atlas.preflight.frame"))
            assertEquals(1, recorder.count("atlas.preflight.fallbackRequest"))
            assertEquals(1, recorder.count("atlas.preflight.fallback.MaskedBlurUnsupported"))
            assertEquals(1, recorder.count("atlas.preflight.noEligibleFrame"))
            assertEquals(1, recorder.count("atlas.fallback.slot"))
            assertEquals(listOf(0L), recorder.values("gauge.atlas.preflight.eligibleRequestCount"))
            assertEquals(listOf(1L), recorder.values("gauge.atlas.preflight.fallbackRequestCount"))
            assertFalse(recorder.atlasRecordNames().contains("atlas.pipeline.execute"))
            assertFalse(recorder.atlasRecordNames().contains("atlas.copy.batch"))
            assertFalse(recorder.atlasRecordNames().contains("atlas.blur.batch"))
            assertFalse(recorder.atlasRecordNames().contains("atlas.composite.slot"))
        }

        assertFalse(atlasProviderRequested)
        assertTrue(atlasComposite.calls.isEmpty())
        assertEquals(listOf("masked-fallback"), perSlotBackdrop.slotIds)
        assertEquals(1, preflightPlanner.preflights.size)
        val preflight = preflightPlanner.preflights.single()
        val fallback = preflight.atlasPlan.fallbacks.single()
        assertFalse(preflight.hasEligiblePlacements)
        assertEquals(BlurSlotId("masked-fallback"), fallback.slotId)
        assertEquals(SceneBlurAtlasFallbackReason.MaskedBlurUnsupported, fallback.reason)
    }

    @Test
    fun slotRunner_enabledUsesAtlasLookupForEligibleSlotsAndPerSlotForFallbacks() {
        val eligible = slotPlan(id = "eligible", drawIndex = 0)
        val fallback = slotPlan(
            id = "fallback",
            drawIndex = 1,
            maskTexture = FakeTexture(id = 56, size = IntSize(width = 12, height = 12))
        )
        val frame = renderFrame(eligible, fallback)
        val preflightPlanner = RecordingAtlasPreflightPlanner()
        val atlasPipeline = RecordingAtlasPipeline { preflight ->
            atlasOutput(preflight, listOf(lookupEntry(eligible)))
        }
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer()
        val contentComposite = RecordingContentComposite()
        val atlasComposite = RecordingAtlasComposite()
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            contentComposite = contentComposite,
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPreflightPlanner = preflightPlanner,
            atlasPipelineProvider = { atlasPipeline }
        )
        val scene = sceneFramebuffer()

        withRecordingSceneTraceCounters { recorder ->
            runner.executeFrame(
                scene = scene,
                frame = frame,
                targetSize = frame.rootSize,
                noiseTexture = null,
                useAtlasCopyImage = true
            )

            assertEquals(1, recorder.count("atlas.preflight.frame"))
            assertEquals(1, recorder.count("atlas.preflight.eligibleRequest"))
            assertEquals(1, recorder.count("atlas.preflight.eligible.PlainScreenSpaceBlur"))
            assertEquals(1, recorder.count("atlas.preflight.fallbackRequest"))
            assertEquals(1, recorder.count("atlas.preflight.fallback.MaskedBlurUnsupported"))
            assertEquals(1, recorder.count("atlas.pipeline.execute"))
            assertEquals(1, recorder.count("atlas.composite.slot"))
            assertEquals(1, recorder.count("atlas.fallback.slot"))
            assertEquals(listOf(1L), recorder.values("gauge.atlas.preflight.eligibleRequestCount"))
            assertEquals(listOf(1L), recorder.values("gauge.atlas.preflight.fallbackRequestCount"))
            assertEquals(listOf(1L), recorder.values("gauge.atlas.pipeline.batchCount"))
        }

        assertEquals(listOf("execute"), atlasPipeline.events)
        assertSame(scene, atlasPipeline.sources.single().sourceFramebuffer)
        assertTrue(atlasPipeline.sources.single().useCopyImage)
        assertEquals(1, preflightPlanner.preflights.size)
        val preflight = preflightPlanner.preflights.single()
        val fallbackRequest = preflight.atlasPlan.fallbacks.single()
        assertSame(preflight, atlasPipeline.preflights.single())
        assertSame(preflight, atlasPipeline.releasedOutputs.single().preflight)
        assertEquals(BlurSlotId("fallback"), fallbackRequest.slotId)
        assertEquals(SceneBlurAtlasFallbackReason.MaskedBlurUnsupported, fallbackRequest.reason)
        assertEquals(listOf("eligible"), atlasComposite.slotIds)
        assertEquals(listOf("fallback"), perSlotBackdrop.slotIds)
        assertEquals(listOf("eligible", "fallback"), contentComposite.slotIds)
        assertEquals(1, atlasPipeline.releasedOutputs.size)
    }

    @Test
    fun slotRunner_enabledUsesAtlasLookupForNoisySlotWhenFrameNoiseExists() {
        val noiseTexture = FakeTexture(id = 57, size = IntSize(width = 320, height = 240))
        val noisy = slotPlan(
            id = "noisy",
            style = atlasStyle(noiseAlpha = 0.25f)
        )
        val frame = renderFrame(noisy)
        val atlasPipeline = RecordingAtlasPipeline { preflight ->
            atlasOutput(preflight, listOf(lookupEntry(noisy)))
        }
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer()
        val atlasComposite = RecordingAtlasComposite()
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = { atlasPipeline }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = frame,
            targetSize = frame.rootSize,
            noiseTexture = noiseTexture
        )

        assertEquals(listOf("noisy"), atlasComposite.slotIds)
        assertEquals(listOf(noiseTexture), atlasComposite.noiseTextures)
        assertTrue(perSlotBackdrop.slotIds.isEmpty())
        assertEquals(1, atlasPipeline.releasedOutputs.size)
    }

    @Test
    fun slotRunner_enabledUsesAtlasLookupForCoverageOnlyMask() {
        val coverageMask = FakeTexture(id = 58, size = IntSize(width = 32, height = 32))
        val coverageOnly = slotPlan(
            id = "coverage-only",
            compositeCoverageMask = coverageMask
        )
        val frame = renderFrame(coverageOnly)
        val atlasPipeline = RecordingAtlasPipeline { preflight ->
            atlasOutput(preflight, listOf(lookupEntry(coverageOnly)))
        }
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer()
        val atlasComposite = RecordingAtlasComposite()
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = { atlasPipeline }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = frame,
            targetSize = frame.rootSize,
            noiseTexture = null
        )

        assertEquals(listOf("coverage-only"), atlasComposite.slotIds)
        assertEquals(listOf(coverageMask), atlasComposite.maskTextures)
        assertTrue(perSlotBackdrop.slotIds.isEmpty())
    }

    @Test
    fun slotRunner_enabledFallsBackPublicBlurMaskWithBothMaskRoles() {
        val mask = FakeTexture(id = 59, size = IntSize(width = 32, height = 32))
        val masked = slotPlan(id = "masked", maskTexture = mask)
        val frame = renderFrame(masked)
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer()
        val atlasComposite = RecordingAtlasComposite()
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = { null }
        )

        withRecordingSceneTraceCounters { recorder ->
            runner.executeFrame(
                scene = sceneFramebuffer(),
                frame = frame,
                targetSize = frame.rootSize,
                noiseTexture = null
            )

            assertTrue(
                recorder.atlasRecordNames().contains(
                    "atlas.fallback.slot.diag/masked#0/MaskedBlurUnsupported"
                )
            )
            assertTrue(
                recorder.atlasRecordNames().contains(
                    "atlas.preflight.slot.diag/masked#0/Fallback/MaskedBlurUnsupported"
                )
            )
        }

        assertTrue(atlasComposite.calls.isEmpty())
        assertEquals(listOf("masked"), perSlotBackdrop.slotIds)
    }

    @Test
    fun slotRunner_enabledFallsBackNoisyAtlasLookupWhenFrameNoiseIsMissing() {
        val noisy = slotPlan(
            id = "missing-noise",
            style = atlasStyle(noiseAlpha = 0.25f)
        )
        val frame = renderFrame(noisy)
        val atlasPipeline = RecordingAtlasPipeline { preflight ->
            atlasOutput(preflight, listOf(lookupEntry(noisy)))
        }
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer()
        val atlasComposite = RecordingAtlasComposite()
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = { atlasPipeline }
        )

        withRecordingSceneTraceCounters { recorder ->
            runner.executeFrame(
                scene = sceneFramebuffer(),
                frame = frame,
                targetSize = frame.rootSize,
                noiseTexture = null
            )

            assertEquals(1, recorder.count("atlas.preflight.eligibleRequest"))
            assertEquals(1, recorder.count("atlas.pipeline.execute"))
            assertEquals(1, recorder.count("atlas.fallback.slot"))
            assertFalse(recorder.atlasRecordNames().contains("atlas.composite.slot"))
        }

        assertTrue(atlasComposite.calls.isEmpty())
        assertEquals(listOf("missing-noise"), perSlotBackdrop.slotIds)
        assertEquals(1, atlasPipeline.releasedOutputs.size)
    }

    @Test
    fun clippedSlotDefersStencilUntilFinalPerSlotSceneComposite() {
        val events = mutableListOf<String>()
        val clipped = slotPlan(
            id = "clipped",
            clipTexture = FakeTexture(id = 57, size = IntSize(width = 12, height = 12)),
            contentTexture = FakeTexture(id = 58, size = IntSize(width = 100, height = 120))
        )
        val runner = runner(
            perSlotBackdrop = RecordingPerSlotBackdropRenderer(events),
            contentComposite = RecordingContentComposite(events),
            stencilClip = RecordingStencilClip(events),
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Disabled,
            atlasPipelineProvider = { null }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = renderFrame(clipped),
            targetSize = IntSize(width = 320, height = 240),
            noiseTexture = null
        )

        assertEquals(
            listOf(
                "backdrop.offscreen:clipped",
                "stencil.enable",
                "backdrop.sceneComposite:clipped",
                "content:clipped",
                "stencil.disable"
            ),
            events
        )
    }

    @Test
    fun clippedSlotDrawsUnclippedWhenStencilSetupFallsBack() {
        val events = mutableListOf<String>()
        val clipped = slotPlan(
            id = "fallback-clipped",
            clipTexture = FakeTexture(id = 65, size = IntSize(width = 12, height = 12)),
            contentTexture = FakeTexture(id = 66, size = IntSize(width = 100, height = 120))
        )
        val runner = runner(
            perSlotBackdrop = RecordingPerSlotBackdropRenderer(events),
            contentComposite = RecordingContentComposite(events),
            stencilClip = RecordingStencilClip(
                events = events,
                result = SceneStencilClipSetupResult.FallbackUnclipped
            ),
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Disabled,
            atlasPipelineProvider = { null }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = renderFrame(clipped),
            targetSize = IntSize(width = 320, height = 240),
            noiseTexture = null
        )

        assertEquals(
            listOf(
                "backdrop.offscreen:fallback-clipped",
                "stencil.fallback",
                "backdrop.sceneComposite:fallback-clipped",
                "content:fallback-clipped"
            ),
            events
        )
    }

    @Test
    fun unclippedSlotDoesNotTouchStencil() {
        val events = mutableListOf<String>()
        val unclipped = slotPlan(
            id = "unclipped",
            contentTexture = FakeTexture(id = 67, size = IntSize(width = 100, height = 120))
        )
        val runner = runner(
            perSlotBackdrop = RecordingPerSlotBackdropRenderer(events),
            contentComposite = RecordingContentComposite(events),
            stencilClip = RecordingStencilClip(events),
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Disabled,
            atlasPipelineProvider = { null }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = renderFrame(unclipped),
            targetSize = IntSize(width = 320, height = 240),
            noiseTexture = null
        )

        assertEquals(
            listOf(
                "backdrop.offscreen:unclipped",
                "backdrop.sceneComposite:unclipped",
                "content:unclipped"
            ),
            events
        )
    }

    @Test
    fun clippedSlotEnablesStencilForContentWhenPerSlotBackdropSkipsComposite() {
        val events = mutableListOf<String>()
        val clipped = slotPlan(
            id = "content-only",
            area = Rect(left = 4f, top = 4f, right = 4.5f, bottom = 80f),
            clipTexture = FakeTexture(id = 59, size = IntSize(width = 12, height = 12)),
            contentTexture = FakeTexture(id = 60, size = IntSize(width = 100, height = 120))
        )
        val runner = runner(
            perSlotBackdrop = RecordingPerSlotBackdropRenderer(events),
            contentComposite = RecordingContentComposite(events),
            stencilClip = RecordingStencilClip(events),
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Disabled,
            atlasPipelineProvider = { null }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = renderFrame(clipped),
            targetSize = IntSize(width = 320, height = 240),
            noiseTexture = null
        )

        assertEquals(
            listOf(
                "backdrop.offscreen:content-only",
                "stencil.enable",
                "content:content-only",
                "stencil.disable"
            ),
            events
        )
    }

    @Test
    fun clippedSlotDisablesStencilWhenBackdropSceneCompositeFails() {
        val events = mutableListOf<String>()
        val clipped = slotPlan(
            id = "failing-clipped",
            clipTexture = FakeTexture(id = 61, size = IntSize(width = 12, height = 12)),
            contentTexture = FakeTexture(id = 62, size = IntSize(width = 100, height = 120))
        )
        val runner = runner(
            perSlotBackdrop = RecordingPerSlotBackdropRenderer(
                events = events,
                failAfterSceneCompositeBegins = true
            ),
            contentComposite = RecordingContentComposite(events),
            stencilClip = RecordingStencilClip(events),
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Disabled,
            atlasPipelineProvider = { null }
        )

        try {
            runner.executeFrame(
                scene = sceneFramebuffer(),
                frame = renderFrame(clipped),
                targetSize = IntSize(width = 320, height = 240),
                noiseTexture = null
            )
            fail("Expected backdrop composite failure")
        } catch (failure: BackdropCompositeFailure) {
            assertEquals("backdrop composite", failure.message)
        }

        assertEquals(
            listOf(
                "backdrop.offscreen:failing-clipped",
                "stencil.enable",
                "backdrop.sceneComposite:failing-clipped",
                "stencil.disable"
            ),
            events
        )
    }

    @Test
    fun clippedSlotDisablesStencilWhenContentCompositeFails() {
        val events = mutableListOf<String>()
        val clipped = slotPlan(
            id = "content-fail",
            clipTexture = FakeTexture(id = 75, size = IntSize(width = 12, height = 12)),
            contentTexture = FakeTexture(id = 76, size = IntSize(width = 100, height = 120))
        )
        val runner = runner(
            perSlotBackdrop = RecordingPerSlotBackdropRenderer(events),
            contentComposite = RecordingContentComposite(events = events, fail = true),
            stencilClip = RecordingStencilClip(events),
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Disabled,
            atlasPipelineProvider = { null }
        )

        try {
            runner.executeFrame(
                scene = sceneFramebuffer(),
                frame = renderFrame(clipped),
                targetSize = IntSize(width = 320, height = 240),
                noiseTexture = null
            )
            fail("Expected content composite failure")
        } catch (failure: ContentCompositeFailure) {
            assertEquals("content composite", failure.message)
        }

        assertEquals(
            listOf(
                "backdrop.offscreen:content-fail",
                "stencil.enable",
                "backdrop.sceneComposite:content-fail",
                "stencil.disable"
            ),
            events
        )
    }

    @Test
    fun clippedSlotUsesAtlasBackdropUnderStencilAndClipsContentWithSameStencil() {
        val events = mutableListOf<String>()
        val clipped = slotPlan(
            id = "clipped-atlas",
            clipTexture = FakeTexture(id = 63, size = IntSize(width = 12, height = 12)),
            contentTexture = FakeTexture(id = 64, size = IntSize(width = 100, height = 120))
        )
        val atlasPipeline = RecordingAtlasPipeline { preflight ->
            atlasOutput(preflight, listOf(lookupEntry(clipped)))
        }
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer(events)
        val atlasComposite = RecordingAtlasComposite(events = events)
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            contentComposite = RecordingContentComposite(events),
            stencilClip = RecordingStencilClip(events),
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = { atlasPipeline }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = renderFrame(clipped),
            targetSize = IntSize(width = 320, height = 240),
            noiseTexture = null
        )

        assertEquals(
            listOf(
                "stencil.enable",
                "atlas.sceneComposite:clipped-atlas",
                "content:clipped-atlas",
                "stencil.disable"
            ),
            events
        )
        assertEquals(listOf("clipped-atlas"), atlasComposite.slotIds)
        assertTrue(perSlotBackdrop.slotIds.isEmpty())
        assertEquals(1, atlasPipeline.releasedOutputs.size)
    }

    @Test
    fun clippedAtlasSlotDrawsUnclippedWhenStencilSetupFallsBack() {
        val events = mutableListOf<String>()
        val clipped = slotPlan(
            id = "unsupported-stencil-atlas",
            clipTexture = FakeTexture(id = 68, size = IntSize(width = 12, height = 12)),
            contentTexture = FakeTexture(id = 69, size = IntSize(width = 100, height = 120))
        )
        val atlasPipeline = RecordingAtlasPipeline { preflight ->
            atlasOutput(preflight, listOf(lookupEntry(clipped)))
        }
        val atlasComposite = RecordingAtlasComposite(events = events)
        val runner = runner(
            contentComposite = RecordingContentComposite(events),
            stencilClip = RecordingStencilClip(
                events = events,
                result = SceneStencilClipSetupResult.FallbackUnclipped
            ),
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = { atlasPipeline }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = renderFrame(clipped),
            targetSize = IntSize(width = 320, height = 240),
            noiseTexture = null
        )

        assertEquals(
            listOf(
                "stencil.fallback",
                "atlas.sceneComposite:unsupported-stencil-atlas",
                "content:unsupported-stencil-atlas"
            ),
            events
        )
        assertEquals(listOf("unsupported-stencil-atlas"), atlasComposite.slotIds)
        assertEquals(1, atlasPipeline.releasedOutputs.size)
    }

    @Test
    fun clippedAtlasSlotDisablesStencilWhenAtlasCompositeFails() {
        val events = mutableListOf<String>()
        val clipped = slotPlan(
            id = "failing-clipped-atlas",
            clipTexture = FakeTexture(id = 70, size = IntSize(width = 12, height = 12)),
            contentTexture = FakeTexture(id = 71, size = IntSize(width = 100, height = 120))
        )
        val atlasPipeline = RecordingAtlasPipeline { preflight ->
            atlasOutput(preflight, listOf(lookupEntry(clipped)))
        }
        val runner = runner(
            contentComposite = RecordingContentComposite(events),
            stencilClip = RecordingStencilClip(events),
            atlasComposite = RecordingAtlasComposite(fail = true, events = events),
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = { atlasPipeline }
        )

        try {
            runner.executeFrame(
                scene = sceneFramebuffer(),
                frame = renderFrame(clipped),
                targetSize = IntSize(width = 320, height = 240),
                noiseTexture = null
            )
            fail("Expected atlas composite failure")
        } catch (failure: AtlasCompositeFailure) {
            assertEquals("atlas composite", failure.message)
        }

        assertEquals(
            listOf(
                "stencil.enable",
                "stencil.disable"
            ),
            events
        )
        assertEquals(1, atlasPipeline.releasedOutputs.size)
    }

    @Test
    fun laterUnclippedAtlasSlotDoesNotReusePreviousClipStencil() {
        val events = mutableListOf<String>()
        val clipped = slotPlan(
            id = "first-clipped-atlas",
            drawIndex = 0,
            clipTexture = FakeTexture(id = 72, size = IntSize(width = 12, height = 12)),
            contentTexture = FakeTexture(id = 73, size = IntSize(width = 100, height = 120))
        )
        val later = slotPlan(
            id = "later-unclipped-atlas",
            drawIndex = 1,
            contentTexture = FakeTexture(id = 74, size = IntSize(width = 100, height = 120))
        )
        val atlasPipeline = RecordingAtlasPipeline { preflight ->
            atlasOutput(preflight, listOf(lookupEntry(clipped), lookupEntry(later)))
        }
        val runner = runner(
            contentComposite = RecordingContentComposite(events),
            stencilClip = RecordingStencilClip(events),
            atlasComposite = RecordingAtlasComposite(events = events),
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = { atlasPipeline }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = renderFrame(clipped, later),
            targetSize = IntSize(width = 320, height = 240),
            noiseTexture = null
        )

        assertEquals(
            listOf(
                "stencil.enable",
                "atlas.sceneComposite:first-clipped-atlas",
                "content:first-clipped-atlas",
                "stencil.disable",
                "atlas.sceneComposite:later-unclipped-atlas",
                "content:later-unclipped-atlas"
            ),
            events
        )
    }

    @Test
    fun slotRunner_enabledDoesNotUseAtlasCompositeWhenLookupIsEmpty() {
        val eligible = slotPlan(id = "eligible")
        val frame = renderFrame(eligible)
        val atlasPipeline = RecordingAtlasPipeline { preflight ->
            atlasOutput(preflight, entries = emptyList())
        }
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer()
        val atlasComposite = RecordingAtlasComposite()
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = { atlasPipeline }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = frame,
            targetSize = frame.rootSize,
            noiseTexture = null
        )

        assertTrue(atlasComposite.calls.isEmpty())
        assertEquals(listOf("eligible"), perSlotBackdrop.slotIds)
        assertEquals(1, atlasPipeline.releasedOutputs.size)
        assertTrue(atlasPipeline.releasedOutputs.single().isEmpty)
    }

    @Test
    fun slotRunner_releasesAtlasOutputWhenAtlasCompositeFails() {
        val eligible = slotPlan(id = "eligible")
        val frame = renderFrame(eligible)
        val preflight = SceneBlurAtlasPipelinePreflightPlanner.Default.plan(frame)
        val atlasOutput = atlasOutput(preflight, listOf(lookupEntry(eligible)))
        val atlasPipeline = RecordingAtlasPipeline(atlasOutput)
        val runner = runner(
            atlasComposite = RecordingAtlasComposite(fail = true),
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = { atlasPipeline }
        )

        try {
            runner.executeFrame(
                scene = sceneFramebuffer(),
                frame = frame,
                targetSize = frame.rootSize,
                noiseTexture = null
            )
            fail("Expected atlas composite failure")
        } catch (failure: AtlasCompositeFailure) {
            assertEquals("atlas composite", failure.message)
        }

        assertEquals(listOf(atlasOutput), atlasPipeline.releasedOutputs)
    }

    @Test
    fun slotRunner_maskedSlotCannotEnableAtlasPipelineWithDiagnosticFlag() {
        val masked = slotPlan(
            id = "masked",
            maskTexture = FakeTexture(id = 812, size = IntSize(width = 64, height = 64))
        )
        val frame = renderFrame(masked)
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer()
        val atlasComposite = RecordingAtlasComposite()
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = {
                error("masked slots must not request atlas pipeline")
            }
        )

        runner.executeFrame(
            scene = sceneFramebuffer(),
            frame = frame,
            targetSize = frame.rootSize,
            noiseTexture = null
        )

        assertTrue(atlasComposite.calls.isEmpty())
        assertEquals(listOf("masked"), perSlotBackdrop.slotIds)
    }

    @Test
    fun slotRunner_enabledEmptyFrameRecordsOnlyPreflightAndNoEligibleCounters() {
        val perSlotBackdrop = RecordingPerSlotBackdropRenderer()
        val atlasComposite = RecordingAtlasComposite()
        var atlasProviderRequested = false
        val runner = runner(
            perSlotBackdrop = perSlotBackdrop,
            atlasComposite = atlasComposite,
            atlasRenderConfig = SceneBlurAtlasRenderConfig.Enabled,
            atlasPipelineProvider = {
                atlasProviderRequested = true
                error("empty frames must not request the atlas pipeline")
            }
        )
        val frame = renderFrame()

        withRecordingSceneTraceCounters { recorder ->
            runner.executeFrame(
                scene = sceneFramebuffer(),
                frame = frame,
                targetSize = frame.rootSize,
                noiseTexture = null
            )

            assertEquals(1, recorder.count("atlas.preflight.frame"))
            assertEquals(1, recorder.count("atlas.preflight.noEligibleFrame"))
            assertEquals(listOf(0L), recorder.values("gauge.atlas.preflight.eligibleRequestCount"))
            assertEquals(listOf(0L), recorder.values("gauge.atlas.preflight.fallbackRequestCount"))
            assertEquals(listOf(0L), recorder.values("gauge.atlas.preflight.skippedRequestCount"))
            assertFalse(recorder.atlasRecordNames().contains("atlas.pipeline.execute"))
            assertFalse(recorder.atlasRecordNames().contains("atlas.copy.batch"))
            assertFalse(recorder.atlasRecordNames().contains("atlas.blur.batch"))
            assertFalse(recorder.atlasRecordNames().contains("atlas.composite.slot"))
            assertFalse(recorder.atlasRecordNames().contains("atlas.fallback.slot"))
        }

        assertFalse(atlasProviderRequested)
        assertTrue(atlasComposite.calls.isEmpty())
        assertTrue(perSlotBackdrop.slotIds.isEmpty())
    }

    @Test
    fun atlasCountersStayInternalAndDoNotRequirePublicApiExposure() {
        val counterSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneTraceCounters.kt"
        )
        val scenePublicBoundary = scenePublicBoundarySource()

        assertTrue(counterSource.contains("internal fun interface SceneTraceCounterRecorder"))
        assertTrue(counterSource.contains("internal fun recordWith("))
        assertFalse(scenePublicBoundary.contains("SceneTraceCounterRecorder"))
        assertFalse(scenePublicBoundary.contains("recordWith("))
        assertFalse(scenePublicBoundary.contains("atlasPreflightComputed"))
        assertFalse(scenePublicBoundary.contains("atlasPipelineExecuted"))
    }

    @Test
    fun appAtlasProofHookStaysDiagnosticOnlyInvisibleAndPrivate() {
        val appSource = sourceFile("app/src/main/java/dev/serhiiyaremych/imla/MainActivity.kt")

        assertTrue(appSource.contains("private fun AtlasDiagnosticProofSlot("))
        assertTrue(appSource.contains("private fun isAtlasDiagnosticProofEnabled()"))
        assertTrue(appSource.contains("private fun isAtlasDiagnosticBuild()"))
        assertTrue(appSource.contains("isAtlasDiagnosticBuild() && Log.isLoggable(ATLAS_DIAGNOSTIC_TAG, Log.DEBUG)"))
        assertTrue(appSource.contains("noiseAlpha = 0f"))
        assertTrue(appSource.contains("blurOpacity = 0f"))
        assertTrue(appSource.contains("tint = Color.Transparent"))
        assertFalse(appSource.contains("public fun AtlasDiagnosticProofSlot"))
    }

    private fun runner(
        perSlotBackdrop: RecordingPerSlotBackdropRenderer = RecordingPerSlotBackdropRenderer(),
        contentComposite: RecordingContentComposite = RecordingContentComposite(),
        stencilClip: RecordingStencilClip = RecordingStencilClip(),
        atlasComposite: RecordingAtlasComposite = RecordingAtlasComposite(),
        atlasRenderConfig: SceneBlurAtlasRenderConfig,
        atlasPreflightPlanner: SceneBlurAtlasPipelinePreflightPlanner =
            SceneBlurAtlasPipelinePreflightPlanner.Default,
        atlasPipelineProvider: () -> SceneBlurAtlasPipeline?
    ): SceneSlotPassRunner {
        return SceneSlotPassRunner(
            commandsProvider = { RenderCommands(RecordingRendererApi()) },
            perSlotBackdropRenderer = perSlotBackdrop,
            contentCompositePass = contentComposite,
            stencilClipPass = stencilClip,
            atlasRenderConfig = atlasRenderConfig,
            atlasPreflightPlanner = atlasPreflightPlanner,
            atlasPipelineProvider = atlasPipelineProvider,
            atlasBackdropCompositePass = atlasComposite
        )
    }

    private fun renderFrame(vararg slots: SceneSlotPlan): SceneRenderFrame {
        val rootSize = IntSize(width = 320, height = 240)
        return SceneRenderFrame(
            generation = 42L,
            rootSize = rootSize,
            rootTexture = FakeTexture(id = 900, size = rootSize),
            committedSlotCount = slots.size,
            reasons = emptySet(),
            requiresNoiseTexture = slots.any { slot -> slot.style.noiseAlpha > 0f },
            slots = slots.toList()
        )
    }

    private fun slotPlan(
        id: String,
        drawIndex: Int = 0,
        maskTexture: Texture2D? = null,
        blurRadiusMask: Texture2D? = maskTexture,
        compositeCoverageMask: Texture2D? = maskTexture,
        clipTexture: Texture2D? = null,
        contentTexture: Texture2D? = null,
        area: Rect = Rect(left = 10f, top = 20f, right = 110f, bottom = 140f),
        style: Style = atlasStyle()
    ): SceneSlotPlan {
        return SceneSlotPlan(
            id = BlurSlotId(id),
            drawIndex = drawIndex,
            debugName = id,
            area = area,
            localRect = Rect(left = 0f, top = 0f, right = 100f, bottom = 120f),
            contentSize = IntSize(width = 100, height = 120),
            transform = Mat4.identity(),
            zIndex = 0f,
            style = style,
            contentTexture = contentTexture,
            hasBlurRadiusMask = blurRadiusMask != null,
            blurRadiusMask = blurRadiusMask,
            compositeCoverageMask = compositeCoverageMask,
            clipTexture = clipTexture,
            dirtyFlags = BlurSlotDirtyFlags()
        )
    }

    private fun atlasStyle(noiseAlpha: Float = 0f): Style {
        return Style.default.copy(
            sigma = 8f,
            algorithm = BlurAlgorithm.GAUSSIAN,
            noiseAlpha = noiseAlpha
        )
    }

    private fun atlasOutput(
        preflight: SceneBlurAtlasPipelinePreflight,
        entries: List<SceneBlurAtlasCompositeLookupEntry>
    ): SceneBlurAtlasPipelineOutput {
        return SceneBlurAtlasPipelineOutput(
            preflight = preflight,
            copyOutput = null,
            blurOutput = null,
            lookupOutput = SceneBlurAtlasCompositeLookupFrameOutput(
                generation = preflight.generation,
                rootSize = preflight.rootSize,
                entries = entries
            )
        )
    }

    private fun lookupEntry(slot: SceneSlotPlan): SceneBlurAtlasCompositeLookupEntry {
        return SceneBlurAtlasCompositeLookupEntry(
            slotId = slot.id,
            drawIndex = slot.drawIndex,
            compatibilityKey = SceneBlurAtlasCompatibilityKey(
                sigma = slot.style.sigma,
                algorithm = slot.style.algorithm
            ),
            blurredAtlasTexture = SceneBlurAtlasTextureHandle(
                textureId = 44,
                size = IntSize(width = 128, height = 128),
                coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
            ),
            sourceCopyRect = SceneBlurAtlasPixelRect(left = 10, top = 20, right = 110, bottom = 140),
            sourceSampleRect = SceneBlurAtlasPixelRect(left = 10, top = 20, right = 110, bottom = 140),
            sourceSampleCrop = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 100, bottom = 120),
            sourceAtlasContentCrop = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 100, bottom = 120),
            sourceAtlasRect = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 100, bottom = 120),
            sourceAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 100, bottom = 120),
            blurredAtlasContentCrop = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 100, bottom = 120),
            blurredAtlasContentUv = SceneBlurAtlasUvRect(left = 0f, top = 0f, right = 100f / 128f, bottom = 120f / 128f),
            blurredAtlasSampleCrop = SceneBlurAtlasPixelRect(left = 0, top = 0, right = 100, bottom = 120),
            copyScale = SceneBlurAtlasCopyScale(x = 1f, y = 1f),
            downsampleScale = 1f
        )
    }

    private fun sceneFramebuffer(): Framebuffer {
        return FakeFramebuffer(
            rendererId = 1,
            specification = framebufferSpec(IntSize(width = 320, height = 240)),
            textureId = 100
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

    private fun scenePublicBoundarySource(): String {
        return listOf(
            sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/ImlaSceneRenderer.kt"),
            sourceFile("imla/src/main/java/dev/serhiiyaremych/imla/internal/host/BlurSurfaceHost.kt")
        ).joinToString(separator = "\n")
    }

    private class RecordingPerSlotBackdropRenderer(
        private val events: MutableList<String>? = null,
        private val failAfterSceneCompositeBegins: Boolean = false
    ) : SceneSlotBackdropRenderer {
        val slotIds = mutableListOf<String>()

        override fun execute(
            scene: Framebuffer,
            slot: SceneSlotPlan,
            transform: Mat4,
            targetSize: IntSize,
            noiseTexture: Texture2D?,
            beforeSceneComposite: () -> Unit
        ) {
            slotIds += slot.id.value
            events?.add("backdrop.offscreen:${slot.id.value}")
            if (slot.area.width > 1f && slot.area.height > 1f) {
                beforeSceneComposite()
                events?.add("backdrop.sceneComposite:${slot.id.value}")
                if (failAfterSceneCompositeBegins) {
                    throw BackdropCompositeFailure("backdrop composite")
                }
            }
        }
    }

    private class RecordingContentComposite(
        private val events: MutableList<String>? = null,
        private val fail: Boolean = false
    ) : SceneContentCompositeExecutor {
        val slotIds = mutableListOf<String>()

        override fun execute(slot: SceneSlotPlan, transform: Mat4, targetSize: IntSize) {
            if (fail) throw ContentCompositeFailure("content composite")
            slotIds += slot.id.value
            events?.add("content:${slot.id.value}")
        }
    }

    private class RecordingStencilClip(
        private val events: MutableList<String>? = null,
        private val result: SceneStencilClipSetupResult = SceneStencilClipSetupResult.Applied
    ) : SceneStencilClipExecutor {
        override fun execute(
            clipTexture: Texture2D,
            transform: Mat4,
            targetSize: IntSize
        ): SceneStencilClipSetupResult {
            events?.add(
                when (result) {
                    SceneStencilClipSetupResult.Applied -> "stencil.enable"
                    SceneStencilClipSetupResult.FallbackUnclipped -> "stencil.fallback"
                }
            )
            return result
        }

        override fun disable() {
            events?.add("stencil.disable")
        }
    }

    private class RecordingAtlasPreflightPlanner : SceneBlurAtlasPipelinePreflightPlanner {
        val preflights = mutableListOf<SceneBlurAtlasPipelinePreflight>()

        override fun plan(frame: SceneRenderFrame): SceneBlurAtlasPipelinePreflight {
            return SceneBlurAtlasPipelinePreflightPlanner.Default.plan(frame).also { preflight ->
                preflights += preflight
            }
        }
    }

    private class RecordingAtlasPipeline(
        private val outputFactory: (SceneBlurAtlasPipelinePreflight) -> SceneBlurAtlasPipelineOutput
    ) : SceneBlurAtlasPipeline {
        constructor(output: SceneBlurAtlasPipelineOutput) : this({ output })

        val events = mutableListOf<String>()
        val preflights = mutableListOf<SceneBlurAtlasPipelinePreflight>()
        val sources = mutableListOf<SceneBlurAtlasCopySource>()
        val releasedOutputs = mutableListOf<SceneBlurAtlasPipelineOutput>()

        override fun execute(
            preflight: SceneBlurAtlasPipelinePreflight,
            source: SceneBlurAtlasCopySource
        ): SceneBlurAtlasPipelineOutput {
            events += "execute"
            preflights += preflight
            sources += source
            return outputFactory(preflight)
        }

        override fun release(output: SceneBlurAtlasPipelineOutput) {
            releasedOutputs += output
        }
    }

    private class RecordingAtlasComposite(
        private val fail: Boolean = false,
        private val events: MutableList<String>? = null
    ) : SceneBlurAtlasBackdropCompositeExecutor {
        val calls = mutableListOf<SceneSlotPlan>()
        val noiseTextures = mutableListOf<Texture2D?>()
        val maskTextures = mutableListOf<Texture2D?>()
        val slotIds: List<String> get() = calls.map { slot -> slot.id.value }

        override fun execute(
            lookup: SceneBlurAtlasCompositeLookupEntry,
            slot: SceneSlotPlan,
            transform: Mat4,
            style: Style,
            targetSize: IntSize,
            noiseTexture: Texture2D?,
            maskTexture: Texture2D?
        ) {
            if (fail) throw AtlasCompositeFailure("atlas composite")
            calls += slot
            noiseTextures += noiseTexture
            maskTextures += maskTexture
            events?.add("atlas.sceneComposite:${slot.id.value}")
        }
    }

    private class FakeFramebuffer(
        override val rendererId: Int,
        override val specification: FramebufferSpecification,
        textureId: Int = rendererId + 1000
    ) : Framebuffer {
        override val colorAttachmentTexture: Texture2D = FakeTexture(
            id = textureId,
            size = specification.size,
            coordinateOrigin = specification.attachmentsSpec.attachments.single().coordinateOrigin
        )

        override fun invalidate() = Unit
        override fun bind(bind: Bind, updateViewport: Boolean) = Unit
        override fun bind(commands: RenderCommands, bind: Bind, updateViewport: Boolean) = Unit
        override fun bindForOverwrite(bind: Bind) = Unit
        override fun bindForOverwrite(commands: RenderCommands, bind: Bind) = Unit
        override fun bindForMipLevel(level: Int, size: IntSize, bind: Bind) = Unit
        override fun bindForMipLevel(commands: RenderCommands, level: Int, size: IntSize, bind: Bind) = Unit
        override fun unbind() = Unit
        override fun unbind(commands: RenderCommands) = Unit
        override fun resize(width: Int, height: Int) = Unit
        override fun invalidateAttachments() = Unit
        override fun clearAttachment(attachmentIndex: Int, value: Int) = Unit
        override fun getColorAttachmentRendererID(index: Int): Int = colorAttachmentTexture.id
        override fun destroy() = Unit
        override fun setColorAttachmentAt(attachmentIndex: Int) = Unit
    }

    private class FakeTexture(
        override val id: Int,
        size: IntSize,
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
    ) : Texture2D() {
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = size.width
        override val height: Int = size.height
        override val specification: Texture.Specification = Texture.Specification(
            size = size,
            coordinateOrigin = coordinateOrigin
        )

        override fun bind(slot: Int) = Unit
        override fun setData(data: Buffer) = Unit
        override fun isLoaded(): Boolean = true
        override fun destroy() = Unit
        override fun generateMipMaps() = Unit
    }

    private class RecordingRendererApi : RendererApi {
        override val colorBufferBit: Int = 1
        override val stencilBufferBit: Int = 2
        override val linearTextureFilter: Int = 3
        override val stencilAlways: Int = 4
        override val stencilEqual: Int = 5
        override val stencilKeep: Int = 6
        override val stencilReplace: Int = 7

        override fun init() = Unit
        override fun setClearColor(color: Color) = Unit
        override fun clear() = Unit
        override fun enableStencilTest() = Unit
        override fun disableStencilTest() = Unit
        override fun stencilFunc(func: Int, ref: Int, mask: Int) = Unit
        override fun stencilOp(sfail: Int, dpfail: Int, dppass: Int) = Unit
        override fun stencilMask(mask: Int) = Unit
        override fun clearStencil(value: Int) = Unit
        override fun enableScissorTest() = Unit
        override fun disableScissorTest() = Unit
        override fun setScissor(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun clear(mask: Int) = Unit
        override fun drawIndexed(vertexArray: VertexArray, indexCount: Int) = Unit
        override fun setViewPort(x: Int, y: Int, width: Int, height: Int) = Unit
        override fun disableDepthTest() = Unit
        override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) = Unit
        override fun enableBlending() = Unit
        override fun disableBlending() = Unit
        override fun bindFramebuffer(bind: Bind, fboId: Int) = Unit
        override fun bindDefaultFramebuffer(bind: Bind) = Unit
        override fun bindDefaultProgram() = Unit
        override fun blitFramebuffer(
            srcX0: Int,
            srcY0: Int,
            srcX1: Int,
            srcY1: Int,
            dstX0: Int,
            dstY0: Int,
            dstX1: Int,
            dstY1: Int,
            mask: Int,
            filter: Int
        ) = Unit

        override fun copyImageSubData(
            srcName: Int,
            srcTarget: Int,
            srcLevel: Int,
            srcX: Int,
            srcY: Int,
            srcZ: Int,
            dstName: Int,
            dstTarget: Int,
            dstLevel: Int,
            dstX: Int,
            dstY: Int,
            dstZ: Int,
            srcWidth: Int,
            srcHeight: Int,
            srcDepth: Int
        ) = Unit
    }

    private class AtlasCompositeFailure(message: String) : RuntimeException(message)
    private class BackdropCompositeFailure(message: String) : RuntimeException(message)
    private class ContentCompositeFailure(message: String) : RuntimeException(message)

    private companion object {
        fun framebufferSpec(size: IntSize): FramebufferSpecification {
            return FramebufferSpecification(
                size = size,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                    coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
                )
            )
        }
    }
}
