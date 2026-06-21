# Scene2 Blur Radius Scope — extend reach to ~72dp without crawling

Branch line: `syaremych/imla-2.0`. Status doc for extending scratch backdrop
blur from its current ~11.6dp ceiling to ~72dp, via scale-aware deep downsample.

## Problem

Scratch backdrop blur max reach = `radius_cap(8) / scale_floor(0.25)` = **32px =
11.6dp** on 440dpi (2.75×). Hard ceiling: kernel radius caps at 8 downsampled px
(`filterRadiusPx`, `SceneBackdropEffectPasses.kt`) and `downsampleScale` floors at
0.25. That is why the baseline shows σ=96 ≈ σ=24 — both clamp.

The 0.25 floor is a **quality wall**, not arbitrary: the prepare pass is a fixed
8-tap prefilter (`scene_backdrop_prepare.frag`) tuned for ≤4× minification. It is
the anti-crawl low-pass; its cutoff is fixed for ~4×, so deeper downsampling
without widening it brings back pixel crawling (temporal aliasing during scroll).

## Approach — hold downsampled sigma constant (Impeller's CalculateScale)

Pick scale so the kernel always runs at a constant sigma in downsampled space
(`T ≈ 4`). The kernel stays ~fixed-size in texels → fixed tap count regardless of
blur strength; bigger blur means more downsampling, not more taps.

```
scale     = pow2_round(T / sigmaPx)   clamped to [1/16, 1]   // T ≈ 4
sigma_ds  = sigmaPx * scale           // lands near T (~2.8–5.6 after pow2 round)
radius_ds = min(ceil(3 * sigma_ds), RADIUS_CAP = 12)
reach     = radius_ds / scale
```

`radius_ds = 3·sigma_ds` also fixes a latent bug: today `radius_ds ≈ 1·sigma_ds`
truncates the Gaussian at 1σ (~68% of weight) → boxy. Cap 12 = `3·T`.

The prepare prefilter footprint scales with `D = 1/scale` (the scale-aware part)
so its low-pass cutoff always matches the tier.

### Scale table (density 2.75)

| σ (px) | scale | sigma_ds | radius_ds | reach | dp |
|--:|--:|--:|--:|--:|--:|
| 4  | 1.0  | 4 | 12 | 12px | 4.4 |
| 8  | 1/2  | 4 | 12 | 24px | 8.7 |
| 16 | 1/4  | 4 | 12 | 48px | 17 |
| 32 | 1/8  | 4 | 12 | 96px | 35 |
| **64** | **1/16** | **4** | **12** | **192px** | **70** |
| 128 | 1/16 (floor) | 8 | 12 | 192px | 70 (boxy) |

σ≈64 is the 72dp target. Above σ=64 it floors at 1/16 and just gets boxier at the
same reach — the natural ceiling of this method (1/32 aliases badly).

## Decisions

- **A — σ is a TRUE Gaussian sigma** (approved 2026-06-16). Existing screens blur
  wider (σ=24: 16px→72px reach); callers re-tune their σ. No compatibility mapping.
- Radius is **never** scaled down for small slots — for a backdrop blur the radius
  is the frost strength and must look the same on a chip and a card. Small slots
  only get a degenerate-region guard (ease scale *up*, never cut radius).

## Slices

- **0 — scope doc + before-capture.** This doc; capture σ=64 single-slot on MI 9
  showing the current 32px-capped (under-blurred) baseline.
- **1 — scale formula + true 3σ kernel at current depth (floor 1/4).** Replace the
  3-threshold `downsampleScale` with the formula clamped [1/4, 1]; `radius_ds =
  3·sigma_ds`; raise `MAX_RADIUS_PX` 8→12. Max reach 32→48px. No new aliasing
  (still ≤1/4). Foundation that isolates formula+kernel from downsample depth.
- **2 — scale-aware prefilter + deep tiers (1/8, 1/16).** ← critical. Floor to
  1/16; prefilter footprint ∝ D. σ=64 reaches ~70dp. Decision point: if 1/16 is
  crawl-free and affordable, done; else Slice 3.
- **3 — (conditional) mip-assist hybrid for 1/16.** Sample a mip LOD within 2× of
  target + light prefilter for the residual; bounded taps. Only if Slice 2's 1/16
  crawls or costs too much. **Deferred — Slice 2's scale-aware prefilter is clean
  and affordable at 1/16; revisit only if live-motion / high-frequency content
  shows crawl.**
- **4 — stability guards.** Animated-σ pow2-boundary popping; degenerate
  small-region scale-up guard.
- **5 — dp-radius public API.** Expose radius in dp at the modifier boundary
  (dp→σ). **Done** — see Slice 5 result below.

## Verification (every slice)

- `./gradlew -q :app:compileDebugKotlin` after each edit.
- **Crawling is temporal** → verify with a scroll: two frames mid-scroll,
  `tools/screenshot-diff` the blur region (`--pixel-threshold 8 --smooth-radius
  1.0`); flicker shows as large inter-frame diff where the blur should be smooth.
