/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

/**
 * Internal switch for the prepared scene blur atlas render path.
 *
 * Atlas is enabled by default. Opt out by setting the log tag
 * `ImlaAtlasDisabled` to `DEBUG` before the renderer is constructed.
 *
 * Blur-radius masked slots remain on the per-slot fallback path regardless of atlas state.
 * Noise, coverage-only masks, and clips are not stored in atlas buffers. Slots without an
 * atlas lookup continue through the existing per-slot preprocess/blur/backdrop path.
 * Atlas outputs are borrowed pass resources and must be released by the caller in a finally
 * block.
 */
internal data class SceneBlurAtlasRenderConfig(
    val enabled: Boolean = true
) {
    internal companion object {
        val Disabled = SceneBlurAtlasRenderConfig(enabled = false)
        val Enabled = SceneBlurAtlasRenderConfig()
    }
}
