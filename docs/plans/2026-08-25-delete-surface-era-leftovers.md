# Delete surface-era leftovers from the bitmap renderer

Date: 2026-08-25

Status: ready for a fresh implementation session. This file is the handoff.
Do not re-derive the architecture from scratch. Read this, then delete in
the order below. A later investigation (wave 5) covers replacing the
immediate-mode GLES bean stack with a command-buffer shaped API, aimed at
a future WebGPU backend. Do not start wave 5 until waves 1–3 have shrunk
the tree.

Prior conversation (architecture investigation, read-only): Cursor chat
“Rendering architecture simplification”, id `0f734693-1722-4b81-a114-5771510db63f`.

Branch at investigation time: `proto/sync-blocking-present`.

Based on the per-layer bitmap present already landed in
[`docs/plans/2026-08-17-per-layer-source-present-design.md`](2026-08-17-per-layer-source-present-design.md).

## Why we pursue this

Host `SurfaceView` is already gone. Glass already reaches the screen as a
per-layer hardware `Bitmap` drawn by `Modifier.effectLayer`. The public API
already matches that design (`ImlaHost`, `effectSource()`, `effectLayer { }`).

What is left is the previous present model still sitting in types, names, and
unused files:

- A second blur/composite engine that blitted to the default framebuffer.
- A full `SurfaceTexture` capture stack that never runs, because API 23–28 is
  passthrough and API 29+ uses `HardwareBuffer`.
- A `ScenePresenter` interface extracted for blit vs hardware present. Only
  `HardwareBitmapPresenter` remains.
- GL types that still describe an N-slot scene compositor, while
  `assembleSnapshot` always builds `listOf(oneSlot)` and present posts
  `slots.firstOrNull()`.
- A 64×64 `GLRenderer` target named like a present surface. It is only a
  GL-thread pump. Pixels go into `SceneHwBuffer`.

We pursue two things, in order.

First, deletion so the code matches the design that is already on screen.
No new present backend. No thinner feature set. Waves 1–3.

Second, after that tree is small, investigate a command-buffer shaped GPU
API. The live GLES layer is still a playground 2D engine
(`desugar-64/android-opengl-renderer` in the README) wrapped as Java
objects. `RendererApi`, `RenderCommand`, `GraphicsContext`, `Renderer2D`,
`QuadBatchRenderer`, `SimpleRenderer`, `ShaderLibrary`, `ShaderBinder`,
and a `*Pass` class per stage. Modern engines (Vulkan, WebGPU, wgpu)
record into a command encoder and submit a buffer. We expect to move the
GPU backend toward WebGPU later. Matching that recording model now is the
point of wave 5, not wrapping GLES in another `GraphicsContext`.

If we skip the deletion, every later change (atlas, compute blur, capture
pacing, a command encoder) has to keep a one-slot runtime invariant that
the types do not enforce, and has to guess which blur file is live. A
command buffer recorded against two engines is worse than beans.

## What we are not doing

- Do not resurrect `SurfaceView`, `AndroidExternalSurface`, SurfaceControl, or
  `BlitPresenter`.
- Do not invent a new GL owner as a SurfaceView replacement, and do not
  rewrite `SceneGlOwner` in waves 1–4. A later command encoder that
  `SceneGlOwner` records into is wave 5, after leftovers are gone.
- Do not delete live glass. The live path is
  `SceneBackdropEffectPasses.kt` (prepare, separable blur, composite), plus
  `SceneNoisePass`, stencil clip, and slot content. Public DSL stays.
- Do not collapse Path A (OnPreDraw/OnDraw sync capture) and Path B
  (`postOnAnimation` / out-of-window kick) in wave one. Overlay windows and
  late slot content are why Path B exists.
- Do not replace the 64×64 `createRenderTarget` pump with `GLRenderer.execute`
  until the leftover files are gone, and then only with a screenshot plus a
  Perfetto check that the GL context is current.
