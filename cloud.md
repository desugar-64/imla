# Imla Agent Notes

## Scene2 Backdrop Pass Ownership
- Cross-pass sampling data should travel as texture metadata, not framebuffer
  ownership. `BackdropTexture` carries texture/rect/UV/origin sampling data.
  `BorrowedBackdropTexture` carries the framebuffer only so the producing pass
  can release pooled GL storage.

## Scene Metrics Overlay Labels
- `FPS` is GL render-complete callbacks per recent second; use it as an output
  cadence indicator, not a full UI-thread frame-rate proof.
- `Frame budget` is the display frame budget from refresh rate.
- `Frame submit` is source pre-draw to GL queue submission.
- `Capture -> submit` is not capture work. It is the gap after all captures and
  snapshot assembly are ready, before the request reaches GL submission.
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
  snapshots closed before GL import. `Capture failures` counts root capture
  attempts that did not produce a buffer.
- Scene counters from Perfetto explain work volume, such as captures, geometry
  refreshes, render reasons, or scene renders. They do not prove pixel
  correctness or replace the overlay timing labels.
