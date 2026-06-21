# Scene2 Scratch Renderer Status

Date: 2026-06-05

This is the current working note for the scratch renderer prototype. It replaces
the removed Renderer 2 audit/design stack. The previous stack is intentionally
not the design source anymore: it grew too complex, mixed concerns, and carried
too much legacy-retirement context into new work.

## Direction

Build a smaller scene renderer from a few explicit primitives:

- Compose modifiers own Compose/UI objects.
- The renderer owns OpenGL objects and GL-thread state.
- Main-thread capture and GL-thread import are separate phases.
- Root capture is synchronous on the main thread before Android flushes the
  current display list.
- Root and slot canvas renderer work is dispatched through per-capture handler
  threads, while the main thread waits for each result so one submitted scene
  snapshot still represents one coherent UI moment.
- Scene submission is immutable. The renderer may cache GL resources, but it
  should not own UI-layer state.
- Child modifiers register declarative slot state through a scene registry, not
  through renderer handles.
- Frame submission is latest-only and handed to GL on vsync.

## Current Entry Points

Public entry points:

- `ImlaHost`
- `Modifier.effectGroup()`
- `Modifier.effectLayer { ... }`

Internal renderer entry points, locals, and diagnostics stay under
`dev.serhiiyaremych.imla.internal`. The older `ImlaSceneRenderer`,
`ImlaSceneHost`, `Modifier.blurSource()`, and `Modifier.backdropBlur(...)` code is
internal legacy code only; it is not the source of truth for client API shape.
Post-processing remains an internal rendering concept, not the public API name.

## Current Packages

- `imla/EffectLayer.kt`: public `effectGroup()` / `effectLayer { ... }` Compose
  API and the effect layer DSL.
- `imla/ImlaHost.kt`: public host that owns scratch renderer creation and
  provides internal renderer/registry scope.
- `imla/internal/capture/`: `GraphicsLayer` capture to either API 29+
  `HardwareBuffer` frames or pre-29 owned GL texture frames through the
  SurfaceTexture bridge.
- `imla/internal/modifier/SceneSourceModifier.kt`: records the effect group root
  layer and flushes capture from `OnPreDraw`.
- `imla/internal/modifier/SceneSlotModifier.kt`: effect layer modifier
  implementation.
- `imla/internal/render/SceneRenderer.kt`: main-thread orchestration for root
  capture, layer capture, metrics, scheduler submission, and surface lifecycle.
- `imla/internal/layer/registry/`: internal layer registry and slot snapshots.
- `imla/internal/layer/model/`: immutable render snapshot, resources, layer
  declarations, IDs, and transform-aware geometry.
- `imla/internal/render/scheduler/`: latest-only vsync frame scheduler.
- `imla/internal/render/gl/`: GL owner, render target, GL-thread guard, and GL
  resource store.
- `imla/internal/metrics/`: per-renderer metrics snapshots.

## Implemented

- Root content is captured from Compose on the main thread. API 29+ captures
  into `HardwareBuffer`; older APIs use the renderer-owned SurfaceTexture path
  and submit an owned normal `TEXTURE_2D` frame.
- Captured root frames are imported into OpenGL and presented to the host
  surface.
- `GraphicsLayerCapture` returns closeable captured frames and keeps buffer
  release ownership explicit.
- Each `GraphicsLayerCapture` owns its own handler-thread executor. The root
  capture uses `ImlaRootCapture`; slot captures use per-slot executor names.
- Scene2 capture snapshots use `CapturedLayerFrame`, so roots, slot content,
  progressive masks, and clips can carry either a `HardwareBuffer` frame or an
  already-copied GL texture frame.
- `SceneRenderSnapshot` owns the root frame plus optional slot resources and can
  be closed as one unit.
- `SceneGlOwner` starts AndroidX `GLRenderer`, attaches a surface, imports root
  and slot frames, and draws through the existing quad rendering primitives.
- `SceneFrameScheduler` holds only the latest pending frame and dispatches it on
  vsync.
- Metrics are per renderer instance and exposed as snapshots for Compose UI:
  FPS, target resolution, main root/content/mask/clip capture time, scheduler
  wait, GL queue delay, root/content/mask/clip buffer-to-texture import, GL
  draw time, draw latency, drops, failures, scene feature counts, and GL pass
  issue timings.
- `SceneRegistry` lets child modifiers register slot declarations without
  receiving renderer or OpenGL handles.
- Slot content can be captured into a per-slot `GraphicsLayerCapture`, imported
  to GL, and drawn as a texture.
- Slot geometry uses Compose matrices to produce:
  - local content size;
  - local-to-root and root-to-local matrices;
  - transformed root quad;
  - axis-aligned root bounds for backdrop/effect sampling;
  - GL render transform for the visible content quad.
- Rotated slot content renders with the same transform as the Compose card.
- A backdrop debug quad samples the root texture in root-space coordinates so
  checkerboard/background markers align with the clean plate instead of rotating
  with the card.
- The debug backdrop pass documents and avoids the extra Y-flip that previously
  mirrored the sampled root texture inside the rotated card.
- Backdrop effects run through prepare, separable blur, composite, and content
  passes.
- `Modifier.effectLayer { ... }` supports backdrop blur, tint, material noise,
  non-rectangle shape clipping, clip insets, and optional content clipping.
- Progressive mask brushes are rasterized on the main thread at slot-local size,
  imported as GL textures, and consumed by the blur pass as slot-local blur
  strength input.
- Clip shapes are rasterized on the main thread at slot-local size, imported as
  GL textures, written into stencil with the slot render transform, and scoped
  around backdrop composite and/or content draws.
- Backdrop tint and material-attached luma-weighted noise are applied in the
  backdrop composite shader.
- Backdrop prepare samples the current accumulated scene buffer before the
  current slot writes itself, so later slots can blur earlier rendered slots in
  draw order.
- The metrics overlay exposes progressive mask capture, clip shape capture,
  mask and clip imports, lazy noise generation, stencil clip setup, final
  present, composite sample estimates, and latest scene feature counts.
- `NewRendererTestScene` has asymmetric root-space orientation markers (`TL`,
  `BL`, blue bar, orange dot) to catch vertical/horizontal flip mistakes.
