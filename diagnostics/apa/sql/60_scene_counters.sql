SELECT
  counter_track.name AS counter_name,
  COUNT(counter.id) AS samples,
  ROUND(MIN(counter.value), 3) AS min_value,
  ROUND(MAX(counter.value), 3) AS max_value,
  ROUND(MAX(counter.value) - MIN(counter.value), 3) AS observed_delta
FROM counter
JOIN counter_track ON counter.track_id = counter_track.id
WHERE counter_track.name GLOB 'ImlaScene/*'
GROUP BY counter_track.name
ORDER BY counter_track.name;
