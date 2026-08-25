/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.gl

import android.hardware.HardwareBuffer
import android.os.Build
import androidx.annotation.RequiresApi

internal object HardwareBufferId {
    private val nativeLibraryLoaded: Boolean = run {
        try {
            System.loadLibrary("ahb_id")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun nativePtr(buffer: HardwareBuffer): Long {
        if (!nativeLibraryLoaded) {
            return 0L
        }
        return nativePtrNative(buffer)
    }

    @JvmStatic
    private external fun nativePtrNative(buffer: HardwareBuffer): Long
}
