# Scene2 Performance Profiling

Date: 2026-06-04

This file tracks scene2 scratch renderer profiling evidence and decisions.
Use it to record baseline captures, trace artifacts, counter summaries,
findings, rejected hypotheses, and the next narrow profiling question.

The goal is to identify where time is actually spent before changing renderer
code. Do not treat debug overlay screenshots or intuition as proof of a root
cause.

## Profiling Contract

- Use the `benchmark` app variant for performance baselines.
- Verify the app is release-like, non-debuggable, profileable, minified, and
  ART-compiled before trusting device timing.
- Prefer warmed continuous-render traces over launch or first-frame traces.
- Reject traces contaminated by app launch, compilation, device sleep, stale
  process state, or unrelated user interaction.
- Compare overlay metrics, Perfetto slices, and scene counters before choosing
  an optimization.
- Keep screenshot and trace artifacts under `diagnostics/apa/`; they remain
  untracked unless explicitly promoted.

## Baseline Device

- Device: `T81164GB23417442888`
- Model: `KidzPad_Pro`
- Resolution: `1200x1920`
- Package: `dev.serhiiyaremych.imla`
- App variant: `benchmark`

## Device Hardware Budget

Device facts captured from ADB on 2026-06-04:

- Manufacturer/model: `Alldocube T811`
- SoC/platform: `Unisoc T606`, platform `ums9230`
- GPU: `ARM Mali-G57`, driver reports OpenGL ES `3.2`
- GPU driver string:
  `OpenGL ES 3.2 v1.r40p0-01eac0.40e4defc2c2be7601a8ecd13c8057aa1`
- Display mode: `1200x1920`, `60.00 Hz`
- Physical display DPI from SurfaceFlinger: about `270 dpi`
- Android logical density: physical `360`, override `282`
- SurfaceFlinger reports native-fence sync support:
  `EGL_ANDROID_native_fence_sync`, `EGL_KHR_wait_sync`
- SurfaceFlinger display composition during the sampled dump used hardware
  composer device composition, not client composition, for the active display.

External reference notes:

- Public T606 summaries identify the SoC as entry-level, with Mali-G57 MP1
  around `650 MHz` and LPDDR4X `1600 MHz` memory controller.
- Arm positions Mali-G57 as a mainstream Valhall GPU. Arm's Mali-G57 counter
  guide recommends setting a per-pass cycle-per-pixel budget based on target
  resolution and frame rate, because 1080p60 on mass-market devices leaves a
  small per-pixel budget.
- Arm's Mali-G57 bandwidth guidance says external DRAM access is expensive and
  gives a practical mobile rule of thumb of about `100 MB/frame` at `60 FPS` for
  a typical sustainable DRAM-access budget.

Rough local budget math:

- Full display pixels: `1200 * 1920 = 2,304,000 px`.
- One full-screen RGBA8888 buffer: about `9.2 MB`.
- One full-screen write at `60 FPS`: about `553 MB/s`.
- One full-screen read plus write at `60 FPS`: about `1.1 GB/s`.
- The static-root blur profiling slot captured at `388x240`, about `93,120 px`
  or `0.37 MB` for one RGBA8888 buffer.
- A single slot-sized read plus write is small relative to the display, but
  repeated offscreen passes, root capture, scene2 surface presentation,
  SurfaceFlinger/HWC composition, and Android RenderThread capture can quickly
  add multiple full-screen-equivalent memory touches.

Interpretation:

- A strict `60 FPS` full-resolution target on this device is likely unrealistic
  for continuous blur plus Compose capture and scene2 presentation.
- The current traces do not prove a pure shader/fill-rate bottleneck. In the
  static-root blur trace, scene2 GL render and final present stayed in the same
  band while slot `GraphicsLayerCapture#drawWait[388x240]` remained around the
  `9 ms` class.
- Treat this device as a low-end stress target. Use it to find avoidable
  synchronization, recapture, and presentation pressure, then decide whether
  production should use adaptive cadence, reduced blur quality, smaller sample
  regions, or lower-resolution intermediates on similar hardware.

## Setup Checklist

Before each trusted baseline:

- [ ] Build `:app:assembleBenchmark`.
- [ ] Install `app/build/outputs/apk/benchmark/app-benchmark.apk`.
- [ ] Force-stop `dev.serhiiyaremych.imla`.
- [ ] Run `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
- [ ] Confirm `dumpsys package dexopt dev.serhiiyaremych.imla` reports
  `arm64: [status=speed]`.
- [ ] Launch the target scene and let it settle before capture.
- [ ] Capture Perfetto and a screenshot from the same warmed scenario.
- [ ] Force-stop `dev.serhiiyaremych.imla` after diagnostics.

## Scene2 Profiling Scene

`Scene2ProfilingScene` is a benchmark-only diagnostic scene. It keeps the root
checkerboard and one centered slot stable while enabling one controlled feature
step at a time. The current stress scene remains the default unless a profiling
log tag is enabled in the `benchmark` build.

Enable one case before launching the app:

```bash
tools/adb-timeout --device T81164GB23417442888 --timeout 20 shell setprop log.tag.ImlaScene2ProfileSlot DEBUG
```

Reset tags after diagnostics:

```bash
tools/adb-timeout --device T81164GB23417442888 --timeout 20 shell 'setprop log.tag.ImlaScene2Profiling INFO; setprop log.tag.ImlaScene2ProfileRoot INFO; setprop log.tag.ImlaScene2ProfileSlot INFO; setprop log.tag.ImlaScene2ProfileBlur INFO; setprop log.tag.ImlaScene2ProfileStaticRootBlur INFO; setprop log.tag.ImlaScene2ProfileFrozenSlotBlur INFO; setprop log.tag.ImlaScene2ProfileGeometrySlotBlur INFO; setprop log.tag.ImlaScene2ProfileSmallSlotBlur INFO; setprop log.tag.ImlaScene2ProfileLargeSlotBlur INFO; setprop log.tag.ImlaScene2ProfileAnimatedSize INFO; setprop log.tag.ImlaScene2ProfileTint INFO; setprop log.tag.ImlaScene2ProfileNoise INFO; setprop log.tag.ImlaScene2ProfileClip INFO; setprop log.tag.ImlaScene2ProfileMask INFO; setprop log.tag.ImlaScene2ProfileRotation INFO; setprop log.tag.ImlaScene2ProfileTranslation INFO; setprop log.tag.ImlaScene2ProfileCumulative INFO; setprop log.tag.ImlaScene2ProfileMaterialBottomSheetVisualBounds INFO; setprop log.tag.ImlaScene2DiagCaptureOnly INFO; setprop log.tag.ImlaScene2DiagImportOnly INFO; setprop log.tag.ImlaScene2DiagReuseStableSlotContent INFO; setprop log.tag.ImlaScene2DiagNoFinalPresent INFO; setprop log.tag.ImlaScene2DiagPressureNoFinalPresent INFO; setprop log.tag.ImlaScene2DiagReuseStableSlotContentNoPresent INFO; setprop log.tag.ImlaScene2DiagReuseStableSlotContentPresentEvery2 INFO; setprop log.tag.ImlaScene2DiagReuseStableSlotContentPresentEvery4 INFO; setprop log.tag.ImlaScene2DiagReuseStableSlotContentAdaptivePresent INFO; setprop log.tag.ImlaScene2DiagReuseStableSlotContentHalfViewportPresent INFO; setprop log.tag.ImlaScene2DiagRootCleanPlateReuse INFO; setprop log.tag.ImlaScene2DiagRootCaptureScale65 INFO; setprop log.tag.ImlaScene2DiagRootCaptureScale50 INFO; setprop log.tag.ImlaScene2DiagSurfaceTextureRootCapture INFO; setprop log.tag.ImlaScene2DiagCachedGlOnly INFO; setprop log.tag.ImlaScene2DiagCachedGlNoPresent INFO; setprop log.tag.ImlaScene2DiagCachedGlPaced INFO'
```

Cases:

- `ImlaScene2Profiling`: default profiling route, root-only case.
- `ImlaScene2ProfileRoot`: checkerboard plus centered content, no slot.
- `ImlaScene2ProfileSlot`: adds `effectLayer {}` content capture/composite.
- `ImlaScene2ProfileBlur`: adds backdrop blur.
- `ImlaScene2ProfileStaticRootBlur`: adds backdrop blur, keeps slot content
  animation active, and removes the root marker animation.
- `ImlaScene2ProfileFrozenSlotBlur`: adds backdrop blur, freezes visible root
  and slot content, and uses an invisible root tick only to keep capture cadence
  measurable.
- `ImlaScene2ProfileGeometrySlotBlur`: adds backdrop blur, freezes slot content,
  removes visible root animation, and moves slot geometry.
- `ImlaScene2ProfileSmallSlotBlur`: adds backdrop blur, removes visible root
  animation, keeps slot content animation active, and halves the slot size to
  reduce pixel pressure.
- `ImlaScene2ProfileLargeSlotBlur`: adds backdrop blur, freezes visible root
  and slot content, and uses a larger slot to stress root/display queue
  pressure.
- `ImlaScene2ProfileAnimatedSize`: adds slot content whose size changes over
  time.
- `ImlaScene2ProfileTint`: adds blur plus tint.
- `ImlaScene2ProfileNoise`: adds blur, tint, and noise.
- `ImlaScene2ProfileClip`: adds blur, tint, noise, and clip.
- `ImlaScene2ProfileMask`: adds blur, tint, noise, clip, and progressive mask.
- `ImlaScene2ProfileRotation`: adds animated rotation over blur/tint/noise/clip.
- `ImlaScene2ProfileTranslation`: adds animated translation over blur/tint/noise/clip.
- `ImlaScene2ProfileCumulative`: adds two overlapping slots so the second
  backdrop samples prior slot contribution.
- `ImlaScene2ProfileMaterialBottomSheetVisualBounds`: shows a standard
  Material bottom sheet whose scene slot is full-height while its visible
  material panel moves between lower and upper positions. The scene slot uses a
  local visual-bounds override so blur, content, mask, and clip bounds follow
  the visible panel instead of the full measured sheet layout.

Diagnostic isolation modes can be combined with one scene case tag:

- `ImlaScene2DiagCaptureOnly`: captures root, content, mask, and clip resources,
  then closes the snapshot before GL submission. Use this to measure Android
  capture and `CanvasBufferedRenderer` wait without GL import/render work.
- `ImlaScene2DiagImportOnly`: captures and submits normally, imports
  `HardwareBuffer` resources into GL, then retains them without requesting a
  draw. Use this to measure import/EGLImage churn without scene pass work.
- `ImlaScene2DiagReuseStableSlotContent`: forces unchanged slot content reuse
  after the first import while normal final presentation remains enabled.
  Normal rendering already reuses stable content for non-backdrop slots; this
  diagnostic still exists to force reuse for backdrop/effect isolation.
- `ImlaScene2DiagNoFinalPresent`: renders the scene pipeline but skips the
  final default-framebuffer presentation draw.
- `ImlaScene2DiagPressureNoFinalPresent`: keeps capture, snapshot assembly, GL
  import, and scene rendering active, but skips final presentation after two
  consecutive root captures slower than `0.66` of the display frame budget. It
  resumes after two recovered captures. Trace labels
  `SceneFinalPresentPressure#present` and `SceneFinalPresentPressure#skip`
  report the decision.
- `ImlaScene2DiagRootCleanPlateReuse`: after two consecutive slow root
  captures, skips one root clean-plate capture, submits the scene snapshot
  anyway, and asks GL to reuse the previous root texture for that frame.
- `ImlaScene2DiagRootCaptureScale65` and `ImlaScene2DiagRootCaptureScale50`:
  render the root capture into a smaller physical buffer while keeping the
  logical root size for GL sampling.
- `ImlaScene2DiagSurfaceTextureRootCapture`: captures the root through the
  SurfaceTexture path, copies it into a normal `TEXTURE_2D`, and submits that
  copied texture as the scene root.
- `ImlaScene2DiagReuseStableSlotContentNoPresent`: combines stable slot content
  reuse with skipped final presentation.
- `ImlaScene2DiagReuseStableSlotContentPresentEvery2` and
  `ImlaScene2DiagReuseStableSlotContentPresentEvery4`: reuse stable slot
  content and draw final presentation only once per fixed cadence.
- `ImlaScene2DiagReuseStableSlotContentAdaptivePresent`: reuses stable slot
  content and adapts final presentation cadence from recent root capture wait.
  Trace labels `SceneFinalPresentCadence#adaptive[N]` report the selected
  cadence.
- `ImlaScene2DiagReuseStableSlotContentHalfViewportPresent`: reuses stable slot
  content and draws the final scene into a centered half-size default-framebuffer
  viewport. Trace labels `SceneFinalPresentPass#viewport[WxH]` report the
  presented viewport size.
- `ImlaScene2DiagCachedGlOnly`: seeds GL resources with one normal captured
  frame, then skips later Android capture/import and requests redraws of the
  cached GL scene. Use this to measure GL pass/FBO cost with stable input
  textures.
- `ImlaScene2DiagCachedGlNoPresent`: same cached GL scene, but skips
  `SceneFinalPresentPass`. Use this to isolate offscreen backdrop/FBO passes
  from final default-framebuffer presentation work.
- `ImlaScene2DiagCachedGlPaced`: same cached GL scene and final present, but
  requests one redraw per four root-dirty ticks. Use this to test whether
  full-rate request pressure is causing surface queue backpressure.

Capture trace labels now split `GraphicsLayerCapture` into:

- `GraphicsLayerCapture#capture[WxH]`
- `GraphicsLayerCapture#replayLayerToRenderNode[WxH]`
- `GraphicsLayerCapture#drawWait[WxH]`

Use these labels to distinguish cheap display-list replay from synchronous
`CanvasBufferedRenderer` wait time.

Expanded overlay and Perfetto counter snapshots now include GL resource
lifetime counters:

- `FBO`: OpenGL framebuffer object creation/destruction.
- `Texture`: ordinary GL texture creation/destruction, including FBO
  attachments.
- `HB tex`: GL textures/EGLImages imported from Android `HardwareBuffer`.
- `Shader`: GL shader/program wrappers.

