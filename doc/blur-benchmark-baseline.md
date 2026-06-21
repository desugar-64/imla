# Blur Cost Baseline

Reproducible baseline for scene2 backdrop-blur GPU cost, before any blur optimization.

## Question

Separate the two blur cost drivers and their interaction, so we know which lever to pull:

- **Kernel radius** — driven by `sigmaPx`, but capped at 8 px in *downsampled* space
  (`SCENE_BACKDROP_BLUR_MAX_RADIUS_PX`) and gated by sigma-adaptive downsample tiers
  (σ≤6 → 1.0, σ≤32 → 0.5, else 0.25). So per-pixel blur math is already near-constant for wide blur.
- **Punch area** — slot size = the pixel count the pipeline touches (prepare + 2 blur passes +
  composite). Scales linearly with area.
- **Filter stack** — plain blur vs +tint+noise+clip vs progressive mask (a separate, heavier
  masked shader path).

Prior Pixel-6 finding (`memory: imla-blur-cost-bottleneck`): cost is dominated by per-pass
render-pass/FBO-switch tile flushes (4 passes/slot), and per-slot cost scales linearly with N.
This baseline tests whether sigma (radius/tier) or slot area moves frame cost more on a
mid-range device, and quantifies the filter-stack deltas.

## Harness

- Scene: `app/.../ui/BlurBenchmarkScene.kt` — high-contrast scrolling list (effectGroup root) +
  one centered backdrop-blur slot, parametrized by launch-Intent extras. Scrolling moves fresh
  content behind the slot each frame, so the full backdrop pipeline re-runs at full cost.
- Benchmark: `benchmark/.../BlurMatrixBenchmark.kt` — 8 scenarios; continuous-motion swipe.
- Run: `./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.serhiiyaremych.benchmark.BlurMatrixBenchmark`

## Conditions

- Device: MI 9 (cepheus, Adreno 640), API 29+. **Battery ≥ 25%** (macrobench gates LOW-BATTERY).
- Build: `:app` benchmark variant, `CompilationMode.Full()` (AOT). HW-present path (default).
- Metrics overlay OFF for the benchmark scene (kept its per-frame recomposition out of the numbers).
- 3 iterations/scenario, WARM start, fresh process per scenario.

## Caveat on per-pass numbers

`FrameTimingMetric` (`frameDurationCpuMs`) is **ground truth** — on a GPU-bound scene, frame
duration is the GPU cost. The per-pass `TraceSectionMetric`s (`*#…Ms`) are **GL-thread CPU wall
time**, i.e. submission + GPU-bound CPU stalls, **not** GPU timer-query execution. Use them for
attribution (where within the frame), not as absolute GPU ms. True per-pass GPU isolation needs
`GL_EXT_disjoint_timer_query` (separate follow-up).

## Matrix

Grid = plain blur, σ ∈ {4, 24, 96} (one per downsample tier) × size ∈ {Small 110×68, Large 520×360}.
Filter axis at the anchor (σ=24, Large): +full-stack (tint+noise+clip), +progressive-mask.

## Results — MI 9, 2026-06-16 (run 3, 6/8 scenarios)

IMPORTANT metric note: this app's scene renders on a separate SurfaceControl, so
`FrameTimingMetric` emits **only `frameCount`** (no `frameDurationCpuMs`). The cost proxy is
`SceneRenderPipeline#renderAverageMs` (GL-thread render wall time) plus present cadence
(`glPresentInterval*`). All `*Ms` are GL-thread CPU wall time, not GPU timer-query.

Device is 60 Hz (present P50 ≈ 16.7 ms = vsync-locked). Median of 3 iters.

`s4_large` and `s96_small` flaked on UIAutomator `INJECT_EVENTS` (swipe raced window focus),
not a renderer bug — re-run pending.

| Scenario | σ | size | renderAvg | renderMax | prepare | blurAvg | blurMax | composite | present | pP50 | OnBeat% | MAD |
|---|--:|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| s4_small        | 4  | 110×68  | 3.08 | 12.62 | 0.29 | 0.49 | 1.75 | 0.26 | 3.11 | 16.68 | 96.7 | 1.22 |
| s4_large        | 4  | 520×360 | — flaked — | | | | | | | | | |
| s24_small       | 24 | 110×68  | 3.10 | 10.84 | 0.29 | 0.49 | 1.50 | 0.27 | 3.14 | 16.71 | 97.0 | 1.20 |
| s24_large (anchor) | 24 | 520×360 | 3.34 | 15.01 | 0.29 | 0.46 | 1.45 | 0.26 | 3.38 | 16.69 | 96.1 | 1.22 |
| s96_small       | 96 | 110×68  | — flaked — | | | | | | | | | |
| s96_large       | 96 | 520×360 | 4.52 | 26.70 | 0.27 | 0.46 | 1.51 | 0.25 | 4.55 | 16.78 | 97.3 | 1.66 |
| s24_large +full-stack | 24 | 520×360 | 3.37 | 19.23 | 0.29 | 0.49 | 2.39 | 0.25 | 3.40 | 16.84 | 91.2 | 1.02 |
| s24_large +progressive | 24 | 520×360 | 3.80 | 13.57 | 0.30 | 0.55 | 2.45 | 0.27 | 3.84 | 16.96 | **73.6** | **1.97** |

