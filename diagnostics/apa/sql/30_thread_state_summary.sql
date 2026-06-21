SELECT
  thread.name AS thread_name,
  thread_state.state,
  COUNT(*) AS samples,
  ROUND(SUM(thread_state.dur) / 1000000.0, 3) AS total_ms
FROM thread_state
JOIN thread USING (utid)
JOIN process USING (upid)
WHERE process.name = 'dev.serhiiyaremych.imla'
  AND thread.name IN ('GLUiLayerRender', 'RenderThread', 'iiyaremych.imla')
GROUP BY thread.name, thread_state.state
ORDER BY thread.name, SUM(thread_state.dur) DESC;
