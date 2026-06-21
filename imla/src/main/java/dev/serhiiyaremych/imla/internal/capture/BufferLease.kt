package dev.serhiiyaremych.imla.internal.capture

import android.hardware.HardwareBuffer
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.hardware.SyncFenceCompat

internal class BufferLease internal constructor(
    val buffer: HardwareBuffer,
    readyFence: LeaseFence?,
    private val onRelease: (SyncFenceCompat?) -> Unit
) : AutoCloseable {
    private var fence: LeaseFence? = readyFence
    private var released: Boolean = false

    fun awaitReady() {
        val currentFence = fence ?: return
        currentFence.awaitForever()
        currentFence.close()
        fence = null
    }

    fun release(releaseFence: SyncFenceCompat?) {
        if (released) {
            releaseFence?.close()
            return
        }
        released = true
        fence?.close()
        fence = null
        onRelease(releaseFence)
    }

    override fun close() {
        release(null)
    }
}

internal interface LeaseFence {
    fun awaitForever()

    fun close()

    class Compat(
        private val fence: SyncFenceCompat
    ) : LeaseFence {
        override fun awaitForever() {
            fence.awaitForever()
        }

        override fun close() {
            fence.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class Platform(
        private val fence: android.hardware.SyncFence
    ) : LeaseFence {
        override fun awaitForever() {
            fence.awaitForever()
        }

        override fun close() {
            fence.close()
        }
    }
}
