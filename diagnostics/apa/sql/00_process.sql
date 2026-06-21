SELECT
  process.name AS process_name,
  process.pid,
  process.upid,
  thread.name AS thread_name,
  thread.tid,
  thread.utid
FROM process
LEFT JOIN thread USING (upid)
WHERE process.name = 'dev.serhiiyaremych.imla'
ORDER BY thread_name;
