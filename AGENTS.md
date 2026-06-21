# Repository Guidelines

## Project Structure
- `app/` is the Android demo and manual verification surface.
- `imla/` is the Imla rendering library. Public Compose APIs, internal GL
  renderers, shader adapters, and renderer tests live here.
- `benchmark/` runs macrobenchmarks against `:app` through its `benchmark`
  build type.
- `demo/` contains static README artifacts. Update it only when shipped visuals
  change.
- Project-wide Gradle configuration lives in `settings.gradle.kts`,
  `build.gradle.kts`, `gradle/`, and `gradle/libs.versions.toml`.

## Common Source Locations
- Public Compose APIs live in root package
  `imla/src/main/java/dev/serhiiyaremych/imla/`. Start here for `ImlaHost`,
  `Modifier.effectGroup()`, `Modifier.effectLayer { ... }`,
  `EffectLayerScope`, and `EffectLayerBoundsProvider`.
- Scratch renderer code starts in
  `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/SceneRenderer.kt`.
- Effect group/layer modifiers live under
  `imla/src/main/java/dev/serhiiyaremych/imla/internal/modifier/`. Capture
  helpers live under `internal/capture/`.
- Scratch layer internals live under
  `imla/src/main/java/dev/serhiiyaremych/imla/internal/layer/`. Start here for
  immutable snapshots, registry, resources, and layer geometry.
- GL owner, render target, pipeline, scheduler, renderer primitives, and shader
  adapters live under
  `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/`.
- Metrics overlay support lives under `internal/metrics/`.
- Imla GL primitives live under
  `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/`. Start here for
  `CoordinateOrigin`, framebuffers, textures, shader programs, GL state, and
  quad rendering utilities.
- AndroidX Compose source checkout:
  `/Users/syaremych/dev/projects/androidx/compose/ui/`.
  Use this when verifying Compose modifier, draw, layer, graphics-layer,
  `AndroidExternalSurface`, coordinate, semantics, or lifecycle behavior.
- AndroidX Compose UI common node/draw code:
  `/Users/syaremych/dev/projects/androidx/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/`.
  Useful anchors include `draw/`, `graphics/GraphicsLayerModifier.kt`,
  `node/DrawModifierNode.kt`, `node/LayoutNodeDrawScope.kt`,
  `node/NodeCoordinator.kt`, and `node/OwnedLayer.kt`.
- AndroidX Compose UI Android platform code:
  `/Users/syaremych/dev/projects/androidx/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/`.
  Useful anchors include `platform/AndroidComposeView.android.kt`,
  `platform/GraphicsLayerOwnerLayer.android.kt`,
  `platform/RenderNodeLayer.android.kt`, `platform/ViewLayer.android.kt`, and
  `layout/GraphicLayerInfo.android.kt`.
- AndroidX Compose graphics-layer implementation:
  `/Users/syaremych/dev/projects/androidx/compose/ui/ui-graphics/src/`.
  Useful anchors include
  `commonMain/kotlin/androidx/compose/ui/graphics/layer/GraphicsLayer.kt`,
  `androidMain/kotlin/androidx/compose/ui/graphics/layer/AndroidGraphicsLayer.android.kt`,
  `GraphicsLayerV23.android.kt`, `GraphicsLayerV29.android.kt`, and
  `LayerSnapshot.android.kt`.
- AndroidX graphics-core checkout:
  `/Users/syaremych/dev/projects/androidx/graphics/graphics-core/`.
  Use this when verifying GL renderer, EGL, HardwareBuffer, SyncFence,
  `CanvasBufferedRenderer`, or `SurfaceTextureRenderer` behavior.
- AndroidX graphics-core GL sources:
  `/Users/syaremych/dev/projects/androidx/graphics/graphics-core/src/main/java/androidx/graphics/opengl/`.
  Useful anchors include `GLRenderer.kt`, `GLThread.kt`,
  `FrameBufferRenderer.kt`, `FrameBufferPool.kt`, `FrameBuffer.kt`, and
  `QuadTextureRenderer.kt`.
- AndroidX graphics-core capture/import sources:
  `/Users/syaremych/dev/projects/androidx/graphics/graphics-core/src/main/java/androidx/graphics/`
  and
  `/Users/syaremych/dev/projects/androidx/graphics/graphics-core/src/main/java/androidx/hardware/`.
  Useful anchors include `CanvasBufferedRenderer.kt`,
  `CanvasBufferedRendererV29.kt`, `CanvasBufferedRendererV34.kt`,
  `SurfaceTextureRenderer.kt`, `HardwareBufferFormat.kt`,
  `HardwareBufferUsage.kt`, and `SyncFenceCompat.kt`.

