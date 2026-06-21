# Legacy / Dead-Code Removal Map

Branch: `syaremych/imla-2.0`. Generated from `android studio find-usages` per-symbol
classification (4 parallel passes over all 51 `internal/legacy/` files) plus authoritative
import/grep cross-checks.

## Methodology note (important)
`find-usages` produced **false-positive LIVE verdicts** because several legacy symbols share
names with scratch-path classes (`SceneNoisePass`, `SceneBackdropCompositePass`,
`BackdropCompositeInput`, `StencilClipPass`, `rootCoordinates`). The authoritative test for
"production uses a legacy symbol" is whether a non-legacy `main` file **imports it from
`dev.serhiiyaremych.imla.internal.legacy…`**. Only 5 main files do, importing 8 symbols.

## Pivotal finding
`internal/host/BlurSurfaceHost.kt` has **zero usages** anywhere in `main`/`app`. The live
`ImlaHost` wires the scratch `SceneRenderer`, never `BlurSurfaceHost`. Since the legacy
`ImlaSceneRenderer` graph was anchored only by `BlurSurfaceHost`, **the entire legacy scene
graph is dead.** Only small standalone utilities survive.

---

## A. `internal/legacy/` (51 files)

### KEEP — genuinely live (used by scratch renderer). Recommend relocating out of `legacy/`.
- **`RenderingPipelineMath.kt`** — only 4 functions are live; trim the rest:
  - keep: `buildRenderTransform` (+ private `localRectToTransform`), `composeMatrixToMat4`,
    `computeUvBoundsFlippedYFloat`, `computeUvBoundsNoFlipFloat`
  - drop (dead/test-only): `UvBounds`, `RegionAabb`, `GlYCoordinates`, `LocalPoint`,
    `computeAabbRegion`, `computeUvBoundsFlippedYInt`, `convertToGlYCoordinates`,
    `convertPointToLocalSpace`, `clampOpacity`, `offsetTransformForRegion`, `computeFacingRatio`
  - consumers: `PostBlendEffect.kt`, `SceneBackdropEffectPasses.kt`, `SceneSlotGeometry.kt`
- **`SurfaceTextureRenderer.kt`** — whole file live; consumer: `SceneSurfaceTextureCapture.kt`

### REMOVE — top-level legacy (14 files)
BlurAlgorithm, BlurRenderPlanning, ContentCaptureDelegate, GraphicsLayerCaptureDiagnostics,
GraphicsLayerRenderer, GraphicsLayerTexture, ImlaSceneRenderer, ImlaSceneSession,
LayoutCoordinatesExtensions, MaskTextureRenderer, RenderableRootLayer, StencilClipRenderer,
Style, TextureRegion

### REMOVE — `internal/legacy/scene/` (all 35 files)
Entire subgraph dead (BackdropCompositeEffect, BlurSlot*, GraphicsLayerSlotAccess,
ImlaSceneCoordinator, ModifierLocalImlaSceneCoordinator, SceneBackdropPasses,
SceneBlurAtlas* ×12, SceneCapturePolicy, SceneContentCompositePass, **SceneCoordinatorLocals**,
SceneGlRenderer, SceneLayerRepository, SceneMaskRepository, **SceneNoisePass**, ScenePresentPass,
SceneRenderGate, SceneRenderPlan, SceneRenderScheduler, SceneResourceStore, SceneRootSeedPass,
SceneSlotPassRunner, SceneStencilClipPass, SceneTraceCounters, SceneTypes, SlotContentCaptureAccess).
`SceneCoordinatorLocals`/`SceneNoisePass`/`BackdropCompositeEffect` were the find-usages false
positives — confirmed not imported by any live main file.

### REMOVE — dead production seam
- **`internal/host/BlurSurfaceHost.kt`** (zero usages; the only thing that imported legacy
  `ImlaSceneRenderer` / `LocalImlaSceneCoordinator` / `LocalImlaSceneRenderer`)

---

## B. Diagnostics
### REMOVE
- `internal/render/debug/ShaderStatsDebugWidget.kt` — zero refs
- App diagnostic scenes (wired in HomeScreen — unwire on removal):
  `CoverageMaskAtlasDiagnosticScene.kt`, `ClipAtlasDiagnosticScene.kt`,
  `CaptureImportParityDiagnosticScene.kt` (+ tests in section C)
- `app/.../ui/components/DebugStatsDropdown.kt` — wired in `MainActivity` (unwire)
- Legacy diagnostics already covered in §A (GraphicsLayerCaptureDiagnostics,
  SceneBlurAtlasGeometryDiagnostics, SceneBlurAtlasDiagnosticMode, SceneTraceCounters)

### DECISION — metrics overlay: KEEP
- `internal/metrics/SceneMetrics.kt`, `internal/metrics/SceneRenderMetricsLog.kt` — referenced
  by live `SceneRenderer.kt` and `SceneGlOwner.kt`. **Kept** as the live metrics overlay
  (decision 2026-06-21). Not part of removal.