- Static screenshot for blur quality (banding / Y-flip / shift — the `highp`
  contract). Small σ (≤4, scale 1.0) stays a real parity check (unchanged).
- `am force-stop dev.serhiiyaremych.imla` after diagnostics. MI 9 stays connected.

## Baseline comparison (regression gate)

Reference: `doc/blur-benchmark-baseline.md` N-slot table — present P50 N2 16.8 /
N4 20.0 / N6 25.6 / **N8 31.6 ms**.

Because σ=24 now blurs ~4.5× wider (Decision A), the recorded `blur_s24_n*` runs
are **not pixel-parity** post-change — but they remain the **cost** gate:

1. Re-run recorded N-slot scenarios after each cost-affecting slice (1–3); compare
   present P50 / renderAvg to the doc table. **Gate: N=8 present P50 ≤ 31.6 ms.**
2. Matched-reach check: a scenario at the σ that reproduces the old 16px reach, to
   isolate per-slot pipeline cost change from the remap.
3. New-capability cost: σ=64 (72dp) single-slot + N-slot, recorded as new rows.

## Results

### Slice 1 — MI 9, 2026-06-16 (scale formula + true 3σ kernel, floor 1/4)
Cost gate vs recorded N-slot baseline (`doc/blur-benchmark-baseline.md`), present P50:

| N | base P50 | Slice 1 P50 | Δ |
|--:|--:|--:|--:|
| 2 | 16.76 | 16.89 | +0.13 |
| 4 | 20.05 | 17.06 | −2.99 |
| 6 | 25.64 | 22.00 | −3.64 |
| 8 | 31.65 | **26.17** | **−5.48** |

Passes (N=8 ≤ 31.6). σ=24 now blurs 2× wider (32px reach, true Gaussian) yet is
**cheaper** — the deeper 1/4 downsample shrinks the intermediate to 1/4 the pixels,
more than offsetting the larger padding. Visual: σ=24 smooth, no crawling (≤1/4).

### Slice 2 — MI 9, 2026-06-16 (floor 1/16 + scale-aware prefilter, radius cap 12)
σ=24 N-slot cost (now 1/8 downsample) held vs Slice 1; new σ=64 (72dp, 1/16) row:

| N | base P50 | Slice 2 σ=24 | σ=64 (72dp) |
|--:|--:|--:|--:|
| 4 | 20.05 | 17.64 | 17.09 |
| 8 | 31.65 | 26.37 | **25.56** |

- **72dp reach works** (`σ=64`): smooth proper Gaussian, dissolves high-contrast
  bands the 32px cap left visible. No crawling/banding on the test content — the
  prefilter widened ×4 at 1/16 band-limits well enough that the **mip-assist
  (Slice 3) is not needed** for this content. (Stills only; live-motion /
  high-frequency confirmation still advisable.)
- **Cost is independent of radius**: σ=64 (72dp) ≈ σ=24, both ~25–26 ms at N=8 —
  *better* than the original 31.6 ms baseline that was only a 16px blur. The
  constant-downsampled-sigma design keeps the intermediate tiny (1/16 → 1/256 px).

### Slice 4 — MI 9, 2026-06-17 (half-octave quantization + animated-σ harness)
Animating σ across the full-octave boundaries (σ≈23, ≈45) showed a slight,
hunt-to-find pop (tier resolution halving). Snapping scale to √2 (half-octave)
steps halves each per-flip jump; device re-eyeball confirmed it dropped below
noticeable. Cost trade is negligible — σ=24 now downsamples ~1/5.7 (vs 1/8):

| N | base P50 | Slice 4 P50 | Δ vs base |
|--:|--:|--:|--:|
| 8 | 31.65 | 27.22 | −4.43 |

`BlurBenchmarkScene` gained an `animateSigma` mode (sweeps σ 2↔max with a live
readout) as the eyeball + regression harness. Mip-assist (Slice 3) still not
needed. Remaining: small-region degenerate guard (defer until it shows).

### Slice 5 — 2026-06-17 (dp-radius public API)
Added a `backdropBlur(radius: Dp, progressiveMask)` overload to `EffectLayerScope`
alongside the existing `backdropBlur(sigmaPx)`. `radius` is the Gaussian sigma in
dp; it is resolved to px via `LocalDensity` inside `SceneSlotNode`
(`resolveBlurSigmaPx`), so the render path past `SceneBackdropEffect.blur` is
unchanged. The two overloads are mutually exclusive (`checkNoBackdropBlur`). No
pixel change for existing `sigmaPx` call sites; demo/benchmark scenes keep
`sigmaPx`. Unit-tested in `EffectLayerScopeTest`. No cost gate needed — pure
front-door unit conversion, no shader/geometry change.

## Per-slice finish (Goal Protocol)

Each slice: verify requirements → commit → load `code-simplifier` on the slice →
rerun focused verification if code changed → amend / small follow-up commit.