- Do not merge `SingleBufferRenderer` and `CanvasBufferedRenderer`. They share
  HardwareRenderer under the hood. Their backpressure and vsync contracts
  differ.
- Do not add an HWUI consumer-release fence as part of this refactor. The
  2-buffer `SceneHwBufferRing` is an intentional producer-side assumption.
  Treat a missing consumer ack as a later correctness goal if a device repro
  exists.

## Current product path (keep this)

On API 29+:

1. `effectSource` records a `GraphicsLayer` and `drawLayer`s it through HWUI.
2. OnPreDraw `beginSyncPresent` submits a per-layer backdrop crop
   (`GraphicsLayerCapture.submit` → `HardwareRenderer` → `ImageReader`) and
   kicks slot-content captures without waiting for them.
3. OnDraw `completeSyncPresent` waits up to 100 ms per job for that capture,
   then `assembleSnapshot` → `SceneFrameRequest` → GL.
4. GL imports the lease (`CapturedFrameImporter` copies into owned textures,
   then `HardwareBufferReleaseQueue` returns the buffer), runs
   `SceneRenderPipeline` into a `SceneHwBuffer` FBO, fences, and posts the
   already-wrapped `ImageBitmap` to main.
5. `effectLayer` `drawImage`s that bitmap with a Y-flip (`CoordinateOrigin`
   bottom-left FBO vs HWUI top-left).

Threads that stay: Compose main, `ImlaSceneCapture`, `ImlaSceneGL`.

API 23–28 today: source and layers draw recorded content. No GL effects.
`ensureGlTarget()` returns immediately. Capture observers are not subscribed.
`SurfaceTexture` code compiles and never runs.

Live wiring (do not delete):

- `SceneGlOwner.createBackdropPass()` → `SceneBackdropPreparePass`,
  `SceneBackdropBlurPass`, `SceneBackdropCompositePass`
- `SceneRenderPipeline` → noise, overlay clear, backdrop, clip, content,
  `HardwareBitmapPresenter.present`
- Capture → `SingleBufferRenderer` (GraphicsLayer) and `CanvasBufferedRenderer`
  (mask/clip `RenderNode`s)
- Present → `SceneHwBuffer` / `SceneHwBufferRing` / `drawPresentedImage`

`QuadBatchRenderer` and `SimpleQuadRenderer` stay through waves 1–4. Live
passes draw through them. Wave 5 may replace that immediate batcher with
recorded draws. Do not replace it while the dead `EffectPipeline` still
exists.

## Fear this is not

Deleting `processing/effects`, `PreProcessFilter`,
`BackdropCompositeShaderEffect`, `PostBlendEffect`, and `PostProcessingEffect`
does not strip Imla down to a blit with no blur.

Those types are a second, unused engine. Nothing in production constructs
`EffectPipeline` or `GaussianBlurEffect`. `PostBlendEffect.blendToDefaultBuffer()`
blits to the default framebuffer. That is the old window present. Live blur
does not call it.

`PostProcessingEffect.kt` is `@file:Suppress("unused")`.

If a helper in `processing/` is still imported by `SceneBackdropEffectPasses`
or `SceneRenderPipeline`, it is not on the delete list. Check imports
file-by-file. At investigation time, `BackdropCompositeSamplingOrigins` was
only used by the dead `BackdropCompositeShaderEffect` and its unit test.
Confirm with grep before deleting.

## Why the leftover exists

`dd5aeb3` extracted `ScenePresenter` for blit vs hardware-buffer present.
Later commits removed blit and SurfaceControl. `156d74b` made each
`effectLayer` a hardware bitmap. `ImlaHost` no longer has an output view.

The rewrite landed beside the old engine instead of deleting it.
`SceneGlOwner.SceneRenderCallback` still comments “blit pass or HW hand-off.”

`hasPresentTarget` still means “64×64 EGL pump attached,” not “we have a
window.” `SceneGlOwner.draw(targetSize)` ignores that size and draws at the
captured layer size.

## Verdict from critique (what to do vs later)