Each row reports `active/peak` and `created/destroyed`. A steadily growing
`active` count indicates a likely leak. A steadily growing `created` count with
bounded `active` indicates churn, such as per-frame hardware-buffer imports.
Perfetto counters use the `ImlaScene2Resource.*` prefix.
`HB tex` is additionally split by source as `root`, `content`, `mask`, `clip`,
and `other`, with cumulative imported pixels and bytes.

## Current Symptom

Existing screenshots show scene2 missing frame budget even before a full
baseline pass:

- Debug smoke screenshot:
  `diagnostics/apa/scene2-metrics-overlay-expanded-updated-half.png`
  - `Frame submit`: about `167.69 ms`
  - `Content captures`: about `132.05 ms`
  - `GL thread frame`: about `49.83 ms`
  - Interpretation: useful phase direction only; not a trusted performance
    baseline.
- Older release-like screenshot:
  `diagnostics/apa/scene2-benchmark-r8full-speed-metrics-topleft.png`
  - `FPS`: about `30.5`
  - `Frame submit`: about `26.26 ms`
  - `GL`: about `9.94 ms`
  - `Input -> rendered`: about `66.73 ms`
  - Interpretation: enough to justify profiling; not enough to prove the root
    cause.

## Run Log

### Profiling Scene Smoke: Slot content

- Date: 2026-06-04 18:49 CEST
- Commit: local work after `078e7fd`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Case tag: `log.tag.ImlaScene2ProfileSlot=DEBUG`
- Screenshot: `diagnostics/apa/scene2-profile-slot-smoke-half.png`
- Trace: `diagnostics/apa/traces/imla-smoke-2026-06-04-184938.perfetto-trace`
- Analysis directory:
  `diagnostics/apa/traces/imla-smoke-2026-06-04-184938.perfetto-trace_analysis/`
- Frame stats:
  - Frames: `34`
  - Janky frames: `33`
  - Average frame: `20.803 ms`
  - Max frame: `33.508 ms`
  - Average UI: `18.052 ms`
- Capture trace labels:
  - `GraphicsLayerCapture#capture[1200x1920]`: `34` calls,
    `8.952 ms` average.
  - `GraphicsLayerCapture#drawWait[1200x1920]`: `34` calls,
    `8.837 ms` average.
  - `GraphicsLayerCapture#replayLayerToRenderNode[1200x1920]`: `34` calls,
    `0.065 ms` average.
  - `GraphicsLayerCapture#capture[388x240]`: `34` calls,
    `4.655 ms` average, `19.560 ms` max.
  - `GraphicsLayerCapture#drawWait[388x240]`: `34` calls,
    `4.545 ms` average, `19.481 ms` max.
  - `GraphicsLayerCapture#replayLayerToRenderNode[388x240]`: `34` calls,
    `0.056 ms` average.
- Initial finding:
  - The new trace labels work. In the controlled slot-content case, replaying
    the Compose layer into the capture `RenderNode` is tiny; synchronous
    `drawWait` accounts for nearly all capture wall time.

### Profiling Ladder 1: Continuous root and slot updates

- Date: 2026-06-04 18:55-18:59 CEST
- Commit: local work after `078e7fd`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Scene: `Scene2ProfilingScene`
- Update model: every case animates a small root marker and a small marker
  inside the slot content. Rotation and translation cases additionally animate
  the slot transform.
- Device dexopt status: `cmd package compile -m speed -f dev.serhiiyaremych.imla`
  returned `Success` before the ladder.

| Case | Trace | Avg frame | Avg UI | Root draw wait | Slot draw wait | GL render | Notable pass |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| root | `diagnostics/apa/traces/imla-smoke-2026-06-04-185538.perfetto-trace` | `22.305 ms` | `13.976 ms` | `10.559 ms` | none | `4.739 ms` | final present `4.037 ms` |
| slot | `diagnostics/apa/traces/imla-smoke-2026-06-04-185604.perfetto-trace` | `22.741 ms` | `19.931 ms` | `5.609 ms` | `11.002 ms` | `2.331 ms` | no backdrop pass |
| blur | `diagnostics/apa/traces/imla-smoke-2026-06-04-185629.perfetto-trace` | `29.299 ms` | `25.249 ms` | `5.699 ms` | `16.403 ms` | `5.275 ms` | prepare `2.395 ms`, blur `1.568 ms` |
| tint | `diagnostics/apa/traces/imla-smoke-2026-06-04-185655.perfetto-trace` | `29.584 ms` | `25.646 ms` | `5.972 ms` | `16.540 ms` | `5.236 ms` | prepare `2.388 ms`, blur `1.564 ms` |
| noise | `diagnostics/apa/traces/imla-smoke-2026-06-04-185721.perfetto-trace` | `29.794 ms` | `25.481 ms` | `5.907 ms` | `16.462 ms` | `5.383 ms` | prepare `2.443 ms`, blur `1.599 ms` |
| clip | `diagnostics/apa/traces/imla-smoke-2026-06-04-185747.perfetto-trace` | `33.492 ms` | `28.426 ms` | `6.426 ms` | `18.766 ms` | `5.599 ms` | stencil `0.978 ms` |
| mask | `diagnostics/apa/traces/imla-smoke-2026-06-04-185813.perfetto-trace` | `34.152 ms` | `29.032 ms` | `6.541 ms` | `19.326 ms` | `5.623 ms` | stencil `0.961 ms` |
| rotation | `diagnostics/apa/traces/imla-smoke-2026-06-04-185838.perfetto-trace` | `33.695 ms` | `28.343 ms` | `6.364 ms` | `18.718 ms` | `5.610 ms` | stencil `1.010 ms` |
| translation | `diagnostics/apa/traces/imla-smoke-2026-06-04-185904.perfetto-trace` | `33.666 ms` | `28.392 ms` | `6.411 ms` | `18.683 ms` | `5.644 ms` | stencil `1.021 ms` |
| cumulative | `diagnostics/apa/traces/imla-smoke-2026-06-04-185930.perfetto-trace` | `36.355 ms` | `33.670 ms` | `5.183 ms` | `12.406 ms` per `388x240` capture, two slot captures per frame | `5.811 ms` | prepare `1.198 ms`, two calls per frame |

Findings:

- The first major feature step is backdrop blur. It raises the controlled slot
  draw wait from about `11.0 ms` to about `16.4 ms`, and raises GL render from
  about `2.3 ms` to about `5.3 ms`.
- Tint and noise are not separate major cost steps in this ladder; they stay in
  the same range as blur.
- Clip adds the next meaningful step. Slot draw wait rises to about `18.8 ms`,
  and stencil setup appears at about `1.0 ms` per frame.
- Progressive mask does not add a large additional steady-state step beyond the
  clipped blur case in this run. The current mask case changes content every
  frame, but the mask brush itself is stable.
- Rotation and translation over blur/tint/noise/clip remain in the same band as
  the static clipped case. They do not appear to be the dominant steady-state
  cost in this controlled ladder.
- Cumulative has the worst average frame time in the ladder because it performs
  two slot captures and two backdrop prepare calls per frame. The per-slot draw
  wait is lower than the one-slot clipped cases, but total frame work is higher.
- The dominant recurring capture phase is still `drawWait`, not
  `replayLayerToRenderNode`.

Question for overlap follow-up:

- Is blur increasing capture wait because the prior scene2 GL work backs up the
  shared GPU queue before the next synchronous Android capture, or because the
  blurred slot content itself changes the Android capture dependency chain?

### Follow-up: Slot capture overlap with GL and GPU waits

- Date: 2026-06-04
- Source traces:
  - Slot:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-185604.perfetto-trace`
  - Blur:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-185629.perfetto-trace`
  - Clip:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-185747.perfetto-trace`
- Method: Perfetto SQL overlap between each
  `GraphicsLayerCapture#drawWait[388x240]` slice and:
  - `Scene2RenderPipeline#render`
  - `GPU completion` thread `waitForever`
  - `RenderThread` `waitForever`
  - RenderThread slot drawing
    `Drawing  0.00  0.00 388.00 240.00`

| Case | Slot draw wait | Scene2 render overlap | GPU completion overlap | RenderThread wait overlap | Slot drawing overlap |
| --- | ---: | ---: | ---: | ---: | ---: |
| slot | `11.005 ms` | `0.165 ms` | `9.446 ms` | `7.878 ms` | `10.129 ms` |
| blur | `16.403 ms` | `2.553 ms` | `13.945 ms` | `13.378 ms` | `15.539 ms` |
| clip | `18.804 ms` | `2.288 ms` | `16.548 ms` | `15.789 ms` | `17.921 ms` |

Interpretation:

- The blur step raises slot capture wait mostly by increasing the time spent
  inside RenderThread slot drawing while RenderThread and the GPU-completion
  worker are blocked in `waitForever`.
- Direct overlap between the main-thread slot `drawWait` and
  `Scene2RenderPipeline#render` increases in the blur and clip cases, but it is
  only a few milliseconds. The larger wait is seen through the shared Android
  RenderThread/GPU-completion path.
- `effectLayer { backdropBlur(...) }` records the same Compose content layer as
  `effectLayer {}`. The effect configuration is passed as scene metadata to the
  registry, so the slot-vs-blur delta is not explained by replaying different
  Compose UI into the captured layer.
- Current conclusion: the first concrete dependency is synchronous Android
  `CanvasBufferedRenderer` capture waiting behind shared GPU completion after
  scene2 has introduced backdrop GL work. The exact command/fence inside the GL
  path still needs a smaller GL-side isolation pass before changing renderer
  code.

### Follow-up: GL resource lifetime counters

- Date: 2026-06-04 19:32-19:33 CEST
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Force-stopped the app before each trace.
  - Let `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`
    be the only launcher for each trace.
- Note: an earlier 19:29-19:30 trace batch was rejected for resource-lifetime
  conclusions because the app was manually launched before the capture helper
  launched `MainActivity` again, doubling active renderer owners in the trace.

| Case | Trace | FBO active/peak | Texture active/peak | HB tex active/peak | Shader active/peak | HB tex created/destroyed |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| slot | `diagnostics/apa/traces/imla-smoke-2026-06-04-193224.perfetto-trace` | `1/1` | `2/2` | `2/4` | `6/6` | `1647/1645` |
| blur | `diagnostics/apa/traces/imla-smoke-2026-06-04-193249.perfetto-trace` | `4/4` | `5/5` | `2/4` | `6/6` | `1217/1215` |
| clip | `diagnostics/apa/traces/imla-smoke-2026-06-04-193314.perfetto-trace` | `5/5` | `6/6` | `3/5` | `7/7` | `1108/1105` |

Counter findings:

- FBO, ordinary texture, and shader active counts were flat from first queried
  sample to last queried sample in each clean trace. This does not indicate an
  endless active-resource leak in these controlled cases.
- Hardware-buffer texture active counts were also bounded, but
  `created/destroyed` increased throughout each trace. This is per-frame import
  churn, not unbounded retention.
- The next GL-side isolation question should therefore start with capture/import
  churn and synchronization behavior, not a growing FBO, ordinary texture, or
  shader cache.

### Follow-up: Blur isolation ladder

- Date: 2026-06-04 19:43-19:45 CEST
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Force-stopped the app before each trace.
  - Used `ImlaScene2ProfileBlur=DEBUG` for all rows.
  - Let `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`
    be the only launcher for each trace.

| Mode | Trace | Avg frame | Root wait | Slot wait | HB create | HB createImage | HB destroy | Render | Final present |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| normal | `diagnostics/apa/traces/imla-smoke-2026-06-04-194345.perfetto-trace` | `29.928 ms` | `6.118 ms` | `16.374 ms` | `0.791 ms` | `0.631 ms` | `0.181 ms` | `5.627 ms` | `1.215 ms` |
| capture-only | `diagnostics/apa/traces/imla-smoke-2026-06-04-194410.perfetto-trace` | `16.493 ms` | `5.850 ms` | `3.318 ms` | none | none | none | none | none |
| import-only | `diagnostics/apa/traces/imla-smoke-2026-06-04-194435.perfetto-trace` | `19.255 ms` | `6.596 ms` | `3.375 ms` | `0.939 ms` | `0.733 ms` | `0.224 ms` | none | none |
| cached-GL-only | `diagnostics/apa/traces/imla-smoke-2026-06-04-194500.perfetto-trace` | `71.330 ms` | none | none | none after seed | none after seed | none after seed | `16.677 ms` | `12.162 ms` |

Counter and slice findings:

- Normal blur imports one root and one content `HardwareBuffer` per frame.
  Source-attributed counters ended at root `566/565` and content `565/564`,
  with total HB texture active fixed at `2`.
- Capture-only performed no GL resource creation and no GL render slices. Slot
  capture wait fell from `16.374 ms` in normal blur to `3.318 ms`.
- Import-only performed per-frame root/content EGLImage import and destroy, but
  still kept slot capture wait near capture-only at `3.375 ms`. The direct
  `OpenGLHardwareBufferTexture2D#createImage` average was under `1 ms`.
- Cached-GL-only removed recurring capture/import after the seed frame, but
  exposed present/backpressure: `SceneFinalPresentPass#draw` rose to
  `12.162 ms`, `ImlaScene2GL dequeueBuffer` averaged `11.004 ms`, and GPU
  completion `waitForever` averaged `10.905 ms`.

Interpretation:

- Per-frame `HardwareBuffer`/EGLImage import churn is real, but the blur
  capture slowdown is not explained by import cost alone.
- The normal blur slot wait appears when scene2 GL render/present work is active.
  Removing render while keeping import drops slot wait back to the capture-only
  band.
- Cached-GL-only is not a clean "shader-only is slow" proof because the dominant
  work is final present and buffer dequeue/backpressure, not backdrop prepare,
  blur, or composite issue time.
- The next GL-side question should isolate final-present/surface queue behavior:
  request pacing, `eglSwapBuffers`, FBO invalidation/clear policy, and whether
  Android capture waits behind scene2 present/completion fences.

### Follow-up: Final-present and surface-queue isolation

- Date: 2026-06-04 20:04-20:05 CEST
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Force-stopped the app before each trace.
  - Used `ImlaScene2ProfileBlur=DEBUG` for all rows.
  - Let `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`
    be the only launcher for each trace.