- `NewRendererTestScene` includes a fixed `BASE` / `STACK` overlap probe for
  cumulative backdrop validation.
- `tools/adb-screenshot-half` captures half-size screenshots for lower-token
  visual inspection.
- Normal rendering reuses already-imported stable slot content for slots without
  backdrop effects. Backdrop slots still recapture content every frame because
  stable reuse removes the child capture wait but can expose worse root
  capture / presentation backpressure on the low-end tablet. The
  `ImlaScene2DiagReuseStableSlotContent` modes still force reuse for diagnostic
  isolation.

## Verified Recently

- Root GL present rendered through OpenGL, with the Compose root not drawn back
  into the main canvas by the source modifier.
- Rotated slot content rendered in the expected position and orientation.
- Root-anchored backdrop sampling lined up with the root checkerboard during
  3D rotation.
- Backdrop debug sampling no longer vertically flips the root texture inside the
  rotated card.
- Scene2 renders into an owned intermediate scene framebuffer before presenting
  that texture to the default surface. That framebuffer is also the accumulated
  backdrop input for later slots.
- Backdrop pass outputs separate texture sampling data from borrowed framebuffer
  ownership: `BackdropTexture` is the cross-pass image payload, while
  `BorrowedBackdropTexture` keeps pooled FBO ownership for release.
- Progressive mask brushes are rasterized on the main thread at slot-local size,
  imported as GL textures, and consumed by the blur pass as slot-local blur
  strength input.
- `QuadBatchRenderer.begin(reservedTextures = ...)` lets specialized shaders
  declare extra texture inputs before flush and receive stable texture-slot
  indices in `configureShader`, while keeping texture ownership in the renderer
  resource store.
- `SceneBackdropBlurPass` now produces the processed backdrop through a simple
  two-pass separable blur: horizontal into a temporary borrowed FBO, then
  vertical into the processed FBO.
- `BackdropBlurInput` carries pass-local shader data for the blur stage:
  source sampling view, requested sigma, prepared downsample scale, and capped
  filter radius. It does not own GL resources.
- Rotated backdrop blur uses a stable diagonal sample envelope in root space:
  the sample crop follows the transformed slot center, but its size is derived
  from the slot-local diagonal plus blur padding. Do not replace this with tight
  projected axis-aligned bounds unless rotation drift and blur-band stability
  are reverified; changing the prepared texture footprint with rotation caused
  visible drift.
- Large-area backdrop blur showed visible bending/banding with default
  `mediump` fragment precision. Switching the prepare and screen-space
  composite shaders to `highp`, and promoting blur shader coordinates, weights,
  bounds, sampled color, accumulation, and output to explicit `highp`, improved
  the artifact enough for the current prototype. `RGB10_A2` intermediate FBOs,
  disabling the Wronski prefilter, and disabling progressive blur strength did
  not remove the bending.
- The half-size screenshot tool produced a `600x960` PNG from the `1200x1920`
  device screen and kept the marker details readable.
- Scene2 stencil clips were visually checked with rounded slot cards, backdrop
  only clipping, content clipping, and clip insets.
- Scene2 noise/tint composite was visually checked with all demo cards using a
  visible testing noise value.
- Cumulative backdrop sampling was visually checked with the fixed `BASE` /
  `STACK` overlap probe: the later blurred card samples the earlier rendered
  source card, while existing clips, tint, noise, and rotated cards remain
  visually coherent.
- On `T81164GB23417442888`, the benchmark app was installed, ART speed-compiled
  (`arm64: [status=speed] [reason=cmdline]`), and warmed no-exercise Perfetto
  captures were taken for scene2 profiling:
  - normal `static-root-backdrop-blur` preserved recurring slot captures after
    the diagnostic gate was added:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-230540.perfetto-trace`
    showed `GraphicsLayerCapture#drawWait[388x240]` count `349`, avg `9.316ms`;
  - normal `static-root-geometry-slot-blur`:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-230821.perfetto-trace`
    showed slot `drawWait[388x240]` count `350`, avg `9.161ms`, root
    `drawWait[1200x1920]` avg `6.614ms`, and frame avg `21.111ms`;
  - `static-root-geometry-slot-blur` with
    `ImlaScene2DiagReuseStableSlotContent=DEBUG`:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-230725.perfetto-trace`
    removed recurring `388x240` slot captures, but root
    `drawWait[1200x1920]` rose to avg `14.493ms`, and frame avg worsened to
    `35.004ms`.
- The slot reuse diagnostic proves the per-frame slot capture can be removed
  for stable content, but it is not a speed fix by itself on this device. The
  next bottleneck is root capture / presentation / GPU queue pressure, not just
  the child `GraphicsLayerCapture`.
- A follow-up four-way geometry capture on the same speed-compiled benchmark
  build isolated final presentation:
  - normal `static-root-geometry-slot-blur`:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-231803.perfetto-trace`
    showed frame avg `21.208ms`, slot `drawWait[388x240]` avg `9.163ms`, root
    `drawWait[1200x1920]` avg `6.750ms`, and Imla GL render avg `6.163ms`;
  - `ImlaScene2DiagReuseStableSlotContent=DEBUG`:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-231840.perfetto-trace`
    removed recurring slot captures, but root `drawWait[1200x1920]` rose to
    avg `14.523ms`, `RenderThread dequeueBuffer` rose to avg `5.800ms`, and
    frame avg worsened to `35.069ms`;
  - `ImlaScene2DiagNoFinalPresent=DEBUG` with normal slot capture:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-231918.perfetto-trace`
    kept slot `drawWait[388x240]` at avg `9.152ms`, lowered root
    `drawWait[1200x1920]` to avg `6.119ms`, lowered Imla GL render to avg
    `4.778ms`, and frame avg improved to `18.815ms`;
  - `ImlaScene2DiagReuseStableSlotContentNoPresent=DEBUG`:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-231959.perfetto-trace`
    removed recurring slot captures while keeping root `drawWait[1200x1920]`
    low at avg `5.749ms`, Imla GL render avg `4.661ms`, and frame avg
    `18.716ms`.
- No-present screenshots are expected to show a black Imla surface because the
  diagnostic intentionally skips the scene final-present draw. The trace is
  still useful because fresh root capture, slot capture/import, backdrop
  processing, and GL render scheduling continue.