Act on in this refactor:

1. Dead processing tree.
2. Unreachable `SurfaceTexture` types. minSdk may stay 23.
3. One-impl present leftovers (`ScenePresenter`, `Backing.Plain`, dead fields).
4. Make GL request types match the one-slot mailbox.

Consider later, not in wave one:

- 2-buffer ring has no HWUI release fence (`HardwareBitmapPresenter.release()`
  is a no-op). Real hazard. Needs a device repro, not a drive-by protocol.
- Mask/clip `drawAsync` then `await` on main during snapshot assembly.
- Path A `submit()`s every slot crop without `inFlightBackdropIds`. Path B
  serializes because concurrent `GraphicsLayer` reads can blank the feed.
  Verify on a multi-layer demo before “unifying.”
- After the first bitmap, `SceneSlotNode.draw` skips `recordContentLayer()`.
  Backdrop behind the glass still moves. Children inside the layer freeze,
  while PreDraw still captures that frozen display list. Product call.
- Lazy GL/capture threads on API 23–28 if minSdk stays 23.
- `GLRenderer.execute` instead of the dummy `RenderTarget`.
- Command-buffer / WebGPU-shaped GPU API (wave 5). Investigate after the
  unused engine and dual capture types are gone.

Dismissed:

- Merging the two HardwareBuffer producers.
- A new GL owner in the leftover-deletion session (wave 5 is the GPU API
  investigation, not a second `SceneGlOwner`).
- A second `ScenePresenter`.
- Treating Path A/B as SurfaceView leftovers to delete in wave one.
- Requiring a minSdk bump before deleting `SurfaceTexture`.

## Wave 1. Dead engine

Proof: `./gradlew -q :imla:build` and JVM tests. No screenshot required if
grep shows zero production callers after the cut.

### Delete if unused (confirm with grep)

Production types with no callers outside their own package at investigation
time:

- `internal/render/processing/effects/EffectPipeline.kt`
- `GaussianBlurEffect.kt`, `GaussianKernel.kt`,
  `GaussianBlurKernelShaderProgram.kt`, `PreProcessEffect.kt`,
  `EffectContext.kt`, `Effect.kt`, `SizedFramebuffer.kt`
- `internal/render/processing/preprocess/PreProcessFilter.kt`
- `internal/render/processing/composite/BackdropCompositeShaderEffect.kt`
- `internal/render/processing/blend/PostBlendEffect.kt`
- `internal/render/processing/PostProcessingEffect.kt`
- `internal/render/processing/SinglePassQuadExecutor.kt`
- `internal/render/framebuffer/FramebufferPool.kt` (distinct from live
  `FramebufferLendingPool`)
- `internal/render/camera/OrthographicCameraController.kt`

Tests that only exist for that stack:

- `PreProcessGeometryTest.kt`
- `GaussianKernelTest.kt`
- `BackdropCompositeSamplingOriginsTest.kt` (only if the origins type goes)

Benchmark strings that name `GaussianBlurEffect*` in
`benchmark/.../ImlaBenchmark.kt`. Update or drop those names so they do not
look like a live pass.

Keep `FramebufferLendingPool`. Live backdrop passes use it.

Keep `QuadBatchRenderer`, `SimpleQuadRenderer`, `Renderer2D`, shaders used by
`SceneBackdrop*` and clip/noise, until wave 5.

### Reason

Two blur homes. The next sigma or mask change will land in the wrong file.
Benchmarks will look dead while the live pass moves. The blit-to-default-FBO
code encodes a present model we already rejected.

## Wave 2. Stop plumbing SurfaceTexture on the live path, then delete it

Proof: same compile bar, then a device screenshot of Showcase or Social Feed
if you also delete the types (Y-flip, mask/clip, blur still correct).

### Wiring first (even if minSdk stays 23)

