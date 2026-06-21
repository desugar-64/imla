/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.Buffer

class SceneFramePlannerTest {
    @Test
    fun planStructures_doNotExposeMutableProperties() {
        planTypes.forEach { type ->
            type.declaredFields
                .filterNot { it.isSynthetic }
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
                type.methods.any { it.name.startsWith("set") }
            )
        }
    }

    @Test
    fun planStructures_doNotReferenceLegacyRenderObject() {
        planTypes.assertNoForbiddenType("RenderObject")
    }

    @Test
    fun planStructures_doNotCarryRendererSessionRepositoryOrComposeLayerOwnership() {
        planTypes.assertNoForbiddenType("SceneGlRenderer")
        planTypes.assertNoForbiddenType("SceneBackdropPreprocessPass")
        planTypes.assertNoForbiddenType("SceneBlurPass")
        planTypes.assertNoForbiddenType("ImlaSceneSession")
        planTypes.assertNoForbiddenType("SceneLayerRepository")
        planTypes.assertNoForbiddenType("SceneMaskRepository")
        planTypes.assertNoForbiddenType("ImlaSceneCoordinator")
        planTypes.assertNoForbiddenType("GraphicsLayer")
        planTypes.assertNoForbiddenType("GLRenderer")
    }

    @Test
    fun planner_preservesDrawOrderAndRequiredSlotFields() {
        val firstTexture = FakeTexture(id = 1)
        val firstMask = FakeTexture(id = 2)
        val secondClip = FakeTexture(id = 3)
        val rootTexture = FakeTexture(id = 4)
        val frame = CommittedSceneFrame(
            generation = 7L,
            rootSize = IntSize(300, 400),
            slots = listOf(
                record(
                    id = "front",
                    drawIndex = 4,
                    area = Rect(10f, 20f, 110f, 140f),
                    localRect = Rect(0f, 0f, 100f, 120f),
                    zIndex = 2f,
                    style = Style.default.copy(sigma = 6f),
                    dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Style))
                ),
                record(
                    id = "back",
                    drawIndex = 1,
                    area = Rect(0f, 5f, 30f, 45f),
                    localRect = Rect(0f, 0f, 30f, 40f),
                    zIndex = 1f,
                    style = Style.default.copy(noiseAlpha = 0.2f),
                    dirtyFlags = BlurSlotDirtyFlags(setOf(BlurSlotDirtyReason.Geometry))
                )
            ),
            reasons = setOf(RenderReason.RootCaptured, RenderReason.SlotChanged)
        )
        val plan = SceneFramePlanner.plan(
            frame = frame,
            resources = SceneResolvedResources(
                rootTexture = rootTexture,
                slots = mapOf(
                    BlurSlotId("front") to SceneResolvedSlotResources(
                        contentTexture = firstTexture,
                        blurRadiusMask = firstMask,
                        compositeCoverageMask = firstMask,
                        clipTexture = null
                    ),
                    BlurSlotId("back") to SceneResolvedSlotResources(
                        contentTexture = null,
                        blurRadiusMask = null,
                        compositeCoverageMask = null,
                        clipTexture = secondClip
                    )
                )
            )
        )

        assertEquals(7L, plan.generation)
        assertEquals(IntSize(300, 400), plan.rootSize)
        assertSame(rootTexture, plan.rootTexture)
        assertEquals(2, plan.committedSlotCount)
        assertEquals(setOf(RenderReason.RootCaptured, RenderReason.SlotChanged), plan.reasons)
        assertTrue(plan.requiresNoiseTexture)
        assertEquals(listOf("front", "back"), plan.slots.map { it.id.value })

        val front = plan.slots[0]
        assertEquals(4, front.drawIndex)
        assertEquals("front", front.debugName)
        assertEquals(Rect(10f, 20f, 110f, 140f), front.area)
        assertEquals(Rect(0f, 0f, 100f, 120f), front.localRect)
        assertEquals(IntSize(100, 120), front.contentSize)
        assertEquals(2f, front.zIndex)
        assertEquals(6f, front.style.sigma)
        assertSame(firstTexture, front.contentTexture)
        assertSame(firstMask, front.blurRadiusMask)
        assertSame(firstMask, front.compositeCoverageMask)
        assertEquals(setOf(BlurSlotDirtyReason.Style), front.dirtyFlags.reasons)

        val back = plan.slots[1]
        assertEquals(1, back.drawIndex)
        assertEquals(IntSize(30, 40), back.contentSize)
        assertSame(secondClip, back.clipTexture)
        assertEquals(setOf(BlurSlotDirtyReason.Geometry), back.dirtyFlags.reasons)
    }

    private fun List<Class<*>>.assertNoForbiddenType(forbiddenName: String) {
        forEach { type ->
            val matchingField = type.declaredFields
                .filterNot { it.isSynthetic }
                .firstOrNull { field ->
                    field.genericType.typeName.contains(forbiddenName) ||
                        field.type.name.contains(forbiddenName)
                }
            assertNull("${type.simpleName} must not reference $forbiddenName", matchingField)
        }
    }

    private fun record(
        id: String,
        drawIndex: Int,
        area: Rect,
        localRect: Rect,
        zIndex: Float,
        style: Style,
        dirtyFlags: BlurSlotDirtyFlags
    ): BlurSlotRecord {
        return BlurSlotRecord(
            id = BlurSlotId(id),
            drawIndex = drawIndex,
            debugName = id,
            geometry = BlurSlotGeometry(
                area = area,
                localRect = localRect,
                contentOffset = Offset.Zero,
                transformMatrix = FloatArray(16),
                zIndex = zIndex
            ),
            style = BlurSlotStyleRecord(
                style = style,
                blurMask = null
            ),
            content = null,
            dirtyFlags = dirtyFlags
        )
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
        val planTypes: List<Class<*>> = listOf(
            SceneRenderFrame::class.java,
            SceneSlotPlan::class.java,
            SceneResolvedResources::class.java,
            SceneResolvedSlotResources::class.java
        )
    }
}