- This points to final presentation / surface-buffer backpressure as the reason
  slot reuse got worse when presentation was enabled. The removed slot capture
  did not merely save work; it changed pacing, then full-scene presentation
  pressure showed up as slower root capture waits and buffer dequeue waits.
- A paced-present diagnostic then tested whether reduced scene-surface
  presentation cadence relieves that pressure while stable slot reuse remains
  enabled:
  - `ImlaScene2DiagReuseStableSlotContent=DEBUG` control:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-232834.perfetto-trace`
    showed frame avg `34.566ms`, root `drawWait[1200x1920]` avg `14.077ms`,
    `RenderThread dequeueBuffer` avg `5.685ms`, and final-present draw count
    `291`;
  - `ImlaScene2DiagReuseStableSlotContentPresentEvery2=DEBUG`:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-232912.perfetto-trace`
    produced `144` final-present draws and `144` skips, but still showed frame
    avg `33.695ms`, root `drawWait[1200x1920]` avg `13.155ms`, and
    `RenderThread dequeueBuffer` avg `5.766ms`;
  - `ImlaScene2DiagReuseStableSlotContentPresentEvery4=DEBUG`:
    `diagnostics/apa/traces/imla-smoke-2026-06-04-232947.perfetto-trace`
    produced `88` final-present draws and `262` skips, kept the scene visible,
    and improved to frame avg `18.907ms`, root `drawWait[1200x1920]` avg
    `6.030ms`, `RenderThread dequeueBuffer` avg `0.745ms`, and Imla GL render
    avg `5.082ms`.
- Present-every-2 is not enough on the test device. In the first paced run,
  present-every-4 was enough to avoid the root-capture wait jump while keeping
  stable slot reuse active. A later `final_v2` adaptive diagnostic showed the
  margin is not stable:
  - an initial adaptive mode that returned to faster presentation too eagerly
    (`diagnostics/apa/traces/imla-smoke-2026-06-04-234222.perfetto-trace`)
    drew `110` final presents and skipped `190`, but still showed frame avg
    `31.783ms`, root `drawWait[1200x1920]` avg `12.056ms`, and
    `RenderThread dequeueBuffer` avg `5.290ms`;
  - after fixing the cadence reset bug, adaptive cadence `4`
    (`diagnostics/apa/traces/imla-smoke-2026-06-04-234755.perfetto-trace`)
    drew `73` final presents and skipped `222`, but still showed frame avg
    `34.446ms`, root `drawWait[1200x1920]` avg `13.181ms`, and
    `RenderThread dequeueBuffer` avg `6.109ms`;
  - a same-session fixed present-every-4 control
    (`diagnostics/apa/traces/imla-smoke-2026-06-04-234902.perfetto-trace`)
    also drew `73` final presents and skipped `219`, with frame avg
    `34.159ms`, root `drawWait[1200x1920]` avg `13.404ms`, and
    `RenderThread dequeueBuffer` avg `6.077ms`;
  - a no-present control
    (`diagnostics/apa/traces/imla-smoke-2026-06-04-234955.perfetto-trace`)
    recovered to frame avg `18.793ms`, root `drawWait[1200x1920]` avg
    `5.733ms`, and `RenderThread dequeueBuffer` avg `0.793ms`;
  - adaptive cadence `8`
    (`diagnostics/apa/traces/imla-smoke-2026-06-04-235145.perfetto-trace`)
    drew `41` final presents and skipped `289`, improving over the bad
    every-4 control but still showing frame avg `29.004ms`, root
    `drawWait[1200x1920]` avg `10.607ms`, and `RenderThread dequeueBuffer`
    avg `4.424ms`.
- A reduced-present-area diagnostic then tested whether final-present pressure
  is primarily default-framebuffer pixel fill:
  - after a short idle, `ImlaScene2DiagReuseStableSlotContentNoPresent=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-000101.perfetto-trace`)
    recovered to frame avg `18.676ms`, root `drawWait[1200x1920]` avg
    `5.706ms`, `RenderThread dequeueBuffer` avg `0.791ms`, and final-present
    skip count `352`;
  - `ImlaScene2DiagReuseStableSlotContentHalfViewportPresent=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-000147.perfetto-trace`)
    drew the final scene into a centered `600x960` viewport, confirmed by
    `SceneFinalPresentPass#viewport[600x960]` count `271`, but still showed
    frame avg `33.368ms`, root `drawWait[1200x1920]` avg `13.033ms`,
    `RenderThread dequeueBuffer` avg `5.997ms`, and final-present draw count
    `271`.
- A plain root-only floor check then tested whether the device can handle the
  renderer with no slot/effect stack:
  - `ImlaScene2ProfileRoot=DEBUG` normal present
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-001310.perfetto-trace`)
    showed frame avg `17.064ms`, root `drawWait[1200x1920]` avg `7.852ms`,
    `RenderThread dequeueBuffer` avg `0.661ms`, Imla GL render avg `2.415ms`,
    and final-present draw count `358`;
  - `ImlaScene2ProfileRoot=DEBUG` with `ImlaScene2DiagNoFinalPresent=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-001358.perfetto-trace`)
    showed frame avg `16.484ms`, root `drawWait[1200x1920]` avg `9.297ms`,
    `RenderThread dequeueBuffer` avg `0.746ms`, Imla GL render avg `0.832ms`,
    and final-present skip count `358`;
  - a second `ImlaScene2ProfileRoot=DEBUG` normal-present run after a short idle
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-001524.perfetto-trace`)
    reproduced the normal-present floor with frame avg `17.057ms`, root
    `drawWait[1200x1920]` avg `7.504ms`, `RenderThread dequeueBuffer` avg
    `0.652ms`, Imla GL render avg `2.478ms`, and final-present draw count
    `358`.