| Mode | Trace | Avg frame | Slot wait | Render | Final present | Imla dequeue | RT dequeue | GPU wait | HWC wait |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| normal | `diagnostics/apa/traces/imla-smoke-2026-06-04-200424.perfetto-trace` | `30.039 ms` | `16.481 ms` | `5.622 ms` | `1.220 ms` | low | low | `5.420 ms` | not dominant |
| cached GL | `diagnostics/apa/traces/imla-smoke-2026-06-04-200449.perfetto-trace` | `71.926 ms` | none | `17.009 ms` | `12.795 ms` | `11.630 ms` | `19.185 ms` | `11.030 ms` | `5.576 ms` |
| cached GL, no present | `diagnostics/apa/traces/imla-smoke-2026-06-04-200514.perfetto-trace` | `49.127 ms` | none | `4.324 ms` | skipped | `0.064 ms` | `13.383 ms` | `15.060 ms` | `10.000 ms` |
| cached GL, paced | `diagnostics/apa/traces/imla-smoke-2026-06-04-200539.perfetto-trace` | `37.395 ms` | none | `6.588 ms` | `1.364 ms` | `0.070 ms` | `12.987 ms` | `7.953 ms` | `10.028 ms` |

Findings:

- Full-rate cached GL reproduces the severe present path problem without
  recurring Android capture or EGLImage import. The dominant slices are Imla GL
  `dequeueBuffer`, `SceneFinalPresentPass#draw`, RenderThread `dequeueBuffer`,
  and GPU completion waits.
- Skipping `SceneFinalPresentPass` removes Imla GL dequeue pressure and drops
  `Scene2RenderPipeline#render` from `17.009 ms` to `4.324 ms`, while backdrop
  prepare/blur/composite issue times stay in the same range. The screenshot is
  intentionally black in this mode because the offscreen scene texture is not
  presented to the default framebuffer.
- Even with final present skipped, RenderThread still blocks in `dequeueBuffer`
  around `13.383 ms`, and GPU/HWC waits remain high. This means final quad
  drawing is not the only queue-pressure source.
- Pacing cached GL to one redraw per four root-dirty ticks removes Imla GL
  dequeue pressure and restores final present to `1.364 ms`, but RenderThread
  dequeue and HWC waits still remain around `13 ms` and `10 ms`.

Interpretation:

- The backdrop shader/FBO issue time is not the dominant slow path in these
  diagnostics.
- Full-rate scene2 presentation can create its own Surface queue backpressure,
  but the larger floor is cross-surface composition/backpressure involving the
  Compose/RenderThread surface and HWC/GPU completion.
- The next optimization design should avoid continuously presenting unchanged
  scene2 output and should consider render pacing/deduplication before shader
  or FBO micro-optimizations.
- A separate follow-up can still test explicit FBO clear/invalidate policy, but
  current evidence points first at scheduling/presentation pressure.

### Follow-up: Render-complete latch probe

- Date: 2026-06-04 20:16-20:17 CEST
- Commit: local work after `ddc91b2`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Used `ImlaScene2ProfileBlur=DEBUG`.
  - Let `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`
    be the only launcher for each trace.
- Change under probe:
  - `SceneRenderTarget` now tracks one render request in flight until
    `GLRenderer.RenderTarget.requestRender` invokes its completion callback.
  - `SceneRenderer` drops captured snapshots as `RenderRequestInFlight` before GL
    import if that latch is still held.
  - The overlay splits total `Drops` from the render-request pacing subset as
    `render-paced`.

| Mode | Trace | Avg frame | Slot wait | Render | Final present | Imla dequeue | RT dequeue |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| normal blur | `diagnostics/apa/traces/imla-smoke-2026-06-04-201602.perfetto-trace` | `29.975 ms` | `16.179 ms` | `5.742 ms` | `1.238 ms` | `0.062 ms` | `0.071 ms` |
| cached GL | `diagnostics/apa/traces/imla-smoke-2026-06-04-201639.perfetto-trace` | `37.365 ms` | none | `4.497 ms` | `3.682 ms` | `1.873 ms` | `12.943 ms` |

Findings:

- The normal blur path did not materially change from the prior trace:
  `16.481 ms` slot wait before the latch versus `16.179 ms` after it, and
  `5.622 ms` render before versus `5.742 ms` after.
- No `SceneFrameDrop#RenderRequestInFlight` slices appeared in the normal blur or
  cached-GL trace. The new latch is therefore not deep enough to prove actual
  surface-drain pacing in the current steady-state runs.
- Cached GL improved compared with the prior full-rate cached-GL trace:
  `Scene2RenderPipeline#render` fell from `17.009 ms` to `4.497 ms`,
  `SceneFinalPresentPass#draw` from `12.795 ms` to `3.682 ms`, and Imla GL
  `dequeueBuffer` from `11.630 ms` to `1.873 ms`. Because the pacing-drop trace
  did not fire, this should be treated as useful but not conclusive proof that
  the latch solved queue pressure.
- AndroidX `GLRenderer` documents that `requestRender` coalesces already queued
  render requests, and source inspection shows the completion callback runs
  after `swapAndFlushBuffers`. It does not represent buffer release by
  SurfaceFlinger/HWC, so a render-complete latch is shallower than the
  backpressure observed in `dequeueBuffer` and GPU/HWC waits.

Interpretation:

- The render-complete latch is a useful safety guard and metric hook, but it is
  not the real presentation pacing mechanism for this problem.
- The next pacing experiment needs a signal beyond GLRenderer completion, such
  as explicit lower-rate scene2 presentation, latest-frame coalescing over
  multiple Choreographer ticks, or an Android surface/frame callback that
  correlates with buffer release.

### Follow-up: Pre-capture cadence pacing

- Date: 2026-06-04 20:26-20:27 CEST
- Commit: local work after `fbb306a`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Used `ImlaScene2ProfileBlur=DEBUG`.
  - Let `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`
    be the only launcher for each trace.
- Change under probe:
  - `ScenePresentationPacer` accepts one dirty source tick and skips the next
    presented-surface tick before root capture starts.
  - Capture-only, import-only, and cached-GL-no-present diagnostics are not
    cadence-paced so they remain valid isolation modes.
  - Perfetto marks skipped ticks as `SceneFramePace#CaptureSkipped`; the overlay
    reports them as `Pacing capture`.

| Mode | Trace | Avg frame | Avg UI | Capture skips | Root wait | Slot wait | Render | Final present | Imla dequeue | RT dequeue |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| normal blur | `diagnostics/apa/traces/imla-smoke-2026-06-04-202627.perfetto-trace` | `20.417 ms` | `15.254 ms` | `343` | `17.813 ms` | `5.443 ms` | `6.301 ms` | `1.320 ms` | `0.069 ms` | `0.136 ms` |
| cached GL | `diagnostics/apa/traces/imla-smoke-2026-06-04-202705.perfetto-trace` | `32.519 ms` | `10.105 ms` | `358` | none | none | `2.775 ms` | `1.923 ms` | `0.090 ms` | `12.550 ms` |

Findings:

- The cadence gate fired in both runs and skipped about half of dirty ticks
  before capture/import/present work.
- Normal blur improved materially compared with the previous render-latch trace:
  average frame time fell from `29.975 ms` to `20.417 ms`, average UI from
  `25.831 ms` to `15.254 ms`, and slot draw wait from `16.179 ms` to
  `5.443 ms`.
- Scene2 GL surface queue pressure is no longer present in the measured paths:
  normal blur Imla GL `dequeueBuffer` stayed around `0.069 ms`, and cached GL
  stayed around `0.090 ms` instead of the prior full-rate cached-GL
  `11.630 ms`.
- The remaining normal-blur cost moved mostly to full-root capture wait:
  root `drawWait[1200x1920]` rose to `17.813 ms`. This is a separate capture
  cost/fence issue, not scene2 GL dequeue pressure.
- Cached GL still shows RenderThread `dequeueBuffer` around `12.550 ms` and HWC
  release waits around `8.774 ms`; the cadence gate prevents scene2 GL from
  building its own queue pressure, but it does not remove the broader
  cross-surface composition floor.

Interpretation:

- Pre-capture cadence pacing is the first verified change in this series that
  reduces the normal blur path, because it avoids half of the root/slot capture,
  GL import, and scene2 present work instead of dropping already-captured
  snapshots.
- The goal should treat scene2 GL presentation backpressure as mitigated for
  the controlled blur/cached-GL profiling scene, with explicit remaining work on
  root capture wait and RenderThread/HWC composition pressure.

### Follow-up: Idle-aware cadence pacing

- Date: 2026-06-04 20:40-20:42 CEST
- Commit: local work after `6368573`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Captured root-only, slot-only, and blur profiling cases.
  - Let `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`
    be the only launcher for each trace.
- Change under probe:
  - `ScenePresentationPacer` now resets cadence after a dirty-source idle gap,
    so the first dirty tick after idle presents immediately.
  - Continuous dirty ticks still use the same one-present, one-skip cadence as
    the prior pre-capture pacing run.

| Case | Trace | Avg frame | Avg UI | Capture skips | Root wait | Slot wait | Render | Final present | Imla dequeue | RT dequeue |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| root | `diagnostics/apa/traces/imla-smoke-2026-06-04-204047.perfetto-trace` | `18.695 ms` | `7.490 ms` | `358` | `7.018 ms` | none | `2.429 ms` | `1.603 ms` | `0.073 ms` | `1.600 ms` |
| slot | `diagnostics/apa/traces/imla-smoke-2026-06-04-204123.perfetto-trace` | `18.094 ms` | `8.711 ms` | `358` | `5.745 ms` | `3.815 ms` | `2.705 ms` | `1.425 ms` | `0.075 ms` | `0.505 ms` |
| blur | `diagnostics/apa/traces/imla-smoke-2026-06-04-204202.perfetto-trace` | `22.931 ms` | `14.379 ms` | `347` | `17.853 ms` | `4.283 ms` | `5.881 ms` | `1.216 ms` | `0.064 ms` | `0.652 ms` |

Findings:

- The idle-aware policy preserved the continuous-animation cadence: each case
  recorded about one `SceneFramePace#CaptureSkipped` slice per presented frame.
- Cheap root and slot cases did not show a bad over-throttle tradeoff in this
  batch. Their average frame times stayed below the earlier unpaced ladder
  (`22.305 ms` root, `22.741 ms` slot), while Imla GL dequeue stayed near zero.
- Blur remained well below the earlier unpaced blur trace (`29.299 ms`) and kept
  slot draw wait in the low single digits. This run was slower than the first
  pre-capture paced blur trace (`20.417 ms`), mostly because root capture wait
  again dominated at about `17.853 ms`.
- The policy still does not solve the root-capture wait floor. It prevents
  scene2 from adding GL dequeue pressure, then exposes root capture and broader
  composition waits as the next visible costs.

Interpretation:

- The production shape is now better: first update after idle is immediate,
  while continuous dirtiness is cadence-limited before capture.
- The next optimization question should move to root capture/composition
  behavior, unless we first decide to make the cadence adaptive to scene cost
  rather than fixed for all presented scene2 output.

### Follow-up: Root capture wait isolation

- Date: 2026-06-04 20:55-20:59 CEST
- Commit: `5c95f60`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Used `ImlaScene2ProfileBlur=DEBUG` for all rows.
  - Let `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`
    be the only launcher for each trace.

| Mode | Trace | Avg frame | Root wait | Slot wait | Render | Final present | HB createImage |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| normal paced blur | `diagnostics/apa/traces/imla-smoke-2026-06-04-205550.perfetto-trace` | `21.641 ms` | `17.078 ms` | `5.234 ms` | `6.447 ms` | `1.364 ms` | `0.717 ms` |
| capture-only | `diagnostics/apa/traces/imla-smoke-2026-06-04-205630.perfetto-trace` | `16.583 ms` | `5.950 ms` | `3.337 ms` | none | none | none |
| import-only | `diagnostics/apa/traces/imla-smoke-2026-06-04-205706.perfetto-trace` | `16.402 ms` | `5.900 ms` | `3.395 ms` | none | none | `0.727 ms` |
| cached GL, no present | `diagnostics/apa/traces/imla-smoke-2026-06-04-205906.perfetto-trace` | `15.357 ms` | none in measured window | none | `0.904 ms` | skipped | none after seed |

Root `GraphicsLayerCapture#drawWait[1200x1920]` overlap:

| Mode | Avg root wait | RenderThread root drawing | GPU completion wait | RenderThread wait | Scene2 render | Final present |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| normal paced blur | `17.078 ms` | `15.633 ms` | `14.444 ms` | `11.690 ms` | `3.186 ms` | `0.982 ms` |
| capture-only | `5.950 ms` | `4.895 ms` | `1.850 ms` | `1.730 ms` | none | none |
| import-only | `5.900 ms` | `4.843 ms` | `1.806 ms` | `1.680 ms` | none | none |

RenderThread root-sized drawing state split:

| Mode | Avg root drawing slice | Running | Sleeping/waiting |
| --- | ---: | ---: | ---: |
| normal paced blur | `6.799 ms` | `2.484 ms` | `4.230 ms` |
| capture-only | `3.654 ms` | `2.416 ms` | `1.208 ms` |
| import-only | `3.645 ms` | `2.407 ms` | `1.200 ms` |

Findings:

- Root capture itself is not inherently a `17 ms` operation in this scene.
  Capture-only and import-only both keep root wait near `5.9 ms`.
- Import-only stays near capture-only even though it creates root/content
  `EGLImage` imports. Per-frame import churn is still real, but it does not
  explain the root wait spike.
- The normal presented path is different: root wait overlaps heavily with GPU
  completion and RenderThread waits. It also overlaps `Scene2RenderPipeline`,
  but only for about `3.186 ms` of a `17.078 ms` root wait.
- RenderThread root drawing does not become much more CPU-expensive in normal
  mode. The running portion remains around `2.4 ms`; the extra drawing slice
  time is mostly sleeping/waiting.
- The cached no-present trace is context only for root capture because it reuses
  cached GL content after seeding and produced no root captures in the measured
  window. It does show that removing final scene2 present keeps Imla GL render
  small and frame time low in that diagnostic mode.

Interpretation:

- The current root bottleneck is fence/composition timing around presented
  scene2 work, not root display-list replay or EGLImage import cost by itself.
