INCLUDE PERFETTO MODULE android.frames.timeline;
INCLUDE PERFETTO MODULE android.frames.per_frame_metrics;

SELECT
  COUNT(*) AS frames,
  SUM(stats.was_jank) AS janky_frames,
  SUM(stats.was_big_jank) AS big_jank_frames,
  ROUND(AVG(frames.dur) / 1000000.0, 3) AS avg_frame_ms,
  ROUND(MAX(frames.dur) / 1000000.0, 3) AS max_frame_ms,
  ROUND(MAX(stats.overrun) / 1000000.0, 3) AS max_overrun_ms,
  ROUND(AVG(stats.cpu_time) / 1000000.0, 3) AS avg_cpu_ms,
  ROUND(AVG(stats.ui_time) / 1000000.0, 3) AS avg_ui_ms
FROM android_frames AS frames
JOIN android_frame_stats AS stats USING (frame_id)
WHERE frames.process_name = 'dev.serhiiyaremych.imla';