- A follow-up profiling ladder on the same speed-compiled benchmark build
  looked for the first budget cliff after root-only:
  - `ImlaScene2ProfileRoot=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-002211.perfetto-trace`)
    showed frame avg `16.997ms`, root `drawWait[1200x1920]` avg `7.585ms`,
    `RenderThread dequeueBuffer` avg `0.710ms`, Imla GL render avg `2.390ms`,
    and final-present draw count `360`;
  - `ImlaScene2ProfileSlot=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-002246.perfetto-trace`)
    raised frame avg to `21.872ms`, with slot `drawWait[388x240]` avg
    `7.733ms`, root `drawWait[1200x1920]` avg `6.889ms`,
    `RenderThread dequeueBuffer` avg `1.153ms`, and Imla GL render avg
    `2.781ms`;
  - `ImlaScene2ProfileFrozenSlotBlur=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-002319.perfetto-trace`)
    showed frame avg `21.295ms`, slot `drawWait[388x240]` avg `8.993ms`,
    root `drawWait[1200x1920]` avg `7.120ms`, `RenderThread dequeueBuffer`
    avg `0.726ms`, and Imla GL render avg `6.032ms`. The GL render cost split
    across backdrop prepare avg `2.741ms`, blur stage avg `1.748ms`, and
    backdrop composite avg `0.813ms`.
- The latest picture is that final presentation pressure is real, but the
  plain root-only floor is near, but only slightly over, the `60Hz` frame
  budget. The first clear cliff after root-only is per-frame slot content
  capture, not blur itself. Blur then adds meaningful GL work, but on this
  device the plain slot already breaks the budget. The severe `30ms+` cases
  come from slot/effect work interacting with presentation pressure, not from
  the root-only renderer alone. The safe cadence on this device is not a simple
  fixed `4`, and reducing the default-framebuffer viewport area alone does not
  remove the wait. Reducing presentation cadence helps more than reducing the
  presented pixel area. Treat adaptive cadence and half-viewport present as
  diagnostics, not production policies yet.
- Stable slot content reuse was promoted narrowly after the profiling ladder:
  normal non-backdrop slots can now reuse a previously imported content texture
  when `SceneSlotContentKey` still matches. Backdrop slots keep the old
  per-frame capture path unless a diagnostic reuse mode is explicitly enabled.
  - `ImlaScene2ProfileRoot=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-003622.perfetto-trace`)
    remained the current same-build floor with frame avg `16.970ms`, root
    `drawWait[1200x1920]` avg `7.242ms`, Imla GL render avg `2.402ms`, and
    final-present draw avg `1.621ms`;
  - `ImlaScene2ProfileSlot=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-003657.perfetto-trace`)
    had no recurring `GraphicsLayerCapture#drawWait[388x240]` slices, kept the
    centered slot visually present, and showed root `drawWait[1200x1920]` avg
    `8.846ms`, Imla GL render avg `2.849ms`, and frame avg `25.488ms`;
  - `ImlaScene2ProfileFrozenSlotBlur=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-003522.perfetto-trace`)
    intentionally kept recurring `388x240` slot captures for the backdrop case:
    slot `drawWait[388x240]` avg `9.085ms`, root `drawWait[1200x1920]` avg
    `7.193ms`, Imla GL render avg `6.057ms`, and frame avg `21.259ms`.
- The new policy removes one proven waste case, but it does not solve output
  cadence on the low-end tablet. In plain slot runs, the missing slot capture
  can still leave root capture waiting behind presentation/GPU queue pressure.
  The next optimization should target root-capture pacing after content reuse,
  not broader content-cache promotion.
- Root-capture pacing after stable content reuse now uses a pressure-sensitive
  one-callback defer: if a frame can reuse stable slot content, no slot content
  capture is expected, and the previous root capture exceeded the pressure
  threshold, root capture is deferred through Choreographer. The policy is only
  a pacing replacement for the removed slot-capture wait; it does not broaden
  backdrop-slot reuse.
  - `ImlaScene2ProfileRoot=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-005327.perfetto-trace`)
    showed a bad same-build root floor: frame avg `30.858ms`, root
    `drawWait[1200x1920]` avg `14.034ms`, Imla GL render avg `2.649ms`;
  - `ImlaScene2ProfileSlot=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-005356.perfetto-trace`)
    fired `SceneRootCapture#DeferralScheduled[stableContentReuse]` `159`
    times, kept recurring `388x240` slot captures absent, and showed frame avg
    `27.991ms`, root `drawWait[1200x1920]` avg `10.714ms`;
  - after a short idle, a second `ImlaScene2ProfileRoot=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-005538.perfetto-trace`)
    still had an elevated floor with frame avg `25.215ms` and root
    `drawWait[1200x1920]` avg `11.344ms`;
  - the paired `ImlaScene2ProfileSlot=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-005609.perfetto-trace`)
    had no recurring `388x240` slot captures, fired the stable-content root
    defer once, kept the centered slot visually correct, and reached frame avg
    `17.181ms`, root `drawWait[1200x1920]` avg `5.887ms`, Imla GL render avg
    `2.849ms`;
  - `ImlaScene2ProfileFrozenSlotBlur=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-005424.perfetto-trace`)
    preserved the backdrop baseline: slot `drawWait[388x240]` avg `9.417ms`,
    root `drawWait[1200x1920]` avg `6.984ms`, Imla GL render avg `6.184ms`,
    and frame avg `21.692ms`.
- The pacing policy gives a controlled replacement for one lost delay source,
  but the low-end tablet can still enter a root-only bad state. The next
  investigation should distinguish app scheduling from device/display thermal or
  surface pressure by tracking root-only floor stability before judging further
  scene optimizations.
- A paired stability protocol then kept the same benchmark APK and speed-compiled
  app, checked thermal status `0`, and captured root-only A, root-only B,
  plain-slot, then root-only C:
  - root-only A
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-010456.perfetto-trace`)
    showed frame avg `17.023ms`, root `drawWait[1200x1920]` avg `7.167ms`,
    app `RenderThread` `dequeueBuffer` avg `0.731ms`, and Imla GL render avg
    `2.342ms`;
  - root-only B
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-010551.perfetto-trace`)
    showed frame avg `17.093ms`, root `drawWait[1200x1920]` avg `7.433ms`,
    app `RenderThread` `dequeueBuffer` avg `0.512ms`, and Imla GL render avg
    `2.469ms`;
  - plain-slot
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-010621.perfetto-trace`)
    kept recurring `388x240` slot captures absent and kept root
    `drawWait[1200x1920]` in the normal band at avg `7.892ms`, but frame avg
    rose to `22.020ms` because app `RenderThread` `dequeueBuffer` rose to avg
    `2.406ms`, max `15.417ms`;
  - root-only C
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-010651.perfetto-trace`)
    returned to frame avg `17.069ms`, root `drawWait[1200x1920]` avg
    `7.436ms`, app `RenderThread` `dequeueBuffer` avg `0.550ms`, and Imla GL
    render avg `2.421ms`.
