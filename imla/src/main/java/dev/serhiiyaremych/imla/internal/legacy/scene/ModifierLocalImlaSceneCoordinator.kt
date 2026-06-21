/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import androidx.compose.ui.modifier.ProvidableModifierLocal
import androidx.compose.ui.modifier.modifierLocalOf

/**
 * Modifier-local scene scope provided by the internal legacy source modifier and consumed by blur
 * slots.
 *
 * This is the primary coordinator path for the scene renderer because it ties a slot to the nearest
 * source subtree instead of to a renderer object.
 */
internal val ModifierLocalImlaSceneCoordinator: ProvidableModifierLocal<ImlaSceneCoordinator?> =
    modifierLocalOf { null }
