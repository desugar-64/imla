/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.GlTextureFrame
import dev.serhiiyaremych.imla.internal.legacy.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.Buffer

class SceneModelTest {
    @Test
    fun dirtyFlags_mergeReasons() {
        val geometry = BlurSlotDirtyFlags().with(BlurSlotDirtyReason.Geometry)
        val content = BlurSlotDirtyFlags().with(BlurSlotDirtyReason.Content)

        val merged = geometry.merge(content)

        assertTrue(merged.isDirty)
        assertEquals(
            setOf(BlurSlotDirtyReason.Geometry, BlurSlotDirtyReason.Content),
            merged.reasons
        )
    }

    @Test
    fun dirtyFlags_cleanHasNoReasons() {
        assertFalse(BlurSlotDirtyFlags.Clean.isDirty)
        assertEquals(emptySet<BlurSlotDirtyReason>(), BlurSlotDirtyFlags.Clean.reasons)
    }

    @Test
    fun registry_snapshotSortsByZThenDrawIndex() {
        val registry = BlurSlotRegistry()

        registry.update(record(id = "late", drawIndex = 2, zIndex = 5f))
        registry.update(record(id = "early", drawIndex = 0, zIndex = 1f))
        registry.update(record(id = "middle", drawIndex = 1, zIndex = 5f))

        val ids = registry.snapshot().map { it.id.value }

        assertEquals(listOf("early", "middle", "late"), ids)
    }

    @Test
    fun captureTransaction_nextDrawIndexFollowsSubmittedRecords() {
        val transaction = CaptureTransaction(generation = 1L, rootSize = IntSize(100, 100))
        assertEquals(0, transaction.nextDrawIndex())

        transaction.records.add(record(id = "slot", drawIndex = transaction.nextDrawIndex(), zIndex = 0f))

        assertEquals(1, transaction.nextDrawIndex())
    }

    @Test
    fun scheduler_coalescesRequestsBeforeRenderStarts() {
        var renderRequests = 0
        val scheduler = SceneRenderScheduler {
            renderRequests += 1
            true
        }

        scheduler.request(RenderReason.RootCaptured)
        scheduler.request(RenderReason.SlotChanged)

        assertEquals(1, renderRequests)
        scheduler.onRenderStarted()
        assertEquals(
            setOf(RenderReason.RootCaptured, RenderReason.SlotChanged),
            scheduler.consumePendingReasons()
        )
        scheduler.onRenderFinished()
        assertEquals(1, renderRequests)
    }

    @Test
    fun scheduler_requestsFollowUpForDirtyReasonsDuringRender() {
        var renderRequests = 0
        val scheduler = SceneRenderScheduler {
            renderRequests += 1
            true
        }

        scheduler.request(RenderReason.RootCaptured)
        scheduler.onRenderStarted()
        assertEquals(setOf(RenderReason.RootCaptured), scheduler.consumePendingReasons())

        scheduler.request(RenderReason.SlotChanged)

        assertEquals(1, renderRequests)
        scheduler.onRenderFinished()
        assertEquals(2, renderRequests)
        scheduler.onRenderStarted()
        assertEquals(setOf(RenderReason.SlotChanged), scheduler.consumePendingReasons())
    }

    @Test
    fun scheduler_cancelAllowsLaterRenderRequest() {
        var renderRequests = 0
        val scheduler = SceneRenderScheduler {
            renderRequests += 1
            true
        }

        scheduler.request(RenderReason.SurfaceChanged)
        scheduler.cancelRenderRequest()
        scheduler.request(RenderReason.RootCaptured)

        assertEquals(2, renderRequests)
    }

    @Test
    fun registry_refreshGeometryCommitsExplicitGeometryDirty() {
        val registry = BlurSlotRegistry()
        val id = BlurSlotId("slot")
        val geometry = geometry()
        registry.register(
            id = id,
            debugName = "slot",
            geometryProvider = BlurSlotGeometryProvider { geometry }
        )
        registry.update(record(id = id.value, geometry = geometry))
        registry.markDirty(id, BlurSlotDirtyReason.Geometry)

        assertTrue(registry.refreshGeometry())
    }

