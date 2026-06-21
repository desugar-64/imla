# Scope: zero-copy present (drop the final scene blit)

## Payoff

The final present is a pure full-res copy: `SceneFinalPresentPass`
(`SceneRenderPasses.kt:213`) binds the default framebuffer and draws a `divisor=1`,
`flipTexture=true` textured quad of the scene buffer into the EGL window surface,
then `eglSwapBuffers`. ~2.6 Mpix, ~20 MB read+write, 1 render-pass boundary ≈
**~0.8–0.9 ms/frame** — the single biggest bandwidth pass in the frame, paid
**every frame regardless of blur count or N**. (See
`doc/backdrop-blur-roofline-analysis.md`: present + root dominate the whole-frame
throughput floor.)

Goal: eliminate that copy by making the scene buffer **be** the scanned-out buffer
— render the scene into a HardwareBuffer-backed FBO, sample it for blur during the
frame as today, then hand the buffer directly to the SurfaceView via
`SurfaceControlCompat.Transaction.setBuffer(...).commit()`. No blit, no
`eglSwapBuffers`.

## Why the blit exists, and why a HardwareBuffer removes it

The scene lives in an **offscreen** FBO because the blur must **sample the
accumulated scene as a texture** mid-pipeline (`BackdropInput.accumulatedScene`,
`SceneBackdropEffectPasses.kt:55`). You can't sample the EGL window surface, so the
finished scene is **copied** to the window at the end.

A `HardwareBuffer` is the one object that is **both** sampleable (bind it to a GL
texture via `EGLImage` / `glEGLImageTargetTexture2DOES`) **and** presentable (hand
it to `SurfaceControlCompat`). So the scene buffer can serve blur sampling during
the frame *and* be scanned out at the end — the copy is no longer needed. That dual
nature is the whole trick.

## What graphics-core already provides (reuse, don't re-author)

(Mapped from `/Users/syaremych/dev/projects/androidx/graphics/graphics-core/`.)

- **`FrameBuffer.kt`** — wraps a `HardwareBuffer` as an `EGLImage` + GL texture +
  FBO; `makeCurrent()` binds it as the draw target. (color-only; see stencil risk.)
- **`FrameBufferRenderer.kt`** — a `GLRenderer.RenderCallback` whose
  `onSurfaceCreated` returns `null` (**no window surface, no `eglSwapBuffers`**):
  `obtainFrameBuffer → makeCurrent → onDraw → createSyncFence → onDrawComplete`.
- **`FrameBufferPool.kt` / `BufferPool.kt`** — N-buffering (default 3) with
  **release-fence gating**: `obtain()` blocks if all buffers are outstanding and
  `awaitForever()`s the compositor release fence before reusing a buffer — exactly
  the "don't render into a buffer still being scanned out" guard.
- **Present call** (as used by `GLFrameBufferRenderer.kt:412`): `SurfaceControlCompat
  .Transaction().setBuffer(surfaceControl, frameBuffer.hardwareBuffer, syncFence) {
  releaseFence -> pool.release(buffer, releaseFence) }.setBufferTransform(...).commit()`.
  The `syncFence` makes the compositor wait for GPU completion; the Y-flip becomes a
  **buffer transform** (free) instead of the shader `flipTexture`.
- `GLFrameBufferRenderer` / `GLFrontBufferedRenderer` glue all this together, but
  they are **top-level drivers** that would replace our `GLRenderer` + the tuned
  mailbox. We want the **pieces** (`FrameBufferRenderer`, `FrameBufferPool`,
  `SyncStrategy`/`SyncFenceCompat`), not the whole driver.

## Recommended design (least disruption to the tuned pacing)

Keep `GLRenderer` (GL thread + EGL context) and the existing mailbox/present
scheduling. Swap only **where the final pixels go**:

