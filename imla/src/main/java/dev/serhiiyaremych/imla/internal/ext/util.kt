/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.ext

import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLES30
import androidx.activity.ComponentActivity
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

internal fun threadTag(): String = "thread=${Thread.currentThread().name}"

@Suppress("UNUSED_PARAMETER")
internal inline fun checkGlError(action: Unit = Unit) {
    if (BuildConfig.DEBUG) {
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

internal fun throwError(errorCode: Int) {
    error(errorCode)
}

/**
 * Finds the ComponentActivity for this Context, traversing ContextWrapper chain.
 * Returns null if not attached to an Activity (e.g., in preview or tests).
 */
internal tailrec fun Context.findActivityOrNull(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}
