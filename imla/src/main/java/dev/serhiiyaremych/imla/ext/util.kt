/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ext

import android.util.Log
import dev.serhiiyaremych.imla.BuildConfig
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext

internal fun logw(tag: String, message: String) {
    if (BuildConfig.DEBUG) Log.w(tag, message)
}

internal fun logd(tag: String, message: String) {
    if (BuildConfig.DEBUG) Log.d(tag, message)
}

internal fun isGLThread(): Boolean {
    val egl = EGLContext.getEGL() as? EGL10
    return egl != null && egl.eglGetCurrentContext() != EGL10.EGL_NO_CONTEXT
}