## Commands
- `./gradlew -q compileDebugKotlin` is the default quick compile check.
- `./gradlew -q :imla:build` focuses on the library.
- `./gradlew -q build` compiles all modules, runs tests, and enforces lint.
- `./gradlew -q test` runs JVM tests.
- `./gradlew -q lint` runs Android lint with warnings as errors.
- `./gradlew -q :app:assembleDebug` builds the debug APK for manual device work.
- `./gradlew -q :app:connectedDebugAndroidTest` runs instrumentation tests on a
  connected device or emulator.
- Use `tools/adb-timeout` for every ADB command in this repo. Example:
  `tools/adb-timeout --timeout 10 devices -l`.

## Current Scratch Renderer Contract
- Active branch line: `syaremych/imla-2.0`.
- Current public API is page-local and modifier-first: `ImlaHost`,
  `Modifier.effectGroup()`, and `Modifier.effectLayer { ... }`.
- Do not pass renderer or OpenGL objects into child slots. Slots register UI
  state through the internal layer registry exposed by the effect group and host
  scope.
- The previous Renderer 2 audit/design stack was removed. Do not use deleted
  `doc/renderer-2-*` files, `doc/scene-renderer-2-design.md`, or old
  pre-Renderer-2 notes as architecture sources.
- The deprecated public bridge remains removed. Do not restore
  `rememberUiLayerRenderer`, `UiLayerRenderer`, `ImlaRenderPipeline`,
  renderer-taking `blurSource` / `backdropBlur`, or renderer-taking host APIs.
- `CopyLessRenderingPipeline` and the old render-object path are removed. Do not
  use their behavior as the scratch renderer design source.
- Keep main-thread Compose capture separate from GL-thread import/rendering.
  Compose modifiers own UI objects; the renderer owns OpenGL objects. Capture
  flows through the `CapturePipe` primitives (`SingleBufferRenderer`,
  `BufferLease`, `CapturedFrameImporter`); do not reintroduce lease-holding slot
  imports.
- Scene2 backdrop pass outputs separate cross-pass sampling data from borrowed
  GL storage ownership: `BackdropTexture` carries texture/rect sampling data,
  while `BorrowedBackdropTexture` wraps that image plus the producing
  framebuffer so the pass can release pooled storage independently of the
  sampling handle.
- Scene2 backdrop blur is active in the scratch path
  (`SceneBackdropEffectPasses`, `SceneBackdropOperation.Blur`,
  `GaussianBlurEffect`, `BackdropCompositeShaderEffect`), and `SceneRenderer`
  already handles backdrop clip and progressive mask. Atlas routing and
  production API polish are not yet ported to the scratch path; they remain
  under `internal/legacy/`.
- A large `internal/legacy/` package still coexists with the scratch path and is
  still imported by production code (`BlurSurfaceHost`,
  `ImlaSceneBlurSourceModifier`, `SceneBackdropBlurModifier`). The scratch
  renderer is not the only live path yet; do not assume legacy is gone.

## Goal Protocol
- Keep goals narrow and progressive. Include explicit non-goals, verification,
  documentation expectations, cleanup, and finish criteria.
- Every implementation goal should document changed APIs: ownership, usage,
  lifecycle/threading assumptions, and deliberate non-ownership.
- Every goal should end by verifying requirements, committing the completed
  slice, loading `code-simplifier`, simplifying only the goal changes, rerunning
  focused verification if code changed, and amending or adding a small follow-up
  commit.
- Do not spend goal budget on generic Gradle explanations. Mention only
  task-specific verification.

## Project Tools And Skills
- Use `kotlin_outline` / `development/kotlin-outline` before broad Kotlin source
  reads in Imla renderer, Compose modifier, AndroidX Compose UI, or AndroidX
  graphics-core code. Run it on the smallest useful file or directory, then open
  only the surfaced line ranges. It is structural only; use source reads for
  behavior and ownership.
- Use `imla_adb_timeout` / `tools/adb-timeout` for every ADB operation. Use
  `--timeout 10` for quick reads, `--timeout 20` for input/screenshot/force-stop
  commands, `--timeout 60` for installs and launches, and longer only for known
  long-running tests. A `124` exit code is an infrastructure timeout.
- Use `imla_screenshot_diff` / `tools/screenshot-diff` after any output-affecting
  renderer, blur, mask, clip, geometry, shader, atlas, capture, or composite
  change. Use crops for bottom sheets or individual slots, and start blur-heavy
  comparisons with `--pixel-threshold 8 --smooth-radius 1.0`.
- Use `imla_adb_screenshot_half` / `tools/adb-screenshot-half` for ordinary
  inline visual checks when full native resolution is not required.
- Use `imla_perfetto_feedback` / `tools/imla-perfetto-feedback` when runtime
  behavior matters beyond screenshot parity: render scheduling, GL thread time,
  capture cost, frame timing, idle work, or bottom-sheet interaction cost. Read
  the generated analysis directory before changing performance-sensitive code.
