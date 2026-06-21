/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import kotlin.math.sqrt

/**
 * Pacing-jitter metric for the Imla capture pipeline.
 *
 * Reads the `SceneCaptureKick` (capture dispatch) and `SceneGlPresent` (GL frame
 * present) trace instants and reports the *distribution* of intervals between
 * consecutive events, plus the offset of each capture kick from the
 * `Choreographer#doFrame` that precedes it on the same thread.
 *
 * Cadence regularity — not the mean — is the signal. A vsync-aligned dispatch
 * tightens interval spread toward the refresh period and collapses the doFrame
 * phase offset; a `view.post` dispatch smears both. Rolling averages hide that
 * spread, so every statistic here is computed from raw trace-clock timestamps.
 *
 * Emitted measurements (per iteration):
 * - captureKickIntervalP50Ms / P95Ms / StdDevMs, captureKickCount
 * - captureKickVsyncPhaseP50Ms / P95Ms / StdDevMs  (offset from doFrame start)
 * - glPresentIntervalP50Ms / P95Ms / StdDevMs, glPresentCount
 * - {prefix}OnBeatPct / FastPct / SlowPct  (cadence classification)
 *
 * Cadence classification isolates the *shape* of the jitter, not just its
 * magnitude. A perfectly vsync-paced producer presents exactly one frame per
 * refresh period; a bursty producer alternates double-pumps (two frames inside
 * one refresh) with drops (a skipped refresh). MAD/P95 conflate both into one
 * number, so we also classify each steady-scroll interval relative to the median
 * period M (which tracks the refresh period under continuous motion): Fast
 * (< 0.75*M, double-pump), OnBeat ([0.75*M, 1.25*M]), Slow (> 1.25*M, drop).
 * Idle gaps between flings (> 3*M) are excluded so the percentages reflect
 * cadence under motion, not between-gesture pauses. Refresh-agnostic: M is
 * derived from the data, no hard-coded Hz.
 */
@OptIn(ExperimentalMetricApi::class)
class CapturePacingMetric : TraceMetric() {

    override fun getMeasurements(
        captureInfo: Metric.CaptureInfo,
        traceSession: TraceProcessor.Session
    ): List<Metric.Measurement> {
        val kickTs = traceSession.sectionStarts("SceneCaptureKick")
        val presentTs = traceSession.sectionStarts("SceneGlPresent")
        val phaseMs = traceSession.captureKickVsyncPhaseNs().map { it.toMs() }

        return buildList {
            val kickIntervals = intervalsMs(kickTs)
            val presentIntervals = intervalsMs(presentTs)
            addStats("captureKickInterval", kickIntervals)
            addCadenceStats("captureKickInterval", kickIntervals)
            add(Metric.Measurement("captureKickCount", kickTs.size.toDouble()))
            addStats("captureKickVsyncPhase", phaseMs)
            addStats("glPresentInterval", presentIntervals)
            addCadenceStats("glPresentInterval", presentIntervals)
            add(Metric.Measurement("glPresentCount", presentTs.size.toDouble()))
        }
    }

    private fun MutableList<Metric.Measurement>.addStats(prefix: String, values: List<Double>) {
        add(Metric.Measurement("${prefix}P50Ms", percentile(values, 50.0)))
        add(Metric.Measurement("${prefix}P95Ms", percentile(values, 95.0)))
        add(Metric.Measurement("${prefix}StdDevMs", stdDev(values)))
        // MAD is robust to the idle gaps between flings: it reflects steady-scroll
        // jitter, while StdDev/P95 also surface real stalls. Watch both.
        add(Metric.Measurement("${prefix}MadMs", medianAbsoluteDeviation(values)))
    }

    // Classifies steady-scroll intervals against the median period M. See the
    // class doc: Fast/OnBeat/Slow as a percentage of in-motion intervals, with
    // between-fling idle gaps (> 3*M) excluded from the denominator.
    private fun MutableList<Metric.Measurement>.addCadenceStats(
        prefix: String,
        values: List<Double>
    ) {
        val median = percentile(values, 50.0)
        val active = if (median > 0.0) values.filter { it <= 3.0 * median } else emptyList()
        val total = active.size.toDouble()
        fun pct(predicate: (Double) -> Boolean): Double =
            if (total > 0.0) 100.0 * active.count(predicate) / total else 0.0
        add(Metric.Measurement("${prefix}OnBeatPct", pct { it in (0.75 * median)..(1.25 * median) }))
        add(Metric.Measurement("${prefix}FastPct", pct { it < 0.75 * median }))
        add(Metric.Measurement("${prefix}SlowPct", pct { it > 1.25 * median }))
    }

    // thread_slice is a newer convenience view; join slice+thread_track directly
    // so the query also runs on the older trace_processor bundled with benchmark.
    private fun TraceProcessor.Session.sectionStarts(section: String): List<Long> =
        query(
            """
            SELECT s.ts AS ts
            FROM slice s JOIN thread_track tt ON s.track_id = tt.id
            WHERE s.name = '$section'
            ORDER BY s.ts
            """.trimIndent()
        )
            .map { it.long("ts") }
            .toList()

    // doFrame slices are often suffixed (e.g. "Choreographer#doFrame 123"), so
    // match by prefix. Both the kick and doFrame run on the single UI thread, so
    // the nearest preceding doFrame start globally is the vsync reference.
    private fun TraceProcessor.Session.captureKickVsyncPhaseNs(): List<Long> =
        query(
            """
            WITH frame AS (
                SELECT ts FROM slice WHERE name LIKE 'Choreographer#doFrame%'
            ),
            kick AS (
                SELECT ts FROM slice WHERE name = 'SceneCaptureKick'
            )
            SELECT kick.ts - (
                SELECT MAX(frame.ts) FROM frame WHERE frame.ts <= kick.ts
            ) AS phase_ns
            FROM kick
            ORDER BY kick.ts
            """.trimIndent()
        )
            .mapNotNull { it.nullableLong("phase_ns") }
            .toList()

    private fun intervalsMs(ts: List<Long>): List<Double> =
        ts.zipWithNext { a, b -> (b - a).toMs() }

    private fun Long.toMs(): Double = this / 1_000_000.0

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = (p / 100.0) * (sorted.size - 1)
        val low = rank.toInt()
        val high = (low + 1).coerceAtMost(sorted.size - 1)
        return sorted[low] + (sorted[high] - sorted[low]) * (rank - low)
    }

    private fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun medianAbsoluteDeviation(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val median = percentile(values, 50.0)
        return percentile(values.map { kotlin.math.abs(it - median) }, 50.0)
    }
}