- The next fix should target capture scheduling relative to scene2
  render/present, for example delaying root capture away from the frame that
  just presented scene2 output or adding a stronger coalescing phase before
  root capture. Reducing root content complexity is not the first move based on
  these traces.

### Follow-up: Root capture deferral after present

- Date: 2026-06-04 21:18 CEST
- Commit: local work after `a98697b`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Used `ImlaScene2ProfileBlur=DEBUG`.
  - Captured with
    `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`.
- Rejected trace:
  `diagnostics/apa/traces/imla-smoke-2026-06-04-210959.perfetto-trace`
  crashed on launch with `StackOverflowError` on `ImlaScene2GL`. Cause was an
  experiment callback named the same as `SceneGlOwner.onRenderComplete()`,
  which recursively called the method instead of the callback in the benchmark
  build. The callback was renamed before recapture.
- Valid trace:
  `diagnostics/apa/traces/imla-smoke-2026-06-04-211850.perfetto-trace`
- Screenshot:
  `diagnostics/apa/screenshots/imla-smoke-2026-06-04-211850.png`

| Trace | Avg frame | Root wait | Slot wait | Render | Final present | HB createImage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| previous normal paced blur | `21.641 ms` | `17.078 ms` | `5.234 ms` | `6.447 ms` | `1.364 ms` | `0.717 ms` |
| root capture deferred | `21.095 ms` | `7.442 ms` | `8.715 ms` | `6.046 ms` | `1.322 ms` | `0.670 ms` |

Deferral slices:

- `SceneRootCapture#DeferralScheduled`: `157` calls.
- `SceneRootCapture#Deferred`: `156` calls, `10.459 ms` average including the
  delayed root capture work.
- Root captures: `347` calls.

Root `GraphicsLayerCapture#drawWait[1200x1920]` overlap in the deferral trace:

| Avg root wait | RenderThread root drawing | GPU completion wait | RenderThread wait | Scene2 render | Final present |
| ---: | ---: | ---: | ---: | ---: | ---: |
| `7.442 ms` | `5.966 ms` | `4.153 ms` | `2.457 ms` | `1.245 ms` | `0.162 ms` |

Slot `GraphicsLayerCapture#drawWait[388x240]` overlap in the deferral trace:

| Avg slot wait | RenderThread slot drawing | GPU completion wait | RenderThread wait | Scene2 render | Final present |
| ---: | ---: | ---: | ---: | ---: | ---: |
| `8.715 ms` | `7.719 ms` | `5.870 ms` | `5.170 ms` | `1.745 ms` | `0.532 ms` |

Findings:

- Delaying normal-mode root capture by one Choreographer frame when dirty
  arrives just after scene2 render completion reduced root wait from the
  `17 ms` class back to the `6 ms` class.
- The root wait now looks similar to capture-only/import-only behavior: mostly
  root-sized RenderThread drawing plus a smaller GPU wait, instead of a long
  fence wait after scene2 present.
- The remaining visible capture pressure moved to slot content capture. Slot
  wait rose from `5.234 ms` in the previous normal paced blur trace to
  `8.715 ms`, and its wait overlaps slot-sized RenderThread drawing plus GPU
  completion and scene2 render/present work.
- GL render, final present, and hardware-buffer import stayed in the same band.
  This experiment did not identify GL pass or EGLImage churn as the new dominant
  cost.

Interpretation:

- The root scheduling hypothesis is supported: the root capture spike was
  caused by timing relative to recently presented scene2 work.
- This is an improvement in the right place, but not a complete speed fix. The
  next visible problem is slot capture wait under blur, especially when slot
  capture overlaps the previous scene2 render/present cadence.

### Follow-up: Delayed slot capture isolation

- Date: 2026-06-04 21:30 CEST
- Commit: local work after `0ae63a3`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Used `ImlaScene2ProfileBlur=DEBUG` and the removed delayed slot-capture
    isolation switch.
  - Captured with
    `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`.
- Initial diagnostic trace:
  `diagnostics/apa/traces/imla-smoke-2026-06-04-212712.perfetto-trace`
  was useful only as a rejected diagnostic shape. Root captures outran deferred
  slot continuations, so many captured root buffers were closed before slot
  capture.
- Valid trace:
  `diagnostics/apa/traces/imla-smoke-2026-06-04-213000.perfetto-trace`
- Screenshot:
  `diagnostics/apa/screenshots/imla-smoke-2026-06-04-213000.png`

| Trace | Avg frame | Root wait | Slot wait | Render | Final present | HB createImage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| root capture deferred | `21.095 ms` | `7.442 ms` | `8.715 ms` | `6.046 ms` | `1.322 ms` | `0.670 ms` |
| delayed slot capture | `28.822 ms` | `13.361 ms` | `11.239 ms` | `6.648 ms` | `1.494 ms` | `0.727 ms` |

Diagnostic slices:

- `SceneSlotCapture#DeferralScheduled`: `287` calls.
- `SceneSlotCapture#Deferred`: `286` calls, `11.499 ms` average including
  deferred slot capture and snapshot continuation work.
- `SceneSlotCapture#DirtySkipped`: `143` calls.
- Root captures: `287` calls.
- Slot captures: `286` calls.

Root `GraphicsLayerCapture#drawWait[1200x1920]` overlap:

| Avg root wait | RenderThread root drawing | GPU completion wait | RenderThread wait | Scene2 render | Final present |
| ---: | ---: | ---: | ---: | ---: | ---: |
| `13.361 ms` | `9.615 ms` | `10.012 ms` | `4.496 ms` | `1.481 ms` | `0.230 ms` |

Slot `GraphicsLayerCapture#drawWait[388x240]` overlap:

| Avg slot wait | RenderThread slot drawing | GPU completion wait | RenderThread wait | Scene2 render | Final present |
| ---: | ---: | ---: | ---: | ---: | ---: |
| `11.239 ms` | `7.192 ms` | `9.056 ms` | `4.392 ms` | `0.264 ms` | `0.226 ms` |

Findings:

- Delaying slot capture one extra Choreographer frame did not reduce slot wait.
  It raised slot wait from `8.715 ms` to `11.239 ms`.
- The diagnostic also regressed root wait from `7.442 ms` to `13.361 ms`.
  Holding the root buffer while waiting for slot capture creates a worse cadence
  and more GPU/RenderThread wait, even after collapsing dirty events while slot
  capture is pending.
- Scene2 render and hardware-buffer import stayed in roughly the same band, so
  the regression is not explained by GL pass cost.

Interpretation:

- A second capture delay is not the right fix for slot capture weight.
- The next slot-capture direction should be reuse or avoiding unnecessary
  captures, not pushing slot capture later. The useful question is whether a
  slot whose content is unchanged can keep its last imported content texture or
  last hardware buffer while root/backdrop continue updating.

### Follow-up: Cached slot content isolation

- Date: 2026-06-04 21:44 CEST
- Commit: local work after `54c3043`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Used `ImlaScene2ProfileBlur=DEBUG`.
  - Captured with
    `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`.
- Same-build normal control trace:
  `diagnostics/apa/traces/imla-smoke-2026-06-04-214020.perfetto-trace`
- Invalid cached-slot trace:
  `diagnostics/apa/traces/imla-smoke-2026-06-04-213923.perfetto-trace`
  skipped slot capture before the GL thread had proven the content texture was
  imported, and the screenshot missed slot content. The diagnostic was fixed to
  mark slot content cached only after successful GL content import.
- Valid cached-slot trace:
  `diagnostics/apa/traces/imla-smoke-2026-06-04-214415.perfetto-trace`
- Screenshot:
  `diagnostics/apa/screenshots/imla-smoke-2026-06-04-214415.png`

| Trace | Avg frame | Avg CPU | Avg UI | Root wait | Slot wait | Render | Final present | HB createImage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| same-build normal blur | `20.004 ms` | `19.285 ms` | `11.646 ms` | `6.935 ms` | `9.101 ms` | `6.057 ms` | `1.320 ms` | `0.669 ms` |
| cached slot content | `30.175 ms` | `22.331 ms` | `10.797 ms` | `11.862 ms` | none in measured window | `6.304 ms` | `1.316 ms` | `0.936 ms` |

Diagnostic slices:

- `SceneSlotCapture#Cached`: `356` calls.
- `GraphicsLayerCapture#drawWait[388x240]`: no measured-window calls.
- `OpenGLHardwareBufferTexture2D#createImage`: `339` calls in cached-slot mode
  versus `702` calls in same-build normal blur, because only root buffers were
  imported after the cached content texture was established.

Cached-slot root `GraphicsLayerCapture#drawWait[1200x1920]` overlap:

| Avg root wait | RenderThread root drawing | GPU completion wait | RenderThread wait | Scene2 render | Final present |
| ---: | ---: | ---: | ---: | ---: | ---: |
| `11.862 ms` | `7.915 ms` | `8.813 ms` | `2.473 ms` | `1.518 ms` | `0.256 ms` |

Findings:

- Cached slot content removed per-frame slot capture and roughly halved
  hardware-buffer imports, but the scene did not get faster.
- Root wait regressed from `6.935 ms` to `11.862 ms`, and average frame time
  regressed from `20.004 ms` to `30.175 ms`.
- GL render and final present stayed in the same range. The regression is
  primarily Android/RenderThread/GPU timing around root capture, not scene2 GL
  pass cost.
- Visual output was valid in the corrected trace: slot content remained visible
  and aligned while content capture was skipped.

Interpretation:

- Slot recapture avoidance by itself is not sufficient and can make cadence
  worse if root capture remains tied to the same continuous animation loop.
- The next useful experiment should reduce or decouple root capture frequency
  under continuous root animation, or make the profiling scene split root
  animation from backdrop-required root invalidation. Otherwise root capture
  backpressure hides any slot-cache upside.

### Follow-up: Static root backdrop blur isolation

- Date: 2026-06-04 21:57-21:58 CEST
- Commit: local work after `fa9cbaa`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Captured a same-build animated-root blur control with
    `ImlaScene2ProfileBlur=DEBUG`.
  - Captured the static-root blur case with
    `ImlaScene2ProfileStaticRootBlur=DEBUG`.
  - Captured with
    `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`.
- Runtime cleanup:
  - Removed the failed delayed slot-capture and cached slot-content switches
    from source. Their results remain documented above as rejected diagnostic
    evidence.

| Trace | Avg frame | Avg CPU | Avg UI | Root wait | Slot wait | Render | Final present | HB createImage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| animated-root blur, same build `diagnostics/apa/traces/imla-smoke-2026-06-04-215738.perfetto-trace` | `22.829 ms` | `20.939 ms` | `11.896 ms` | `7.258 ms` | `9.087 ms` | `6.237 ms` | `1.383 ms` | `0.667 ms` |
| static-root blur `diagnostics/apa/traces/imla-smoke-2026-06-04-215811.perfetto-trace` | `20.277 ms` | `19.264 ms` | `11.432 ms` | `6.580 ms` | `9.206 ms` | `6.025 ms` | `1.335 ms` | `0.675 ms` |

Screenshot:

- `diagnostics/apa/screenshots/imla-smoke-2026-06-04-215811.png`

Findings:

- Removing the root marker animation improved average frame time by about
  `2.552 ms` and average CPU time by about `1.675 ms`.
- Root capture wait improved only modestly, from `7.258 ms` to `6.580 ms`.
- Slot capture wait did not improve; it stayed around `9.2 ms`.
- GL render, final present, and hardware-buffer import stayed in the same band.
- The static-root screenshot was visually valid for the profiling target:
  checkerboard root, centered blurred slot, no obvious flip, stretch, or stale
  content artifact.

Interpretation:

- Continuous root animation contributes measurable cost, but it is not the whole
  blur bottleneck after root capture deferral and cadence pacing.
- The next visible problem remains the synchronous slot `drawWait` floor and
  the shared RenderThread/GPU-completion timing around that capture. The static
  root case gives a cleaner baseline for future slot-capture experiments because
  root animation no longer hides slot behavior.

### Follow-up: Static root slot capture classification

- Date: 2026-06-04 22:16-22:21 CEST
- Commit: local work after `6223016`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported `arm64: [status=speed]`.
  - Captured with
    `tools/imla-perfetto-feedback capture --warmup-seconds 5 --no-exercise`.
  - Captured a same-build static-root animated-slot blur control, frozen slot
    content, geometry-only slot motion, and a smaller-slot pixel-pressure case.
- Rejected trace:
  - `diagnostics/apa/traces/imla-smoke-2026-06-04-221800.perfetto-trace`
    was the first small-slot capture, rejected because an incoming-call overlay
    was visible during the screenshot and could contaminate frame timing.

| Case | Trace | Avg frame | Avg CPU | Avg UI | Root wait | Slot wait | Render | Final present | HB createImage |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| static-root blur control | `diagnostics/apa/traces/imla-smoke-2026-06-04-221615.perfetto-trace` | `20.627 ms` | `20.482 ms` | `12.138 ms` | `8.632 ms` | `8.387 ms` at `388x240` | `6.142 ms` | `1.355 ms` | `0.702 ms` |
| frozen slot content | `diagnostics/apa/traces/imla-smoke-2026-06-04-222424.perfetto-trace` | `20.909 ms` | `19.737 ms` | `11.417 ms` | `7.213 ms` | `8.989 ms` at `388x240` | `6.157 ms` | `1.351 ms` | `0.677 ms` |
| geometry-only slot motion | `diagnostics/apa/traces/imla-smoke-2026-06-04-221725.perfetto-trace` | `20.749 ms` | `19.437 ms` | `11.370 ms` | `6.223 ms` | `9.438 ms` at `388x240` | `6.175 ms` | `1.358 ms` | `0.688 ms` |
| small animated slot | `diagnostics/apa/traces/imla-smoke-2026-06-04-222109.perfetto-trace` | `20.768 ms` | `18.266 ms` | `10.474 ms` | `6.834 ms` | `6.314 ms` at `194x120` | `5.572 ms` | `1.236 ms` | `0.651 ms` |

Slot `drawWait` overlap:

