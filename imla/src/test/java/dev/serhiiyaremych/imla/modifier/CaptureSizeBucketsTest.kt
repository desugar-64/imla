package dev.serhiiyaremych.imla.internal.capture

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSizeBucketsTest {
    @Test
    fun keepsZeroSizeUnchanged() {
        assertEquals(IntSize.Zero, CaptureSizeBuckets.bucket(IntSize.Zero))
    }

    @Test
    fun roundsSmallSizesToThirtyTwoPixelBuckets() {
        // Content under 256px keeps fine-grained 32px buckets.
        assertEquals(
            IntSize(224, 192),
            CaptureSizeBuckets.bucket(IntSize(200, 180))
        )
    }

    @Test
    fun coarsensMidSizesToReduceResizeRecreations() {
        // Card-sized content uses coarser steps (>=512 -> 128px, >=256 -> 64px) so a resize ramp
        // reallocates a handful of times instead of once per 32px.
        assertEquals(
            IntSize(640, 384),
            CaptureSizeBuckets.bucket(IntSize(520, 360))
        )
    }

    @Test
    fun roundsLargeDimensionsWithAdaptiveSteps() {
        assertEquals(
            IntSize(1280, 1920),
            CaptureSizeBuckets.bucket(IntSize(1200, 1920))
        )
    }
}