`SceneRenderer` still passes `glOwner::createCanvasTextureCapture` into
backdrop, slot, mask, and clip captures. On API 29+,
`SceneCanvasFrameRenderer.create()` sets `textureCapture = null` and never
calls the factory. `GraphicsLayerCapture` never calls `textureCapture()` on
API 29+.

Stop passing the factory on the live path. Make the constructor parameter
absent or unused-deleted.

### Then delete the unreachable backend

- `SceneSurfaceTextureCapture.kt`
- `SurfaceTextureRenderer.kt`
- `CanvasTextureCapture.kt` / `CanvasTextureCaptureFactory` if nothing else
  implements them
- `CapturedTextureFrame` and the `importCapturedFrame` texture branch
- `GraphicsLayerCapture.kickTextureCapture`, `captureToTexture`,
  `textureCapture()`
- `simple_ext_quad` shader (only `SurfaceTextureRenderer` loaded it)
- `TEXTURE_EXTERNAL_OES` handling if nothing else needs it (check
  `OpenGLTexture2D` and `Renderer2D` after the cut)

API 23–28 can stay as Compose passthrough without these files.
`ImlaHost` KDoc already says that.

Optional in the same PR if minSdk stays 23: do not start `CaptureThread` and
`GLRenderer` on API < 29. Today they start eagerly and sit idle.

### Reason

Nobody on the API 29+ product path needs `SurfaceTexture`. Keeping the types
makes `CapturedLayerFrame` a lie (two backends, one never reached). Every new
capture site pays a dual-frame tax. Demo `CaptureFidelityScene` uses a
`SurfaceView` as a nested-surface probe. That is not product present. Leave
the demo unless you explicitly drop issue #13 coverage.

minSdk 23 vs 29 is a product call. It is not a prerequisite for this wave.
Raising minSdk only makes the deletion a type-system fact. If you bump, do it
as its own decision and then delete the `< 29` no-ops in modifiers.

## Wave 3. Present types that still describe a window

Proof: compile. Rename is behavior-neutral. One-slot types need a screenshot
if you change `UiSceneCapture` / pipeline signatures.

### Cleanup

- Inline `HardwareBitmapPresenter`. Delete `ScenePresenter`.
- Delete `SceneRenderBuffer.Backing.Plain` and its `when` branches. Only
  `Backing.Hw` is constructed.
- `requiresStencil` is ignored. Every HW buffer already gets a stencil RBO.
  Stop pretending acquire is configurable.
- Delete `SceneRenderer.attachedSize` (written, never read).
- Delete `SceneRenderTarget.resize()` (no callers).
- Rename `hasPresentTarget` / `ensureGlTarget` / `detachPresentTarget` /
  `SceneFrameDropReason.NoRenderTarget` to GL-session language.
- Fix the `SceneRenderCallback` comment that still mentions blit.
- Move `SceneFrameRequest` out of `internal/render/scheduler` or update the
  KDoc. There is no frame scheduler. Handoff is direct.
- Drop unused `SceneFrameDropReason` values (`Superseded`, `SourceDetached`,
  `RenderRequestInFlight`) or start recording mailbox displace as
  `Superseded`. Do not leave an enum that looks load-bearing.

### Make the mailbox honest

`assembleSnapshot` always does `val slots = listOf(remappedSlot)`.
`HardwareBitmapPresenter.present` and `SceneGlOwner.submit` key off
`slots.firstOrNull()`. `SceneRenderPipeline` still sorts “every slot” by
`drawOrder` into one buffer.

Tighten `UiSceneCapture` / `SceneGlRenderFrame` / pipeline so one request is
one slot. A second declaration must not silently composite into the first
layer’s bitmap.

`BackdropInput.accumulatedScene()` has no callers. Delete it until cumulative
blur is a real goal. Atlas is still a non-goal (see `AGENTS.md`).

### Reason

The migration stopped at the Compose `drawImage` call. GL still types a scene
surface. That is the leftover we set out to scrap, more than any filename
that contains `Surface`.

## Wave 4. Dummy EGL pump (only after 1–3)

