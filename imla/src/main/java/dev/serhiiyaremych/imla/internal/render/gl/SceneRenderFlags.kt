/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl

import android.util.Log

/**
 * Runtime A/B switches for the scene renderer, snapshotted once per [SceneGlOwner].
 *
 * Zero-copy HardwareBuffer present (API 29+) is ON by default. Force the blit control
 * path — same binary, same SurfaceView output, only the present mechanism differs — for
 * an honest A/B with:
 *
 *   adb shell setprop log.tag.ImlaBlitPresent DEBUG   # then relaunch: blit present
 *   adb shell setprop log.tag.ImlaBlitPresent ASSERT  # then relaunch: HW present (default)
 */
internal object SceneRenderFlags {
    fun hwPresentEnabled(): Boolean = !Log.isLoggable("ImlaBlitPresent", Log.DEBUG)
}