| Case | Slot wait | Slot drawing overlap | GPU completion overlap | RenderThread wait overlap | Scene2 render overlap | Final present overlap |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| static-root blur control | `8.387 ms` | `7.351 ms` | `5.537 ms` | `4.744 ms` | `1.528 ms` | `0.464 ms` |
| frozen slot content | `8.963 ms` | `7.917 ms` | `5.955 ms` | `5.324 ms` | `1.838 ms` | `0.551 ms` |
| geometry-only slot motion | `9.410 ms` | `8.381 ms` | `6.265 ms` | `5.765 ms` | `1.935 ms` | `0.603 ms` |
| small animated slot | `6.314 ms` | `5.262 ms` | `3.527 ms` | `2.644 ms` | `1.581 ms` | `0.515 ms` |

Screenshots:

- `diagnostics/apa/screenshots/imla-smoke-2026-06-04-221615.png`
- `diagnostics/apa/screenshots/imla-smoke-2026-06-04-222424.png`
- `diagnostics/apa/screenshots/imla-smoke-2026-06-04-221725.png`
- `diagnostics/apa/screenshots/imla-smoke-2026-06-04-222109.png`

Findings:

- Freezing visible slot content did not reduce slot capture wait. It rose from
  `8.387 ms` in the same-build animated-slot control to `8.989 ms`.
- Geometry-only slot movement also did not reduce slot capture wait. It stayed
  in the same class at `9.410 ms`.
- Halving the slot dimensions from `220x136dp` to `110x68dp` reduced captured
  slot size from `388x240` to `194x120` and reduced slot draw wait to
  `6.314 ms`.
- The slot wait reduction followed the blocking path: slot drawing overlap,
  GPU-completion overlap, and RenderThread wait overlap all fell in the small
  slot case.
- Smaller slot did not materially improve average frame time in the clean run
  because root wait was still around `6.834 ms` and the frame remained near the
  `20 ms` class. This result classifies the slot wait; it is not a complete
  frame-time fix.
- Visual checks for the accepted screenshots showed the expected static
  checkerboard, frozen/moving/smaller slot variants, no obvious Y-flip, stretch,
  stale content, or blur alignment artifact.

Interpretation:

- The remaining slot capture wait is not driven by visible slot content
  animation. Our current path still records/captures the slot content layer when
  the scene updates, even when slot pixels are frozen.
- The slot wait is also not solved by separating geometry motion from content
  animation; geometry-only movement still pays the same captured-content wait.
- Slot pixel pressure matters. Smaller captured slot content reduces
  RenderThread/GPU-completion wait, so low-end hardware bandwidth/fill pressure
  is part of the remaining cost.
- The next optimization direction should be content-dirty reuse or lower-quality
  captured slot/backdrop intermediates. A geometry-only update path should avoid
  slot content recapture, but it likely needs explicit content dirty tracking or
  last-content reuse to matter.

### Baseline 1: Release-like warmed scene2 steady state

- Date: 2026-06-04 18:18 CEST
- Commit: `078e7fd`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Build/install:
  - `./gradlew -q :app:assembleBenchmark`
  - `tools/adb-timeout --device T81164GB23417442888 --timeout 60 install -r -d app/build/outputs/apk/benchmark/app-benchmark.apk`
- Device dexopt status:
  - `cmd package compile -m speed -f dev.serhiiyaremych.imla` returned
    `Success`.
  - `dumpsys package dexopt dev.serhiiyaremych.imla` reported
    `arm64: [status=speed] [reason=cmdline]`.
- Scenario: `MainActivity` benchmark APK, current early-return
  `NewRendererTestScene` scene2 surface.
- Warmup: launched with `am start -W`, waited about 8 seconds, captured a
  warmup screenshot, then captured Perfetto with no scripted exercise.
- Trace:
  `diagnostics/apa/traces/imla-smoke-2026-06-04-181620.perfetto-trace`
- Analysis directory:
  `diagnostics/apa/traces/imla-smoke-2026-06-04-181620.perfetto-trace_analysis/`
- Screenshots:
  - `diagnostics/apa/scene2-baseline-1-warmed-half.png`
  - `diagnostics/apa/screenshots/imla-smoke-2026-06-04-181620.png`
  - `diagnostics/apa/scene2-baseline-1-expanded-half.png`
- Validity notes:
  - `tools/imla-perfetto-feedback capture --no-exercise` issued `am start`
    before capture. The process was already warmed, so this is accepted as a
    first steady-scene baseline, but a stricter manual capture can be used if
    launch contamination becomes a concern.
  - `60_scene_counters.txt` contained only headers, so scene counters were not
    available in this trace.
- Overlay values from expanded release-like screenshot:
  - FPS: `7.3`
  - Frame budget: `16.67 ms`
  - Frame submit: `138.87 ms`
  - Root capture: `6.77 ms`
  - Content captures: `124.01 ms`
  - Mask captures: `0.02 ms`
  - Clip captures: `0.09 ms`
  - Capture -> submit: `7.60 ms`
  - Root texture import: `1.95 ms`
  - Content texture imports: `3.43 ms`
  - Mask texture imports: `0.00 ms`
  - Clip texture imports: `0.00 ms`
  - Scene render total: `15.06 ms`
  - GL thread frame: `20.46 ms`
  - Input -> rendered: `164.80 ms`
  - Drops: `0`
  - Capture failures: `0`
- Perfetto frame stats:
  - Frames: `91`
  - Janky frames: `91`
  - Big jank frames: `91`
  - Average frame: `129.146 ms`
  - Max frame: `163.282 ms`
  - Average UI: `127.916 ms`
- Perfetto top slices:
  - Main thread `traversal`: `92` slices, `125.126 ms` average,
    `158.321 ms` max.
  - `ImlaScene2GL` `Scene2RenderPipeline#render`: `92` slices,
    `18.731 ms` average, `28.726 ms` max.
  - `ImlaScene2GL` `SceneBackdropEffectPass#prepare`: `831` slices,
    `1.196 ms` average, `994.282 ms` total.
  - `ImlaScene2GL` `SceneBackdropBlurPass#process`: `740` slices,
    `0.869 ms` average, `643.394 ms` total.
  - `ImlaScene2GL` `Scene2StencilClipPass#draw`: `740` slices,
    `0.586 ms` average, `433.490 ms` total.
  - `RenderThread` repeated layer draws show slot-sized capture work, including
    `Drawing 0.00 0.00 261.00 162.00`: `93` slices, `65.034 ms` average,
    `83.428 ms` max.
- Scene counters:
  - Root captures: unavailable
  - Frame commits: unavailable
  - Geometry refreshes: unavailable
  - Slot content captures: unavailable
  - Slot mask captures: unavailable
  - Slot clip captures: unavailable
  - Scene renders: unavailable
  - Backdrop composites: unavailable
  - Content composites: unavailable
  - Stencil setups: unavailable
- Initial finding:
  - The first trusted baseline is dominated by main-thread submission wall
    time, specifically the synchronous content-capture path. Overlay reports
    `Content captures` at `124.01 ms`, and Perfetto reports main-thread
    `traversal` at about `125 ms` average.
  - Follow-up thread-state analysis shows the long capture-shaped slices are
    mostly blocked, not actively drawing. The worst `RenderThread`
    `Drawing 0.00 0.00 261.00 162.00` slice lasted `83.428 ms`, but only
    `2.289 ms` was `Running`; `80.682 ms` was sleeping. Across the top sampled
    `261x162` drawing slices, average wall time was `72.049 ms`, average
    running time was `1.749 ms`, and average sleeping time was `69.824 ms`.
  - GL rendering is also over budget at about `18.7 ms` average for
    `Scene2RenderPipeline#render`, and an overlapping bad frame had
    `ImlaScene2GL` actively running for `22.794 ms` of a `24.064 ms` render.
    The current evidence shows Android capture blocked on GPU completion after
    scene2 GL submitted work, but it does not yet prove scene2 GL is the only
    source of the GPU backlog.
- Rejected explanations:
  - Debug-build overhead is not sufficient to explain the issue; the benchmark
    APK was release-like, non-debuggable, minified, and speed-compiled.
  - Mask and clip rasterization are not the primary observed cost in the
    overlay; they were near zero during this capture.
  - GL import waits are not the primary observed cost; content imports were
    about `3.43 ms`, much smaller than content capture.
  - The slot contents themselves are not proven CPU-expensive to draw; the
    worst capture-sized RenderThread slices were mostly sleeping in
    `GPU completion` / `waitForever` paths.
- Next profiling question:
  - What GPU work is the synchronous `CanvasBufferedRenderer` capture waiting
    behind: scene2 GL work, Android RenderThread capture work, final present,
    or another shared GPU queue dependency?

## Bottleneck Questions

Answer these from trace evidence before optimizing:

- Is the main thread dominated by root capture, slot content capture, mask
  capture, clip capture, or scheduler handoff?
- Are masks or clips rerasterized every frame without a matching config, size,
  density, or shape change?
- Are slot content captures happening every frame when only geometry changes?
- Is GL time dominated by import waits, backdrop prepare, blur, composite,
  stencil setup, content composite, or final present?
- Is cumulative sampling forcing accumulated-scene reads for slots whose sample
  regions cannot include earlier slot contributions?

## Findings

- Baseline 1 is a trusted release-like, speed-compiled device capture with one
  caveat: scene counters were unavailable in the generated analysis.
- The dominant observed wall-time cost is synchronous per-frame slot content
  capture, but the long RenderThread drawing slices are mostly sleeping while
  waiting for GPU completion. This is not ordinary CPU drawing cost.
- GL rendering is also over budget, with `Scene2RenderPipeline#render` at about
  `18.7 ms` average. A bad-frame chronology shows scene2 GL work completing
  shortly before a long Android GPU-completion wait, but causality still needs
  an isolation pass.
- Mask capture, clip capture, and GL texture import are not the primary
  observed bottlenecks in Baseline 1.
- The controlled profiling ladder identifies backdrop blur as the first major
  feature step, clip/stencil as the next meaningful step, and cumulative as a
  total-work multiplier from two slots rather than one unusually slow slot.
- Tint, noise, progressive mask, rotation, and translation did not create a
  separate dominant steady-state cost step in the controlled ladder.
- Follow-up overlap analysis identifies the slot-capture dependency as
  RenderThread/GPU-completion waiting during synchronous
  `CanvasBufferedRenderer` capture. Blur and clip raise this wait while the
  captured Compose slot content remains the same.
- Cadence pacing and root capture deferral reduced the normal blur path by
  preventing scene2 from presenting and then immediately forcing root capture
  into the same backpressure window.
- Static-root blur improves the same-build blur control, but slot draw wait
  remains around the `9 ms` class. Root animation is a contributor, not the whole
  remaining problem.
- Static-root slot classification shows slot draw wait does not improve when
  visible slot content is frozen or when only slot geometry moves. It does
  improve when slot pixel dimensions are halved, so slot capture cost is tied to
  captured pixel pressure plus RenderThread/GPU-completion synchronization.
- Production stable-content reuse is now intentionally narrow. It removes
  recurring slot content capture for non-backdrop slots, but backdrop slots keep
  recurring slot capture because forced reuse can shift the bottleneck into root
  capture and final presentation pressure on `T81164GB23417442888`.

## Decisions

- Do not optimize from the existing debug screenshot.
- Treat synchronous capture waiting on GPU completion as the first concrete
  bottleneck.
- Keep the failed delayed-slot-capture and cached-slot-content probes out of
  active runtime code. They remain documented as rejected experiments.
- Use the static-root backdrop blur case as the cleaner next baseline when the
  question is slot capture behavior rather than root animation.
- Treat slot content dirty tracking/reuse or reduced-quality captured
  slot/backdrop intermediates as the next design direction. Geometry-only
  movement should not require content recapture, but the current path still pays
  that cost.
- Investigate why scene counters were unavailable in the release-like trace
  before relying on counter-based work-volume conclusions.
- Use `Scene2ProfilingScene` for feature-by-feature performance work. Keep
  `NewRendererTestScene` as the broader correctness and stress scene.
- After narrow stable reuse, focus the next slice on root capture pacing when a
  stable non-backdrop slot skips content capture. The content-cache policy is no
  longer the main unknown for that case; the open question is how to avoid
  immediately driving root capture into the same surface/GPU backpressure window.

### Follow-up: Narrow production stable content reuse

- Date: 2026-06-05 00:30-00:37 CEST
- Commit: local work after `06824ab`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported
    `arm64: [status=speed] [reason=cmdline]`.
  - Captured warmed no-exercise traces on `T81164GB23417442888`.
- Code policy:
  - Normal rendering reuses stable slot content only when the slot has no
    backdrop effect.
  - Backdrop slots retain recurring slot content capture unless a diagnostic
    `ImlaScene2DiagReuseStableSlotContent*` mode explicitly forces reuse.
- Same-build floor:
  - `ImlaScene2ProfileRoot=DEBUG`:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-003622.perfetto-trace`
    showed frame avg `16.970 ms`, root `drawWait[1200x1920]` avg `7.242 ms`,
    Imla GL render avg `2.402 ms`, and final present avg `1.621 ms`.
- Non-backdrop slot result:
  - `ImlaScene2ProfileSlot=DEBUG`:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-003657.perfetto-trace`
    removed recurring `GraphicsLayerCapture#drawWait[388x240]` slices. The
    centered slot remained visible in
    `diagnostics/apa/screenshots/imla-smoke-2026-06-05-003657.png`.
  - The same trace showed frame avg `25.488 ms`, root
    `drawWait[1200x1920]` avg `8.846 ms`, Imla GL render avg `2.849 ms`, and
    final present avg `1.503 ms`.
- Backdrop slot guard result:
  - `ImlaScene2ProfileFrozenSlotBlur=DEBUG`:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-003522.perfetto-trace`
    kept recurring `GraphicsLayerCapture#drawWait[388x240]` slices by design.
  - The trace showed frame avg `21.259 ms`, slot `drawWait[388x240]` avg
    `9.085 ms`, root `drawWait[1200x1920]` avg `7.193 ms`, Imla GL render avg
    `6.057 ms`, backdrop prepare avg `2.717 ms`, blur process avg `1.761 ms`,
    and backdrop composite avg `0.834 ms`.
- Interpretation:
  - Stable content reuse is a real work-volume fix for plain slots: recurring
    child `GraphicsLayerCapture` disappears.
  - It is not sufficient as a speed fix on the low-end tablet because removing
    that capture also removes pacing. Root capture can still wait behind
    presentation/GPU queue pressure.
  - The next production step should be root-capture pacing or present/backpressure
    control after skipped content capture, not broader backdrop-slot reuse.