    @Test
    fun dirtyDecider_firstRecordMarksFullDirty() {
        val dirtyFlags = BlurSlotDirtyDecider.dirtyFlags(
            previous = null,
            next = record(id = "slot")
        )

        assertEquals(BlurSlotDirtyReason.entries.toSet(), dirtyFlags.reasons)
    }

    @Test
    fun dirtyDecider_styleOnlyChangeMarksStyle() {
        val previous = record(id = "slot")
        val next = previous.copy(
            style = previous.style.copy(
                style = Style.default.copy(sigma = 4f)
            )
        )

        assertEquals(
            setOf(BlurSlotDirtyReason.Style),
            BlurSlotDirtyDecider.dirtyFlags(previous, next).reasons
        )
    }

    @Test
    fun dirtyDecider_geometryOnlyChangeMarksGeometry() {
        val previous = record(id = "slot")
        val next = previous.copy(geometry = geometry(width = 12f))

        assertEquals(
            setOf(BlurSlotDirtyReason.Geometry),
            BlurSlotDirtyDecider.dirtyFlags(previous, next).reasons
        )
    }

    @Test
    fun dirtyDecider_maskOrClipChangeMarksMask() {
        val previous = record(id = "slot")
        val maskChanged = previous.copy(
            style = previous.style.copy(
                blurMask = Brush.verticalGradient(listOf(Color.Black, Color.White))
            )
        )
        val clipChanged = previous.copy(
            style = previous.style.copy(
                clipShape = RoundedCornerShape(12.dp)
            )
        )

        assertEquals(
            setOf(BlurSlotDirtyReason.Mask),
            BlurSlotDirtyDecider.dirtyFlags(previous, maskChanged).reasons
        )
        assertEquals(
            setOf(BlurSlotDirtyReason.Mask),
            BlurSlotDirtyDecider.dirtyFlags(previous, clipChanged).reasons
        )
    }

    @Test
    fun dirtyDecider_contentSizeChangeMarksContentIdentityChanged() {
        assertTrue(
            BlurSlotDirtyDecider.contentIdentityChanged(
                firstLayer = null,
                firstSize = IntSize(10, 10),
                secondLayer = null,
                secondSize = IntSize(20, 10)
            )
        )
    }

    @Test
    fun dirtyDecider_sameContentIdentityDoesNotMarkContentIdentityChanged() {
        val layer = Any()

        assertFalse(
            BlurSlotDirtyDecider.contentIdentityChanged(
                firstLayer = layer,
                firstSize = IntSize(10, 10),
                secondLayer = layer,
                secondSize = IntSize(10, 10)
            )
        )
    }

    @Test
    fun dirtyDecider_drawIndexChangeMarksOrder() {
        val previous = record(id = "slot", drawIndex = 0)
        val next = previous.copy(drawIndex = 1)

        assertEquals(
            setOf(BlurSlotDirtyReason.Order),
            BlurSlotDirtyDecider.dirtyFlags(previous, next).reasons
        )
    }

    @Test
    fun registry_updateComputesDirtyFlagsFromPreviousRecord() {
        val registry = BlurSlotRegistry()
        val initial = record(id = "slot")
        registry.update(initial)
        registry.clearDirty()

        registry.update(
            initial.copy(
                style = initial.style.copy(
                    style = Style.default.copy(noiseAlpha = 0.4f)
                )
            )
        )

        assertEquals(
            setOf(BlurSlotDirtyReason.Style),
            registry.snapshot().single().dirtyFlags.reasons
        )
    }