`ensureGlTarget()` attaches `GLRenderer.createRenderTarget(64, 64)`
(`EGL_PLACEHOLDER_SIZE`). `onDrawFrame` calls `target.draw()`. Output is
still the `SceneHwBuffer` FBO.

`GLRenderer.execute` already runs teardown. Replacing `requestRender` with
`execute` can drop the dummy surface, but you must keep:

- one-in-flight coalescing (`presentScheduled`)
- detach cancellation
- an attached/session token so modifiers know GL exists
- render-complete callbacks used by metrics

Verify with `./gradlew -q compileDebugKotlin`, a smoke screenshot, and
`imla_perfetto_feedback` on the KidzPad. Do not rewrite `SceneGlOwner` to do
this. Swap the clock.

## Wave 5. Investigate a command-buffer GPU API (after 1–3)

This wave is an investigation with a written design, not a rewrite in the
leftover-deletion PRs. The product path (capture → layer bitmap →
`drawImage`) stays. Only the way GL work is expressed should change.

### Why we pursue this

The current GPU layer is immediate-mode GLES hidden behind many types.
A pass object calls `QuadBatchRenderer`, which calls `Renderer2D`, which
calls `RenderCommands`, which calls `RendererApi` / `OpenGLRendererAPI`,
which calls `glDraw*`. There is also a global `RenderCommand` singleton
the live path is not supposed to use. `GraphicsContext` is a service
locator with HDR/premium helpers that only log.

That shape is common in Java/Android “engine” ports. It is not how Vulkan
or WebGPU are used. Those APIs record commands, then submit:

- WebGPU: `GPUCommandEncoder` → `beginRenderPass` → `setPipeline` /
  `setBindGroup` / `setVertexBuffer` / `draw` → `end` → `finish` →
  `GPUQueue.submit([commandBuffer])`
- Vulkan: `vkBeginCommandBuffer` → `vkCmdBeginRenderPass` →
  `vkCmdBindPipeline` / `vkCmdBindDescriptorSets` / `vkCmdDraw` →
  `vkEndCommandBuffer` → `vkQueueSubmit`

We expect a WebGPU backend later (Dawn, wgpu-native, or whatever Android
story exists then). If Imla’s GPU work is already “encode a render pass,
bind a pipeline, bind resources, draw, submit,” swapping GLES for WebGPU
is a backend. If Imla’s GPU work is `SceneBackdropBlurPass.process()`
mutating GL state through four wrappers, the WebGPU port is a second
engine.

The entities we rarely want in that future:

- `RendererApi` + `OpenGLRendererAPI` + `RenderCommands` + `RenderCommand`
  (four types for `glClear` / `glDrawElements`)
- `GraphicsContext` as a bean bag of capabilities, pools, and binders
- `Renderer2D` (CPU sprite batcher with `beginScene` / `endScene` /
  texture slots) plus `QuadBatchRenderer` plus `SimpleRenderer` plus
  `SimpleQuadRenderer`
- `OrthographicCamera` / `OrthographicCameraController` for fullscreen
  quads
- `ShaderLibrary` + `ShaderBinder` + `ShaderProgram` +
  `QuadShaderProgram` + a `*ShaderProgram` per pass
- A Java class per pass that immediately issues GL, instead of a function
  that appends draws to an encoder
- Present/effect strategy interfaces with one implementation
  (`ScenePresenter`, `PostProcessingEffect`)

Keep the *jobs* those types do: bind a color+stencil target, draw a
textured quad, ping-pong blur FBOs, copy a HardwareBuffer into a texture.
Lose the object graph.

### What “match WebGPU earlier” means

Match the *recording model*, not the TypeScript names, and not a 1:1 GLES
wrapper branded as WebGPU.

A small honest stack to evaluate:

1. Device / queue (today: EGL context on `ImlaSceneGL` + implicit default
   queue).
2. Resources as handles: texture, buffer, sampler, pipeline (shader +
   vertex layout + render-target format), bind group (the textures and
   uniforms one draw needs).
