# Delete leftover present types

Glass already draws as a per-layer hardware bitmap. This program deletes the unused blit engine, the unused SurfaceTexture path, and types that still describe a host window. It does not add a present backend and it does not rewrite SceneGlOwner. PR order is pr-dead-engine, pr-drop-st, pr-present-types, pr-one-slot, pr-egl-pump, pr-cmdbuf-design. App users keep ImlaHost, effectSource, and effectLayer. Maintainers get one blur home and one-slot request types.

## How to read this

One box is one unit of work. Every box names the evidence that checks it. A nested box is a sub-step of the box above it. Check a box only when its evidence exists, a file, a log line, a screenshot, a test run, or a SHA. The body is a how-to. The appendices explain and record.

The program runs `pstack/skills/poteto-mode/playbooks/autopilot-stack.md`. The operator lands every PR. Owners stop at STACK-READY.

Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

## Program checklist

### Arm the program

- [ ] State the protocol and this plan to the operator, then stop. Start execution only on her explicit go.
- [x] On her go, arm a `/goal` with this exact text. "docs/plans/2026-08-25-delete-surface-era-leftovers-program.md. PR order pr-dead-engine, pr-drop-st, pr-present-types, pr-one-slot, pr-egl-pump, pr-cmdbuf-design. Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. The operator lands the Graphite stack. Done when every PR box is checked and the operator has landed the stack."
- [ ] Read these from trunk at program start. Re-read them at every tick.
  - [ ] `git show origin/main:AGENTS.md`
  - [ ] `git show origin/main:.agents/skills/imla_adb_screenshot_half/SKILL.md`
  - [ ] `git show origin/main:.agents/skills/imla_screenshot_diff/SKILL.md`
  - [ ] `git show origin/main:.agents/skills/imla_perfetto_feedback/SKILL.md`
  - [ ] `git show origin/main:.agents/skills/imla_scene_counters/SKILL.md`
  - [ ] `git show origin/main:pstack/skills/poteto-mode/playbooks/autopilot-stack.md`
  - [ ] `git show origin/main:pstack/skills/swarm/SKILL.md`
  - [ ] `git show origin/main:pstack/skills/poteto-mode/playbooks/opening-a-pr.md`
- [x] Arm the 30-minute audit tick. In a local session, a real terminal `/loop`. In a cloud root, a cloud-sleeper wake chain. Never leave the cadence to memory.
- [ ] Use this tick prompt, verbatim. "Re-read the execution playbook from trunk and the armed /goal. Audit the operation against both and fix drift in this tick. Probe every active lane and judge progress by side effects only. Stand down a stuck lane and dispatch its replacement now. Then send the operator a status message, whether or not anything changed, with the queue table of PR, owner, state, and head SHA, the verdicts since the last tick, what merged, open operator gates, and blockers."
- [ ] On the operator's hold or stand-down, send every owner a zero-writes order at once.

### Spawn owners

- [ ] Spawn one owner per PR with the full lifecycle the execution playbook names.
- [ ] Follow this dependency graph. Start dependent work only after its parent merges, or base it on the parent branch when the execution playbook stacks.
  - [ ] pr-dead-engine is first. It branches from `main`.
  - [ ] pr-drop-st after pr-dead-engine.
  - [ ] pr-present-types after pr-drop-st.
  - [ ] pr-one-slot after pr-present-types.
  - [ ] pr-egl-pump after pr-one-slot.
  - [ ] pr-cmdbuf-design after pr-egl-pump.
- [ ] Hold the file boundaries. pr-dead-engine touches dead processing types, `FramebufferPool.kt`, `BumpAllocatorPool.kt`, `OrthographicCameraController.kt`, unused gaussian and noise-blend shaders, matching tests, `ImlaBenchmark.kt`, `benchmark/README.md`, `scripts/gaussian_trace_query.py`, and the live-path sentence in `AGENTS.md`. It must not edit `QuadBatchRenderer.kt` or `SimpleQuadRenderer.kt`. pr-drop-st touches capture factory wiring, `executeWithGlResources`, SurfaceTexture files, and OES shader branches. pr-present-types touches presenter, buffer backing, unused fields, and GL-session names. pr-one-slot touches `UiSceneCapture`, `SceneSlotDeclaration`, `SceneGlRenderFrame`, `GlStore`, `assembleSnapshot`, and pipeline slot lists. pr-egl-pump touches the `GLRenderer` pump in `SceneGlOwner.kt` and `ensureGlTarget`. pr-cmdbuf-design touches only a new design doc.
- [ ] Hold the review gate. pr-drop-st, pr-one-slot, and pr-egl-pump change pixels or GL timing. They wait for the operator's review in chat with screenshots and a video before merge.