- Use `imla_scene_counters` after a Perfetto capture when the question is about
  render reasons, root captures, slot content captures, mask/clip captures,
  geometry refreshes, scene renders, or idle detached rendering. Counters explain
  work volume; they do not prove pixels are correct.
- Use AndroidX source locations above when behavior depends on Compose or
  AndroidX graphics internals. Prefer source-backed answers over assumptions
  about modifier nodes, graphics layers, RenderNode capture, fences, or GL
  threading.

## Verification
- The current physical device for relative lower-end checks is
  `T81164GB23417442888` (`KidzPad_Pro`, `1200x1920`). Treat its numbers as
  relative, not representative production performance.
- For performance judgments on that device, prefer the `benchmark` app variant
  built release-like, non-debuggable, profileable, minified, and ART-compiled
  with `cmd package compile -m speed -f dev.serhiiyaremych.imla`. Verify with
  `dumpsys package dexopt` that `dev.serhiiyaremych.imla` reports
  `arm64: [status=speed]`; a command result of `Success` alone is not enough.
- Always force-stop `dev.serhiiyaremych.imla` after diagnostics.
- Visual renderer changes require screenshots. Use noise-tolerant screenshot
  diffs when frame noise, GL nondeterminism, or device variance makes exact
  comparison unstable.
- Perfetto and scene counters are useful after visual correctness is established;
  they do not replace screenshot parity for output-affecting changes.
- Diagnostics under `diagnostics/apa/` are intentionally ignored by git.

## JVM Test Boundaries
- JVM tests must not require Android framework methods that local unit tests do
  not mock, such as `android.os.Trace.beginSection`. If a test fails with a
  `Method ... not mocked` runtime error, treat it as a boundary leak unless the
  goal explicitly requires Robolectric or instrumentation.
- Prefer extracting a narrow production seam for the Android-dependent operation
  and inject a fake in JVM tests. Keep the real implementation in Android-facing
  code, and test policy/decision logic against the seam.
- Do not fix scratch renderer JVM tests with Gradle-wide `isReturnDefaultValues`,
  global `--add-opens`, reflection into Android/Compose internals, `Unsafe`, or
  source mutations that only make tests pass. Those hide framework crossings and
  make future renderer failures harder to diagnose.
- Avoid constructing Compose or Android graphics objects in JVM tests when the
  test only needs repository, scheduler, pass-planning, or policy behavior. For
  slot capture tests, prefer faking access above `GraphicsLayer` instead of
  constructing `BlurSlotContentRecord` or triggering `GraphicsLayer` class
  initialization.
- Source-string guard tests are acceptable only as temporary boundary guards for
  deleted legacy APIs, internal diagnostic gates, or known migration hazards.
  Prefer behavior tests when behavior can be reached without Android framework
  initialization.

## Coding Style
- Kotlin is primary. Prefer `val`, keep explicit types on public APIs, and avoid
  wildcard imports.
- Use four-space indentation, PascalCase for classes/objects, lowerCamelCase for
  methods/fields, and descriptive effect/pass names.
- Keep packages under `dev.serhiiyaremych.imla`.
- Keep code locally coherent and single-functioned. Favor self-documenting names
  and small functions over explanatory comments.
- Preserve shader math, GL state ownership, coordinate-origin handling, and
  lifecycle boundaries unless the task is explicitly about changing them and has
  focused evidence.
- Avoid per-frame allocations in hot paths. Reuse mutable buffers where the
  surrounding code already follows that pattern.

## Coordinate And UV Contract
- Root/sample areas use top-left screen coordinates.
- Effect FBO pixel space uses bottom-left OpenGL coordinates.
- Texture and FBO origin must be expressed through `CoordinateOrigin` where the
  existing renderer primitives expose it.
- For scene2 backdrop debug sampling, explicit root-space UVs are already in
  root texture screen convention. Do not apply the generic texture-origin flip a
  second time.
- For rotated scene2 backdrop blur, start rotation-drift debugging at
  `BackdropSampleRegion`: the prepared sample region must use a stable
  root-space diagonal envelope, centered on transformed slot coverage, then
  snap to the downsample grid. Tight projected axis-aligned bounds changed the
  prepared texture footprint during rotation and caused visible blur drift/band
  movement.
- Scene2 backdrop blur is precision-sensitive on large/high-contrast areas.
  `RGB10_A2` intermediate FBOs did not remove bending; shader precision did.
  Keep prepare and screen-space composite fragments at `highp`, and keep blur
  coordinate, weight, and color accumulation math explicitly `highp` before
  changing formats or sample-region geometry.
- Visual renderer checks must look for shift, stretch, Y-flip, rotation drift,
  mask/clip alignment, alpha/color, and stale-frame artifacts.

## Commit And PR Notes
- Use imperative commit titles that match the repository history.
- PRs should summarize behavior, cite linked issues when present, and list
  verification commands.
- Attach screenshots or traces when visual or GL changes affect output.