### Follow-up: Stable content root-capture pacing

- Date: 2026-06-05 00:53-00:56 CEST
- Commit: local work after `331fdab`
- App APK: `app/build/outputs/apk/benchmark/app-benchmark.apk`
- Setup:
  - Installed benchmark APK.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - Confirmed `dumpsys package dexopt` reported
    `arm64: [status=speed] [reason=cmdline]`.
  - Captured warmed no-exercise traces on `T81164GB23417442888`.
- Code policy:
  - When a frame can reuse stable slot content, no slot content capture is
    predicted, and recent root capture duration is above the pressure threshold,
    root capture is deferred through Choreographer.
  - Backdrop slots still use recurring slot content capture in normal rendering.
- Trace results:
  - Root-only first pass:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-005327.perfetto-trace`
    had frame avg `30.858 ms`, root `drawWait[1200x1920]` avg `14.034 ms`,
    Imla GL render avg `2.649 ms`, and `180`
    `SceneRootCapture#DeferralScheduled[renderComplete]` slices.
  - Plain slot first pass:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-005356.perfetto-trace`
    had frame avg `27.991 ms`, root `drawWait[1200x1920]` avg `10.714 ms`,
    Imla GL render avg `2.913 ms`, `140` render-complete root deferrals, and
    `159` `SceneRootCapture#DeferralScheduled[stableContentReuse]` slices.
    Recurring `GraphicsLayerCapture#drawWait[388x240]` slices stayed absent.
  - Root-only second pass after short idle:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-005538.perfetto-trace`
    had frame avg `25.215 ms`, root `drawWait[1200x1920]` avg `11.344 ms`,
    Imla GL render avg `2.491 ms`, and `179` render-complete root deferrals.
  - Plain slot second pass:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-005609.perfetto-trace`
    had frame avg `17.181 ms`, root `drawWait[1200x1920]` avg `5.887 ms`,
    Imla GL render avg `2.849 ms`, `179` render-complete root deferrals, and
    one stable-content root deferral. Recurring `388x240` slot capture stayed
    absent, and
    `diagnostics/apa/screenshots/imla-smoke-2026-06-05-005609.png` showed the
    centered slot content correctly.
  - Frozen blur guard:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-005424.perfetto-trace`
    preserved normal backdrop behavior with frame avg `21.692 ms`, slot
    `drawWait[388x240]` avg `9.417 ms`, root `drawWait[1200x1920]` avg
    `6.984 ms`, Imla GL render avg `6.184 ms`, backdrop prepare avg
    `2.780 ms`, blur process avg `1.790 ms`, and composite avg `0.835 ms`.
- Interpretation:
  - The new policy is observable in Perfetto and does not reintroduce recurring
    slot captures for the plain-slot scene.
  - It can produce the desired plain-slot shape when the root floor is sane:
    slot capture absent, root wait below the prior `8-9 ms` band, and frame avg
    near the earlier root-only floor.
  - It is not a complete answer for the tablet because the same build also
    produced bad root-only floors. That variability is below the slot policy and
    must be understood before making broader scene optimizations.
- Next:
  - Treat root-only floor stability as the next profiling target.
  - Keep the pressure-sensitive stable-content root defer, but do not broaden
    reuse to backdrop slots.

### Follow-up: Paired root floor stability protocol

- Date: 2026-06-05 01:04-01:06 CEST
- Commit: `3a006a7`
- App APK: existing installed benchmark APK from the same code line.
- Setup:
  - Confirmed `dumpsys package dexopt dev.serhiiyaremych.imla` reported
    `arm64: [status=speed] [reason=cmdline]`.
  - Thermal service reported `Thermal Status: 0` before each capture.
  - Display mode stayed `1200 x 1920`, `60.0Hz`, presentation deadline
    `16.666667 ms`.
  - CPU governors were `schedutil` on policy0 and policy6.
  - Captured root-only A, waited about `25s`, captured root-only B, captured
    plain-slot, then captured root-only C.
- Results:
  - Root-only A:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-010456.perfetto-trace`
    had frame avg `17.023 ms`, root `drawWait[1200x1920]` avg `7.167 ms`,
    Imla GL render avg `2.342 ms`, final present avg `1.564 ms`, and app
    `RenderThread` `dequeueBuffer` avg `0.731 ms`.
  - Root-only B:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-010551.perfetto-trace`
    had frame avg `17.093 ms`, root `drawWait[1200x1920]` avg `7.433 ms`,
    Imla GL render avg `2.469 ms`, final present avg `1.647 ms`, and app
    `RenderThread` `dequeueBuffer` avg `0.512 ms`.
  - Plain-slot:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-010621.perfetto-trace`
    had frame avg `22.020 ms`, root `drawWait[1200x1920]` avg `7.892 ms`,
    Imla GL render avg `2.880 ms`, final present avg `1.546 ms`, and app
    `RenderThread` `dequeueBuffer` avg `2.406 ms`, max `15.417 ms`.
    Recurring `GraphicsLayerCapture#drawWait[388x240]` slices remained absent.
  - Root-only C:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-010651.perfetto-trace`
    had frame avg `17.069 ms`, root `drawWait[1200x1920]` avg `7.436 ms`,
    Imla GL render avg `2.421 ms`, final present avg `1.612 ms`, and app
    `RenderThread` `dequeueBuffer` avg `0.550 ms`.
- Interpretation:
  - This protocol did not reproduce the prior root-only `25-30 ms` bad floor.
    Root-only remained tightly clustered around `17.0 ms`.
  - The plain-slot regression in this run is not recurring slot capture and not
    root capture itself. The differentiator is app `RenderThread`
    `dequeueBuffer`, which rose from about `0.5-0.7 ms` in root-only to
    `2.4 ms` average and `15.4 ms` max in plain-slot.
  - The current next bottleneck is buffer acquisition / presentation pressure in
    the app RenderThread path when the scene surface and Compose root are both
    active with the plain slot.
- Next:
  - Keep paired root/plain/root captures as the basic guardrail.
  - Compare plain-slot normal present against no-final-present and cached-GL
    diagnostics while reading app `RenderThread` `dequeueBuffer` waits.

### Follow-up: Plain-slot present isolation

- Date: 2026-06-05 01:11-01:14 CEST
- Commit: `ba0eed9`
- App APK: existing installed benchmark APK from the same code line.
- Setup:
  - Confirmed `dumpsys package dexopt dev.serhiiyaremych.imla` reported
    `arm64: [status=speed] [reason=cmdline]`.
  - Thermal service reported `Thermal Status: 0`.
  - Captured plain-slot normal, no-final-present, cached-GL-present,
    cached-GL-no-present, then a normal plain-slot repeat.
- Results:
  - Normal plain-slot:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-011112.perfetto-trace`
    had frame avg `17.158 ms`, root `drawWait[1200x1920]` avg `5.866 ms`,
    Imla GL render avg `2.843 ms`, final present avg `1.507 ms`, and app
    `RenderThread` `dequeueBuffer` avg `0.741 ms`.
  - Plain-slot no-final-present:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-011142.perfetto-trace`
    had frame avg `16.523 ms`, root `drawWait[1200x1920]` avg `9.000 ms`,
    Imla GL render avg `1.394 ms`, final present skipped, and app
    `RenderThread` `dequeueBuffer` avg `0.711 ms`.
  - Cached-GL-present:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-011213.perfetto-trace`
    had frame avg `15.923 ms`, no recurring root `drawWait` slices in the
    measured window, Imla GL render avg `2.713 ms`, final present avg
    `1.895 ms`, and app `RenderThread` `dequeueBuffer` avg `2.022 ms`.
  - Cached-GL-no-present:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-011243.perfetto-trace`
    had frame avg `15.380 ms`, Imla GL render avg `0.910 ms`, final present
    skipped, and app `RenderThread` `dequeueBuffer` avg `2.096 ms`.
  - Normal plain-slot repeat:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-011410.perfetto-trace`
    had frame avg `17.138 ms`, root `drawWait[1200x1920]` avg `5.738 ms`,
    Imla GL render avg `2.848 ms`, final present avg `1.508 ms`, and app
    `RenderThread` `dequeueBuffer` avg `0.738 ms`.
- Interpretation:
  - The bad plain-slot `RenderThread` `dequeueBuffer` state from the previous
    protocol did not reproduce. Normal plain-slot was stable near `17.1 ms` in
    both captures and kept app `RenderThread` `dequeueBuffer` under `0.8 ms`.
  - Skipping final scene presentation improves normal plain-slot only modestly
    in this clean state. It lowers GL render work but root capture wait can rise,
    so it is not a standalone production answer.
  - Cached-GL diagnostics show that high app `RenderThread` `dequeueBuffer`
    can coexist with good frame time when fresh root capture is removed. That
    means `dequeueBuffer` is a useful pressure signal, but not sufficient by
    itself to prove the jank source.
- Next:
  - Reproduce the bad plain-slot state before changing production behavior.
  - When it appears, compare the same bad-state trace against no-final-present
    and cached-GL modes without an intervening long idle.
  - Treat final-present throttling as a candidate only if it removes the bad
    state while root capture and slot reuse stay stable.

### Follow-up: Bad-window plain-slot isolation

- Date: 2026-06-05 01:21-01:24 CEST
- Commit: `0e6b6e9`
- App APK: existing installed benchmark APK from the same code line.
- Setup:
  - Confirmed `dumpsys package dexopt dev.serhiiyaremych.imla` reported
    `arm64: [status=speed] [reason=cmdline]`.
  - Thermal service reported `Thermal Status: 0` before and after the protocol.
  - Captured root-only, plain-slot, root-only, then immediately captured
    plain-slot no-final-present, cached-GL-present, and cached-GL-no-present.
- Paired protocol:
  - Root-only A:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-012154.perfetto-trace`
    had frame avg `26.845 ms`, root `drawWait[1200x1920]` avg `12.192 ms`,
    Imla GL render avg `2.577 ms`, final present avg `1.745 ms`, app
    `RenderThread` `dequeueBuffer` avg `3.614 ms`, and no slot capture.
  - Plain-slot:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-012217.perfetto-trace`
    had frame avg `23.324 ms`, root `drawWait[1200x1920]` avg `8.608 ms`,
    Imla GL render avg `2.852 ms`, final present avg `1.531 ms`, app
    `RenderThread` `dequeueBuffer` avg `2.799 ms`, and no recurring
    `GraphicsLayerCapture#drawWait[388x240]` slices.
  - Root-only B:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-012240.perfetto-trace`
    had frame avg `27.074 ms`, root `drawWait[1200x1920]` avg `12.085 ms`,
    Imla GL render avg `2.563 ms`, final present avg `1.711 ms`, app
    `RenderThread` `dequeueBuffer` avg `3.682 ms`, and no slot capture.
- Isolation:
  - Plain-slot no-final-present:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-012354.perfetto-trace`
    recovered to frame avg `16.523 ms`, root `drawWait[1200x1920]` avg
    `9.016 ms`, Imla GL render avg `1.460 ms`, final present skipped, and app
    `RenderThread` `dequeueBuffer` avg `0.690 ms`.
  - Cached-GL-present:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-012417.perfetto-trace`
    recovered to frame avg `15.971 ms`, had no recurring root capture,
    Imla GL render avg `2.785 ms`, final present avg `1.922 ms`, and app
    `RenderThread` `dequeueBuffer` avg `1.971 ms`.
  - Cached-GL-no-present:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-012440.perfetto-trace`
    recovered to frame avg `15.387 ms`, had no recurring root capture,
    Imla GL render avg `0.920 ms`, final present skipped, and app
    `RenderThread` `dequeueBuffer` avg `2.236 ms`.
- Interpretation:
  - This was a bad-window repro, but not a clean plain-slot-only repro:
    root-only was already bad before and after the plain-slot capture. The
    bad state therefore sits below slot content capture.
  - Stable slot reuse is still behaving as intended. The plain-slot trace had
    no recurring `388x240` slot capture.
  - Skipping final present recovered app `RenderThread` `dequeueBuffer` and
    frame cadence even though fresh root capture continued and root draw wait
    stayed elevated near `9 ms`.
  - Cached-GL-present recovered frame cadence while final present continued,
    because fresh root capture was removed. Cached-GL-no-present was the
    cleanest floor.
  - The likely bottleneck is the coupling between synchronous root capture and
    continuous scene-surface presentation/display-queue pressure. Neither final
    present alone nor `RenderThread` `dequeueBuffer` alone explains all runs.
- Next:
  - Do not make a production optimization from this diagnostic mode directly.
  - Discuss a narrow production policy that decouples root capture from the
    same cadence as scene presentation when content is stable, while preserving
    full-rate presentation on devices/windows that can sustain it.

### Follow-up: Production scene-work coalescing prototype

- Date: 2026-06-05
- Commit: local work after `891e66f`
- Change:
  - Added a normal-mode-only `SceneWorkCoalescingPolicy`.
  - The policy watches recent real root-capture duration. After two consecutive
    root captures at or above `0.66` of the current frame budget, it can skip
    selected dirty ticks when a reusable GL frame exists, stable slot content
    was reused, and no slot content capture is required.
  - A skipped dirty tick does no root capture, slot capture, mask/clip capture,
    snapshot assembly, GL submission, or final presentation. The previously
    presented frame remains visible.
  - The policy alternates at most one skipped dirty tick per real frame, exits
    after two root captures at or below `0.50` of the current frame budget, and
    resets after idle, detach, or surface clear.
  - Diagnostic modes keep their existing behavior so prior isolation traces stay
    comparable.
- Metrics:
  - The collapsed overlay appends `Skip N` only after production pressure skips
    have occurred.
  - The expanded overlay reports `Pressure skips dirty ticks N`, backed by
    `SceneMetricsSnapshot.sceneWorkCoalescedFrames`.
  - Perfetto should include `SceneFramePace#WorkCoalesced` slices when the
    production policy fires.
- Verification before device capture:
  - `./gradlew -q :imla:testDebugUnitTest --tests 'dev.serhiiyaremych.imla.internal.render.scheduler.*'`
  - `./gradlew -q compileDebugKotlin`