### PR mechanics, for every PR

- [x] Open the PR ready, never draft, with `gh pr create` and `draft: false`, or with Graphite `gt` for a stack.
- [x] Run the repo's lint and typecheck once before the PR-facing push. Push with hooks on. Commands are `./gradlew -q compileDebugKotlin` and `./gradlew -q :imla:build`.
- [ ] Run `/deslop` before each commit and `/no-comments` before review.
- [ ] Triage every Bugbot and security-reviewer comment per `../references/bugbot-triage.md`.
- [ ] Rebase onto current trunk before babysit and again before the merge-ready report.

### Verdict and merge, for every PR

- [ ] At the merge-ready head SHA, run the swarm per `pstack/skills/swarm/SKILL.md`. One gates lane. The ten live lanes from the PR's **Verify, live** block. The perf lane from its **Verify, perf** block. One audit lane that reads the diff and the receipts and distrusts the PR body.
- [ ] Clean only when every lane is `PASS`. Findings go back to the owner. A new head gets a fresh swarm and a fresh verdict.
- [ ] Compare `git patch-id` at the verdict SHA against the current head after any restack. Re-verify anything that drifted. The root appends a clean PR to the Graphite stack. The operator lands it. No owner merges.

### Boot recipe, for every live lane

Each live lane checks out the PR head and drives the installed debug app on serial `T81164GB23417442888`. Drive through `tools/adb-timeout` and `tools/adb-screenshot-half`. Capture full-size PNGs for `tools/screenshot-diff`. Crop the status bar before compare. Force-stop `dev.serhiiyaremych.imla` after the lane.

- [ ] `git fetch origin <head-branch> && git checkout <head SHA>`.
- [ ] Build and install with `./gradlew -q :app:assembleDebug`, then `tools/adb-timeout --timeout 60 -s T81164GB23417442888 install -r app/build/outputs/apk/debug/app-debug.apk`. Launch `dev.serhiiyaremych.imla/.MainActivity`. Wait until HomeScreen demo cards show.
- [ ] Deliver input only through `tools/adb-timeout --timeout 20`. Tap the HomeScreen card whose title matches the lane scene. Read-only diagnostics are logcat via `tools/adb-timeout` and `dumpsys package dexopt` when a perf lane needs ART speed.
- [ ] Save every screenshot to `/tmp/swarm-<pr-id>/worker-<n>/<slug>.png` and return the paths with the report.

## Delete the dead processing engine (pr-dead-engine)

**Depends on.** None.

**Files.**

- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/EffectPipeline.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianBlurEffect.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianKernel.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianBlurKernelShaderProgram.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/PreProcessEffect.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/EffectContext.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/Effect.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/SizedFramebuffer.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/effects/PreProcessGeometry.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/preprocess/PreProcessFilter.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/composite/BackdropCompositeShaderEffect.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/blend/PostBlendEffect.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/PostProcessingEffect.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/SinglePassQuadExecutor.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/processing/noise/BackdropCompositeSamplingOrigins.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/framebuffer/FramebufferPool.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/framebuffer/BumpAllocatorPool.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/camera/OrthographicCameraController.kt`.
- [x] Delete `imla/src/main/assets/shader/gaussian_blur.vert`.
- [x] Delete `imla/src/main/assets/shader/gaussian_blur_kernel.frag`.
- [x] Delete `imla/src/main/assets/shader/noise_blend_quad.frag`.
- [x] Edit `AGENTS.md`.
- [x] Edit `benchmark/README.md`.
- [x] Delete `scripts/gaussian_trace_query.py`.
- [x] Delete `imla/src/test/java/dev/serhiiyaremych/imla/internal/render/processing/effects/PreProcessGeometryTest.kt`.
- [x] Delete `imla/src/test/java/dev/serhiiyaremych/imla/internal/render/processing/effects/GaussianKernelTest.kt`.
- [x] Delete `imla/src/test/java/dev/serhiiyaremych/imla/internal/render/processing/noise/BackdropCompositeSamplingOriginsTest.kt`.
- [x] Edit `benchmark/src/main/java/dev/serhiiyaremych/benchmark/ImlaBenchmark.kt`.

**Build.**

- [x] Grep `EffectPipeline`, `GaussianBlurEffect`, `PostBlendEffect`, `PreProcessFilter`, `FramebufferPool`, and `OrthographicCameraController` from the repo root. Delete only after production callers outside those files are zero.
- [x] Keep `FramebufferLendingPool`, `QuadBatchRenderer`, `SimpleQuadRenderer`, `Renderer2D`, `SceneBackdropEffectPasses`, and `SceneRenderPipeline`.
- [x] Replace `GaussianBlurEffect*` names in `ImlaBenchmark.kt` and `benchmark/README.md` so they do not look like a live pass.
- [x] Fix the `AGENTS.md` renderer contract so live blur is only `SceneBackdropEffectPasses`. Remove `GaussianBlurEffect` and `BackdropCompositeShaderEffect` from that sentence.

**You see.**

- [x] `./gradlew -q :imla:build` exits 0.
- [x] `rg EffectPipeline imla/src/main` prints nothing.
- [ ] Showcase still shows glass after install.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [x] Remaining `imla` JVM tests still pass after the dead tests are gone. Run `./gradlew -q :imla:test`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on `grok-4.6-fast-xhigh` at the PR head, per the boot recipe.

- [ ] Lane 1. Open Showcase from HomeScreen. Wait 2 s. Save `showcase-idle.png`. Pass when `tools/screenshot-diff` against the trunk crop reports changed_ratio under 0.02 at `--pixel-threshold 8 --smooth-radius 1.0`.
- [ ] Lane 2. Stay on Showcase. Wait 4 s. Save `showcase-moved.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 3. Open Social Feed. Wait 2 s. Save `feed-idle.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 4. Swipe the feed up. Wait 1 s. Save `feed-scrolled.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 5. Open Pager. Wait 2 s. Save `pager-page0.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 6. Swipe the pager to the next page. Wait 1 s. Save `pager-page1.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 7. Open Rotating Cards. Wait 3 s. Save `rotating-cards.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 8. Open Resize Card and leave it collapsed. Save `resize-collapsed.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 9. Expand Resize Card. Wait 1 s. Save `resize-expanded.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 10. Open Capture Fidelity. Wait 2 s. Save `capture-fidelity.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. `SceneGlRenderer#render` p50 duration on a Showcase smoke.
- [ ] Probe. `tools/imla-perfetto-feedback smoke --device T81164GB23417442888` at trunk and at the head, interleaved.
- [ ] Baseline. Record the trunk p50 first.
- [ ] Rule. Head p50 must not exceed trunk p50 plus 10 percent.

**Review gate.** None. pr-dead-engine is not review-gated.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [x] Bugbot triage done.
- [ ] Rebased onto current trunk after the verdict, patch-id unchanged.
- [ ] The root appends the PR to the Graphite stack. The operator lands it.

## Delete the SurfaceTexture capture backend (pr-drop-st)

**Depends on.** pr-dead-engine.

**Files.**

- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/SceneRenderer.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/layer/resources/SceneCanvasFrameRenderer.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/capture/GraphicsLayerCapture.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/layer/resources/SceneProgressiveMaskCache.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/layer/resources/SceneClipShapeCache.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/SceneGlOwner.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/capture/CapturedLayerFrame.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/Texture.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/opengl/OpenGLTexture2D.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/Renderer2D.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/SceneSurfaceTextureCapture.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/SurfaceTextureRenderer.kt`.
- [x] Delete `imla/src/main/java/dev/serhiiyaremych/imla/internal/capture/CanvasTextureCapture.kt`.
- [x] Delete `imla/src/main/assets/shader/simple_ext_quad.frag`.
- [x] Delete `imla/src/main/assets/shader/external_quad.frag` after OES constructors are gone.
- [x] Edit `app/src/main/java/dev/serhiiyaremych/imla/CaptureFidelityScene.kt` only if compile fails. Prefer leaving the demo.

**Build.**

- [x] Stop passing `glOwner::createCanvasTextureCapture` from `SceneRenderer` into backdrop, slot, mask, and clip captures.
- [x] Remove `CanvasTextureCaptureFactory` from `SceneCanvasFrameRenderer`, `GraphicsLayerCapture`, `SceneProgressiveMaskCache`, and `SceneClipShapeCache`.
- [x] Delete `kickTextureCapture`, `captureToTexture`, `CapturedTextureFrame`, the `importCapturedFrame` texture branch, and `executeWithGlResources`. That helper is only wired from `createCanvasTextureCapture`.
- [x] Delete `TEXTURE_EXTERNAL_OES` from `Texture.Target`, `OpenGLTexture2D`, and `Renderer2D` after grep shows no remaining constructors.
- [x] Leave `CaptureFidelityScene` in the app unless the operator later drops issue 13.

**You see.**

- [x] `./gradlew -q :imla:build` exits 0.
- [x] `rg CanvasTextureCapture imla/src/main` prints nothing.
- [ ] Showcase glass is not Y-flipped. Mask and clip still line up.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [x] Remaining capture and importer JVM tests still pass. Run `./gradlew -q :imla:test`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on `grok-4.6-fast-xhigh` at the PR head, per the boot recipe.

- [ ] Lane 1. Open Showcase from HomeScreen. Wait 2 s. Save `showcase-idle.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8 and glass is not Y-flipped.
- [ ] Lane 2. Stay on Showcase. Wait 4 s. Save `showcase-moved.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 3. Open Social Feed. Wait 2 s. Save `feed-idle.png`. Pass when frosted chrome still blurs the feed and screenshot-diff reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 4. Swipe the feed up. Wait 1 s. Save `feed-scrolled.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 5. Open Pager. Wait 2 s. Save `pager-page0.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 6. Swipe the pager to the next page. Wait 1 s. Save `pager-page1.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 7. Open Rotating Cards. Wait 3 s. Save `rotating-cards.png`. Pass when blur does not drift off the tiles and screenshot-diff reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 8. Open Resize Card and leave it collapsed. Save `resize-collapsed.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 9. Expand Resize Card. Wait 1 s. Save `resize-expanded.png`. Pass when mask and clip stay aligned and screenshot-diff reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 10. Open Capture Fidelity. Wait 2 s. Save `capture-fidelity.png`. Pass when the probe still draws. A nested SurfaceView in the demo is allowed.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. `SceneGlRenderer#render` p50 duration on a Showcase smoke.
- [ ] Probe. `tools/imla-perfetto-feedback smoke --device T81164GB23417442888` at trunk and at the head, interleaved.
- [ ] Baseline. Record the trunk p50 first.
- [ ] Rule. Head p50 must not exceed trunk p50 plus 10 percent.

**Review gate.** The operator reviews before merge.

- [ ] Copy lane 1, lane 3, lane 7, and lane 9 screenshots into `diagnostics/apa/screenshots/pr-drop-st-review-<slug>.png`.
- [ ] Record a 30 to 60 second video of Showcase then Social Feed on the device. Save it as `diagnostics/apa/screenshots/pr-drop-st-review.mp4`.
- [ ] Post the screenshots and the video in chat. Stop at merge-ready. Wait for the operator's click.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [x] Bugbot triage done.
- [ ] Rebased onto current trunk after the verdict, patch-id unchanged.
- [ ] The root appends the PR to the Graphite stack. The operator lands it.

## Inline the hardware bitmap presenter (pr-present-types)

**Depends on.** pr-drop-st.

**Files.**

- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/pipeline/ScenePresenter.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/pipeline/SceneRenderPipeline.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/pipeline/SceneRenderBuffer.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/SceneGlOwner.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/SceneRenderer.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/SceneRenderTarget.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/modifier/SceneSourceModifier.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/modifier/SceneSlotModifier.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/scheduler/SceneFrameRequest.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/metrics/SceneMetrics.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/SceneBackdropEffectPasses.kt`.

**Build.**

- [x] Inline `HardwareBitmapPresenter`. Delete `ScenePresenter`.
- [x] Delete `SceneRenderBuffer.Backing.Plain` and its `when` branches.
- [x] Stop taking `requiresStencil` on acquire. Every HW buffer already has a stencil RBO.
- [x] Delete `SceneRenderer.attachedSize` and `SceneRenderTarget.resize`.
- [x] Rename `hasPresentTarget`, `ensureGlTarget`, `detachPresentTarget`, and `SceneFrameDropReason.NoRenderTarget` to GL-session words.
- [x] Drop unused `SceneFrameDropReason` values `Superseded` and `SourceDetached`, or start recording mailbox displace as `Superseded`. Do not leave dead enum members that look load-bearing.
- [x] Delete `BackdropInput.accumulatedScene`.
- [x] Fix the `SceneRenderCallback` comment that still mentions blit. Update `SceneFrameRequest` KDoc so it does not claim a frame scheduler.

**You see.**

- [x] `./gradlew -q compileDebugKotlin` exits 0.
- [x] `rg interface ScenePresenter` prints nothing.
- [ ] `rg hasPresentTarget` prints nothing.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [x] Remaining renderer JVM tests still pass after the rename. Run `./gradlew -q :imla:test`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on `grok-4.6-fast-xhigh` at the PR head, per the boot recipe.

- [ ] Lane 1. Open Showcase from HomeScreen. Wait 2 s. Save `showcase-idle.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 2. Stay on Showcase. Wait 4 s. Save `showcase-moved.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 3. Open Social Feed. Wait 2 s. Save `feed-idle.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 4. Swipe the feed up. Wait 1 s. Save `feed-scrolled.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 5. Open Pager. Wait 2 s. Save `pager-page0.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 6. Swipe the pager to the next page. Wait 1 s. Save `pager-page1.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 7. Open Rotating Cards. Wait 3 s. Save `rotating-cards.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 8. Open Resize Card and leave it collapsed. Save `resize-collapsed.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 9. Expand Resize Card. Wait 1 s. Save `resize-expanded.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 10. Open Capture Fidelity. Wait 2 s. Save `capture-fidelity.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. `SceneGlRenderer#render` p50 duration on a Showcase smoke.
- [ ] Probe. `tools/imla-perfetto-feedback smoke --device T81164GB23417442888` at trunk and at the head, interleaved.
- [ ] Baseline. Record the trunk p50 first.
- [ ] Rule. Head p50 must not exceed trunk p50 plus 10 percent.

**Review gate.** None. pr-present-types is not review-gated.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [x] Bugbot triage done.
- [ ] Rebased onto current trunk after the verdict, patch-id unchanged.
- [ ] The root appends the PR to the Graphite stack. The operator lands it.

## Make one request one slot (pr-one-slot)

**Depends on.** pr-present-types.

**Files.**

- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/layer/model/UiSceneCapture.kt`.
- [ ] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/layer/model/SceneSlotDeclaration.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/SceneGlRenderFrame.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/GlStore.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/SceneRenderer.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/SceneGlOwner.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/pipeline/SceneRenderPipeline.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/pipeline/ScenePresenter.kt`.

**Build.**

- [x] Change `assembleSnapshot` so a request holds one slot, not `listOf(oneSlot)`.
- [x] Change `SceneGlOwner.submit`, `GlStore`, and present so they take that slot. Delete `slots.firstOrNull()`.
- [x] Fail compile if a second declaration would composite into the first layer bitmap. Do not keep a silent N-slot list.

**You see.**

- [x] `./gradlew -q compileDebugKotlin` exits 0.
- [x] `rg slots.firstOrNull imla/src/main` prints nothing.
- [ ] Two `effectLayer`s on one source still draw two bitmaps.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [x] Snapshot and pipeline JVM tests assert one slot per request. Run `./gradlew -q :imla:test`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on `grok-4.6-fast-xhigh` at the PR head, per the boot recipe.

