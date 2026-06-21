SELECT
  thread.name AS thread_name,
  thread.tid,
  COUNT(*) AS slices,
  ROUND(SUM(sched_slice.dur) / 1000000.0, 3) AS running_ms
FROM sched_slice
JOIN thread USING (utid)
JOIN process USING (upid)
WHERE process.name = 'dev.serhiiyaremych.imla'
GROUP BY thread.utid
ORDER BY SUM(sched_slice.dur) DESC
LIMIT 25;
