/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.capture

import android.hardware.HardwareBuffer
import android.os.Build
import androidx.hardware.SyncFenceCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
@RunWith(AndroidJUnit4::class)
class BufferLeaseTest {
    @Before
    fun requireHardwareBufferApi() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
    }

    @Test
    fun awaitReadyIsIdempotent() {
        val fence = CountingLeaseFence()
        val buffer = hardwareBuffer()
        val lease = BufferLease(
            buffer = buffer,
            readyFence = fence,
            onRelease = { buffer.close() }
        )

        lease.awaitReady()
        lease.awaitReady()
        lease.release(null)

        assertEquals(1, fence.awaitCalls)
        assertEquals(1, fence.closeCalls)
    }

    @Test
    fun releaseIsIdempotent() {
        val buffer = hardwareBuffer()
        var releaseCalls = 0
        val lease = BufferLease(
            buffer = buffer,
            readyFence = null,
            onRelease = {
                releaseCalls++
                buffer.close()
            }
        )

        lease.release(null)
        lease.release(null)

        assertEquals(1, releaseCalls)
    }

    @Test
    fun releaseClosesUnawaitedReadyFenceOnce() {
        val fence = CountingLeaseFence()
        val buffer = hardwareBuffer()
        var releaseCalls = 0
        val lease = BufferLease(
            buffer = buffer,
            readyFence = fence,
            onRelease = {
                releaseCalls++
                buffer.close()
            }
        )

        lease.release(null)
        lease.release(null)

        assertEquals(0, fence.awaitCalls)
        assertEquals(1, fence.closeCalls)
        assertEquals(1, releaseCalls)
    }

    @Test
    fun closeReleasesWithoutFence() {
        val buffer = hardwareBuffer()
        var releaseCalls = 0
        var releaseFence: SyncFenceCompat? = null
        val lease = BufferLease(
            buffer = buffer,
            readyFence = null,
            onRelease = { fence ->
                releaseCalls++
                releaseFence = fence
                buffer.close()
            }
        )

        lease.close()
        lease.close()

        assertEquals(1, releaseCalls)
        assertNull(releaseFence)
    }

    private fun hardwareBuffer(): HardwareBuffer {
        return HardwareBuffer.create(
            1,
            1,
            HardwareBuffer.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
    }

    private class CountingLeaseFence : LeaseFence {
        var awaitCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun awaitForever() {
            awaitCalls++
        }

        override fun close() {
            closeCalls++
        }
    }
}