- [ ] Lane 1. Open Showcase from HomeScreen. Wait 2 s. Save `showcase-idle.png`. Pass when each glass card is its own bitmap and screenshot-diff reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 2. Stay on Showcase. Wait 4 s. Save `showcase-moved.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 3. Open Social Feed. Wait 2 s. Save `feed-idle.png`. Pass when top bar, nav, and FAB are separate layers and screenshot-diff reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 4. Swipe the feed up. Wait 1 s. Save `feed-scrolled.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 5. Open Pager. Wait 2 s. Save `pager-page0.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 6. Swipe the pager to the next page. Wait 1 s. Save `pager-page1.png`. Pass when the next page glass is not the previous page bitmap and screenshot-diff reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 7. Open Rotating Cards. Wait 3 s. Save `rotating-cards.png`. Pass when blur does not drift and screenshot-diff reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 8. Open Resize Card and leave it collapsed. Save `resize-collapsed.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 9. Expand Resize Card. Wait 1 s. Save `resize-expanded.png`. Pass when the sibling chrome stays sharp and screenshot-diff reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 10. Open Capture Fidelity. Wait 2 s. Save `capture-fidelity.png`. Pass when the probe still draws.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. Scene render count on a Social Feed fling smoke.
- [ ] Probe. `tools/imla-perfetto-feedback smoke --device T81164GB23417442888` then `imla_scene_counters` at trunk and at the head, interleaved.
- [ ] Baseline. Record the trunk scene render count first.
- [ ] Rule. Head scene render count must not exceed trunk plus 10 percent.

**Review gate.** The operator reviews before merge.

- [ ] Copy lane 1, lane 3, lane 6, and lane 9 screenshots into `diagnostics/apa/screenshots/pr-one-slot-review-<slug>.png`.
- [ ] Record a 30 to 60 second video of Showcase then Pager on the device. Save it as `diagnostics/apa/screenshots/pr-one-slot-review.mp4`.
- [ ] Post the screenshots and the video in chat. Stop at merge-ready. Wait for the operator's click.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [x] Bugbot triage done.
- [ ] Rebased onto current trunk after the verdict, patch-id unchanged.
- [ ] The root appends the PR to the Graphite stack. The operator lands it.

## Swap the dummy EGL pump (pr-egl-pump)

**Depends on.** pr-one-slot.

**Files.**

- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/SceneGlOwner.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/SceneRenderer.kt`.
- [x] Edit `imla/src/main/java/dev/serhiiyaremych/imla/internal/render/gl/SceneRenderTarget.kt`.

**Build.**

- [x] Keep one-in-flight coalescing via `presentScheduled`.
- [x] Keep detach cancellation and the attached-session token modifiers already use.
- [x] Keep render-complete callbacks used by metrics.
- [x] Replace `createRenderTarget(64, 64)` plus `requestRender` with `GLRenderer.execute` for the draw kick. Do not rewrite `SceneGlOwner` into a new owner.

**You see.**

- [x] `./gradlew -q compileDebugKotlin` exits 0.
- [ ] `rg EGL_PLACEHOLDER_SIZE` prints nothing.
- [ ] A Perfetto smoke shows the GL context current on `ImlaSceneGL` during render.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [x] Remaining GL owner JVM tests still pass. Run `./gradlew -q :imla:test`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on `grok-4.6-fast-xhigh` at the PR head, per the boot recipe.

- [ ] Lane 1. Open Showcase from HomeScreen. Wait 2 s. Save `showcase-idle.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 2. Stay on Showcase. Wait 4 s. Save `showcase-moved.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 3. Open Social Feed. Wait 2 s. Save `feed-idle.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 4. Swipe the feed up. Wait 1 s. Save `feed-scrolled.png`. Pass when frames are not stale and screenshot-diff reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 5. Open Pager. Wait 2 s. Save `pager-page0.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 6. Swipe the pager to the next page. Wait 1 s. Save `pager-page1.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 7. Open Rotating Cards. Wait 3 s. Save `rotating-cards.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 8. Open Resize Card and leave it collapsed. Save `resize-collapsed.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 9. Expand Resize Card. Wait 1 s. Save `resize-expanded.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 10. Open Capture Fidelity. Wait 2 s. Save `capture-fidelity.png`. Pass when the probe still draws.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. `ImlaSceneGL` thread time and `presentScheduled` coalescing on a Social Feed fling.
- [ ] Probe. `tools/imla-perfetto-feedback smoke --device T81164GB23417442888` at trunk and at the head, interleaved. Then `imla_scene_counters`.
- [ ] Baseline. Record trunk `ImlaSceneGL` time and scene render count first.
- [ ] Rule. Head `ImlaSceneGL` p50 must not exceed trunk p50 plus 10 percent. Coalescing must still drop extra kicks while one draw is in flight.

**Review gate.** The operator reviews before merge.

- [ ] Copy lane 1, lane 4, and the Perfetto summary into `diagnostics/apa/screenshots/pr-egl-pump-review-<slug>.png`.
- [ ] Record a 30 to 60 second video of Social Feed fling on the device. Save it as `diagnostics/apa/screenshots/pr-egl-pump-review.mp4`.
- [ ] Post the screenshots and the video in chat. Stop at merge-ready. Wait for the operator's click.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [x] Bugbot triage done.
- [ ] Rebased onto current trunk after the verdict, patch-id unchanged.
- [ ] The root appends the PR to the Graphite stack. The operator lands it.

## Write the command-buffer design (pr-cmdbuf-design)

**Depends on.** pr-egl-pump.

**Files.**

- [x] Create `docs/plans/2026-08-25-command-buffer-gpu-api.md`.

**Build.**

- [x] List every live GL call in order from `SceneGlOwner.draw` through present. Bind, clear, draw, fence. No type tour.
- [x] Sketch the same frame as a WebGPU command encoder in that file.
- [x] Name live steps with no WebGPU equivalent, including EGLImage import and `glCopyImageSubData` if they appear.
- [x] Compare two paper encoder shapes. Do not land a throwaway encoder in this PR.
- [x] Keep capture, Compose, and `drawImage` present outside the encoder.

**You see.**

- [x] The design file exists under `docs/plans/`.
- [x] `./gradlew -q compileDebugKotlin` still exits 0 with no Kotlin edits required.
- [x] The file names `SceneRenderPipeline` as the one encoder caller.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [x] Library tests still pass because this PR is docs only. Run `./gradlew -q :imla:test`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on `grok-4.6-fast-xhigh` at the PR head, per the boot recipe.

- [ ] Lane 1. Open Showcase from HomeScreen. Wait 2 s. Save `showcase-idle.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 2. Stay on Showcase. Wait 4 s. Save `showcase-moved.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 3. Open Social Feed. Wait 2 s. Save `feed-idle.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 4. Swipe the feed up. Wait 1 s. Save `feed-scrolled.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 5. Open Pager. Wait 2 s. Save `pager-page0.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 6. Swipe the pager to the next page. Wait 1 s. Save `pager-page1.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 7. Open Rotating Cards. Wait 3 s. Save `rotating-cards.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 8. Open Resize Card and leave it collapsed. Save `resize-collapsed.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 9. Expand Resize Card. Wait 1 s. Save `resize-expanded.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.
- [ ] Lane 10. Open Capture Fidelity. Wait 2 s. Save `capture-fidelity.png`. Pass when screenshot-diff against the trunk crop reports changed_ratio under 0.02 at pixel-threshold 8.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. `SceneGlRenderer#render` p50 duration on a Showcase smoke.
- [ ] Probe. `tools/imla-perfetto-feedback smoke --device T81164GB23417442888` at trunk and at the head, interleaved.
- [ ] Baseline. Record the trunk p50 first.
- [ ] Rule. Head p50 must match trunk within noise because this PR adds no Kotlin.

**Review gate.** None. pr-cmdbuf-design is not review-gated.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [x] Bugbot triage done.
- [ ] Rebased onto current trunk after the verdict, patch-id unchanged.
- [ ] The root appends the PR to the Graphite stack. The operator lands it.

## Close the program

- [ ] Every box above is checked with its evidence.
- [ ] Reply to the operator with the stack root, the stack tip, and a one-line verdict per link.

## Appendix A. Prototype evidence

No prototype ran. The 2026-08-25 handoff already traced the live path. Product calls stay unproven until the operator overrules the defaults below.

minSdk stays 23. A bump to 29 is a separate product call. It is not required to delete SurfaceTexture types.

First-frame glass may lag by one Compose frame. Blocking main on the GL fence is out of this program.

Animated children inside `effectLayer` stay frozen after the first bitmap. Changing that is a product call.

`CaptureFidelityScene` stays. Drop it only if the operator drops issue 13.

Path A OnPreDraw sync capture and Path B postOnAnimation drain stay. Whether Path B is load-bearing for in-window slots is unproven. Trace that in a later program. Do not mix it into these PRs.

Wave 5 implementation starts only after this stack lands and the operator accepts the design file.

## Appendix B. Alternatives rejected

A new `SceneGlOwner` in the leftover-deletion PRs. Waves 1 to 4 only delete or rename. Wave 5 is a design, not a second owner.