---

## C. Old tests
### REMOVE — `imla/src/test` (test dead legacy)
ImlaSceneRendererLifetimeTest, ImlaSceneSessionRootCaptureContractTest,
Renderer2LegacyCompatibilityBoundaryTest, RenderingPipelineAlgorithmsTest,
scene/ImlaSceneRendererRootCaptureContractTest, scene/RecordingSceneTraceCounterRecorder,
scene/Renderer2ActiveShaderAssetGuardTest, scene/Renderer2BackdropCompositeAlphaContractTest,
scene/Renderer2CaptureImportCorrectnessTest, scene/Renderer2MaskSemanticsTest,
scene/SceneBlurAtlas*Test ×7, scene/SceneCommandPassContractTest, scene/SceneFramePlannerTest,
scene/SceneLayerRepositoryCaptureTest, scene/SceneMaskRepositoryCaptureTest, scene/SceneModelTest,
scene/ScenePassTest, scene/SceneResourceStoreTest, scene/SceneStencilClipPassTest
### TRIM
- `RenderingPipelineMathTest.kt` — keep only cases for the 4 surviving functions
### REMOVE — `app/src/test`
- CoverageMaskAtlasDiagnosticSceneTest, ClipAtlasDiagnosticSceneTest,
  CaptureImportParityDiagnosticSceneTest (with their scenes)
- Optional boilerplate: ExampleUnitTest, app/androidTest/ExampleInstrumentedTest
### VERIFY
- AtlasBenchmarkSceneTest / AtlasBenchmarkScene — confirm whether it exercises the (dead) legacy
  atlas before removing; DemoSceneBlurUsageTest exercises live demo path (keep)

---

## D. Shaders (`imla/src/main/assets/shader/`)
### REMOVE now (no Kotlin refs)
- `simple_quad_clamp.frag`, `stencil_quad.frag`
### REMOVE with legacy (referenced only by dead legacy code)
- `stencil_clip_quad.frag`, `stencil_clip_quad.vert`, `screen_space_quad.frag`
- NOTE: `simple_ext_quad.frag` / `simple_quad.vert` are used by the **kept** `SurfaceTextureRenderer`
  (relocated out of legacy) — KEEP them.
### KEEP — referenced by scratch path
all others (scene_backdrop_*, gaussian_blur*, default_quad*, simple_quad(.frag/.vert),
noise_*, debug_quad, external_quad, stencil_clip_batch, scene_backdrop_separable_blur)

---

## E. Demo artifacts (`demo/`) — REMOVE (unreferenced in README/docs)
mosaic_blur.png, trace_total_pass00.png, trace_total_pass01.png, trace_blur_effect.png,
current-state-2026-05-23.png, blur_gamma_correction_side_by_side.png
(`.webp` variants stay — they are the ones README embeds)

---

## F. App example scenes — REMOVE (orphans, zero refs)
- `app/.../ui/Scene2ProfilingScene.kt`
- `app/.../ui/NewRendererTestScene.kt`
(`FauxCube.kt` is referenced by MainActivity — keep)

---

## Execution result (2026-06-21) — DONE
- All phases executed. **98 files deleted, 2 added (relocated utils), 8 modified.**
- `compileDebugKotlin` green; **all app + imla unit tests pass.**
- Full-harness diagnostics removed from the app, BUT kept `isAtlasDiagnosticBuild()` +
  `ATLAS_DIAGNOSTIC_BUILD_TYPE="benchmark"` + the `benchmark` Gradle build type — they are
  load-bearing for the macrobenchmark module and the kept Mask/Alpha/AtlasBenchmark scenes
  (the name means "debug-or-benchmark build", not legacy-atlas diagnostics).
- Repaired two **pre-existing-stale** source-guard tests (both already failing at HEAD before
  this work): removed the obsolete legacy-renderer guard in `DemoSceneBlurUsageTest`, and dropped
  the stale `selectedNavIndex` assertion in `AtlasBenchmarkSceneTest`.
- KNOWN PRE-EXISTING (not from this removal): `./gradlew build` lint fails on
  `SceneRenderBuffer.kt:67` `bindForDraw [NewApi]` (API 26 vs min 23) — scratch-path file,
  byte-identical to HEAD. Out of scope; left untouched.

## Suggested removal order (keep build green)
1. Relocate the 2 live legacy utils (`RenderingPipelineMath` trimmed, `SurfaceTextureRenderer`)
   into a live package; repoint the 4 consumer imports.
2. Delete `BlurSurfaceHost.kt`, then all of `internal/legacy/` (now unreferenced).
3. Delete dead diagnostics + unwire diagnostic scenes/`DebugStatsDropdown` from app.
4. Delete dead tests; trim `RenderingPipelineMathTest`.
5. Delete orphan app scenes + dead shaders + unreferenced demo artifacts.
6. `./gradlew -q compileDebugKotlin` and `:imla:build` after each phase; decide metrics overlay (§B).
