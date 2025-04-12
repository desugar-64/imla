/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.ext

import android.opengl.GLES30
import android.util.Log
import androidx.tracing.trace
import dev.serhiiyaremych.imla.BuildConfig
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext

internal fun logw(tag: String, message: String) {
    if (BuildConfig.DEBUG) Log.w(tag, message)
}

internal fun logd(tag: String, message: String) {
    if (BuildConfig.DEBUG) Log.d(tag, message)
}
internal fun loge(tag: String, message: String) {
    if (BuildConfig.DEBUG) Log.e(tag, message)
}

internal fun isGLThread(): Boolean {
    val egl = EGLContext.getEGL() as? EGL10
    return egl != null && egl.eglGetCurrentContext() != EGL10.EGL_NO_CONTEXT
}

@Suppress("UNUSED_PARAMETER", "NOTHING_TO_INLINE")
internal fun checkGlError(action: Unit = Unit) {
    if (BuildConfig.DEBUG) {
        trace("checkGlError") {
            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) {
                Log.e(
                    "checkGlError",
                    "failed with error $error on thread ${Thread.currentThread().name}"
                )
                throwError(error)
            }
        }
    }
}

internal fun throwError(errorCode: Int) {
    error(errorCode)
}

internal inline fun <A, B> ifNotNull(a: A?, b: B?, block: (A, B) -> Unit) {
    if (a != null && b != null) {
        block(a, b)
    }
}

internal inline fun <A, B, C> ifNotNull(a: A?, b: B?, c: C?, block: (A, B, C) -> Unit) {
    if (a != null && b != null && c != null) {
        block(a, b, c)
    }
}