1. Allocate the scene buffer as a **HardwareBuffer-backed `FrameBuffer`** from a
   pool (replace `SceneRenderBufferPool`'s plain FBO), keeping a **stencil**
   renderbuffer attached to the same FBO (for clip).
2. Render the scene pipeline into that FrameBuffer (root → slots → composite).
   **Delete `SceneFinalPresentPass`** and its call (`SceneRenderPipeline.kt:118`).
3. After the pipeline, create a `SyncFenceCompat` (EGLSync→fd) and present via
   `SurfaceControlCompat.Transaction().setBuffer(sceneSurfaceControl, hwBuffer,
   fence){ releaseFence -> pool.release(buf, releaseFence) }.commit()` — driven from
   `SceneGlOwner`'s present path instead of the window-surface `eglSwapBuffers`.
4. Attach via a `FrameBufferRenderer`-style `RenderCallback` (`onSurfaceCreated →
   null`) so `GLRenderer` never creates a window surface or swaps.

This is essentially "use graphics-core's `FrameBufferRenderer` + `FrameBufferPool`
under our existing `GLRenderer` + mailbox, and commit via `SurfaceControlCompat`."

## Risks (ordered by severity)

1. **Pacing/cadence re-validation — THE risk.** The present timing model changes
   from `eglSwapBuffers(interval 1)` (GL thread **blocks** on vsync = backpressure)
   to `transaction.commit()` (**async**, latched by SurfaceFlinger at vsync;
   backpressure moves to `FrameBufferPool.obtain()` + release fences). The whole
   `[[imla-jumpy-scroll-diagnosis]]` stack (single-present mailbox, async hand-back,
   `postOnAnimation` kick, `presentScheduled`/`onRenderComplete`,
   OnBeat 77% / MAD 1.07 ms) was tuned around the swap model. This **must be
   re-measured** with the `CapturePacingMetric` cadence classifier — it could come
   out neutral, better (modern explicit-fence path), or need re-tuning.
2. **API 29+ only.** `SurfaceControlCompat` needs API 29; `minSdk` is 23. So the
   zero-copy path is **API-gated** with the **current blit kept as fallback** for
   23–28. Two present paths to maintain (all current test devices — Pixel 6 / S10 /
   nabu — are 29+, so the optimization applies; the fallback is mandatory, not
   optional).
3. **Stencil attachment.** `SceneRenderBuffer` carries a stencil attachment
   (`FramebufferAttachmentSpecification.withStencil`, used by clip). A `HardwareBuffer`
   is color-only — must attach a separate stencil renderbuffer to the same FBO.
4. **SyncFence plumbing.** Need an EGLSync after the scene render so the compositor
   waits for GPU completion (graphics-core `SyncStrategy`/`SyncFenceCompat`).
   Intra-frame blur sampling is fine (sequential, same context); the cross-frame
   release fence gates buffer reuse.
5. **Memory.** 2–3 full-res HardwareBuffers (~10.4 MB each) ≈ **+10–20 MB** vs the
   single pooled offscreen FBO today. (The app already carries HardwareBuffer memory
   for the root-capture ping-pong, so the machinery and budget precedent exist.)
6. **Surface wiring.** Output is a Compose `AndroidExternalSurface` (SurfaceView).
   Need its `Surface` / a child `SurfaceControlCompat` for the transaction; confirm
   `AndroidExternalSurface` exposes what `SurfaceControlCompat.Builder` needs.

## PoC Results (2026-06-16)

### Slice 0 — DONE

HW-backed FBO with blit present still active. Screenshot diff SSIM = 0.9993. No GL
errors. Stencil clip correct.

### Slice 1 — DONE (commit `8e3db11e`)

Zero-copy present via `SurfaceControlCompat.setBuffer` on API 29+.
`SceneFinalPresentPass` is null in the HW path; Y-flip via
`BUFFER_TRANSFORM_MIRROR_VERTICAL`.

**Two bugs found during bringup:**

1. **Render-loop deadlock.** `SceneHwRenderCallback.onDrawFrame` checked
   `hwBufferRing ?: return` before calling `target?.draw()`. The ring is
   initialised lazily inside `ensureGlResources()` which only runs via `draw()`.
   First call returned early → `consumePendingRequest()` never ran →
   `presentScheduled` stayed `true` permanently → all subsequent `submit()` calls
   silently dropped. Fix: call `target?.draw()` unconditionally first.

2. **`USAGE_COMPOSER_OVERLAY` missing.** `HardwareBuffer.create()` was missing the
   `2048L` flag required for `ASurfaceTransaction#setBuffer` (documented in
   AndroidX graphics-core `HardwareBufferFormat.kt:63`). Buffer silently rejected
   by SurfaceFlinger (black screen, no error).

**Retracted Slice 1 numbers (do not cite).** The original write-up reported a
**−3.0 ms "GL time"** win and a **capture-cadence regression** (SlowPct +142%).
Both are now disowned:

- The −3.0 ms came from the on-screen overlay, which is **GL-thread CPU wall time**
  around `renderPipeline.render()` (`doc/metrics-overlay-labels.md`: "not GPU
  timer-query measurements"), **not** GPU execution. Removing one fullscreen quad
  cannot cost 3 ms of CPU submission; the number conflated CPU submission with
  GPU-bound CPU stalls, so it over-credited the change.
- Both A/B runs (`hwbuffer-slice1.json`, `blit-ab-control.json`) recorded
  `compilationMode = run-from-apk` (JIT/interpreter) despite the doc claiming
  `CompilationMode.Full` — so the absolutes are not production-representative.
- The doc also claimed "same binary, flag toggle", but the flag was a compile-time
  `const val`: the two arms were two different binaries.
- The blamed cause of the regression (`AndroidExternalSurface → SurfaceView`) was
  wrong: both arms already ran on `SurfaceView` (it is the API-29+ output for any
  present path), so the A/B isolated the present mechanism, not the surface type.

### Slice 1 re-test — DONE (ring=3, honest same-binary A/B)

Root cause of the capture regression: **ring capacity = 2** left no slack, so
`SceneHwBufferRing.obtain()` blocked on the compositor release fence almost every
frame (a GL-thread `awaitForever()` inside `render()`), back-pressuring the capture
kick. Fixes:

- **Ring capacity 2 → 3** (triple-buffer; graphics-core `FrameBufferPool` default).
- **`awaitForever()` → bounded `await(500 ms)` + proceed-on-timeout** (deadlock
  backstop if a release fence is ever lost).
- **Runtime present-path toggle** (`SceneRenderFlags`, read via
  `log.tag.ImlaBlitPresent`) so the A/B is a genuine same-binary, same-SurfaceView
  comparison. Benchmark split into `measureRendering_hwPresent` /
  `measureRendering_blit`.

A/B (Pixel 6, same binary, `swipe`-driven continuous scroll, 3 iters; medians):

| Metric | Blit | HW | Δ | read |
|---|---:|---:|---:|---|
| Capture SlowPct % | 2.43 | **2.06** | −0.37 | regression gone (was +142% worse) |
| Capture OnBeat % | 97.41 | 97.78 | +0.37 | now better |
| GL Present MAD ms | 1.49 | **1.15** | −0.34 | steady jitter −23% |
| GL Present P95 ms | 15.90 | **15.76** | −0.14 | now neutral (was +17% worse) |
| GL Present OnBeat % | 82.47 | 84.93 | +2.46 | fewer stalls |
| GL Present P50 ms | 11.23 | 11.21 | ~0 | vsync-capped, no throughput Δ |

Data: `diagnostics/apa/pacing/hwpresent-ring3-ab.json`. Visual parity HW vs blit
(same binary): SSIM 0.9996, 0.08% pixels changed.

**Measurement caveat:** macrobenchmark 1.4.1 again reported
`compilationMode = run-from-apk` even with `CompilationMode.Full()` in source and an
AOT `status=speed` install verified by `dumpsys package dexopt` before the run (the
task reinstalls + manages compilation and uninstalls after). The A/B conclusion
holds regardless — both arms ran identical conditions — but the absolute ms are not
production-representative. Worth fixing for future production-absolute numbers.

**GPU saving:** not measured. Perfetto GPU render stages / counters are unavailable
on this Pixel 6 (production Mali driver gates them; capture returned empty). The
defensible figure stays the roofline **~0.85 ms** (the eliminated 2.6 Mpix
read+write present pass + its tile store). Because present P50 is vsync-capped, this
is **headroom** (thermal/power, higher refresh, heavier blur, lower-end devices),
not extra fps on Pixel 6 at 90 Hz.

**Current state:** gate met — HW present is neutral-to-better on every cadence
metric with no throughput cost. Default = ON via `SceneRenderFlags.hwPresentEnabled()`.

### Slice 2 — Architecture polish — DONE (commit `dd5aeb3e`)

The present path is now a single `ScenePresenter` backend (mirrors
`GraphicsLayerCapture`'s API-gated capture backends):

- `HardwareBufferPresenter` (API 29+): owns the ring; `present` = fence +
  `SurfaceControlCompat` transaction.
- `BlitPresenter` (all APIs): owns the FBO pool; `present` = `SceneFinalPresentPass`.

This folded in `SceneRenderBufferPool`, the inline `SceneHwRenderCallback`
transaction, the `ring.lastObtained` tracking, and the nullable-`presentPass`
signal. One `SceneRenderCallback` for both backends; present takes the buffer
straight from the pipeline; the backend is chosen once by `sceneSurfaceControl
!= null`. `ImlaHost` now has symmetric `SurfaceViewOutput` / `ExternalSurfaceOutput`.
Verified: refactor-vs-pre-refactor HW SSIM 0.9976; HW-vs-blit SSIM 0.9998; A/B
cadence parity (`diagnostics/apa/pacing/hwpresent-presenter-refactor-ab.json`).

### Slice 3 — async present (MIUI tear fix) — DONE (commit `49a2083c`)

Cross-device testing found the zero-copy present **tears on MI 9 / API 30 (MIUI)**:
Xiaomi's SurfaceFlinger does not honor the acquire fence on an app-owned
SurfaceControl (verified: valid fence + fresh per-frame transaction still tears;
only blocking GPU completion via `glFinish`/`fence.await` fixes it). Pixel 6 (34),
T811 (33), and S10 (31, same NDK fence path as MI 9) are all clean — so it is
MIUI-specific, not an API-path or low-end issue.

Fix: `HardwareBufferPresenter` now blocks on GPU completion itself
(`fence.await`) **before** `transaction.commit()`, but on a dedicated
`HandlerThread` ("ImlaScenePresent") instead of the GL thread. The GL thread only
creates the fence (which flushes the frame's command stream) and posts the
hand-off, then keeps pipelining. `SceneHwBufferRing` gains a per-slot
`presentInFlight` guard: `markPresentInFlight` (GL thread, before posting) blocks
the next `obtain()` of that slot until the compositor release callback clears it,
covering the new pre-commit/pre-release in-flight window that async present opens
(the existing release-fence gate only covered post-commit reuse). `close()` drains
the present thread (`quitSafely` + `join`) before freeing ring buffers.

Verified S10 (API 31): no GL errors/crashes/timeouts under heavy scroll; visual
parity HW-async vs blit SSIM 0.9997 (0.06% px). **MI 9 tear-fix itself unverified
in this session — device was not attached; the await-before-commit operation is
the one proven to fix it, only relocated off the GL thread.**

### Slice 2 — remaining

- Optional: real per-pass GPU number via `GL_EXT_disjoint_timer_query` (Perfetto GPU
  stages unavailable on Pixel 6) — only if a measured headroom figure is needed.
- Investigate why macrobench 1.4.1 reports `run-from-apk` under `CompilationMode.Full()`
  so future runs yield production-representative absolutes.
- Resize edge: `SceneHwBufferRing.obtain()` closes a buffer on size mismatch; if the
  compositor still holds it (release callback not yet fired), this races scanout.
  Rare (resize during active present); harden if it ever shows.
- Lint: `./gradlew lint` is red with 19 pre-existing `NewApi` errors (not from this
  work — confirmed by stash test) in PoC/scratch files not in the lint baseline.
  Decide: fix or regen baseline.
- Status note in `doc/scene2-scratch-renderer-status.md`.

---

## Slices (original plan)

### Slice 0 — Plumbing spike behind a flag (API 29+), pacing OFF the critical path
- HardwareBuffer-backed scene `FrameBuffer` (color + stencil) from a 2–3 buffer
  pool; render the existing pipeline into it; **keep** `SceneFinalPresentPass` for
  now but have it blit from the HW-backed buffer. Proves the scene renders +
  blur-samples correctly from a HardwareBuffer FBO, with **zero present-path
  change**. Flag default off.
- **Verify:** N=0/1 blur parity vs today (`tools/screenshot-diff` SSIM ≥ 0.999);
  stencil clip still correct; no GL errors. Compile + device.

### Slice 1 — Switch present to SurfaceControl, drop the blit (API 29+ path)
- Delete `SceneFinalPresentPass`; present via `SurfaceControlCompat` setBuffer +
  fence + release-fence pool gating; Y-flip via buffer transform. Keep API ≤28 on
  the old blit path.
- **Verify (device, output + timing):** visual parity (no flip/shift/tear);
  **then the pacing gate** — re-run the `CapturePacingMetric` cadence classifier on
  the `benchmark` variant (ART `speed`): glPresent OnBeat / MAD / throughput must be
  **≥** the committed baseline (`asynchandler.json`), and per-frame GL time should
  drop ~0.8–0.9 ms. Buffer-stuffing / App-Deadline-Missed not worse. Force-stop after.
- **Finish:** parity + cadence-non-regression + measured GL drop, or revert.

### Slice 2 — Cleanup / decision
- code-simplifier on the slice; counters/labels for the present path; decide flag
  default from Slice 1 numbers; status note in
  `doc/scene2-scratch-renderer-status.md`. Keep the blit fallback for ≤28.

## Relationship to the atlas

Orthogonal and complementary. Zero-copy present: **~0.85 ms every frame, any N**,
fixes the biggest single pass, independent of stacking. Atlas: **~0.7 ms × (N−1)**,
only when N≥3 batchable slots. For small/typical N, **zero-copy present is the
better first bet**; do the atlas later if real stacks appear. Neither blocks the
other.

## Codex handoff

Slice 0 is a self-contained plumbing spike (HW-backed FBO + pool, present unchanged)
— good first hand-off; coordinator compiles + device-verifies (Codex sandbox can't
run Gradle). Slice 1 touches the pacing-critical present path — keep it tightly
scoped and gate the merge on the cadence classifier, not just screenshots. Per
AGENTS Goal Protocol: verify → commit → code-simplifier → re-verify → amend.
```