### Read

- **Blur per-pass cost is flat across sigma AND area.** prepare ~0.3 / blur ~0.5 / composite
  ~0.25 ms hold from σ=4→96 and small→large. The radius cap (8 px downsampled) + adaptive
  downsample tiers already neutralize both "kernel radius" and "punch area" as single-slot cost
  drivers. "Downsample harder" has almost no fat to cut on a single slot.
- **renderAvg moves only mildly** (3.1 → 4.5 ms) and present stays vsync-locked at 60 fps with
  healthy OnBeat (≥96%) for plain/large — single-slot blur is NOT the bottleneck on this device.
- **Progressive mask is the cadence outlier**: OnBeat 73.6% (vs ~96%), MAD 1.97 — the masked
  shader path destabilizes cadence even though its average passes are cheap. Candidate for the
  user-reported scroll flicker.
- **full-stack** (clip/stencil) dips OnBeat to 91% — milder.

### Decision / next

- The 30 fps "heavy scene" is the **N-slot** case (top bar + nav + FAB + cards), NOT single-slot.
  This matrix proves single-slot blur is cheap; the real target needs an **N-slot scenario**
  (e.g. 4–8 simultaneous blur slots) to reproduce the budget break. Add it before choosing a
  pass-count / atlas / fusion optimization.
- Investigate the progressive-mask cadence collapse + the reported scroll flicker (separate from
  steady-state cost).
- Re-run `s4_large` + `s96_small` (event-injection flakes).

## N-slot results — MI 9, 2026-06-16 (the heavy-scene baseline)

The N-slot scenario the single-slot matrix called for. `BlurBenchmarkScene` now lays out N
simultaneous backdrop-blur slots (`FlowRow`, even tiling) over the same scrolling root, driven by
the `imla.blurbench.slotCount` extra. All slots σ=24, MEDIUM 170×110 dp, plain blur. Scenarios
`blur_s24_n{2,4,6,8}_medium`. 3 iters, WARM, `CompilationMode.Full()`, HW-present path. Medians.

Cost proxies (same as single-slot): `SceneRenderPipeline#renderAverageMs` is GL-thread render
wall time; `glPresentIntervalP50Ms` is the present cadence = effective frame time on this
GPU-bound scene. Per-pass `*AverageMs` are GL-thread CPU wall time, not GPU timer-query.

| N | renderAvg | renderMax | prepare | blurAvg | composite | presentAvg | pP50 | pOnBeat% | frames | **fps** |
|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 2 | 6.77  | 18.32 | 0.22 | 0.40 | 0.24 | 6.99  | 16.76 | 77.2 | 599 | **59.7** |
| 4 | 9.72  | 27.49 | 0.26 | 0.51 | 0.30 | 9.76  | 20.05 | 51.8 | 536 | **49.9** |
| 6 | 12.31 | 36.62 | 0.24 | 0.47 | 0.28 | 12.34 | 25.64 | 59.1 | 490 | **39.0** |
| 8 | 12.47 | 40.92 | 0.24 | 0.46 | 0.31 | 12.50 | 31.65 | 73.4 | 469 | **31.6** |

### Read

- **The budget break reproduces.** Present P50 climbs monotonically 16.8 → 20.1 → 25.6 →
  31.6 ms as N goes 2 → 8; N=8 medium slots = **~32 fps**, matching the user-reported heavy-scene
  ~30 fps. Single-slot never left 60 fps. The N-slot scene is the right target.
- **Per-pass cost is flat across N** (prepare ~0.24, blur ~0.46, composite ~0.29 ms) — exactly as
  on the sigma/area axes. Cost is the *count* of passes / FBO switches × N, not kernel math.
  Confirms `memory: imla-blur-cost-bottleneck` on the multi-slot axis.
- **Frame time scales ~linearly with N, GPU-bound.** renderAvg (GL-thread submit) tracks N from
  2→6 (6.8 → 9.7 → 12.3) then plateaus ~12.5 at N≥6, while present P50 keeps rising to 31.6 —
  the GPU is doing ~2.5× the CPU submit time at N=8, i.e. the cost lives on the GPU, not in
  submission. Fewer frames land per swipe (599 → 469) as N grows.

### Decision / next

- Optimization target is now concrete: cut the **per-slot pass count / FBO-switch** overhead that
  scales N× (atlas-batch the N slots into shared passes, or fuse prepare→blur→composite to drop
  FBO switches). N=8 medium is the regression gate; hold present P50 ≤ 16.7 ms (60 fps).
- Hold this table as the pre-optimization baseline. Re-run `blur_s24_n{2,4,6,8}_medium` after any
  pass-count / atlas / fusion change and compare present P50 + renderAvg.