- Rejected broader gate:
  - A first device run allowed root-only coalescing. Root-only
    `diagnostics/apa/traces/imla-smoke-2026-06-05-014845.perfetto-trace`
    fired `171` `SceneFramePace#WorkCoalesced` slices, but worsened to frame
    avg `33.679 ms`, root `drawWait[1200x1920]` avg `14.698 ms`, and app
    `RenderThread` `dequeueBuffer` avg `8.267 ms`.
  - The policy was tightened to require stable slot content reuse so root-only
    pressure cannot trigger it.
- Revised device validation:
  - Root-only A:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-015309.perfetto-trace`
    had no `WorkCoalesced` slices, frame avg `32.216 ms`, root
    `drawWait[1200x1920]` avg `14.616 ms`, Imla GL render avg `2.667 ms`,
    final present avg `1.772 ms`, and app `RenderThread` `dequeueBuffer` avg
    `5.076 ms`.
  - Plain-slot:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-015332.perfetto-trace`
    fired `33` `WorkCoalesced` slices, had no recurring `388x240` slot
    captures, and showed frame avg `20.239 ms`, root `drawWait[1200x1920]`
    avg `6.786 ms`, Imla GL render avg `2.892 ms`, final present avg
    `1.553 ms`, and app `RenderThread` `dequeueBuffer` avg `1.879 ms`.
  - Root-only B:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-015355.perfetto-trace`
    had no `WorkCoalesced` slices, frame avg `29.959 ms`, root
    `drawWait[1200x1920]` avg `13.443 ms`, Imla GL render avg `2.570 ms`,
    final present avg `1.714 ms`, and app `RenderThread` `dequeueBuffer` avg
    `4.442 ms`.
  - Screenshot
    `diagnostics/apa/screenshots/imla-smoke-2026-06-05-015332.png` showed the
    centered plain slot without obvious shift, stretch, Y-flip, alpha/color, or
    slot-alignment artifacts.
- Interpretation:
  - Coalescing root-only work is not viable from this evidence.
  - Stable-slot-gated coalescing does not fix the low-end root floor, but it
    partially mitigated the plain-slot path inside a bad root window: compared
    with the prior bad plain-slot trace `012217`, frame avg moved from
    `23.324 ms` to `20.239 ms`, app `RenderThread` `dequeueBuffer` from
    `2.799 ms` to `1.879 ms`, and root wait from `8.608 ms` to `6.786 ms`.
- Next:
  - Treat this as a partial low-end mitigation, not a full fix.
  - Keep root-only coalescing disabled.
  - If continuing this direction, tune the stable-slot gate against more paired
    traces and compare against a no-policy control from the same bad window.

### Follow-up: Optimization code self-review

- Commit under review: local work after `70fbfeb`.
- Review scope:
  - Production renderer optimization code from stable slot reuse, stable-content
    root pacing, and pressure dirty-tick coalescing.
  - Excluded throwaway diagnostic modes/scenes except as benchmark evidence.
- Architecture decision:
  - Keep the scheduler policy classes. `SceneWorkCoalescingPolicy` owns
    pressure/recovery state and reset behavior, while
    `SceneStableContentRootPacingPolicy` follows the existing scheduler-policy
    shape used by root capture pacing. Inlining either into `SceneRenderer`
    would save a file but make the renderer own more timing-policy detail.
- Simplification:
  - Moved `DeferredRootCapture` construction until after the pressure
    coalescing gate so skipped dirty ticks do not copy slot lists or allocate a
    root-capture request.
  - Stopped slot-content pacing inspection once it already knows both whether
    any slot will capture content and whether any stable slot content can be
    reused.
- Verification:
  - `./gradlew -q :imla:testDebugUnitTest --tests 'dev.serhiiyaremych.imla.internal.render.scheduler.*' --tests 'dev.serhiiyaremych.imla.internal.layer.model.SceneSlotContentReusePolicyTest'`
  - `./gradlew -q compileDebugKotlin`
  - `./gradlew -q :app:assembleBenchmark`
- Plain-slot A/B on `T81164GB23417442888`, benchmark APK, speed-compiled
  `arm64: [status=speed] [reason=cmdline]`, same profiling tag
  `ImlaScene2ProfileSlot`:
  - Baseline before simplification:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-022319.perfetto-trace`
    reported frame avg `17.185 ms`, CPU avg `12.705 ms`, root
    `drawWait[1200x1920]` avg `5.947 ms`, Imla GL render avg `2.854 ms`,
    final present avg `1.556 ms`, app `RenderThread` `dequeueBuffer` avg
    `0.514 ms`, and `2` `SceneFramePace#WorkCoalesced` slices.
  - After simplification:
    `diagnostics/apa/traces/imla-smoke-2026-06-05-022437.perfetto-trace`
    reported frame avg `17.114 ms`, CPU avg `12.509 ms`, root
    `drawWait[1200x1920]` avg `5.845 ms`, Imla GL render avg `2.830 ms`,
    final present avg `1.519 ms`, app `RenderThread` `dequeueBuffer` avg
    `0.511 ms`, and no `SceneFramePace#WorkCoalesced` slices.
  - The after screenshot
    `diagnostics/apa/screenshots/imla-smoke-2026-06-05-022437.png` showed the
    centered plain slot without obvious shift, stretch, Y-flip, alpha/color, or
    slot-alignment artifacts.
- Interpretation:
  - The simplification preserves benchmark behavior within device variability
    and marginally reduces hot-path work before skipped dirty ticks.
  - A separate after trace
    `diagnostics/apa/traces/imla-smoke-2026-06-05-022106.perfetto-trace`
    reproduced the known bad root/display queue window, with frame avg
    `33.953 ms`, root `drawWait[1200x1920]` avg `14.988 ms`, and app
    `RenderThread` `dequeueBuffer` avg `8.125 ms`; that remains evidence for
    the next root/presentation investigation, not a regression from this
    simplification.

### Follow-up: Root-only bad-window resume check

- Date: 2026-06-05 11:09-11:11 CEST
- Commit: `1e4639b`
- App APK: existing installed benchmark APK from the simplification check.
- Setup:
  - Confirmed the tablet `T81164GB23417442888` was connected at `1200x1920`.
  - Confirmed the installed package was still ART speed-compiled:
    `arm64: [status=speed] [reason=cmdline]`.
  - Thermal service reported `Thermal Status: 0`.
  - The first screenshot attempt was black because the screen was asleep and
    keyguard was showing; after waking/dismissing keyguard, the app had focus.
- Visual check:
  - `diagnostics/apa/screenshots/resume-plain-slot-visible-2026-06-05-half.png`
    showed the centered plain slot without obvious shift, stretch, Y-flip,
    alpha/color, or slot-alignment artifacts.
  - `diagnostics/apa/screenshots/imla-smoke-2026-06-05-111111.png` showed the
    root-only scene without obvious orientation or alignment artifacts.
- Plain-slot good-window trace:
  - `diagnostics/apa/traces/imla-smoke-2026-06-05-110956.perfetto-trace`
    reported frame avg `17.272 ms`, CPU avg `11.781 ms`, root
    `drawWait[1200x1920]` avg `5.579 ms`, Imla GL render avg `2.645 ms`,
    final present avg `1.424 ms`, and app `RenderThread` `dequeueBuffer` avg
    `0.592 ms`.
- Root-only bad-window trace:
  - `diagnostics/apa/traces/imla-smoke-2026-06-05-111111.perfetto-trace`
    reported frame avg `25.653 ms`, CPU avg `19.935 ms`, root
    `drawWait[1200x1920]` avg `10.588 ms`, Imla GL render avg `2.337 ms`,
    final present avg `1.547 ms`, and app `RenderThread` `dequeueBuffer` avg
    `3.463 ms`.
- Interpretation:
  - The bad window reproduced in root-only mode after a plain-slot good-window
    trace. That supports the earlier conclusion that the remaining floor is
    below slot content capture.
  - Imla GL render and final present stayed near the previous good-window
    range; the differentiators were root `GraphicsLayerCapture` wait and app
    `RenderThread` `dequeueBuffer`.
- Next:
  - Keep the next investigation focused on root capture and Android display
    queue coupling.
  - Do not tune slot reuse further unless a paired trace shows slot content
    capture has become the active bottleneck again.

### Follow-up: Diagnostic root clean-plate reuse prototype

- Date: 2026-06-05 12:00-12:04 CEST
- Commit under test: local work after `46cb65c`.
- Diagnostic tag: `ImlaScene2DiagRootCleanPlateReuse=DEBUG`.
- Setup:
  - Installed `:app:benchmark` APK on `T81164GB23417442888`.
  - Forced ART speed compile and verified
    `arm64: [status=speed] [reason=cmdline]`.
  - Thermal service reported `Thermal Status: 0` before and after traces.
- Visual checks:
  - Root-only:
    `diagnostics/apa/screenshots/root-clean-plate-reuse-root-only-half.png`.
  - Plain slot:
    `diagnostics/apa/screenshots/root-clean-plate-reuse-plain-slot-half.png`.
  - Backdrop blur:
    `diagnostics/apa/screenshots/root-clean-plate-reuse-backdrop-blur-half.png`.
  - These screenshots showed no obvious black frame, crop shift, Y-flip,
    stretch, alpha/color flash, slot alignment error, or backdrop blur edge
    artifact.
- Root-only diagnostic trace:
  - `diagnostics/apa/traces/imla-smoke-2026-06-05-120159.perfetto-trace`
    reported frame avg `32.759 ms`, CPU avg `26.481 ms`, root
    `drawWait[1200x1920]` avg `14.654 ms`, Imla GL render avg `2.499 ms`,
    final present avg `1.706 ms`, and app `RenderThread` `dequeueBuffer` avg
    `7.108 ms`.
  - `trace_processor` SQL found `118` `SceneRootReuse#ReusePrevious` slices
    and `118` `SceneRootReuse#GlReused` slices.
  - Root capture count was `240` while scene render count was `357`, so the
    diagnostic reduced fresh root captures by roughly one third in this window.
- Same-session root-only control with the diagnostic tag off:
  - `diagnostics/apa/traces/imla-smoke-2026-06-05-120422.perfetto-trace`
    reported frame avg `32.409 ms`, CPU avg `26.296 ms`, root
    `drawWait[1200x1920]` avg `13.971 ms`, Imla GL render avg `2.416 ms`,
    final present avg `1.651 ms`, and app `RenderThread` `dequeueBuffer` avg
    `5.448 ms`.
  - The raw trace had no `SceneRootReuse#` markers.
- Interpretation:
  - The diagnostic mechanism works: after pressure, main thread requests one
    previous-root frame, GL satisfies it, and normal tag-off behavior does not
    emit reuse markers.
  - The first bad-window A/B does not show useful pressure relief. It reduced
    fresh root capture count, but frame time and app `RenderThread`
    `dequeueBuffer` did not improve in the paired root-only run.
  - Keep this diagnostic off for normal rendering. Treat it as a mechanism
    proof and visual-safety check, not a production candidate yet.
- Next:
  - Before enabling any normal behavior, decide whether to test a less frequent
    reuse cadence, combine reuse with presentation pacing, or stop this line
    because display queue pressure remains dominant even when root captures are
    skipped.

### Follow-up: Surface/display queue conclusion

- Date: 2026-06-05
- Goal:
  - Determine why continuous scene2 surface presentation raises app
    `RenderThread` `dequeueBuffer` during root capture, without changing normal
    rendering behavior.
- Code-path check:
  - `ImlaHost` creates a full-size `AndroidExternalSurface` behind Compose
    content and gives that `Surface` to scene2 GL.
  - Root capture uses `CanvasBufferedRenderer.drawAsync(...)` and blocks the
    main thread until the renderer callback returns.
  - AndroidX `GLRenderer.RenderTarget.requestRender` invokes its completion
    callback after `EGLManager.swapAndFlushBuffers()`, which calls
    `eglSwapBuffers` for the current surface. This confirms Imla's
    render-complete latch is a submit-complete signal, not a SurfaceFlinger/HWC
    buffer-release signal.
- Trace comparison:
  - Good-window plain-slot trace
    `diagnostics/apa/traces/imla-smoke-2026-06-05-110956.perfetto-trace`:
    root `drawWait[1200x1920]` avg `5.579 ms`, app `RenderThread`
    `dequeueBuffer` avg `0.831 ms`, Imla GL `dequeueBuffer` avg `0.069 ms`,
    `Scene2RenderPipeline#render` avg `2.645 ms`, and final present avg
    `1.424 ms`.
  - Bad-window root-only trace
    `diagnostics/apa/traces/imla-smoke-2026-06-05-111111.perfetto-trace`:
    root `drawWait[1200x1920]` avg `10.588 ms`, app `RenderThread`
    `dequeueBuffer` avg `3.460 ms`, Imla GL `dequeueBuffer` avg `0.073 ms`,
    render avg `2.337 ms`, and final present avg `1.547 ms`.
  - Root-reuse diagnostic bad-window trace
    `diagnostics/apa/traces/imla-smoke-2026-06-05-120159.perfetto-trace`:
    root reuse fired `118` times, root capture count fell to `240`, but root
    `drawWait[1200x1920]` still averaged `14.654 ms`, app `RenderThread`
    `dequeueBuffer` averaged `7.093 ms`, Imla GL `dequeueBuffer` averaged
    `0.112 ms`, render averaged `2.499 ms`, and final present averaged
    `1.706 ms`.
  - Same-session root-only control
    `diagnostics/apa/traces/imla-smoke-2026-06-05-120422.perfetto-trace`:
    root `drawWait[1200x1920]` avg `13.971 ms`, app `RenderThread`
    `dequeueBuffer` avg `5.448 ms`, Imla GL `dequeueBuffer` avg `0.073 ms`,
    render avg `2.416 ms`, and final present avg `1.651 ms`.
  - Bad-window no-final-present recovery
    `diagnostics/apa/traces/imla-smoke-2026-06-05-012354.perfetto-trace`:
    final present skipped, root `drawWait[1200x1920]` stayed elevated at
    `9.016 ms`, but app `RenderThread` `dequeueBuffer` recovered to
    `0.689 ms` and frame cadence recovered.