    @Test
    fun registry_cleanUpdateReportsNoDirtyAfterStyleDirtyIsCleared() {
        val registry = BlurSlotRegistry()
        val initial = record(id = "slot", drawIndex = 7)

        registry.update(initial)
        registry.clearDirty()

        val styleUpdate = initial.copy(
            style = initial.style.copy(
                style = Style.default.copy(noiseAlpha = 0.4f)
            ),
            dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Style))
        )

        assertEquals(
            setOf(BlurSlotDirtyReason.Style),
            registry.update(styleUpdate).reasons
        )
        registry.clearDirty()
        assertFalse(registry.update(styleUpdate.copy(dirtyFlags = BlurSlotDirtyFlags.Clean)).isDirty)
    }

    @Test
    fun registry_updateMergesExplicitContentDirtyReason() {
        val registry = BlurSlotRegistry()
        val initial = record(id = "slot")
        registry.update(initial)
        registry.clearDirty()

        val dirtyFlags = registry.update(
            initial.copy(dirtyFlags = BlurSlotDirtyFlags.Clean.with(BlurSlotDirtyReason.Content))
        )

        assertEquals(setOf(BlurSlotDirtyReason.Content), dirtyFlags.reasons)
    }

    @Test
    fun capturePolicy_styleOnlyChangeReusesValidMaskTextures() {
        val dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Style))
        val existingFrame = validFrame(IntSize(10, 10))

        assertFalse(
            SceneCapturePolicy.shouldCaptureSlotTexture(
                dirtyFlags = dirtyFlags,
                dirtyReason = BlurSlotDirtyReason.Mask,
                requiredSize = IntSize(10, 10),
                existingFrame = existingFrame
            )
        )
    }

    @Test
    fun capturePolicy_contentDirtyRecapturesValidTexture() {
        val dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Content))
        val existingFrame = validFrame(IntSize(10, 10))

        assertTrue(
            SceneCapturePolicy.shouldCaptureSlotTexture(
                dirtyFlags = dirtyFlags,
                dirtyReason = BlurSlotDirtyReason.Content,
                requiredSize = IntSize(10, 10),
                existingFrame = existingFrame
            )
        )
    }

    @Test
    fun capturePolicy_geometryOnlyTranslationReusesValidTextures() {
        val dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Geometry))
        val existingFrame = validFrame(IntSize(10, 10))

        assertFalse(
            SceneCapturePolicy.shouldCaptureSlotTexture(
                dirtyFlags = dirtyFlags,
                dirtyReason = BlurSlotDirtyReason.Content,
                requiredSize = IntSize(10, 10),
                existingFrame = existingFrame
            )
        )
        assertFalse(
            SceneCapturePolicy.shouldCaptureSlotTexture(
                dirtyFlags = dirtyFlags,
                dirtyReason = BlurSlotDirtyReason.Mask,
                requiredSize = IntSize(10, 10),
                existingFrame = existingFrame
            )
        )
    }

    @Test
    fun capturePolicy_sizeChangeRecaptures() {
        val dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Geometry))
        val previousSizeFrame = validFrame(IntSize(10, 10))

        assertTrue(
            SceneCapturePolicy.shouldCaptureSlotTexture(
                dirtyFlags = dirtyFlags,
                dirtyReason = BlurSlotDirtyReason.Content,
                requiredSize = IntSize(20, 10),
                existingFrame = previousSizeFrame
            )
        )
        assertTrue(
            SceneCapturePolicy.shouldCaptureSlotTexture(
                dirtyFlags = dirtyFlags,
                dirtyReason = BlurSlotDirtyReason.Mask,
                requiredSize = IntSize(20, 10),
                existingFrame = previousSizeFrame
            )
        )
    }

    @Test
    fun capturePolicy_missingPriorTextureRecaptures() {
        val dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Geometry))

        assertTrue(
            SceneCapturePolicy.shouldCaptureSlotTexture(
                dirtyFlags = dirtyFlags,
                dirtyReason = BlurSlotDirtyReason.Content,
                requiredSize = IntSize(10, 10),
                existingFrame = null
            )
        )
        assertTrue(
            SceneCapturePolicy.shouldCaptureSlotTexture(
                dirtyFlags = dirtyFlags,
                dirtyReason = BlurSlotDirtyReason.Mask,
                requiredSize = IntSize(10, 10),
                existingFrame = null
            )
        )
    }

    @Test
    fun capturePolicy_geometryOnlyContentKeyChangeUsesTwoFrameBudget() {
        val firstGeometry = geometry()
        val movedGeometry = firstGeometry.copy(contentOffset = Offset(4f, 0f))
        val slot = record(
            id = "slot",
            geometry = movedGeometry,
            dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Geometry))
        )
        val existingFrame = validFrame(SceneCapturePolicy.slotTextureSize(firstGeometry))
        val freshness = ContentCaptureFreshnessRecord(
            captureKey = ContentCaptureKey(firstGeometry.localRect, firstGeometry.contentOffset)
        )

        assertEquals(
            SlotContentCaptureDecision.ReuseStaleGeometry,
            SceneCapturePolicy.slotContentCaptureDecision(slot, existingFrame, freshness)
        )
        assertEquals(
            SlotContentCaptureDecision.ForceAfterBudget,
            SceneCapturePolicy.slotContentCaptureDecision(
                slot,
                existingFrame,
                freshness.copy(geometryOnlyReuseFrames = 2)
            )
        )
    }

    @Test
    fun capturePolicy_contentOrStyleDirtyCapturesImmediately() {
        val geometry = geometry()
        val existingFrame = validFrame(SceneCapturePolicy.slotTextureSize(geometry))
        val freshness = ContentCaptureFreshnessRecord(
            captureKey = ContentCaptureKey(geometry.localRect, geometry.contentOffset)
        )

        listOf(BlurSlotDirtyReason.Content, BlurSlotDirtyReason.Style).forEach { reason ->
            val slot = record(
                id = "slot",
                geometry = geometry,
                dirtyFlags = BlurSlotDirtyFlags(setOf(reason))
            )

            assertEquals(
                SlotContentCaptureDecision.Capture,
                SceneCapturePolicy.slotContentCaptureDecision(slot, existingFrame, freshness)
            )
        }
    }

    @Test
    fun capturePolicy_noGeometryNeverCapturesContent() {
        val geometry = geometry()
        val existingFrame = validFrame(SceneCapturePolicy.slotTextureSize(geometry))
        val freshness = ContentCaptureFreshnessRecord(
            captureKey = ContentCaptureKey(geometry.localRect, geometry.contentOffset)
        )

        assertFalse(
            SceneCapturePolicy.shouldCaptureSlotContent(
                slot = record(id = "slot", geometry = null),
                existingFrame = existingFrame,
                freshnessRecord = freshness
            )
        )
    }

    @Test
    fun dirtyDecider_maskAddedMarksMaskDirty() {
        val previous = record(id = "slot")
        val next = recordWithMask(id = "slot", blurMask = Brush.horizontalGradient(listOf(Color.Black, Color.White)))

        assertEquals(
            setOf(BlurSlotDirtyReason.Mask),
            BlurSlotDirtyDecider.dirtyFlags(previous, next).reasons
        )
    }

    @Test
    fun dirtyDecider_maskRemovedMarksMaskDirty() {
        val previous = recordWithMask(id = "slot", blurMask = Brush.horizontalGradient(listOf(Color.Black, Color.White)))
        val next = record(id = "slot")

        assertEquals(
            setOf(BlurSlotDirtyReason.Mask),
            BlurSlotDirtyDecider.dirtyFlags(previous, next).reasons
        )
    }

    @Test
    fun dirtyDecider_clipAddedMarksMaskDirty() {
        val previous = record(id = "slot")
        val next = recordWithClip(id = "slot", clipShape = RoundedCornerShape(12.dp))

        assertEquals(
            setOf(BlurSlotDirtyReason.Mask),
            BlurSlotDirtyDecider.dirtyFlags(previous, next).reasons
        )
    }

    @Test
    fun dirtyDecider_clipRemovedMarksMaskDirty() {
        val previous = recordWithClip(id = "slot", clipShape = RoundedCornerShape(12.dp))
        val next = record(id = "slot")

        assertEquals(
            setOf(BlurSlotDirtyReason.Mask),
            BlurSlotDirtyDecider.dirtyFlags(previous, next).reasons
        )
    }

    @Test
    fun dirtyDecider_unchangedMaskAndClipSkipsMaskReason() {
        val blurMask = Brush.horizontalGradient(listOf(Color.Black, Color.White))
        val clipShape = RoundedCornerShape(12.dp)
        val previous = record(id = "slot", style = BlurSlotStyleRecord(
            style = Style.default,
            blurMask = blurMask,
            clipShape = clipShape
        ))
        val next = previous.copy(
            geometry = geometry(width = 12f)
        )

        val reasons = BlurSlotDirtyDecider.dirtyFlags(previous, next).reasons
        assertTrue(BlurSlotDirtyReason.Geometry in reasons)
        assertFalse(BlurSlotDirtyReason.Mask in reasons)
    }

    @Test
    fun dirtyDecider_maskAndClipChangeTogetherMarksMaskDirtyOnce() {
        val previous = record(id = "slot")
        val next = record(id = "slot", style = BlurSlotStyleRecord(
            style = Style.default,
            blurMask = Brush.verticalGradient(listOf(Color.Black, Color.White)),
            clipShape = RoundedCornerShape(8.dp)
        ))

        assertEquals(
            setOf(BlurSlotDirtyReason.Mask),
            BlurSlotDirtyDecider.dirtyFlags(previous, next).reasons
        )
    }

    @Test
    fun dirtyDecider_mutableBrushInternalsCaveat() {
        val brushA = Brush.horizontalGradient(listOf(Color.Black, Color.White))
        val brushB = Brush.horizontalGradient(listOf(Color.Black, Color.White))
        val previous = recordWithMask(id = "slot", blurMask = brushA)
        val next = previous.copy(
            style = previous.style.copy(blurMask = brushB)
        )

        assertTrue(
            "Structurally equal Brush objects may compare equal, skipping dirty detection",
            BlurSlotDirtyDecider.dirtyFlags(previous, next).reasons.isEmpty()
        )
    }

    @Test
    fun scheduler_blocksRequestsWhenGateIsDisabled() {
        var renderRequests = 0
        val gate = MutableSceneRenderGate(initialEnabled = false)
        val scheduler = SceneRenderScheduler(
            renderGate = gate,
            onRenderRequested = {
                renderRequests += 1
                true
            }
        )

        scheduler.request(RenderReason.RootCaptured)

        assertEquals(0, renderRequests)
    }

    @Test
    fun scheduler_accumulatesReasonsWhenGateIsDisabledThenDrainsWhenEnabled() {
        var renderRequests = 0
        val gate = MutableSceneRenderGate(initialEnabled = false)
        val scheduler = SceneRenderScheduler(
            renderGate = gate,
            onRenderRequested = {
                renderRequests += 1
                true
            }
        )

        scheduler.request(RenderReason.RootCaptured)
        scheduler.request(RenderReason.SlotChanged)

        assertEquals(0, renderRequests)

        gate.enabled = true
        val drained = scheduler.drainPendingReasonsIfGateAllows()

        assertTrue(drained)
        assertEquals(1, renderRequests)
        scheduler.onRenderStarted()
        assertEquals(
            setOf(RenderReason.RootCaptured, RenderReason.SlotChanged),
            scheduler.consumePendingReasons()
        )
    }

    @Test
    fun scheduler_allowsSingleRequestAfterGateReopensThenCoalesces() {
        var renderRequests = 0
        val gate = MutableSceneRenderGate(initialEnabled = false)
        val scheduler = SceneRenderScheduler(
            renderGate = gate,
            onRenderRequested = {
                renderRequests += 1
                true
            }
        )

        scheduler.request(RenderReason.SlotChanged)
        assertEquals(0, renderRequests)

        gate.enabled = true
        scheduler.request(RenderReason.RootCaptured)

        assertEquals(1, renderRequests)
        scheduler.onRenderStarted()
        assertEquals(
            setOf(RenderReason.SlotChanged, RenderReason.RootCaptured),
            scheduler.consumePendingReasons()
        )
    }

    @Test
    fun scheduler_drainDoesNotRequestWhenGateIsStillBlocked() {
        var renderRequests = 0
        val gate = MutableSceneRenderGate(initialEnabled = false)
        val scheduler = SceneRenderScheduler(
            renderGate = gate,
            onRenderRequested = {
                renderRequests += 1
                true
            }
        )

        scheduler.request(RenderReason.SlotChanged)
        val drained = scheduler.drainPendingReasonsIfGateAllows()

        assertFalse(drained)
        assertEquals(0, renderRequests)
    }

    @Test
    fun scheduler_onRenderFinishedDoesNotRetryWhenGateIsBlocked() {
        var renderRequests = 0
        val gate = MutableSceneRenderGate(initialEnabled = true)
        val scheduler = SceneRenderScheduler(
            renderGate = gate,
            onRenderRequested = {
                renderRequests += 1
                true
            }
        )

        scheduler.request(RenderReason.RootCaptured)
        assertEquals(1, renderRequests)
        scheduler.onRenderStarted()
        scheduler.consumePendingReasons()

        gate.enabled = false
        scheduler.request(RenderReason.SlotChanged)
        scheduler.onRenderFinished()

        assertEquals(1, renderRequests)
    }

    @Test
    fun scheduler_onRenderFinishedEmitsBlockedCounterWhenGateClosedDuringRender() {
        withRecordingSceneTraceCounters { recorder ->
            val gate = MutableSceneRenderGate(initialEnabled = true)
            val scheduler = SceneRenderScheduler(renderGate = gate) { true }

            scheduler.request(RenderReason.RootCaptured)
            scheduler.onRenderStarted()
            scheduler.consumePendingReasons()

            scheduler.request(RenderReason.SlotChanged)

            gate.enabled = false
            scheduler.onRenderFinished()

            assertEquals(1, recorder.count("render.gate.blocked.total"))
            assertEquals(1, recorder.count("render.gate.blocked.SlotChanged"))
        }
    }

    @Test
    fun scheduler_onRenderFinishedDoesNotDoubleCountAlreadyBlockedReasons() {
        withRecordingSceneTraceCounters { recorder ->
            val gate = MutableSceneRenderGate(initialEnabled = true)
            val scheduler = SceneRenderScheduler(renderGate = gate) { true }

            scheduler.request(RenderReason.RootCaptured)
            scheduler.onRenderStarted()
            scheduler.consumePendingReasons()

            gate.enabled = false
            scheduler.request(RenderReason.SlotChanged)

            scheduler.onRenderFinished()

            assertEquals(1, recorder.count("render.gate.blocked.total"))
            assertEquals(1, recorder.count("render.gate.blocked.SlotChanged"))
        }
    }

    @Test
    fun scheduler_blockedRequestEmitsGateBlockedCounter() {
        withRecordingSceneTraceCounters { recorder ->
            val gate = MutableSceneRenderGate(initialEnabled = false)
            val scheduler = SceneRenderScheduler(renderGate = gate) { false }

            scheduler.request(RenderReason.RootCaptured)

            assertEquals(1, recorder.count("render.gate.blocked.total"))
            assertEquals(1, recorder.count("render.gate.blocked.RootCaptured"))
        }
    }

    @Test
    fun scheduler_multipleBlockedRequestsEmitSeparateCounters() {
        withRecordingSceneTraceCounters { recorder ->
            val gate = MutableSceneRenderGate(initialEnabled = false)
            val scheduler = SceneRenderScheduler(renderGate = gate) { false }

            scheduler.request(RenderReason.RootCaptured)
            scheduler.request(RenderReason.SlotChanged)
            scheduler.request(RenderReason.RootCaptured)

            assertEquals(3, recorder.count("render.gate.blocked.total"))
            assertEquals(2, recorder.count("render.gate.blocked.RootCaptured"))
            assertEquals(1, recorder.count("render.gate.blocked.SlotChanged"))
        }
    }

    @Test
    fun scheduler_drainAfterGateReopensEmitsDrainCounter() {
        withRecordingSceneTraceCounters { recorder ->
            val gate = MutableSceneRenderGate(initialEnabled = false)
            var renderRequested = false
            val scheduler = SceneRenderScheduler(renderGate = gate) {
                renderRequested = true
                true
            }

            scheduler.request(RenderReason.RootCaptured)
            scheduler.request(RenderReason.SlotChanged)

            gate.enabled = true
            scheduler.drainPendingReasonsIfGateAllows()

            assertTrue(renderRequested)
            assertEquals(1, recorder.count("render.gate.drained.total"))
            assertEquals(listOf(2L), recorder.values("gauge.render.gate.drainedCount"))
        }
    }

    @Test
    fun scheduler_visibleGateDoesNotEmitBlockedCounters() {
        withRecordingSceneTraceCounters { recorder ->
            val gate = MutableSceneRenderGate(initialEnabled = true)
            val scheduler = SceneRenderScheduler(renderGate = gate) { true }

            scheduler.request(RenderReason.RootCaptured)

            assertEquals(0, recorder.count("render.gate.blocked.total"))
            assertEquals(1, recorder.count("render.request.total"))
        }
    }

    @Test
    fun scheduler_visibleGateDrainWithNoPendingEmitsNoDrainCounter() {
        withRecordingSceneTraceCounters { recorder ->
            val gate = MutableSceneRenderGate(initialEnabled = true)
            var drainCalled = false
            val scheduler = SceneRenderScheduler(renderGate = gate) {
                drainCalled = true
                false
            }

            val drained = scheduler.drainPendingReasonsIfGateAllows()

            assertFalse(drained)
            assertFalse(drainCalled)
            assertEquals(0, recorder.count("render.gate.drained.total"))
        }
    }

    @Test
    fun scheduler_drainFailsWhenRenderRequestReturnsFalseAndEmitsNoDrainCounter() {
        withRecordingSceneTraceCounters { recorder ->
            val gate = MutableSceneRenderGate(initialEnabled = false)
            var requestCalled = false
            val scheduler = SceneRenderScheduler(renderGate = gate) {
                requestCalled = true
                false
            }

            scheduler.request(RenderReason.RootCaptured)
            scheduler.request(RenderReason.SlotChanged)

            gate.enabled = true
            val drained = scheduler.drainPendingReasonsIfGateAllows()

            assertFalse(drained)
            assertTrue(requestCalled)
            assertEquals(0, recorder.count("render.gate.drained.total"))
            assertEquals(0, recorder.count("gauge.render.gate.drainedCount"))
        }
    }

    @Test
    fun traceCounters_gateStateEmitsGauge() {
        withRecordingSceneTraceCounters { recorder ->
            SceneTraceCounters.renderGateState(true)

            assertEquals(1, recorder.count("gauge.render.gate.open"))
            assertEquals(listOf(1L), recorder.values("gauge.render.gate.open"))

            SceneTraceCounters.renderGateState(false)

            assertEquals(2, recorder.count("gauge.render.gate.open"))
            assertEquals(listOf(1L, 0L), recorder.values("gauge.render.gate.open"))
        }
    }

    private fun record(
        id: String,
        drawIndex: Int = 0,
        zIndex: Float = 0f,
        geometry: BlurSlotGeometry? = geometry(),
        dirtyFlags: BlurSlotDirtyFlags = BlurSlotDirtyFlags.Clean,
        style: BlurSlotStyleRecord = BlurSlotStyleRecord(
            style = Style.default,
            blurMask = null
        )
    ): BlurSlotRecord {
        return BlurSlotRecord(
            id = BlurSlotId(id),
            drawIndex = drawIndex,
            debugName = id,
            geometry = geometry,
            style = style,
            content = null,
            dirtyFlags = dirtyFlags
        )
    }

    private fun recordWithMask(
        id: String,
        blurMask: Brush?,
        drawIndex: Int = 0,
        zIndex: Float = 0f,
        geometry: BlurSlotGeometry? = geometry()
    ): BlurSlotRecord {
        return record(
            id = id,
            drawIndex = drawIndex,
            zIndex = zIndex,
            geometry = geometry,
            style = BlurSlotStyleRecord(
                style = Style.default,
                blurMask = blurMask
            )
        )
    }

    private fun recordWithClip(
        id: String,
        clipShape: Shape = RoundedCornerShape(12.dp),
        drawIndex: Int = 0,
        zIndex: Float = 0f,
        geometry: BlurSlotGeometry? = geometry()
    ): BlurSlotRecord {
        return record(
            id = id,
            drawIndex = drawIndex,
            zIndex = zIndex,
            geometry = geometry,
            style = BlurSlotStyleRecord(
                style = Style.default,
                blurMask = null,
                clipShape = clipShape
            )
        )
    }

    private fun geometry(width: Float = 10f, height: Float = 10f): BlurSlotGeometry {
        return BlurSlotGeometry(
            area = Rect(0f, 0f, width, height),
            localRect = Rect(0f, 0f, width, height),
            contentOffset = Offset.Zero,
            transformMatrix = FloatArray(16),
            zIndex = 0f
        )
    }

    private fun validFrame(size: IntSize): GlTextureFrame {
        return GlTextureFrame(
            sizePx = size,
            textureOrigin = CoordinateOrigin.TOP_LEFT,
            texture2D = FakeTexture(size)
        )
    }

    private class FakeTexture(size: IntSize) : Texture2D() {
        override val id: Int = 1
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = size.width
        override val height: Int = size.height
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.TOP_LEFT
        override val specification: Texture.Specification = Texture.Specification(size = size)

        override fun bind(slot: Int) = Unit
        override fun setData(data: Buffer) = Unit
        override fun isLoaded(): Boolean = true
        override fun destroy() = Unit
        override fun generateMipMaps() = Unit
    }
}
