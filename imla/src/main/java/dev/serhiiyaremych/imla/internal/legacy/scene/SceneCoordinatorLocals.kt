/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.runtime.staticCompositionLocalOf
import dev.serhiiyaremych.imla.internal.legacy.ImlaSceneRenderer

/**
 * Host-level bridge for the active scene coordinator.
 *
 * The internal legacy source modifier republishes this coordinator as a modifier local for its
 * subtree; blur slots should prefer that modifier-local scope so the source owns the captured scene
 * region.
 */
internal val LocalImlaSceneCoordinator = staticCompositionLocalOf<ImlaSceneCoordinator?> { null }

/**
 * Host-level renderer scope consumed by the internal legacy source modifier.
 *
 * This local keeps the renderer page-local without making child composables accept renderer
 * parameters. It is internal to the modifier/host bridge.
 */
internal val LocalImlaSceneRenderer = staticCompositionLocalOf<ImlaSceneRenderer?> { null }
