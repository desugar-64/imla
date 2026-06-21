package dev.serhiiyaremych.imla.internal.render.gl

import android.opengl.GLES30
import androidx.hardware.SyncFenceCompat
import dev.serhiiyaremych.imla.internal.capture.BufferLease

internal class HardwareBufferReleaseQueue(
    private val threadGuard: SceneGlThreadGuard
) {
    fun releaseAfterSubmittedGlWork(lease: BufferLease) {
        threadGuard.checkCurrentThread()
        lease.release(createReleaseFence())
    }

    private fun createReleaseFence(): SyncFenceCompat? {
        return runCatching {
            SyncFenceCompat.createNativeSyncFence().let { fence ->
                if (fence.isValid()) {
                    fence
                } else {
                    fence.close()
                    null
                }
            }
        }.getOrElse {
            GLES30.glFinish()
            null
        }
    }
}
