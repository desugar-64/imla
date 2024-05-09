/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ext

import android.util.Log
import dev.serhiiyaremych.imla.BuildConfig

internal fun logw(tag: String, message: String) {
    if (BuildConfig.DEBUG) Log.w(tag, message)
}

internal fun logd(tag: String, message: String) {
    if (BuildConfig.DEBUG) Log.d(tag, message)
}