3. Encoder: begin, set pipeline, set bind group, set viewport, draw, end.
4. Command buffer: immutable recorded work.
5. Submit on the GL (later WebGPU) thread.
6. Render pass attachments: color `SceneHwBuffer` FBO, optional stencil.
   That *is* `beginRenderPass`. Today it is `bindForDraw()` scattered
   through `SceneRenderBuffer`.

Fullscreen blur then looks like three encoded passes (prepare, H, V) plus
composite, not three `*Pass` classes that each know `SimpleQuadRenderer`.

Do not encode a WebGPU compatibility layer on top of the current beans
and call it done. That adds a fifth wrapper.

### What this wave must not swallow

Capture, Compose, and present stay outside the encoder.

- `GraphicsLayerCapture` / `SingleBufferRenderer` / `CanvasBufferedRenderer`
  are HWUI → `HardwareBuffer`. Not GPU commands.
- `HardwareBitmapPresenter` fence + `drawImage` is how pixels re-enter
  Compose. WebGPU does not replace that on Android unless we change the
  product path.
- `CapturedFrameImporter` (EGLImage → owned texture) becomes an upload /
  copy command later. Do not invent `GPUQueue.copyExternalImageToTexture`
  in the leftover-deletion waves.

Shader language is a separate decision (GLSL ES vs WGSL). Wave 5 can keep
GLSL while the *encoder* looks like WebGPU. Translating shaders is its
own project.

### How to investigate (do not skip)

1. Finish waves 1–3 so only live passes remain.
2. List every GL call the live frame makes, in order, from
   `SceneGlOwner.draw` through present. One page. No types, just
   bind / clear / draw / fence.
3. Sketch the same frame as a WebGPU command encoder (pseudocode is
   enough). If a live step has no WebGPU equivalent, name it
   (`glCopyImageSubData`, EGLImage import, `glBlitFramebuffer`).
4. Compare two prototypes on paper, then at most one throwaway encoder
   used by a single pass (noise or content blit), not a new owner for
   the whole pipeline.
5. Write the design into this folder. Only then migrate passes one at a
   time. Screenshot after each pass. Blur is precision-sensitive
   (`AGENTS.md`).

### Reason we wait until after deletion

`EffectPipeline` / `GaussianBlurEffect` / `PostBlendEffect` are a second
immediate-mode engine. Recording commands against both is how we get a
third engine. Subtract first. The encoder should have one caller:
`SceneRenderPipeline` (or its one-slot successor).

### Open questions for that investigation

- Keep `GLRenderer` as the thread+context, and only replace
  `RenderCommands`, or also drop AndroidX `GLRenderer` once WebGPU has a
  device/queue.
- Bind groups vs today’s UBO + texture-slot batching in `Renderer2D`.
  WebGPU bind groups are the thing to match. The 2D batcher is the thing
  to lose if a frame is a handful of fullscreen quads anyway.
- How `HardwareBuffer` import maps to WebGPU (external texture, copy, or
  stay GLES-only at the import edge).
- Whether compute blur (already a README “to explore”) belongs on the
  same encoder as render passes. WebGPU makes that natural. GLES 3.1
  compute is a different backend tale.

## Open decisions only a human should make

- minSdk 23 vs 29. Glass requires 29. 23–28 is passthrough. Bump only if you
  are willing to drop those installs.
- First-frame glass may lag by a Compose frame (current). Blocking main on
  the GL fence would be true sync present and a jank risk.
- Animated children inside `effectLayer` after the first bitmap. Current code
  freezes that `GraphicsLayer` on purpose so HWUI does not replay the subtree.
  Static chrome is fine. Animated in-layer content is not, unless you add an
  offscreen dirty-content record that does not `drawLayer` to the screen.
- Whether `CaptureFidelityScene` stays after `SurfaceTexture` internals go.
- Whether Path B drain is load-bearing for in-window slot content, or only
  for out-of-window slots. Trace before deleting.
