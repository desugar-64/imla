/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.romainguy.kotlin.math.Mat4
import dev.serhiiyaremych.imla.internal.legacy.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.Buffer
import java.nio.file.Files
import java.nio.file.Paths

class SceneBlurAtlasPlannerTest {
    @Test
    fun planner_simpleSupportedBlurSlotsAreAtlasEligibleAndPreserveDrawOrder() {
        val compatibleStyle = atlasStyle(sigma = 6f)
        val otherStyle = atlasStyle(sigma = 9f)
        val frame = renderFrame(
            slots = listOf(
                record(id = "first", drawIndex = 3, style = compatibleStyle),
                record(id = "second", drawIndex = 1, style = otherStyle),
                record(id = "third", drawIndex = 8, style = compatibleStyle)
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertEquals(frame.generation, plan.generation)
        assertEquals(frame.rootSize, plan.rootSize)
        assertEquals(listOf(listOf("first", "third"), listOf("second")), plan.batchSlotIds())
        assertEquals(listOf(listOf(3, 8), listOf(1)), plan.batchDrawIndexes())
        assertTrue(plan.fallbacks.isEmpty())
        assertTrue(plan.skipped.isEmpty())
        assertEquals(
            listOf(
                SceneBlurAtlasEligibilityOutcome.AtlasEligible,
                SceneBlurAtlasEligibilityOutcome.AtlasEligible,
                SceneBlurAtlasEligibilityOutcome.AtlasEligible
            ),
            plan.diagnostics.map { diagnostic -> diagnostic.outcome }
        )
        assertEquals(
            listOf(
                compatibleStyle.sigma,
                otherStyle.sigma,
                compatibleStyle.sigma
            ),
            plan.diagnostics.map { diagnostic -> requireNotNull(diagnostic.compatibilityKey).sigma }
        )

        val firstRequest = plan.batches.first().requests.first()
        val firstSlot = frame.slots.first()
        assertEquals(SceneBlurAtlasEligibilityReason.PlainScreenSpaceBlur, firstRequest.eligibilityReason)
        assertEquals(firstSlot.area, firstRequest.sampleArea)
        assertEquals(firstSlot.localRect, firstRequest.localContentArea)
        assertEquals(firstSlot.contentSize, firstRequest.contentSize)
        assertNull(firstRequest.blurRadiusMask)
    }

    @Test
    fun planner_batchesBenchmarkLikePlainSlotsIntoCompatibleAtlasGroups() {
        val sixSigma = atlasStyle(sigma = 6f)
        val eightSigma = atlasStyle(sigma = 8f)
        val tenSigma = atlasStyle(sigma = 10f)
        val frame = renderFrame(
            slots = listOf(
                record(id = "slot-6-a", drawIndex = 0, style = sixSigma),
                record(id = "slot-6-b", drawIndex = 1, style = sixSigma),
                record(id = "slot-8-a", drawIndex = 2, style = eightSigma),
                record(id = "slot-8-b", drawIndex = 3, style = eightSigma),
                record(id = "slot-10-a", drawIndex = 4, style = tenSigma),
                record(id = "slot-10-b", drawIndex = 5, style = tenSigma)
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertEquals(
            listOf(
                listOf("slot-6-a", "slot-6-b"),
                listOf("slot-8-a", "slot-8-b"),
                listOf("slot-10-a", "slot-10-b")
            ),
            plan.batchSlotIds()
        )
        assertEquals(listOf(6f, 8f, 10f), plan.batches.map { batch -> batch.key.sigma })
        assertEquals(
            List(frame.slots.size) { SceneBlurAtlasEligibilityOutcome.AtlasEligible },
            plan.diagnostics.map { diagnostic -> diagnostic.outcome }
        )
        assertTrue(plan.fallbacks.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun planner_batchesOnlyEligibleRequestsAndPreservesMaskFallbackIdentity() {
        val sharedStyle = atlasStyle(sigma = 5f)
        val wideStyle = atlasStyle(sigma = 8f)
        val firstMask = FakeTexture(id = 101)
        val secondMask = FakeTexture(id = 102)
        val maskedFirstId = BlurSlotId("masked-first")
        val maskedSecondId = BlurSlotId("masked-second")
        val frame = renderFrame(
            slots = listOf(
                record(id = "plain", drawIndex = 0, style = sharedStyle),
                record(id = "wide", drawIndex = 1, style = wideStyle),
                record(id = maskedFirstId.value, drawIndex = 2, style = sharedStyle),
                record(id = maskedSecondId.value, drawIndex = 3, style = sharedStyle)
            ),
            resources = mapOf(
                maskedFirstId to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = firstMask,
                    compositeCoverageMask = firstMask,
                    clipTexture = null
                ),
                maskedSecondId to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = secondMask,
                    compositeCoverageMask = secondMask,
                    clipTexture = null
                )
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertEquals(
            listOf(listOf("plain"), listOf("wide")),
            plan.batchSlotIds()
        )
        assertEquals(listOf(5f, 8f), plan.batches.map { batch -> batch.key.sigma })
        assertEquals(listOf("masked-first", "masked-second"), plan.fallbackSlotIds())
        assertEquals(listOf(2, 3), plan.fallbacks.map { fallback -> fallback.drawIndex })
        assertEquals(
            listOf(
                SceneBlurAtlasFallbackReason.MaskedBlurUnsupported,
                SceneBlurAtlasFallbackReason.MaskedBlurUnsupported
            ),
            plan.fallbacks.map { fallback -> fallback.reason }
        )
        assertEquals(listOf(101, 102), plan.fallbacks.map { fallback -> fallback.blurRadiusMask?.textureId })
        assertEquals(listOf(101, 102), plan.diagnostics.takeLast(2).map { diagnostic -> diagnostic.blurRadiusMask?.textureId })
        assertEquals(
            listOf(
                SceneBlurAtlasEligibilityOutcome.AtlasEligible,
                SceneBlurAtlasEligibilityOutcome.AtlasEligible,
                SceneBlurAtlasEligibilityOutcome.Fallback,
                SceneBlurAtlasEligibilityOutcome.Fallback
            ),
            plan.diagnostics.map { diagnostic -> diagnostic.outcome }
        )
    }

    @Test
    fun planner_missingBlurRadiusMaskTextureStillFallsBackAsMaskedUnsupported() {
        val maskedId = BlurSlotId("missing-mask-texture")
        val frame = renderFrame(
            slots = listOf(
                record(
                    id = maskedId.value,
                    drawIndex = 0,
                    blurMask = SolidColor(Color.White)
                )
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertTrue(plan.batches.isEmpty())
        assertEquals(listOf(maskedId.value), plan.fallbackSlotIds())
        assertEquals(SceneBlurAtlasFallbackReason.MaskedBlurUnsupported, plan.fallbacks.single().reason)
        assertNull(plan.fallbacks.single().blurRadiusMask)
        assertNull(plan.diagnostics.single().blurRadiusMask)
    }

    @Test
    fun planner_maskedDiagnosticModeKeepsBlurRadiusMasksOnFallbackUntilParityIsProven() {
        val maskedId = BlurSlotId("masked")
        val maskTexture = FakeTexture(id = 1201)
        val style = atlasStyle(sigma = 7f)
        val frame = renderFrame(
            slots = listOf(
                record(id = "plain", drawIndex = 0, style = style),
                record(id = maskedId.value, drawIndex = 1, style = style)
            ),
            resources = mapOf(
                maskedId to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = maskTexture,
                    compositeCoverageMask = maskTexture,
                    clipTexture = null
                )
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertEquals(listOf(listOf("plain")), plan.batchSlotIds())
        assertEquals(SceneBlurAtlasFallbackReason.MaskedBlurUnsupported, plan.fallbacks.single().reason)
        assertEquals(1201, plan.fallbacks.single().blurRadiusMask?.textureId)
        assertEquals(
            listOf(
                SceneBlurAtlasEligibilityOutcome.AtlasEligible,
                SceneBlurAtlasEligibilityOutcome.Fallback
            ),
            plan.diagnostics.map { diagnostic -> diagnostic.outcome }
        )
    }

    @Test
    fun planner_maskedDiagnosticModeReportsUnsupportedBeforeMissingMaskTexture() {
        val maskedId = BlurSlotId("missing-mask-texture")
        val frame = renderFrame(
            slots = listOf(
                record(
                    id = maskedId.value,
                    drawIndex = 0,
                    blurMask = SolidColor(Color.White)
                )
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertTrue(plan.batches.isEmpty())
        assertEquals(SceneBlurAtlasFallbackReason.MaskedBlurUnsupported, plan.fallbacks.single().reason)
    }

    @Test
    fun planner_allowsCoverageOnlyMasksWithoutChangingGroupingKey() {
        val sharedStyle = atlasStyle(sigma = 5f)
        val coverageOnlyId = BlurSlotId("coverage-only")
        val coverageMask = FakeTexture(id = 103)
        val frame = renderFrame(
            slots = listOf(
                record(id = "plain", drawIndex = 0, style = sharedStyle),
                record(id = coverageOnlyId.value, drawIndex = 1, style = sharedStyle)
            ),
            resources = mapOf(
                coverageOnlyId to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = null,
                    compositeCoverageMask = coverageMask,
                    clipTexture = null
                )
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertEquals(listOf(listOf("plain", "coverage-only")), plan.batchSlotIds())
        assertTrue(plan.fallbacks.isEmpty())
        assertEquals(
            SceneBlurAtlasCompatibilityKey(
                sigma = 5f,
                algorithm = sharedStyle.algorithm
            ),
            plan.batches.single().key
        )
        assertEquals(listOf(null, null), plan.batches.single().requests.map { request -> request.blurRadiusMask })
    }

    @Test
    fun planner_emptyFrameProducesEmptyPlan() {
        val plan = SceneBlurAtlasPlanner.plan(renderFrame(slots = emptyList()))

        assertTrue(plan.batches.isEmpty())
    }

    @Test
    fun planner_frameWithoutDrawableBlurSlotsProducesEmptyPlan() {
        val frame = renderFrame(
            slots = listOf(
                record(
                    id = "missing-geometry",
                    drawIndex = 0,
                    geometry = null
                )
            )
        )

        assertTrue(frame.slots.isEmpty())
        assertTrue(SceneBlurAtlasPlanner.plan(frame).batches.isEmpty())
    }

    @Test
    fun planner_allowsPlainClippedSlotsAndKeepsInvalidSigmaFallback() {
        val clippedId = BlurSlotId("clipped")
        val frame = renderFrame(
            slots = listOf(
                record(id = clippedId.value, drawIndex = 0),
                record(id = "zero-sigma", drawIndex = 1, style = atlasStyle(sigma = 0f)),
                record(id = "nan-sigma", drawIndex = 2, style = atlasStyle(sigma = Float.NaN))
            ),
            resources = mapOf(
                clippedId to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = null,
                    compositeCoverageMask = null,
                    clipTexture = FakeTexture(id = 201)
                )
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertEquals(listOf(listOf("clipped")), plan.batchSlotIds())
        assertEquals(listOf("zero-sigma", "nan-sigma"), plan.fallbackSlotIds())
        assertEquals(
            listOf(
                SceneBlurAtlasFallbackReason.InvalidBlurSigma,
                SceneBlurAtlasFallbackReason.InvalidBlurSigma
            ),
            plan.fallbacks.map { fallback -> fallback.reason }
        )
        assertEquals(
            SceneBlurAtlasCompatibilityKey(
                sigma = Style.default.sigma,
                algorithm = Style.default.algorithm
            ),
            plan.batches.single().key
        )
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun planner_keepsClipTextureOutOfAtlasCompatibilityKeyAndGrouping() {
        val firstClippedId = BlurSlotId("first-clipped")
        val secondClippedId = BlurSlotId("second-clipped")
        val frame = renderFrame(
            slots = listOf(
                record(id = firstClippedId.value, drawIndex = 0),
                record(id = secondClippedId.value, drawIndex = 1),
                record(id = "plain", drawIndex = 2)
            ),
            resources = mapOf(
                firstClippedId to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = null,
                    compositeCoverageMask = null,
                    clipTexture = FakeTexture(id = 401)
                ),
                secondClippedId to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = null,
                    compositeCoverageMask = null,
                    clipTexture = FakeTexture(id = 402)
                )
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertEquals(listOf(listOf("first-clipped", "second-clipped", "plain")), plan.batchSlotIds())
        assertTrue(plan.fallbacks.isEmpty())
        assertEquals(
            SceneBlurAtlasCompatibilityKey(
                sigma = Style.default.sigma,
                algorithm = Style.default.algorithm
            ),
            plan.batches.single().key
        )
    }

    @Test
    fun planner_treatsNoiseOnlySlotsAsAtlasEligibleWithoutChangingGroupingKey() {
        val sharedStyle = atlasStyle(sigma = 6f)
        val noisyStyle = atlasStyle(sigma = 6f, noiseAlpha = 0.27f)
        val frame = renderFrame(
            slots = listOf(
                record(id = "plain", drawIndex = 0, style = sharedStyle),
                record(id = "noisy", drawIndex = 1, style = noisyStyle)
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertEquals(listOf(listOf("plain", "noisy")), plan.batchSlotIds())
        assertTrue(plan.fallbacks.isEmpty())
        assertEquals(
            listOf(
                SceneBlurAtlasEligibilityOutcome.AtlasEligible,
                SceneBlurAtlasEligibilityOutcome.AtlasEligible
            ),
            plan.diagnostics.map { diagnostic -> diagnostic.outcome }
        )
        assertEquals(
            SceneBlurAtlasCompatibilityKey(
                sigma = 6f,
                algorithm = sharedStyle.algorithm
            ),
            plan.batches.single().key
        )
    }

    @Test
    fun planner_keepsNoisyMaskedSlotsOnMaskFallback() {
        val noisyMaskedId = BlurSlotId("noisy-masked")
        val frame = renderFrame(
            slots = listOf(
                record(
                    id = noisyMaskedId.value,
                    drawIndex = 0,
                    style = atlasStyle(noiseAlpha = 0.27f)
                )
            ),
            resources = mapOf(
                noisyMaskedId to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = FakeTexture(id = 301),
                    compositeCoverageMask = FakeTexture(id = 302),
                    clipTexture = null
                )
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertTrue(plan.batches.isEmpty())
        assertEquals(listOf("noisy-masked"), plan.fallbackSlotIds())
        assertEquals(SceneBlurAtlasFallbackReason.MaskedBlurUnsupported, plan.fallbacks.single().reason)
    }

    @Test
    fun planner_keepsClipNoiseCoverageRoutingAndMaskFallbacksUnchanged() {
        val atlasId = BlurSlotId("clip-noise-coverage")
        val maskedId = BlurSlotId("clip-noise-masked")
        val frame = renderFrame(
            slots = listOf(
                record(
                    id = atlasId.value,
                    drawIndex = 0,
                    style = atlasStyle(sigma = 6f, noiseAlpha = 0.27f)
                ),
                record(
                    id = maskedId.value,
                    drawIndex = 1,
                    style = atlasStyle(sigma = 6f, noiseAlpha = 0.27f)
                )
            ),
            resources = mapOf(
                atlasId to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = null,
                    compositeCoverageMask = FakeTexture(id = 501),
                    clipTexture = FakeTexture(id = 502)
                ),
                maskedId to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = FakeTexture(id = 503),
                    compositeCoverageMask = FakeTexture(id = 504),
                    clipTexture = FakeTexture(id = 505)
                )
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertEquals(listOf(listOf(atlasId.value)), plan.batchSlotIds())
        assertEquals(listOf(maskedId.value), plan.fallbackSlotIds())
        assertEquals(SceneBlurAtlasFallbackReason.MaskedBlurUnsupported, plan.fallbacks.single().reason)
        assertEquals(503, plan.fallbacks.single().blurRadiusMask?.textureId)
    }

    @Test
    fun planner_skipsOnlySampleAreasTheExistingBackdropPathCannotRender() {
        val frame = renderFrame(
            rootSize = IntSize(width = 100, height = 100),
            slots = listOf(
                record(id = "zero-width", drawIndex = 0, area = Rect(40f, 40f, 40f, 70f)),
                record(id = "outside-root", drawIndex = 1, area = Rect(-20f, -20f, -10f, -10f))
            )
        )

        val plan = SceneBlurAtlasPlanner.plan(frame)

        assertTrue(plan.batches.isEmpty())
        assertTrue(plan.fallbacks.isEmpty())
        assertEquals(listOf("zero-width", "outside-root"), plan.skippedSlotIds())
        assertEquals(
            listOf(
                SceneBlurAtlasSkipReason.SampleAreaOutsideRoot,
                SceneBlurAtlasSkipReason.SampleAreaOutsideRoot
            ),
            plan.skipped.map { skipped -> skipped.reason }
        )
        assertEquals(
            listOf(
                SceneBlurAtlasEligibilityOutcome.SkippedInvalid,
                SceneBlurAtlasEligibilityOutcome.SkippedInvalid
            ),
            plan.diagnostics.map { diagnostic -> diagnostic.outcome }
        )
    }

    @Test
    fun policy_declaresUnsupportedAlgorithmFallbackUntilASecondAlgorithmIsVerified() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasPlan.kt"
        )

        assertTrue(source.contains("UnsupportedBlurAlgorithm"))
        assertTrue(source.contains("!isSupportedAlgorithm(slot.style.algorithm)"))
        assertTrue(source.contains("algorithm == BlurAlgorithm.GAUSSIAN"))
    }

    @Test
    fun placementPlanner_emptyAtlasPlanProducesEmptyPlacementPlan() {
        val atlasPlan = SceneBlurAtlasPlanner.plan(renderFrame(slots = emptyList()))

        val placementPlan = SceneBlurAtlasPlacementPlanner.plan(atlasPlan)

        assertEquals(atlasPlan.generation, placementPlan.generation)
        assertEquals(atlasPlan.rootSize, placementPlan.rootSize)
        assertTrue(placementPlan.batches.isEmpty())
    }

    @Test
    fun placementPlanner_placesCompatibleRequestsDeterministicallyWithoutOverlap() {
        val style = atlasStyle(sigma = 3f)
        val frame = renderFrame(
            rootSize = IntSize(300, 300),
            slots = listOf(
                record(
                    id = "first",
                    drawIndex = 0,
                    style = style,
                    area = Rect(40f, 40f, 90f, 90f)
                ),
                record(
                    id = "second",
                    drawIndex = 1,
                    style = style,
                    area = Rect(120f, 30f, 180f, 80f)
                )
            )
        )
        val atlasPlan = SceneBlurAtlasPlanner.plan(frame)

        val firstPlacementPlan = SceneBlurAtlasPlacementPlanner.plan(atlasPlan)
        val secondPlacementPlan = SceneBlurAtlasPlacementPlanner.plan(atlasPlan)

        assertEquals(firstPlacementPlan, secondPlacementPlan)

        val batch = firstPlacementPlan.batches.single()
        assertEquals(listOf("first", "second"), batch.placementSlotIds())
        assertEquals(listOf(0, 1), batch.placementDrawIndexes())
        assertFalse(batch.placements[0].atlasRect.overlaps(batch.placements[1].atlasRect))
        batch.placements.forEach { placement ->
            assertTrue(batch.atlasSize.contains(placement.atlasRect))
            assertTrue(placement.atlasRect.contains(placement.atlasSampleCrop))
        }
        assertEquals(IntSize(146, 68), batch.atlasSize)
    }

    @Test
    fun placementPlanner_snapsSampleRectsAndSigmaPaddingToWholePixels() {
        val style = atlasStyle(sigma = 2.25f)
        val frame = renderFrame(
            rootSize = IntSize(200, 200),
            slots = listOf(
                record(
                    id = "fractional",
                    drawIndex = 0,
                    style = style,
                    area = Rect(20.2f, 30.8f, 80.1f, 90.1f)
                )
            )
        )

        val placement = SceneBlurAtlasPlacementPlanner
            .plan(SceneBlurAtlasPlanner.plan(frame))
            .batches
            .single()
            .placements
            .single()

        assertEquals(SceneBlurAtlasPixelRect(20, 30, 81, 91), placement.sourceSampleRect)
        assertEquals(SceneBlurAtlasPixelRect(13, 23, 88, 98), placement.paddedSourceRect)
        assertEquals(SceneBlurAtlasPixelRect(0, 0, 75, 75), placement.atlasRect)
        assertEquals(SceneBlurAtlasPixelRect(7, 7, 68, 68), placement.atlasSampleCrop)
    }

    @Test
    fun placementPlanner_wrapsRowsWithoutChangingRequestOrder() {
        val style = atlasStyle(sigma = 1f)
        val frame = renderFrame(
            rootSize = IntSize(120, 300),
            slots = listOf(
                record(id = "first", drawIndex = 0, style = style, area = Rect(10f, 10f, 70f, 50f)),
                record(id = "second", drawIndex = 1, style = style, area = Rect(20f, 70f, 80f, 110f)),
                record(id = "third", drawIndex = 2, style = style, area = Rect(30f, 130f, 90f, 170f))
            )
        )

        val batch = SceneBlurAtlasPlacementPlanner
            .plan(SceneBlurAtlasPlanner.plan(frame))
            .batches
            .single()

        assertEquals(listOf("first", "second", "third"), batch.placementSlotIds())
        assertEquals(
            listOf(
                SceneBlurAtlasPixelRect(0, 0, 66, 46),
                SceneBlurAtlasPixelRect(0, 46, 66, 92),
                SceneBlurAtlasPixelRect(0, 92, 66, 138)
            ),
            batch.placements.map { placement -> placement.atlasRect }
        )
        assertEquals(IntSize(66, 138), batch.atlasSize)
    }

    @Test
    fun preprocessPlanner_emptyPlacementPlanProducesEmptyPreprocessResult() {
        val placementPlan = SceneBlurAtlasPlacementPlanner.plan(
            SceneBlurAtlasPlanner.plan(renderFrame(slots = emptyList()))
        )

        val preprocessResult = SceneBlurAtlasPreprocessPlanner.plan(placementPlan)

        assertEquals(placementPlan.generation, preprocessResult.generation)
        assertEquals(placementPlan.rootSize, preprocessResult.rootSize)
        assertTrue(preprocessResult.batches.isEmpty())
    }

    @Test
    fun preprocessPlanner_preservesBatchMetadataAndPlacementOrder() {
        val sharedStyle = atlasStyle(sigma = 3f)
        val otherStyle = atlasStyle(sigma = 6f)
        val frame = renderFrame(
            rootSize = IntSize(240, 240),
            slots = listOf(
                record(id = "first", drawIndex = 0, style = sharedStyle, area = Rect(20f, 20f, 70f, 70f)),
                record(id = "second", drawIndex = 1, style = otherStyle, area = Rect(40f, 80f, 100f, 120f)),
                record(id = "third", drawIndex = 2, style = sharedStyle, area = Rect(90f, 20f, 140f, 70f))
            )
        )
        val placementPlan = SceneBlurAtlasPlacementPlanner.plan(SceneBlurAtlasPlanner.plan(frame))

        val preprocessResult = SceneBlurAtlasPreprocessPlanner.plan(placementPlan)

        assertEquals(placementPlan.generation, preprocessResult.generation)
        assertEquals(placementPlan.rootSize, preprocessResult.rootSize)
        assertEquals(placementPlan.batches.map { batch -> batch.key }, preprocessResult.batches.map { batch -> batch.key })
        assertEquals(
            placementPlan.batches.map { batch -> batch.atlasSize },
            preprocessResult.batches.map { batch -> batch.atlasSize }
        )
        assertEquals(listOf(listOf("first", "third"), listOf("second")), preprocessResult.preprocessSlotIds())
        assertEquals(listOf(listOf(0, 2), listOf(1)), preprocessResult.preprocessDrawIndexes())
    }

    @Test
    fun preprocessPlanner_preservesAtlasRectsAndDerivesDeterministicCropMetadata() {
        val style = atlasStyle(sigma = 2.25f)
        val frame = renderFrame(
            rootSize = IntSize(200, 200),
            slots = listOf(
                record(
                    id = "fractional",
                    drawIndex = 0,
                    style = style,
                    area = Rect(20.2f, 30.8f, 80.1f, 90.1f)
                )
            )
        )
        val placementPlan = SceneBlurAtlasPlacementPlanner.plan(SceneBlurAtlasPlanner.plan(frame))

        val firstResult = SceneBlurAtlasPreprocessPlanner.plan(placementPlan)
        val secondResult = SceneBlurAtlasPreprocessPlanner.plan(placementPlan)

        assertEquals(firstResult, secondResult)

        val placement = placementPlan.batches.single().placements.single()
        val preprocessPlacement = firstResult.batches.single().placements.single()
        val request = preprocessPlacement.request
        val output = preprocessPlacement.output

        assertEquals(placement.slotId, request.slotId)
        assertEquals(placement.drawIndex, request.drawIndex)
        assertEquals(placement.sourceSampleRect, request.sourceSampleRect)
        assertEquals(placement.paddedSourceRect, request.sourceCopyRect)
        assertEquals(placement.atlasRect, request.atlasRect)
        assertEquals(placement.atlasSampleCrop, request.atlasSampleCrop)
        assertEquals(placement.atlasRect, output.atlasRect)
        assertEquals(placement.atlasSampleCrop, output.atlasSampleCrop)
        assertEquals(placement.paddedSourceRect, output.sourceCopyRect)
        assertEquals(SceneBlurAtlasPixelRect(7, 7, 68, 68), output.sourceSampleCrop)
        assertEquals(SceneBlurAtlasCopyScale(x = 1f, y = 1f), output.copyScale)
        assertEquals(1f, output.downsampleScale, 0f)
    }

    @Test
    fun atlasMaskMetadataCarriesThroughPlacementAndPreprocessWithoutChangingCropDomain() {
        val maskMetadata = SceneBlurAtlasMaskTextureMetadata(
            textureId = 700,
            size = IntSize(width = 64, height = 32),
            coordinateOrigin = CoordinateOrigin.TOP_LEFT
        )
        val key = SceneBlurAtlasCompatibilityKey(
            sigma = 2.25f,
            algorithm = Style.default.algorithm
        )
        val atlasPlan = SceneBlurAtlasFramePlan(
            generation = 44L,
            rootSize = IntSize(width = 200, height = 200),
            batches = listOf(
                SceneBlurAtlasBatchPlan(
                    key = key,
                    requests = listOf(
                        SceneBlurAtlasRequest(
                            slotId = BlurSlotId("masked"),
                            drawIndex = 6,
                            eligibilityReason = SceneBlurAtlasEligibilityReason.PlainScreenSpaceBlur,
                            sampleArea = Rect(20.2f, 30.8f, 80.1f, 90.1f),
                            localContentArea = Rect(0f, 0f, 45f, 25f),
                            contentSize = IntSize(width = 45, height = 25),
                            blurRadiusMask = maskMetadata,
                            compatibilityKey = key
                        )
                    )
                )
            ),
            fallbacks = emptyList(),
            skipped = emptyList(),
            diagnostics = emptyList()
        )

        val placement = SceneBlurAtlasPlacementPlanner
            .plan(atlasPlan)
            .batches
            .single()
            .placements
            .single()
        val preprocess = SceneBlurAtlasPreprocessPlanner
            .plan(
                SceneBlurAtlasPlacementFramePlan(
                    generation = atlasPlan.generation,
                    rootSize = atlasPlan.rootSize,
                    batches = listOf(
                        SceneBlurAtlasBatchPlacementPlan(
                            key = key,
                            atlasSize = IntSize(width = 128, height = 128),
                            placements = listOf(placement)
                        )
                    )
                )
            )
            .batches
            .single()
            .placements
            .single()

        assertEquals(maskMetadata, placement.blurRadiusMask)
        assertEquals(maskMetadata, preprocess.request.blurRadiusMask)
        assertEquals(maskMetadata, preprocess.output.blurRadiusMask)
        assertEquals(SceneBlurAtlasPixelRect(20, 30, 81, 91), placement.sourceSampleRect)
        assertEquals(SceneBlurAtlasPixelRect(13, 23, 88, 98), placement.paddedSourceRect)
        assertEquals(SceneBlurAtlasPixelRect(7, 7, 68, 68), placement.atlasSampleCrop)
        assertEquals(SceneBlurAtlasPixelRect(7, 7, 68, 68), preprocess.output.sourceSampleCrop)
        assertEquals(placement.atlasSampleCrop, preprocess.output.atlasSampleCrop)
    }

    @Test
    fun atlasPlanStructures_doNotExposeMutableProperties() {
        atlasPlanTypes.forEach { type ->
            type.declaredFields
                .filterNot { field -> field.isSynthetic }
                .forEach { field ->
                    assertTrue("${type.simpleName}.${field.name} must be final", Modifier.isFinal(field.modifiers))
                    assertFalse("${type.simpleName}.${field.name} must not expose arrays", field.type.isArray)
                    assertFalse(
                        "${type.simpleName}.${field.name} must not expose mutable types",
                        field.genericType.typeName.contains("Mutable")
                    )
                }

            assertFalse(
                "${type.simpleName} must not expose setters",
                type.methods.any { method -> method.name.startsWith("set") }
            )
        }
    }

    @Test
    fun atlasPreprocessSource_doesNotReferenceExecutionOrOwnershipTypes() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasPreprocessPlan.kt"
        )
        val forbiddenNames = listOf(
            "RenderObject",
            "Framebuffer",
            "EffectPipeline",
            "SceneBackdropPreprocessPass",
            "SceneBlurPass",
            "SceneBackdropCompositePass",
            "SceneSlotPassRunner",
            "SceneGlRenderer",
            "GraphicsLayer",
            "SceneLayerRepository",
            "SceneMaskRepository",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "SceneResourceStore",
            "GLRenderer",
            "Texture2D",
            "GraphicsContext",
            "Renderer2D",
            "ShaderLibrary",
            "ShaderBinder",
            "StencilClipRenderer",
            "FramebufferLendingPool",
            "RenderCommand",
            "SimpleQuadRenderer"
        )

        forbiddenNames.forEach { forbiddenName ->
            assertFalse(
                "SceneBlurAtlasPreprocessPlan.kt must not reference $forbiddenName",
                source.contains(forbiddenName)
            )
        }
    }

    @Test
    fun atlasPlanSource_doesNotReferenceExecutionOrOwnershipTypes() {
        val source = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneBlurAtlasPlan.kt"
        )
        val forbiddenNames = listOf(
            "RenderObject",
            "Framebuffer",
            "EffectPipeline",
            "SceneBackdropPreprocessPass",
            "SceneBlurPass",
            "SceneBackdropCompositePass",
            "SceneSlotPassRunner",
            "SceneGlRenderer",
            "GraphicsLayer",
            "SceneLayerRepository",
            "SceneMaskRepository",
            "ImlaSceneCoordinator",
            "ImlaSceneRenderer",
            "ImlaSceneSession",
            "SceneResourceStore",
            "GLRenderer"
        )

        forbiddenNames.forEach { forbiddenName ->
            assertFalse(
                "SceneBlurAtlasPlan.kt must not reference $forbiddenName",
                source.contains(forbiddenName)
            )
        }
    }

    @Test
    fun renderingPathUsesAtlasPlanningOnlyBehindInternalFlagGate() {
        val slotRunnerSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneSlotPassRunner.kt"
        )
        val rendererSource = sourceFile(
            "imla/src/main/java/dev/serhiiyaremych/imla/internal/legacy/scene/SceneGlRenderer.kt"
        )
        val scenePublicBoundary = scenePublicBoundarySource()

        assertTrue(slotRunnerSource.contains("if (!atlasRenderConfig.enabled) return null"))
        assertFalse(slotRunnerSource.contains("atlasRenderConfig.blurRadiusMasksEnabled"))
        assertTrue(slotRunnerSource.contains("atlasPreflightPlanner.plan(frame)"))
        assertFalse(slotRunnerSource.contains("SceneBlurAtlasPlanner.plan(this)"))
        assertTrue(rendererSource.contains("SceneBlurAtlasDiagnosticMode.renderConfig()"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasPlanner"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasRenderConfig"))
        assertFalse(scenePublicBoundary.contains("SceneBlurAtlasDiagnosticMode"))
    }

    @Test
    fun classify_plainGaussianBlurWithoutMask_isAtlasEligible() {
        val slot = SceneSlotPlan(
            id = BlurSlotId("benchmark-like"),
            drawIndex = 0,
            debugName = "benchmark-like",
            area = Rect(10f, 20f, 110f, 140f),
            localRect = Rect(0f, 0f, 100f, 120f),
            contentSize = IntSize(100, 120),
            transform = Mat4.identity(),
            zIndex = 0f,
            style = atlasStyle(sigma = 8f),
            contentTexture = null,
            hasBlurRadiusMask = false,
            blurRadiusMask = null,
            compositeCoverageMask = null,
            clipTexture = null,
            dirtyFlags = BlurSlotDirtyFlags()
        )
        val rootSize = IntSize(300, 400)

        val classification = SceneBlurAtlasEligibilityPolicy.classify(slot, rootSize)

        assertTrue(
            "Plain gaussian blur without a mask must be atlas-eligible",
            classification is SceneBlurAtlasSlotClassification.Eligible
        )
        val eligible = classification as SceneBlurAtlasSlotClassification.Eligible
        assertEquals(
            SceneBlurAtlasEligibilityReason.PlainScreenSpaceBlur,
            eligible.reason
        )
    }

    @Test
    fun classify_slotWithBlurRadiusMaskTrue_fallsBackAsMaskedUnsupported() {
        val slot = SceneSlotPlan(
            id = BlurSlotId("masked"),
            drawIndex = 0,
            debugName = "masked",
            area = Rect(10f, 20f, 110f, 140f),
            localRect = Rect(0f, 0f, 100f, 120f),
            contentSize = IntSize(100, 120),
            transform = Mat4.identity(),
            zIndex = 0f,
            style = atlasStyle(sigma = 8f),
            contentTexture = null,
            hasBlurRadiusMask = true,
            blurRadiusMask = null,
            compositeCoverageMask = null,
            clipTexture = null,
            dirtyFlags = BlurSlotDirtyFlags()
        )
        val rootSize = IntSize(300, 400)

        val classification = SceneBlurAtlasEligibilityPolicy.classify(slot, rootSize)

        assertTrue(
            "Slot with hasBlurRadiusMask=true must fall back",
            classification is SceneBlurAtlasSlotClassification.Fallback
        )
        val fallback = classification as SceneBlurAtlasSlotClassification.Fallback
        assertEquals(
            SceneBlurAtlasFallbackReason.MaskedBlurUnsupported,
            fallback.decision.fallbackReason
        )
    }

    @Test
    fun framePlanner_slotWithNullBlurMaskAndNullBlurRadiusMask_producesHasBlurRadiusMaskFalse() {
        val record = BlurSlotRecord(
            id = BlurSlotId("plain"),
            drawIndex = 0,
            debugName = "plain",
            geometry = geometry(Rect(10f, 20f, 110f, 140f), Rect(0f, 0f, 100f, 120f), 0f),
            style = BlurSlotStyleRecord(
                style = atlasStyle(),
                blurMask = null
            ),
            content = null,
            dirtyFlags = BlurSlotDirtyFlags()
        )
        val resources = SceneResolvedResources(
            rootTexture = FakeTexture(id = 900),
            slots = mapOf(
                BlurSlotId("plain") to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = null,
                    compositeCoverageMask = null,
                    clipTexture = null
                )
            )
        )

        val frame = SceneFramePlanner.plan(
            frame = CommittedSceneFrame(
                generation = 42L,
                rootSize = IntSize(300, 400),
                slots = listOf(record),
                reasons = emptySet()
            ),
            resources = resources
        )

        assertEquals(1, frame.slots.size)
        assertFalse(
            "Plain slot with null blurMask and null blurRadiusMask must have hasBlurRadiusMask=false",
            frame.slots.single().hasBlurRadiusMask
        )
    }

    @Test
    fun framePlanner_slotWithNonNullBlurMaskAndNullBlurRadiusMask_producesHasBlurRadiusMaskTrue() {
        val record = BlurSlotRecord(
            id = BlurSlotId("mask-requested"),
            drawIndex = 0,
            debugName = "mask-requested",
            geometry = geometry(Rect(10f, 20f, 110f, 140f), Rect(0f, 0f, 100f, 120f), 0f),
            style = BlurSlotStyleRecord(
                style = atlasStyle(),
                blurMask = SolidColor(Color.White)
            ),
            content = null,
            dirtyFlags = BlurSlotDirtyFlags()
        )
        val resources = SceneResolvedResources(
            rootTexture = FakeTexture(id = 900),
            slots = mapOf(
                BlurSlotId("mask-requested") to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = null,
                    compositeCoverageMask = null,
                    clipTexture = null
                )
            )
        )

        val frame = SceneFramePlanner.plan(
            frame = CommittedSceneFrame(
                generation = 42L,
                rootSize = IntSize(300, 400),
                slots = listOf(record),
                reasons = emptySet()
            ),
            resources = resources
        )

        assertEquals(1, frame.slots.size)
        assertTrue(
            "Slot with non-null blurMask must have hasBlurRadiusMask=true even when blurRadiusMask is null",
            frame.slots.single().hasBlurRadiusMask
        )
    }

    @Test
    fun framePlanner_slotWithNullBlurMaskAndNonNullBlurRadiusMask_fallsBackAsMaskedUnsupported() {
        val maskTexture = FakeTexture(id = 201)
        val record = BlurSlotRecord(
            id = BlurSlotId("leaked-mask"),
            drawIndex = 0,
            debugName = "leaked-mask",
            geometry = geometry(Rect(10f, 20f, 110f, 140f), Rect(0f, 0f, 100f, 120f), 0f),
            style = BlurSlotStyleRecord(
                style = atlasStyle(),
                blurMask = null
            ),
            content = null,
            dirtyFlags = BlurSlotDirtyFlags()
        )
        val resources = SceneResolvedResources(
            rootTexture = FakeTexture(id = 900),
            slots = mapOf(
                BlurSlotId("leaked-mask") to SceneResolvedSlotResources(
                    contentTexture = null,
                    blurRadiusMask = maskTexture,
                    compositeCoverageMask = maskTexture,
                    clipTexture = null
                )
            )
        )

        val frame = SceneFramePlanner.plan(
            frame = CommittedSceneFrame(
                generation = 42L,
                rootSize = IntSize(300, 400),
                slots = listOf(record),
                reasons = emptySet()
            ),
            resources = resources
        )

        assertEquals(1, frame.slots.size)
        assertTrue(
            "Slot with null blurMask but non-null resolved blurRadiusMask must have hasBlurRadiusMask=true",
            frame.slots.single().hasBlurRadiusMask
        )
    }

    private fun SceneBlurAtlasFramePlan.batchSlotIds(): List<List<String>> {
        return batches.map { batch ->
            batch.requests.map { request -> request.slotId.value }
        }
    }

    private fun SceneBlurAtlasFramePlan.batchDrawIndexes(): List<List<Int>> {
        return batches.map { batch ->
            batch.requests.map { request -> request.drawIndex }
        }
    }

    private fun SceneBlurAtlasFramePlan.fallbackSlotIds(): List<String> {
        return fallbacks.map { fallback -> fallback.slotId.value }
    }

    private fun SceneBlurAtlasFramePlan.skippedSlotIds(): List<String> {
        return skipped.map { skipped -> skipped.slotId.value }
    }

    private fun SceneBlurAtlasBatchPlacementPlan.placementSlotIds(): List<String> {
        return placements.map { placement -> placement.slotId.value }
    }

    private fun SceneBlurAtlasBatchPlacementPlan.placementDrawIndexes(): List<Int> {
        return placements.map { placement -> placement.drawIndex }
    }

    private fun SceneBlurAtlasPreprocessFrameResult.preprocessSlotIds(): List<List<String>> {
        return batches.map { batch ->
            batch.placements.map { placement -> placement.request.slotId.value }
        }
    }

    private fun SceneBlurAtlasPreprocessFrameResult.preprocessDrawIndexes(): List<List<Int>> {
        return batches.map { batch ->
            batch.placements.map { placement -> placement.request.drawIndex }
        }
    }

    private fun SceneBlurAtlasPixelRect.overlaps(other: SceneBlurAtlasPixelRect): Boolean {
        return left < other.right &&
            right > other.left &&
            top < other.bottom &&
            bottom > other.top
    }

    private fun SceneBlurAtlasPixelRect.contains(other: SceneBlurAtlasPixelRect): Boolean {
        return left <= other.left &&
            top <= other.top &&
            right >= other.right &&
            bottom >= other.bottom
    }

    private fun IntSize.contains(rect: SceneBlurAtlasPixelRect): Boolean {
        return rect.left >= 0 &&
            rect.top >= 0 &&
            rect.right <= width &&
            rect.bottom <= height
    }

    private fun renderFrame(
        rootSize: IntSize = IntSize(300, 400),
        slots: List<BlurSlotRecord>,
        resources: Map<BlurSlotId, SceneResolvedSlotResources> = emptyMap()
    ): SceneRenderFrame {
        return SceneFramePlanner.plan(
            frame = CommittedSceneFrame(
                generation = 42L,
                rootSize = rootSize,
                slots = slots,
                reasons = emptySet()
            ),
            resources = SceneResolvedResources(
                rootTexture = FakeTexture(id = 900),
                slots = resources
            )
        )
    }

    private fun record(
        id: String,
        drawIndex: Int,
        style: Style = atlasStyle(),
        area: Rect = Rect(10f, 20f, 110f, 140f),
        localRect: Rect = Rect(0f, 0f, 100f, 120f),
        zIndex: Float = 0f,
        blurMask: Brush? = null,
        geometry: BlurSlotGeometry? = geometry(area, localRect, zIndex)
    ): BlurSlotRecord {
        return BlurSlotRecord(
            id = BlurSlotId(id),
            drawIndex = drawIndex,
            debugName = id,
            geometry = geometry,
            style = BlurSlotStyleRecord(
                style = style,
                blurMask = blurMask
            ),
            content = null,
            dirtyFlags = BlurSlotDirtyFlags()
        )
    }

    private fun atlasStyle(
        sigma: Float = Style.default.sigma,
        noiseAlpha: Float = 0f
    ): Style {
        return Style.default.copy(
            sigma = sigma,
            noiseAlpha = noiseAlpha
        )
    }

    private fun geometry(
        area: Rect,
        localRect: Rect,
        zIndex: Float
    ): BlurSlotGeometry {
        return BlurSlotGeometry(
            area = area,
            localRect = localRect,
            contentOffset = Offset.Zero,
            transformMatrix = FloatArray(16),
            zIndex = zIndex
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

    private class FakeTexture(
        override val id: Int
    ) : Texture2D() {
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = 10
        override val height: Int = 10
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.TOP_LEFT
        override val specification: Texture.Specification = Texture.Specification(size = IntSize(10, 10))

        override fun bind(slot: Int) = Unit
        override fun setData(data: Buffer) = Unit
        override fun isLoaded(): Boolean = true
        override fun destroy() = Unit
        override fun generateMipMaps() = Unit
    }

    private companion object {
        val atlasPlanTypes: List<Class<*>> = listOf(
            SceneBlurAtlasFramePlan::class.java,
            SceneBlurAtlasBatchPlan::class.java,
            SceneBlurAtlasRequest::class.java,
            SceneBlurAtlasMaskTextureMetadata::class.java,
            SceneBlurAtlasCompatibilityKey::class.java,
            SceneBlurAtlasFallbackRequest::class.java,
            SceneBlurAtlasSkippedRequest::class.java,
            SceneBlurAtlasEligibilityDecision::class.java,
            SceneBlurAtlasSlotClassification.Eligible::class.java,
            SceneBlurAtlasSlotClassification.Fallback::class.java,
            SceneBlurAtlasSlotClassification.Skipped::class.java,
            SceneBlurAtlasPlacementFramePlan::class.java,
            SceneBlurAtlasBatchPlacementPlan::class.java,
            SceneBlurAtlasRequestPlacement::class.java,
            SceneBlurAtlasPixelRect::class.java,
            SceneBlurAtlasPreprocessFrameResult::class.java,
            SceneBlurAtlasBatchPreprocessResult::class.java,
            SceneBlurAtlasPreprocessRequest::class.java,
            SceneBlurAtlasPreprocessOutput::class.java,
            SceneBlurAtlasPreprocessPlacement::class.java,
            SceneBlurAtlasCopyScale::class.java
        )
    }
}
