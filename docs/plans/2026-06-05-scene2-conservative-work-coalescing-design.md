# Scene2 Conservative Work Coalescing Design

Date: 2026-06-05

## Goal

Keep scene2 production pacing conservative under display-queue pressure.
The renderer may skip a dirty source tick only when the previous visible scene
frame is safe to keep on screen and the last completed eligible root captures
proved sustained pressure.

## Decision

Do not promote root clean-plate reuse to production from the current evidence.
It reduced fresh root capture count, but did not reduce bad-window frame time or
app `RenderThread` `dequeueBuffer`.

Keep the production mitigation as scene-work coalescing:

1. Skip the whole scene work tick before root capture starts.
2. Leave the previously presented scene frame visible.
3. Allow this only in normal mode.
4. Require an already reusable GL frame.
5. Require stable slot content reuse.
6. Require that no slot content capture is needed.
7. Require two completed eligible slow root captures before arming pressure.
8. Alternate at most one skipped dirty tick per real rendered frame.
9. Reset after two eligible recovery captures, idle, detach, surface clear,
   resize, root capture failure, or any ineligible captured frame.

This is stricter than counting pressure from any root capture. Root-only bad
windows must not arm plain-slot coalescing by themselves.

## Ownership And Threading

The main thread owns the coalescing decision and records completed root capture
duration. GL owns the reusable imported scene resources. The policy must not
receive renderer objects, GL texture ids, or Compose `GraphicsLayer` objects.

Pressure learning happens after a real root capture completes, using the
eligibility of that captured frame. Skipped dirty ticks do not re-count stale
root capture duration.

## Visual Contract

A skipped tick may show the previous good scene frame for one dirty tick under
pressure. It must not introduce black frames, crop shift, Y-flip, stretch,
alpha/color flashes, slot misalignment, or backdrop blur edge artifacts.

Backdrop slots stay on the normal capture path in production. Forced backdrop
reuse remains diagnostic-only.

## Verification

Required focused checks:

- `./gradlew -q :imla:testDebugUnitTest --tests 'dev.serhiiyaremych.imla.uirenderer.scene2.scheduler.*'`
- `./gradlew -q compileDebugKotlin`
- `./gradlew -q :app:assembleBenchmark`
- T811 benchmark install, speed compile, and paired Perfetto traces for
  root-only, plain-slot, and backdrop blur.

Accept a clean-window run with zero `SceneFramePace#WorkCoalesced` slices as a
non-regression check. Bad-window tuning requires paired same-window traces.

## Non-Goals

- Do not re-enable root-only coalescing.
- Do not broaden stable content reuse to backdrop slots.
- Do not use `GLRenderer` render-complete callbacks as HWC buffer-release
  signals.
- Do not skip fresh slot content captures.
- Do not restore removed renderer APIs or pass renderer objects into child
  slots.
