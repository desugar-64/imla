SELECT
  thread.name AS thread_name,
  slice.name AS slice_name,
  COUNT(*) AS count,
  ROUND(MIN(slice.dur) / 1000000.0, 3) AS min_ms,
  ROUND(AVG(slice.dur) / 1000000.0, 3) AS avg_ms,
  ROUND(MAX(slice.dur) / 1000000.0, 3) AS max_ms,
  ROUND(SUM(slice.dur) / 1000000.0, 3) AS total_ms
FROM slice
JOIN thread_track ON slice.track_id = thread_track.id
JOIN thread USING (utid)
JOIN process USING (upid)
WHERE process.name = 'dev.serhiiyaremych.imla'
  AND (
    slice.name GLOB 'UiLayerRenderer#*'
    OR slice.name GLOB 'ImlaSceneCoordinator#*'
    OR slice.name GLOB 'RenderableRootLayer#*'
    OR slice.name GLOB 'GraphicsLayerTexture#*'
    OR slice.name GLOB 'SceneGlRenderer#*'
    OR slice.name GLOB 'SceneLayerRepository#*'
    OR slice.name GLOB 'SceneMaskRepository#*'
    OR slice.name GLOB 'SceneBackdropBlurNode#*'
    OR slice.name GLOB 'StackedRegionRenderingPipeline#*'
    OR slice.name GLOB 'Renderer2D#*'
    OR slice.name GLOB 'QuadBatchRenderer#*'
    OR slice.name GLOB 'GaussianBlurEffect#*'
    OR slice.name GLOB 'PreProcessEffect#*'
    OR slice.name GLOB 'glBufferData*'
    OR slice.name GLOB 'glBufferSubData*'
    OR slice.name = 'vboSetData'
  )
GROUP BY thread.name, slice.name
ORDER BY SUM(slice.dur) DESC;