- Interpretation:
  - The slow path is not scene2 shader/FBO issue time. In the bad windows,
    Imla GL render and final-present issue time stay in the low millisecond
    range, and Imla GL `dequeueBuffer` stays near zero in the latest
    root-only/root-reuse traces.
  - The root capture wait rises when the app `RenderThread` is delayed
    acquiring buffers and waiting on GPU/HWC completion. Root capture blocks on
    `CanvasBufferedRenderer`, so that app `RenderThread` queue pressure becomes
    main-thread `GraphicsLayerCapture#drawWait` time.
  - Root clean-plate reuse is therefore the wrong first production fix. It can
    reduce fresh root capture count, but the remaining fresh captures still wait
    behind the same app `RenderThread`/display queue, and full-rate scene
    presentation continues.
  - The pressure source is cross-surface timing between the Compose/root capture
    path and the scene2 `AndroidExternalSurface` presentation path. The useful
    mitigation shape is to pace or coalesce scene work before capture/present
    under pressure, not to wait on `GLRenderer` completion as if it represented
    buffer release.
- Result:
  - Active investigation goal is answered for the current evidence set:
    continuous scene2 presentation can worsen root capture because it adds
    display/composition pressure while root capture synchronously depends on
    app `RenderThread` buffer acquisition and GPU/HWC waits.
- Next:
  - Keep `ImlaScene2DiagRootCleanPlateReuse` diagnostic-only.
  - Do not promote a production root-reuse policy from the current data.
  - If more device work is needed, compare pre-capture work coalescing and
    explicit lower scene-presentation cadence against paired root-only controls
    in the same bad window.

### Follow-up: Conservative production coalescing tightening

- Date: 2026-06-05
- Goal:
  - Turn the display-queue finding into a conservative production pacing
    decision.
- Design:
  - `docs/plans/2026-06-05-scene2-conservative-work-coalescing-design.md`.
- Change:
  - `SceneWorkCoalescingPolicy` now records pressure after completed root
    captures instead of updating pressure inside the pre-capture
    `shouldCoalesce(...)` call.
  - Pressure is learned only from eligible real captures: stable slot content
    was reused and no slot content capture was needed.
  - Ineligible captures clear armed pressure, so root-only bad windows or
    fresh slot-content captures cannot prime later stable-slot coalescing.
  - Root capture failure resets production coalescing state.
  - Output surface set/resize resets production coalescing state.
- Verification:
  - `./gradlew -q :imla:testDebugUnitTest --tests 'dev.serhiiyaremych.imla.internal.render.scheduler.*'`
  - `./gradlew -q compileDebugKotlin`
  - `./gradlew -q :app:assembleBenchmark`
- Device setup:
  - Installed `app/build/outputs/apk/benchmark/app-benchmark.apk` on
    `T81164GB23417442888`.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - `dumpsys package dexopt` contained
    `arm64: [status=speed] [reason=cmdline]`.
  - Thermal service reported `Thermal Status: 0` before traces.
- Device traces:
  - Root-only control
    `diagnostics/apa/traces/imla-smoke-2026-06-05-150809.perfetto-trace`:
    frame avg `17.073 ms`, root `drawWait[1200x1920]` avg `7.640 ms`,
    `Scene2RenderPipeline#render` avg `2.375 ms`, final present avg
    `1.600 ms`, and no `SceneFramePace#WorkCoalesced` slices.
  - Plain-slot
    `diagnostics/apa/traces/imla-smoke-2026-06-05-150843.perfetto-trace`:
    frame avg `17.072 ms`, root `drawWait[1200x1920]` avg `5.771 ms`,
    `Scene2RenderPipeline#render` avg `2.834 ms`, final present avg
    `1.515 ms`, and no `SceneFramePace#WorkCoalesced` slices.
  - Backdrop blur
    `diagnostics/apa/traces/imla-smoke-2026-06-05-150914.perfetto-trace`:
    frame avg `20.404 ms`, root `drawWait[1200x1920]` avg `6.558 ms`,
    slot `drawWait[388x240]` avg `9.192 ms`, `Scene2RenderPipeline#render`
    avg `5.989 ms`, final present avg `1.310 ms`, and no
    `SceneFramePace#WorkCoalesced` slices.
- Visual checks:
  - `diagnostics/apa/screenshots/imla-smoke-2026-06-05-150809.png`
  - `diagnostics/apa/screenshots/imla-smoke-2026-06-05-150843.png`
  - `diagnostics/apa/screenshots/imla-smoke-2026-06-05-150914.png`
  - These screenshots showed no obvious black frame, crop shift, Y-flip,
    stretch, alpha/color flash, slot alignment error, or backdrop blur edge
    artifact.
- Cleanup note:
  - The device disconnected after the traces were pulled. Final tag reset and
    force-stop could not be confirmed in this run.
- Interpretation:
  - The clean-window device run is a non-regression check, not a bad-window
    tuning proof.
  - The tightened policy did not affect root-only, did not fire in clean
    plain-slot/backdrop runs, and keeps the production behavior narrower than
    the earlier prototype.
  - Further threshold tuning still requires paired same-window bad traces.

### Follow-up: Pressure-gated final present diagnostic

- Date: 2026-06-05
- Goal:
  - Test whether continuing fresh capture/import/render work while skipping
    final scene2 surface presentation under bad-window pressure lets later root
    captures recover.
- Change:
  - Added `ImlaScene2DiagPressureNoFinalPresent`.
  - The diagnostic enters pressure after two consecutive root captures slower
    than `0.66` of the current display frame budget.
  - While pressure is active, it skips only the final present draw. Root capture,
    snapshot assembly, GL import, and scene rendering still run.
  - It resumes final presentation after two recovered captures.
- Non-goals:
  - This is not a production policy.
  - This does not reuse root clean plates or stable slot content.
  - This does not enable production work coalescing.
- Verification:
  - `./gradlew -q :imla:testDebugUnitTest --tests 'dev.serhiiyaremych.imla.internal.render.scheduler.*'`
  - `./gradlew -q compileDebugKotlin`
  - `./gradlew -q :app:assembleBenchmark`
- Device setup:
  - Installed `app/build/outputs/apk/benchmark/app-benchmark.apk` on
    `T81164GB23417442888`.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - `dumpsys package dexopt` contained
    `arm64: [status=speed] [reason=cmdline]`.
  - Thermal service reported `Thermal Status: 0` before traces.
- Device traces:
  - Plain-slot pressure-no-final-present diagnostic
    `diagnostics/apa/traces/imla-smoke-2026-06-05-160730.perfetto-trace`:
    frame avg `34.634 ms`, root `drawWait[1200x1920]` avg `16.056 ms`,
    app RenderThread `dequeueBuffer` avg `7.075 ms`,
    `SceneFinalPresentPressure#skip` count `60`, and
    `SceneFinalPresentPressure#present` count `1`.
  - Plain-slot pressure-no-final-present diagnostic repeat
    `diagnostics/apa/traces/imla-smoke-2026-06-05-160953.perfetto-trace`:
    frame avg `25.550 ms`, root `drawWait[1200x1920]` avg `14.893 ms`,
    app RenderThread `dequeueBuffer` avg `5.158 ms`,
    `SceneFinalPresentPressure#skip` count `36`, and
    `SceneFinalPresentPressure#present` count `29`.
- Visual checks:
  - Direct app screenshot
    `diagnostics/apa/screenshots/scene2-pressure-no-final-present-direct.png`
    showed no obvious black frame, crop shift, Y-flip, stretch, alpha/color
    flash, or slot alignment error.
  - Smoke screenshots from the two traces captured system UI, so they were not
    used as renderer visual evidence.
- Interpretation:
  - The diagnostic did skip final presentation under bad-window pressure.
  - Skipping final presentation did not make root capture recover in either
    bad-window trace.
  - The next direction should not be simple final-present skipping. If we keep
    digging, it should target the Android root capture / RenderThread queue
    side more directly.

### Follow-up: Production root clean-plate pressure reuse

- Date: 2026-06-05
- Goal:
  - Promote the root clean-plate reuse mechanism into normal-mode pressure
    pacing while keeping the reuse bounded to one stale root frame.
- Baseline before change:
  - Plain-slot normal-mode bad-window trace
    `diagnostics/apa/traces/imla-smoke-2026-06-05-163201.perfetto-trace`:
    frame avg `41.302 ms`, root `drawWait[1200x1920]` avg `19.657 ms`,
    app RenderThread `dequeueBuffer` avg `6.884 ms`,
    `Scene2RenderPipeline#render` avg `2.480 ms`, and
    `SceneFramePace#WorkCoalesced` count `6`.
- Change:
  - Normal mode now records root capture pressure in `SceneRootReusePolicy`.
  - After two root captures slower than `0.66` of the current display frame
    budget, normal mode enters root-reuse pressure.
  - While pressure is active, root reuse alternates with fresh root captures so
    at most one stale root frame is shown before the next fresh root capture.
  - The reused-root path still assembles the scene snapshot and submits it to
    GL, so presentation work keeps moving.
  - The policy leaves pressure after two recovered fresh root captures.
  - Pending root reuse runs before full scene work coalescing, so pressure
    produces a rendered stale-root frame instead of skipping all scene work.
- Safety:
  - Output surface set/resize/clear and root detach reset the reuse policy.
  - Missing reusable GL root storage resets pending reuse.
  - The `ImlaScene2DiagRootCleanPlateReuse` tag remains available as an
    isolation mode, but the same one-frame mechanism is now allowed in normal
    mode.
- Verification:
  - `./gradlew -q :imla:testDebugUnitTest --tests 'dev.serhiiyaremych.imla.internal.render.scheduler.*'`
  - `./gradlew -q compileDebugKotlin`
  - `./gradlew -q :app:assembleBenchmark`
- Device setup:
  - Installed `app/build/outputs/apk/benchmark/app-benchmark.apk` on
    `T81164GB23417442888`.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - `dumpsys package dexopt` contained
    `arm64: [status=speed] [reason=cmdline]`.
  - Thermal service reported `Thermal Status: 0` before after-change traces.
- Device traces:
  - Plain-slot normal-mode after-change trace
    `diagnostics/apa/traces/imla-smoke-2026-06-05-164030.perfetto-trace`:
    frame avg `32.869 ms`, root `drawWait[1200x1920]` avg `19.072 ms`,
    app RenderThread `dequeueBuffer` avg `5.877 ms`,
    `Scene2RenderPipeline#render` avg `2.620 ms`,
    `SceneRootReuse#ReusePrevious` count `17`, and
    `SceneRootReuse#GlReused` count `17`.
- Visual checks:
  - Root-only direct screenshot
    `diagnostics/apa/screenshots/scene2-production-root-reuse-root-direct.png`
    showed no obvious black frame, crop shift, Y-flip, stretch, alpha/color
    flash, or slot alignment issue.
  - Backdrop direct screenshot
    `diagnostics/apa/screenshots/scene2-production-root-reuse-backdrop-direct.png`
    showed no obvious black frame, crop shift, Y-flip, stretch, alpha/color
    flash, backdrop lag artifact, or slot/backdrop alignment issue.
  - Backdrop slot crop diff
    `diagnostics/screenshot-diff/scene2-production-root-reuse-backdrop-slot`
    passed with `changed=0.000000%`, `max_delta=0`, and
    `ssim_luma_global=1.000000`.
- Interpretation:
  - Root reuse now fires in normal mode and keeps the scene submission path
    moving.
  - Compared with the same-session baseline, the bad-window frame average
    improved from `41.302 ms` to `32.869 ms`, and app RenderThread
    `dequeueBuffer` improved from `6.884 ms` to `5.877 ms`.
  - Root capture `drawWait` did not materially improve: `19.657 ms` baseline
    to `19.072 ms` after-change. This is a partial pacing improvement, not a
    full fix for the Android root capture wait.

### Follow-up: Keep normal root reuse armed until GL storage is ready

- Date: 2026-06-06
- Goal:
  - Make normal-mode bounded root reuse fire in the large-slot pressure case
    that previously reproduced the Android root capture / display-queue bad
    window without requiring the `ImlaScene2DiagRootCleanPlateReuse` tag.
- Change:
  - `SceneRootReusePolicy` now caps its slow-capture threshold at the nominal
    `60 Hz` threshold derived from `0.66 * 16.666 ms`, so a degraded effective
    cadence does not redefine a `29 ms` root capture as acceptable.
  - Root reuse pressure is no longer cleared merely because a reusable GL root
    is not ready at the first reuse decision. Surface set, resize, clear,
    detach, invalid timings, and recovery samples still reset the policy.
  - The one-stale-root-frame alternation remains unchanged: after a reused root,
    a fresh root capture is required before the next reuse.
- Verification:
  - `./gradlew -q :imla:testDebugUnitTest --tests 'dev.serhiiyaremych.imla.internal.render.scheduler.*'`
  - `./gradlew -q compileDebugKotlin`
  - `./gradlew -q :app:assembleBenchmark`
- Device setup:
  - Installed `app/build/outputs/apk/benchmark/app-benchmark.apk` on
    `T81164GB23417442888`.
  - Ran `cmd package compile -m speed -f dev.serhiiyaremych.imla`.
  - `dumpsys package dexopt` contained
    `arm64: [status=speed] [reason=cmdline]`.
  - Thermal service reported `Thermal Status: 0`.
- Device trace:
  - Normal-mode `static-root-large-slot-blur`
    `diagnostics/apa/traces/imla-smoke-2026-06-06-175744.perfetto-trace`
    showed `SceneRootReuse#ReusePrevious` count `152` and
    `SceneRootReuse#GlReused` count `140`. It also showed frame avg
    `53.675 ms`, root `GraphicsLayerCapture#capture[1200x1920]` avg
    `28.717 ms`, app RenderThread `dequeueBuffer` avg `11.549 ms`,
    and `Scene2RenderPipeline#render` avg `6.343 ms`.
- Visual check:
  - Large-slot screenshot diff
    `diagnostics/screenshot-diff/scene2-large-root-reuse-normal-final-2026-06-06`
    passed with `changed=0.087109%`, `max_delta=149`, and
    `ssim_luma_global=0.999925`.
- Interpretation:
  - Normal mode now uses bounded root reuse under this reproduced pressure
    condition.
  - This did not improve frame cadence in the large-slot trace. It confirms the
    policy path and visual safety for this case, but the remaining bottleneck
    is still Android root capture / display-queue pressure rather than the
    absence of stale-root submission alone.
