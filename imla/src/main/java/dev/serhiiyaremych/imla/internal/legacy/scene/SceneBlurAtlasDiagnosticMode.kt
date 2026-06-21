/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

import android.util.Log

/**
 * Internal atlas enablement policy.
 *
 * Atlas is enabled by default. Disable for diagnostics via the log tag:
 *
 * `tools/adb-timeout --device <serial> --timeout 20 shell setprop log.tag.ImlaAtlasDisabled DEBUG`
 *
 * Set the tag back to `INFO` and restart the app to re-enable atlas.
 *
 * This is not public API, does not flow through renderer/modifier constructors, and only
 * affects the conservative atlas subset. Blur-radius masked slots still stay on the
 * per-slot fallback path; there is no masked atlas switch.
 */
internal object SceneBlurAtlasDiagnosticMode {
    private const val ATLAS_DISABLED_TAG = "ImlaAtlasDisabled"

    fun renderConfig(): SceneBlurAtlasRenderConfig {
        return renderConfig(
            overrideDisabled = { Log.isLoggable(ATLAS_DISABLED_TAG, Log.DEBUG) }
        )
    }

    internal fun renderConfig(
        overrideDisabled: () -> Boolean
    ): SceneBlurAtlasRenderConfig {
        if (overrideDisabled()) return SceneBlurAtlasRenderConfig.Disabled
        return SceneBlurAtlasRenderConfig.Enabled
    }
}