Keeping `EffectPipeline` until a command encoder exists. Recording against two engines creates a third engine.

Merging `SingleBufferRenderer` and `CanvasBufferedRenderer`. They share HardwareRenderer. Their backpressure and vsync contracts differ.

Replacing the 64 by 64 pump in wave 1. Leftover files go first.

Unifying Path A and Path B in wave 1. Overlay windows and late slot content are why Path B exists.

Raising minSdk as a prerequisite for deleting SurfaceTexture. API 23 to 28 can stay Compose passthrough.

Skipping the one-slot type change because runtime is already one slot. `listOf(oneSlot)` plus `firstOrNull()` is the leftover the rewrite failed to scrap.

Landing a WebGPU wrapper on the current GLES beans. That adds a fifth wrapper.

Autopilot-full. The work is sequenced. Pixel PRs are review-gated. The operator lands the stack.

## Appendix C. Risks

Imla has no `control-ui` skill. Live lanes drive a physical tablet through `tools/adb-timeout`. Ten cloud VMs cannot share serial `T81164GB23417442888`. Swarm live lanes must run local and must serialize on that device. pr-drop-st, pr-one-slot, and pr-egl-pump.

pstack playbooks are not in this git tree. Owners read them from the Cursor plugin checkout. The `git show origin/main:pstack/...` boxes will fail on this origin. Use the plugin files and `git show origin/main:AGENTS.md`.

Grep dated 2026-08-25 can drift. Each owner greps its delete list at branch time. `BumpAllocatorPool` dies with `FramebufferPool`. Live noise uses `noise_flat`, not `noise_blend_quad`. pr-dead-engine.

`QuadBatchRenderer` and `SimpleQuadRenderer` live under `processing/`. Deleting that directory would kill live blur. pr-dead-engine.

`CaptureFidelityScene` still embeds a SurfaceView probe. Deleting product SurfaceTexture internals must not be used as a reason to rip the demo in the same PR. pr-drop-st.

`SceneRenderer` and `SceneGlOwner` are in pr-drop-st, pr-present-types, pr-one-slot, and pr-egl-pump. Stack them. Do not parallelize those four.

The 2-buffer `SceneHwBufferRing` still has no HWUI consumer-release fence. `HardwareBitmapPresenter.release` is a no-op. Do not "fix" that in this program. Needs a device repro.

## Appendix D. Links and reading list

Read `docs/plans/2026-08-25-delete-surface-era-leftovers.md` and `AGENTS.md` before any edit.

Read `docs/plans/2026-08-17-per-layer-source-present-design.md` for why present is a per-layer bitmap.

Prior investigation chat id `0f734693-1722-4b81-a114-5771510db63f`.

Commits to know, `dd5aeb3`, `8db4f23`, `156d74b`.

pr-one-slot and pr-cmdbuf-design get `pstack/skills/how/SKILL.md` if an owner is tempted to re-derive the pipeline. The handoff already forbids that.

No PR in this stack needs `pstack/skills/interrogate/SKILL.md`. The contested encoder choice is paper-only in pr-cmdbuf-design.

Keep a local `decisions.tsv` per owner as `pstack/skills/show-me-your-work/SKILL.md`. Do not commit it.

`AGENTS.md` still lists `GaussianBlurEffect` and `BackdropCompositeShaderEffect` on the live blur path. That sentence is false. pr-dead-engine fixes it.

Live keep list through pr-egl-pump, `ImlaHost.kt`, `EffectLayer.kt`, `SceneSourceModifier.kt`, `SceneSlotModifier.kt`, `SceneRenderer.kt`, `GraphicsLayerCapture.kt`, `SingleBufferRenderer.kt`, `SceneCanvasFrameRenderer.kt`, `SceneGlOwner.kt`, `CapturedFrameImporter.kt`, `SceneBackdropEffectPasses.kt`, `SceneRenderPipeline.kt`, `SceneHwBuffer.kt`, `RendererApi.kt`, `OpenGLRendererAPI.kt`, `RenderCommands.kt`, `RenderCommand.kt`, `GraphicsContext.kt`, `Renderer2D.kt`, `SimpleRenderer.kt`, `QuadBatchRenderer.kt`, `SimpleQuadRenderer.kt`.