- This run did not reproduce root-only floor instability. It instead shows the
  next visible plain-slot bottleneck as app `RenderThread` buffer acquisition /
  presentation pressure, while root capture itself stayed near the root-only
  band and slot capture stayed removed.
- A plain-slot present-isolation follow-up then compared normal present,
  no-final-present, cached-GL-present, cached-GL-no-present, and a normal
  present repeat on the same installed benchmark APK:
  - normal plain-slot
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-011112.perfetto-trace`)
    showed frame avg `17.158ms`, root `drawWait[1200x1920]` avg `5.866ms`,
    app `RenderThread` `dequeueBuffer` avg `0.741ms`, Imla GL render avg
    `2.843ms`, and final-present draw avg `1.507ms`;
  - `ImlaScene2DiagNoFinalPresent=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-011142.perfetto-trace`)
    showed frame avg `16.523ms`, root `drawWait[1200x1920]` avg `9.000ms`,
    app `RenderThread` `dequeueBuffer` avg `0.711ms`, Imla GL render avg
    `1.394ms`, and final present skipped;
  - `ImlaScene2DiagCachedGlOnly=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-011213.perfetto-trace`)
    showed frame avg `15.923ms`, no recurring root `drawWait` in the measured
    window, app `RenderThread` `dequeueBuffer` avg `2.022ms`, Imla GL render
    avg `2.713ms`, and final-present draw avg `1.895ms`;
  - `ImlaScene2DiagCachedGlNoPresent=DEBUG`
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-011243.perfetto-trace`)
    showed frame avg `15.380ms`, app `RenderThread` `dequeueBuffer` avg
    `2.096ms`, Imla GL render avg `0.910ms`, and final present skipped;
  - normal plain-slot repeat
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-011410.perfetto-trace`)
    returned frame avg `17.138ms`, root `drawWait[1200x1920]` avg `5.738ms`,
    app `RenderThread` `dequeueBuffer` avg `0.738ms`, Imla GL render avg
    `2.848ms`, and final-present draw avg `1.508ms`.
- The bad plain-slot app `RenderThread` `dequeueBuffer` state from the previous
  protocol did not reproduce in the present-isolation follow-up. Normal
  plain-slot stayed near `17.1ms`, no-final-present only modestly improved the
  clean state while root capture wait rose, and cached-GL diagnostics showed
  that high app `RenderThread` `dequeueBuffer` can coexist with good frame time
  when fresh root capture is removed. `dequeueBuffer` remains a useful pressure
  signal, but it is not sufficient by itself to prove the jank source.
- A later bad-window protocol reproduced the slow state, but with root-only
  already bad before and after the plain-slot capture:
  - root-only A
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-012154.perfetto-trace`)
    showed frame avg `26.845ms`, root `drawWait[1200x1920]` avg `12.192ms`,
    app `RenderThread` `dequeueBuffer` avg `3.614ms`, Imla GL render avg
    `2.577ms`, and final-present draw avg `1.745ms`;
  - plain-slot
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-012217.perfetto-trace`)
    kept recurring `388x240` slot captures absent and showed frame avg
    `23.324ms`, root `drawWait[1200x1920]` avg `8.608ms`, app
    `RenderThread` `dequeueBuffer` avg `2.799ms`, Imla GL render avg
    `2.852ms`, and final-present draw avg `1.531ms`;
  - root-only B
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-012240.perfetto-trace`)
    showed frame avg `27.074ms`, root `drawWait[1200x1920]` avg `12.085ms`,
    app `RenderThread` `dequeueBuffer` avg `3.682ms`, Imla GL render avg
    `2.563ms`, and final-present draw avg `1.711ms`;
  - plain-slot no-final-present
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-012354.perfetto-trace`)
    recovered to frame avg `16.523ms` with final present skipped, root
    `drawWait[1200x1920]` avg `9.016ms`, app `RenderThread` `dequeueBuffer`
    avg `0.690ms`, and Imla GL render avg `1.460ms`;
  - cached-GL-present
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-012417.perfetto-trace`)
    recovered to frame avg `15.971ms` with no recurring root capture, app
    `RenderThread` `dequeueBuffer` avg `1.971ms`, Imla GL render avg
    `2.785ms`, and final-present draw avg `1.922ms`;
  - cached-GL-no-present
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-012440.perfetto-trace`)
    recovered to frame avg `15.387ms` with no recurring root capture, final
    present skipped, app `RenderThread` `dequeueBuffer` avg `2.236ms`, and
    Imla GL render avg `0.920ms`.
- This isolates the current low-end bad window as a coupling problem between
  synchronous root capture and continuous scene-surface presentation /
  display-queue pressure. Stable slot reuse is still a valid work-volume fix,
  but the slow state is not caused by recurring slot content capture. Skipping
  final present can restore frame cadence while root capture continues, and
  cached-GL-present can restore frame cadence while final present continues.
  The production question is therefore how to decouple capture and presentation
  cadence under pressure without hurting smooth devices or visibly degrading
  UI output.
- A narrow production scene-work coalescing policy now runs only in normal
  scene2 mode. After two consecutive root captures at or above `0.66` of the
  current frame budget, if a reusable GL frame already exists, stable slot
  content was reused, and no slot content capture is required, the renderer
  skips selected dirty ticks before root capture. A skipped tick does no root
  capture, slot capture, snapshot assembly, GL submission, or final
  presentation; the previously presented surface content stays visible. The
  policy alternates at most one skipped tick per real frame, exits after two
  root captures at or below `0.50` of the current frame budget, and resets after
  idle, detach, or surface clear. Diagnostic modes keep their existing behavior.
- Device validation rejected a broader root-only version of the policy:
  - root-only with broad coalescing
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-014845.perfetto-trace`)
    fired `171` `SceneFramePace#WorkCoalesced` slices but worsened to frame
    avg `33.679ms`, root `drawWait[1200x1920]` avg `14.698ms`, and app
    `RenderThread` `dequeueBuffer` avg `8.267ms`;
  - the current stable-slot-gated policy then kept root-only A
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-015309.perfetto-trace`)
    at zero `WorkCoalesced` slices while the bad root floor remained visible:
    frame avg `32.216ms`, root `drawWait[1200x1920]` avg `14.616ms`, and app
    `RenderThread` `dequeueBuffer` avg `5.076ms`;
  - plain-slot
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-015332.perfetto-trace`)
    fired `33` `WorkCoalesced` slices, kept recurring `388x240` slot captures
    absent, and showed frame avg `20.239ms`, root `drawWait[1200x1920]` avg
    `6.786ms`, app `RenderThread` `dequeueBuffer` avg `1.879ms`, Imla GL
    render avg `2.892ms`, and final-present draw avg `1.553ms`;
  - root-only B
    (`diagnostics/apa/traces/imla-smoke-2026-06-05-015355.perfetto-trace`)
    again had zero `WorkCoalesced` slices and showed frame avg `29.959ms`,
    root `drawWait[1200x1920]` avg `13.443ms`, and app `RenderThread`
    `dequeueBuffer` avg `4.442ms`;
  - `diagnostics/apa/screenshots/imla-smoke-2026-06-05-015332.png` showed the
    centered plain slot without obvious shift, stretch, Y-flip, alpha/color, or
    slot-alignment artifacts.