- When to start wave 5. Default: after waves 1–3 land. Starting a command
  encoder while `EffectPipeline` still exists is how we keep the bean
  stack and add a recorder on top.

## Verification (when you implement)

Default compile: `./gradlew -q compileDebugKotlin`.

Library: `./gradlew -q :imla:build`.

Visual, after any wave that can change pixels (2+ if types/shaders move, 3 if
pipeline signatures change, 4 always):

- `imla_screenshot_diff` on Showcase / Social Feed / Resize Card.
- Start blur-heavy compares with `--pixel-threshold 8 --smooth-radius 1.0`.
- Look for Y-flip, shift, mask/clip alignment, stale frames, alpha.

Device: `T81164GB23417442888` (KidzPad_Pro). Relative numbers only.
Force-stop `dev.serhiiyaremych.imla` after diagnostics.
Use `tools/adb-timeout` for every ADB command.

Pacing or GL-thread changes: `imla_perfetto_feedback`, then
`imla_scene_counters` for work volume. Counters do not prove pixels.

## How to start the next session

1. Read this file and `AGENTS.md` renderer contract.
2. Stay on the bitmap pipeline. Do not reintroduce a host output view.
3. Grep before delete. The investigation dated 2026-08-25. Callers can drift.
4. Wave 1 first. Compile is the proof. Do not mix Path A/B, the EGL pump,
   or a command encoder into that PR.
5. If you are tempted to “just rewrite `SceneGlOwner`,” stop. That is the
   rejected new-owner path for waves 1–4. Wave 5 is a separate design
   pass: list live GL calls, sketch a WebGPU encoder, then one throwaway
   pass. Not a new owner on day one.

Public API stays modifier-first: `ImlaHost`, `Modifier.effectSource()`,
`Modifier.effectLayer { ... }`. Do not pass renderer or GL objects into
child slots.

## Key files (map for the next reader)

Live (keep):

- `imla/src/main/java/dev/serhiiyaremych/imla/ImlaHost.kt`
- `EffectLayer.kt`
- `internal/modifier/SceneSourceModifier.kt`, `SceneSlotModifier.kt`
- `internal/render/SceneRenderer.kt`
- `internal/capture/GraphicsLayerCapture.kt`, `SingleBufferRenderer.kt`
- `internal/layer/resources/SceneCanvasFrameRenderer.kt`
- `internal/render/gl/SceneGlOwner.kt`, `CapturedFrameImporter.kt`
- `internal/render/gl/SceneBackdropEffectPasses.kt`
- `internal/render/gl/pipeline/SceneRenderPipeline.kt`, `ScenePresenter.kt`,
  `SceneHwBuffer.kt`, `SceneRenderBuffer.kt`
- Immediate GLES beans (keep through wave 4, candidates for wave 5):
  `RendererApi.kt`, `OpenGLRendererAPI.kt`, `RenderCommands.kt`,
  `RenderCommand.kt`, `GraphicsContext.kt`, `Renderer2D.kt`,
  `SimpleRenderer.kt`, `QuadBatchRenderer.kt`

Dead or leftover (waves 1–3):

- `internal/render/processing/effects/*`
- `internal/render/processing/blend/PostBlendEffect.kt`
- `internal/render/processing/composite/BackdropCompositeShaderEffect.kt`
- `internal/render/gl/SceneSurfaceTextureCapture.kt`
- `internal/render/gl/SurfaceTextureRenderer.kt`
- `SceneRenderBuffer.Backing.Plain`
- `EGL_PLACEHOLDER_SIZE` / `hasPresentTarget` naming (wave 3–4)

## Related history

- `dd5aeb3` extract `ScenePresenter` for blit vs HW present
- `8db4f23` remove legacy `BlurSurfaceHost` / old renderer graph
- `156d74b` present each effect layer as a hardware bitmap
- Later `proto/sync-blocking-present` commits: OnPreDraw capture, GL-thread
  present, bitmap wrap-once per ring slot
