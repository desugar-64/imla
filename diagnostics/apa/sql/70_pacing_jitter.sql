-- Capture-pipeline pacing jitter.
--
-- Headline question: is the capture dispatch (SceneCaptureKick) vsync-aligned?
-- A regular cadence shows a tight interval spread near the refresh period and a
-- small, stable offset from Choreographer#doFrame. view.post smears both.
--
-- Percentiles/stddev live in CapturePacingMetric (the macrobenchmark). This file
-- is the quick human-readable companion for ad-hoc tools/imla-perfetto-feedback
-- traces: min/avg/max of intervals and the doFrame phase offset, in ms.

WITH kick AS (
    SELECT ts, utid FROM thread_slice WHERE name = 'SceneCaptureKick'
),
present AS (
    SELECT ts FROM thread_slice WHERE name = 'SceneGlPresent'
),
frame AS (
    SELECT ts FROM slice WHERE name LIKE 'Choreographer#doFrame%'
),
kick_iv AS (
    SELECT (ts - LAG(ts) OVER (ORDER BY ts)) / 1e6 AS ms FROM kick
),
present_iv AS (
    SELECT (ts - LAG(ts) OVER (ORDER BY ts)) / 1e6 AS ms FROM present
),
phase AS (
    SELECT (kick.ts - (
        SELECT MAX(frame.ts) FROM frame
        WHERE frame.ts <= kick.ts
    )) / 1e6 AS ms
    FROM kick
)
SELECT 'captureKickIntervalMs' AS metric,
       COUNT(ms) AS samples, MIN(ms) AS min_ms, AVG(ms) AS avg_ms, MAX(ms) AS max_ms
FROM kick_iv WHERE ms IS NOT NULL
UNION ALL
SELECT 'captureKickVsyncPhaseMs',
       COUNT(ms), MIN(ms), AVG(ms), MAX(ms)
FROM phase WHERE ms IS NOT NULL
UNION ALL
SELECT 'glPresentIntervalMs',
       COUNT(ms), MIN(ms), AVG(ms), MAX(ms)
FROM present_iv WHERE ms IS NOT NULL;