- The current policy is a partial mitigation for stable plain-slot pressure,
  not a full fix for this low-end tablet. It improved the plain-slot bad-window
  trace compared with `012217`, but the surrounding root-only floor remained
  bad. Keep root-only coalescing disabled.

Recent screenshot artifacts live under:

- `diagnostics/apa/scene2-root-present/`
- `diagnostics/apa/scene2-child-texture/`
- `diagnostics/apa/scene2-rotated-slot/`
- `diagnostics/apa/scene2-effect-patch-debug/`
- `diagnostics/apa/scene2-backdrop-debug/`
- `diagnostics/apa/scene2-progressive-mask-step/`
- `diagnostics/apa/scene2-stencil-clip-prototype-half.png`
- `diagnostics/apa/scene2-noise-visible-half.png`
- `diagnostics/apa/scene2-cumulative-prototype-half.png`

Diagnostics remain untracked unless explicitly promoted to a summary doc.

## Current Geometry Contract

For rotated children, the renderer treats content and backdrop sampling as
separate concerns:

- child content is captured in local, unrotated space;
- the visible content quad receives the slot render transform;
- backdrop/effect sampling uses root-space coordinates from the accumulated
  scene buffer before the current slot writes itself;
- progressive mask sampling maps prepared sample pixels back into slot-local
  mask coordinates;
- backdrop blur prepares a stable root-space diagonal envelope, centered on the
  transformed slot coverage and sized from the slot-local diagonal plus blur
  padding;
- the prepared sample envelope is snapped to the downsample grid before
  clipping to the root target bounds;
- final backdrop composite samples the processed texture in screen space using
  `gl_FragCoord`, so the blur field stays rooted to the plate while the visible
  quad rotates;
- root and slot content keep the existing scene2 top-left presentation sampling
  contract when drawn into the intermediate scene buffer;
- the final present pass is the only place that adapts the offscreen scene
  framebuffer texture to the Android default surface. Do not replace this with
  a generic texture-origin rule without rechecking root orientation, slot
  orientation, and screen-space backdrop sampling;
- the backdrop debug texture coordinates are computed from the transformed root
  quad and normalized by root texture size;
- explicit root-space texture coordinates must not also receive the generic
  texture-origin flip.

This keeps the frosted-glass sample stable in screen space while the
card/content itself can rotate, and lets later slots include earlier scene
contributions in their backdrop.

## Current Non-Goals

- Do not reintroduce renderer handles into child modifiers.
- Do not restore `UiLayerRenderer`, `ImlaRenderPipeline`,
  `CopyLessRenderingPipeline`, render-object architecture, or renderer-taking
  modifiers as design inputs.
- Do not port the previous atlas/effects roadmap into this prototype.
- Do not reintroduce atlas routing until scene2 cumulative and clipping
  semantics are stable.
- Do not make the renderer own Compose `GraphicsLayer` objects.
- Do not expose cumulative rendering as a public API switch.

## Likely Next Step

Production scene-work coalescing is now intentionally conservative:

- pressure is learned after completed eligible captures, not inside the
  pre-capture skip decision;
- eligible means stable slot content was reused and no slot content capture was
  needed;
- ineligible captures, root capture failure, idle, detach, surface clear, and
  resize reset the policy;
- root-only bad windows cannot arm later plain-slot coalescing by themselves.

The clean-window T811 check on 2026-06-05 did not fire
`SceneFramePace#WorkCoalesced` in root-only, plain-slot, or backdrop blur
traces, and screenshots showed no obvious shift, stretch, Y-flip, alpha/color,
slot alignment, or backdrop edge artifacts. Treat that as a non-regression
check, not threshold tuning proof.

Root-dirty separation now keeps the user API simple:

- `Modifier.effectGroup()` has no root-content key or static-root promise.
  Callers should not have to reason about renderer cache validity.
- `SceneRenderSnapshot` still carries an internal root generation through GL
  import. The GL owner exposes a generation as reusable only after the GL thread
  has imported that exact fresh root. Dropped or stale-pressure reuse frames do
  not advance the confirmed root generation.
- The source currently advances root generation on every root record, so normal
  `effectGroup()` use still captures a fresh root clean plate every draw except
  for the separate one-stale-frame pressure policy.
- An automatic `observeReads` root detector was rejected during the slice:
  root-only animation changed `graphicsLayer` parameters outside the observed
  draw-block reads and incorrectly reused the first root. A public static-root
  key was also rejected because it exposed renderer internals and created a
  caller footgun.

Mechanism evidence captured before removing the public static-key prototype:

- static-root backdrop blur
  `diagnostics/apa/traces/imla-smoke-2026-06-05-172840.perfetto-trace`:
  `SceneRootReuse#CleanRoot` count `358`, `SceneRootReuse#GlReused` count
  `359`, no recurring `GraphicsLayerCapture#capture[1200x1920]`, and recurring
  slot capture remained at `GraphicsLayerCapture#capture[388x240]` count `358`;
- root-only control
  `diagnostics/apa/traces/imla-smoke-2026-06-05-172918.perfetto-trace`:
  recurring `GraphicsLayerCapture#capture[1200x1920]` count `355`, no
  `SceneRootReuse#CleanRoot`, and only `4` pressure `SceneRootReuse#ReusePrevious`
  frames;
- stable checkerboard crop diff between the two screenshots passed with
  `changed=0`, `max_delta=0`, `ssim_luma_global=1.0`.

A later fixed root-capture downscale diagnostic kept the same simple user API
and added only internal diagnostic modes:
`ImlaScene2DiagRootCaptureScale65` and `ImlaScene2DiagRootCaptureScale50`.
`GraphicsLayerCapture` can render the root into a smaller buffer while retaining
the logical root size for GL sampling. Ownership stays unchanged: Compose still
owns the `GraphicsLayer`, captured frames still close through their producing
renderer, and GL stores the imported root texture together with the logical
root size used by backdrop sampling.

Evidence on `T81164GB23417442888`:

- baseline full root after stable backdrop slot reuse
  `diagnostics/apa/traces/imla-smoke-2026-06-05-191016.perfetto-trace`:
  `GraphicsLayerCapture#capture[1200x1920]` count `116`, avg `71.729ms`;
- 65% root diagnostic
  `diagnostics/apa/traces/imla-smoke-2026-06-05-194457.perfetto-trace`:
  `GraphicsLayerCapture#capture[780x1248]` count `117`, avg `71.018ms`;
- 50% root diagnostic
  `diagnostics/apa/traces/imla-smoke-2026-06-05-195617.perfetto-trace`:
  `GraphicsLayerCapture#capture[600x960]` count `118`, avg `70.787ms`;
- the slow slice stayed in `GraphicsLayerCapture#drawWait`, with RenderThread
  `waitForever` around `66ms`, so this capture path is not meaningfully
  pixel-count-bound on the low-end tablet;
- a stable prefilter crop diff between normal and 65% fresh-launch screenshots
  passed with `changed=0%`, `max_delta=4`, `ssim_luma_global=0.999990`.
- a stable prefilter crop diff between 65% and 50% settled screenshots passed
  with `changed=0.307065%`, `max_delta=13`,
  `ssim_luma_global=0.999947`.

A follow-up low-latency-style dispatch diagnostic tested passing a dedicated
handler-thread executor to `CanvasBufferedRenderer.drawAsync` instead of the
direct executor. Evidence:

- direct executor
  `diagnostics/apa/traces/imla-smoke-2026-06-05-201408.perfetto-trace`:
  `GraphicsLayerCapture#drawAsyncCall` avg `0.578ms`,
  `GraphicsLayerCapture#awaitCallback` avg `70.057ms`, Android
  `RenderThread waitForever` avg `65.428ms`;
- handler-thread executor
  `diagnostics/apa/traces/imla-smoke-2026-06-05-201449.perfetto-trace`:
  `GraphicsLayerCapture#drawAsyncCall` avg `0.068ms`,
  `GraphicsLayerCapture#awaitCallback` avg `71.320ms`, and the long
  `waitForever` moved to the `ImlaRootCapture` thread while Android
  `RenderThread DrawFrames -1` dropped to avg `4.517ms`;
- overall frame stats did not improve because the main thread still waits for
  the capture callback before assembling and submitting the snapshot.

The callback-driven diagnostic then recorded the root into the `RenderNode` on
the main thread, started `CanvasBufferedRenderer.drawAsync` on the dedicated
`ImlaRootCapture` handler thread, and posted the finished root buffer back to
main for normal snapshot assembly. The renderer kept only one async root
capture in flight; new dirty ticks while the capture was pending were skipped
instead of canceling the capture, because canceling on every pre-draw starved
scene submission during continuous animation. Stale callbacks closed their
buffers without submission.

Evidence on `T81164GB23417442888` after the starvation fix:

- async root diagnostic
  `diagnostics/apa/traces/imla-smoke-2026-06-05-203853.perfetto-trace`:
  `SceneRootCapture#AsyncStarted` count `11`, avg `0.239ms`;
  `SceneRootCapture#AsyncCompleted` count `10`, avg `0.221ms`;
  `SceneRootCapture#AsyncInFlightSkipped` count `96`; one stale callback was
  closed; root `GraphicsLayerCapture#captureAsync[1200x1920]` avg `0.175ms` on
  the main thread; `ImlaRootCapture waitForever` count `13`, avg `111.336ms`;
  `ImlaScene2GL Scene2RenderPipeline#render` count `16`, avg `33.825ms`;
- same-build handler-thread synchronous comparison
  `diagnostics/apa/traces/imla-smoke-2026-06-05-203703.perfetto-trace`:
  `GraphicsLayerCapture#awaitCallback` count `99`, avg `88.438ms`, and
  `SceneRootCapture#Deferred` avg `88.952ms` on the main thread;
- `diagnostics/apa/scene2-root-capture-async-fixed-half.png` visually showed no
  obvious shift, stretch, Y-flip, alpha, mask/clip, or rotation artifact.

This proves the main-thread wait can be removed, but it does not prove the
optimization is ready. On this device the root buffer callback can still take
about one scene frame or more, so the diagnostic trades main-thread blocking for
lower scene submission cadence and stale root clean plates.

A follow-up async-reuse diagnostic tested the obvious next idea: submit a
`ReusePrevious` scene while async root capture remained in flight, instead of
skipping those dirty ticks. It did not improve the low-end trace. In the same
landscape orientation and same benchmark build:

- async-reuse
  `diagnostics/apa/traces/imla-smoke-2026-06-05-205209.perfetto-trace`:
  `SceneRootCapture#AsyncReusePrevious` count `225`,
  `SceneRootReuse#GlReused` count `85`,
  `Scene2RenderPipeline#render` count `124`, avg `73.710ms`,
  frame avg `120.599ms`, and `SceneFrameDrop#RenderRequestInFlight` count
  `191`;
