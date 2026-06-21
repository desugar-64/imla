/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.legacy.RenderableRootLayer

/**
 * Scene texture facade used by [SceneGlRenderer].
 *
 * The store currently delegates root texture consumption to [RenderableRootLayer],
 * slot foreground captures to [SceneLayerRepository], and mask/clip captures to
 * [SceneMaskRepository]. Scene rendering asks this object for the root texture,
 * resolved slot textures, and pending frame release work on the GL thread.
 *
 * It does not own Compose [androidx.compose.ui.graphics.layer.GraphicsLayer]
 * instances, capture callbacks, GL sessions, renderer instances, or effect/FBO
 * pass resources. Those lifecycles remain with the source modifiers, renderer
 * session, repositories, and effect pipeline until later renderer-2 slices move
 * ownership deliberately.
 */
internal class SceneResourceStore(
    private val rootTextureConsumer: () -> Texture2D?,
    private val layerRepository: SceneLayerResourceRepository,
    private val maskRepository: SceneMaskResourceRepository
) {
    constructor(
        rootLayer: RenderableRootLayer,
        layerRepository: SceneLayerRepository,
        maskRepository: SceneMaskRepository
    ) : this(
        rootTextureConsumer = rootLayer::consumePendingFrame,
        layerRepository = layerRepository,
        maskRepository = maskRepository
    )

    fun captureFrameResources(frame: CommittedSceneFrame) {
        layerRepository.captureSlotContent(frame)
        maskRepository.captureSlotMasks(frame)
    }

    fun releasePendingFrameResources() {
        layerRepository.releasePendingFrames()
        maskRepository.releasePendingFrames()
    }

    fun consumePendingRootTexture(): Texture2D? {
        return rootTextureConsumer()
    }

    fun resolveResources(
        frame: CommittedSceneFrame,
        rootTexture: Texture2D
    ): SceneResolvedResources {
        return SceneResolvedResources(
            rootTexture = rootTexture,
            slots = frame.slots.associate { slot ->
                val maskTexture = maskRepository.maskTextureFor(slot.id)
                val maskResources = maskResourcesFor(slot, maskTexture)
                slot.id to SceneResolvedSlotResources(
                    contentTexture = layerRepository.textureFor(slot.id),
                    blurRadiusMask = maskResources.blurRadiusMask,
                    compositeCoverageMask = maskResources.compositeCoverageMask,
                    clipTexture = maskRepository.clipTextureFor(slot.id)
                )
            }
        )
    }

    fun destroy() {
        layerRepository.destroy()
        maskRepository.destroy()
    }

    private fun maskResourcesFor(
        slot: BlurSlotRecord,
        maskTexture: Texture2D?
    ): SceneResolvedMaskResources {
        val blurRadiusMask = if (slot.style.blurMask != null) maskTexture else null
        return if (slot.debugName?.startsWith(COVERAGE_MASK_ATLAS_DIAGNOSTIC_PREFIX) == true) {
            SceneResolvedMaskResources(
                blurRadiusMask = null,
                compositeCoverageMask = maskTexture
            )
        } else {
            SceneResolvedMaskResources(
                blurRadiusMask = blurRadiusMask,
                compositeCoverageMask = maskTexture
            )
        }
    }

    private data class SceneResolvedMaskResources(
        val blurRadiusMask: Texture2D?,
        val compositeCoverageMask: Texture2D?
    )

    private companion object {
        const val COVERAGE_MASK_ATLAS_DIAGNOSTIC_PREFIX = "coverage-mask-atlas-"
    }
}

internal interface SceneLayerResourceRepository {
    fun captureSlotContent(frame: CommittedSceneFrame)

    fun textureFor(id: BlurSlotId): Texture2D?

    fun releasePendingFrames()

    fun destroy()
}

internal interface SceneMaskResourceRepository {
    fun captureSlotMasks(frame: CommittedSceneFrame)

    fun maskTextureFor(id: BlurSlotId): Texture2D?

    fun clipTextureFor(id: BlurSlotId): Texture2D?

    fun releasePendingFrames()

    fun destroy()
}
