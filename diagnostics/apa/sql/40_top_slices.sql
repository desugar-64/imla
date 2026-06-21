SELECT
  thread.name AS thread_name,
  slice.name AS slice_name,
  COUNT(*) AS count,
  ROUND(SUM(slice.dur) / 1000000.0, 3) AS total_ms,
  ROUND(AVG(slice.dur) / 1000000.0, 3) AS avg_ms,
  ROUND(MAX(slice.dur) / 1000000.0, 3) AS max_ms
FROM slice
JOIN thread_track ON slice.track_id = thread_track.id
JOIN thread USING (utid)
JOIN process USING (upid)
WHERE process.name = 'dev.serhiiyaremych.imla'
  AND slice.dur > 0
GROUP BY thread.name, slice.name
ORDER BY SUM(slice.dur) DESC
LIMIT 40;