- async-skip comparison
  `diagnostics/apa/traces/imla-smoke-2026-06-05-205302.perfetto-trace`:
  `SceneRootCapture#AsyncInFlightSkipped` count `225`,
  `SceneRootReuse#GlReused` count `60`,
  `Scene2RenderPipeline#render` count `119`, avg `33.294ms`,
  frame avg `89.962ms`, and `SceneFrameDrop#RenderRequestInFlight` count `14`;
- `diagnostics/apa/scene2-root-capture-async-reuse-half.png` visually showed no
  obvious shift, stretch, Y-flip, alpha, mask/clip, or rotation artifact.

That means naive reused-root submission while async root is pending mainly
floods the GL render-request gate and increases render pressure. The
callback-driven async diagnostics were removed after this result. The current
capture direction keeps the main thread waiting for capture results to preserve
snapshot synchronization, but dispatches every `CanvasBufferedRenderer`
`drawAsync` through the owning `GraphicsLayerCapture` handler thread. The old
root-only handler-thread diagnostic was also removed because this dispatch shape
is now the normal root and slot capture path.

That left the next useful slice as a full-quality comparison against the older
SurfaceTexture capture route. Downscale remains useful as a visual/perf
diagnostic and possible later quality knob if downstream GPU pressure proves
sensitive to root texture size.

A full-quality SurfaceTexture root-capture diagnostic then tested the older
Canvas-to-SurfaceTexture path without exposing OES textures to scene passes.
`ImlaScene2DiagSurfaceTextureRootCapture` draws the root `GraphicsLayer` into a
`Surface`, receives the frame through `SurfaceTexture`, copies OES into normal
`TEXTURE_2D` FBOs on the scene GL thread, and submits only the copied 2D texture
as the scene root. Slot content, masks, and clips stayed on the existing
`HardwareBuffer` capture/import path.

Evidence on `T81164GB23417442888`, benchmark build, ART speed-compiled
(`arm64: [status=speed] [reason=cmdline]`), full `1200x1920` root quality:

- current normal comparison
  `diagnostics/apa/traces/imla-smoke-2026-06-05-214031.perfetto-trace`:
  `GraphicsLayerCapture#capture[1200x1920]` count `117`, avg `71.512ms`;
  `GraphicsLayerCapture#awaitCallback` avg `71.300ms`;
  `Scene2RenderPipeline#render` avg `21.789ms`; frame avg `71.560ms`;
- SurfaceTexture with per-capture GL prepare
  `diagnostics/apa/traces/imla-smoke-2026-06-05-213557.perfetto-trace`:
  `SceneRootCapture#SurfaceTexture` avg `68.612ms`, but the real
  `SceneSurfaceTextureRootCapture#capture[1200x1920]` child started about
  `63ms` into that parent slice. The diagnostic was mostly waiting behind the
  scene GL queue before drawing;
- SurfaceTexture with prepared size cached
  `diagnostics/apa/traces/imla-smoke-2026-06-05-213905.perfetto-trace`:
  the outer capture no longer waited on per-frame prepare, but
  `SceneSurfaceTextureRootCapture#awaitCopy` rose to avg `39.449ms`,
  `Scene2RenderPipeline#render` rose to avg `53.295ms`, and frame avg worsened
  to `142.328ms`;
- SurfaceTexture with a three-texture copied-root ring
  `diagnostics/apa/traces/imla-smoke-2026-06-05-214255.perfetto-trace`:
  `SceneSurfaceTextureRootCapture#awaitCopy` stayed high at avg `43.686ms`,
  `SurfaceTextureRenderer#copyToFbo` itself was only avg `0.301ms`,
  `Scene2RenderPipeline#render` stayed high at avg `54.744ms`, and frame avg
  stayed poor at `143.748ms`.

The SurfaceTexture route is visually viable but not a speed win in the current
scene2 architecture. Screenshots and diff checks showed no obvious shift,
stretch, Y-flip, alpha/color, mask/clip, or rotation artifact, but the timing
shows that the expensive part moves into SurfaceTexture frame delivery,
GL-thread callback/copy synchronization, and extra scene GL pressure. The OES
copy draw itself is not expensive. Do not pursue this legacy path as the next
near-term optimization unless it is redesigned around a deeper producer/consumer
split than this scene2 diagnostic.

The updated next direction is to stay on the synchronized capture path and
optimize the amount or cadence of fresh root work, instead of replacing root
capture with SurfaceTexture.

## Visual Bounds Override

Scene2 slots now support a visual-bounds provider for cases where the measured
slot layout is larger than the visible material. The provider returns a
slot-local `Rect`; `null` keeps the measured layout bounds. The override is
threaded through slot declaration, content capture, content reuse keys, and
root-space geometry so the captured texture crop, material quad, clip/mask area,
and backdrop sampling bounds stay aligned.

The first diagnostic target is the Material bottom-sheet popup case, where the
sheet content layout can be full height while the visible material is shifted
down. `ImlaScene2ProfileMaterialBottomSheetVisualBounds` uses a standard
Material `ModalBottomSheet` with a tap-toggle between collapsed and expanded
visual positions. Because the sheet lives in a popup/window hierarchy, scene2
registry geometry now falls back from same-hierarchy `transformFrom` to
screen/window affine mapping when needed.

Device verification on `T81164GB23417442888`, benchmark build:

- `diagnostics/apa/scene2-bottom-sheet-visual-bounds-collapsed-half.png`
  showed the collapsed sheet blur bounded to the visible rounded material, with
  the checkerboard above the sheet remaining sharp;
- `diagnostics/apa/scene2-bottom-sheet-visual-bounds-expanded-half.png`
  showed the same bounded blur after the tap-switched expanded position;
- `diagnostics/apa/scene2-bottom-sheet-visual-bounds-diff/contact-sheet.png`
  confirmed the changed region follows the moved sheet instead of a full-height
  layout rectangle;
- the final log slice had no `FATAL EXCEPTION`, `ANR`, registry transform, scene
  source, or capture errors.

This closes the known bottom-sheet/dialog visual-bounds gap for the current
scratch renderer path. The public API now uses effect groups and effect layers;
remaining production API polish should focus on documentation and deliberate
diagnostics boundaries before treating it as final.
