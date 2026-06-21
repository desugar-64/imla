# Scene Metrics Overlay Labels

- `FPS` is GL render-complete callbacks per recent second; use it as an output
  cadence indicator, not a full UI-thread frame-rate proof.
- `Frame budget` is the display frame budget from refresh rate.
- `Frame submit` is source pre-draw to GL queue submission.
- `Capture -> submit` is not capture work. It is the gap after all captures and
  snapshot assembly are ready, before the request reaches GL submission.
- `Root after render` is the time from the previous GL render-complete callback
  to the next root capture start. It helps identify whether root capture starts
  too close to scene presentation.
- `room` is frame budget minus the reported phase total. Negative room means
  that phase alone exceeded the display budget.
- `GL thread frame` is root texture import, slot texture import, and scene pipeline
  render for one submitted scene frame.
- Scene pass rows are GL-thread CPU wall time spent issuing fixed renderer
  passes. They are not GPU timer-query measurements.
- `Backdrop prefilter` reports latest prepare pass calls, output pixels written
  into prepared textures, and estimated texture samples. The current
  Wronski-style prepare filter uses 8 taps per output pixel.
- `Backdrop blur stage` currently reports the diagnostic processed-backdrop copy
  stage. It uses one texture sample per output pixel until real blur kernels are
  wired.
- `Input -> rendered` is source pre-draw to GL render completion. `Submit -> GL
  start` is main submission to GL root import start.
- `Frames` compares completed GL frames with submitted snapshots. `Drops` counts
  snapshots closed before GL import. Collapsed `Skip` reports pressure-driven
  dirty ticks that skipped full scene work. `Pacing capture` counts dirty source
  ticks skipped before root capture to enforce scene2 presentation cadence.
  `Pacing render` counts snapshots closed because a GL render request callback
  is still in flight. `Pressure skips` counts production pressure ticks that
  skipped root capture, snapshot assembly, GL submission, and final
  presentation. `Pacing root` counts root captures deferred to a later
  Choreographer callback. `Capture failures` counts root capture attempts that
  did not produce a buffer.
- Scene counters from Perfetto explain work volume, such as captures, geometry
  refreshes, render reasons, or scene renders. They do not prove pixel
  correctness or replace the overlay timing labels.
