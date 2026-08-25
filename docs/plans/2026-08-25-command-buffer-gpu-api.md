# Command-buffer GPU API (paper design)

Status: design only. No encoder lands in this PR.

Scope: one `SceneGlOwner.draw()` kick on the GL thread through hardware-bitmap present.
Outside scope: Compose capture, main-thread snapshot assembly, HWUI `drawImage` present.

Caller: `SceneRenderPipeline` is the single encoder caller. `SceneGlOwner.draw()` drains
mailbox requests, imports captures into `GlStore`, then calls `renderPipeline.render(frame)`
once per installed slot frame.

---

## Live GLES order (one slot, blur + clip)

Numbers are stable pass order; repeated substeps (blur H/V, clip write/draw) stay in this
sequence.

### A. Per-request import (`consumeRequest` → `buildGlFrame`, still on GL thread)

1. **Hardware-buffer sync** — `BufferLease.awaitReady()` (Android sync fence, not GL).
2. **EGLImage import** (per distinct buffer, cached) — `eglCreateImageFromHardwareBuffer`,
   `glGenTextures`, `glBindTexture(GL_TEXTURE_2D)`, `glEGLImageTargetTexture2DOES`.
3. **Copy into importer-owned texture** (per root / content / mask / clip frame):
   - Fast path: `glCopyImageSubData` (source EGLImage-backed `GL_TEXTURE_2D` → transfer FBO
     color attachment).
   - Fallback: `glBindFramebuffer(GL_DRAW_FRAMEBUFFER)`, `glClear`, textured quad draw via
     `SimpleQuadRenderer` (bind program, viewport, `glDrawElements`).

No scene FBO is bound at end of import; next pass owns bind state.

### B. `SceneRenderPipeline.render(frame)` — acquire

4. **Scene HW buffer** (ring slot; create once per buffer size) — `HardwareBuffer.create`,
   EGLImage import + `glGenFramebuffers`, `glFramebufferTexture2D` (color),
   `glGenRenderbuffers` + `glRenderbufferStorage(DEPTH24_STENCIL8)` +
   `glFramebufferRenderbuffer` (stencil). Per frame: `glBindFramebuffer(scene FBO)`.

### C. Prepare (`prepareFrame`)

5. **Noise FBO** (only if slot backdrop requests noise, once per root size) — allocate RGBA/R8
   FBO if needed, `bindForOverwrite`, `glViewport`, procedural noise quad draw.

### D. Root overlay (`SceneRootDrawPass.beginOverlay`)

6. `glBindFramebuffer(scene FBO)` (via `SceneHwBuffer.bindForDraw`).
7. `glClearColor` + `glClear(COLOR)` — transparent.
8. `glViewport(root size)`.

### E. Slot draw (`renderFrameSlot`)

**E1. Stencil clip** (when `requiresClip`; skip whole slot if clip texture not imported yet)

9. `glBindFramebuffer(scene FBO)`, `glViewport`.
10. `glDisable(GL_STENCIL_TEST)`, `glClearStencil`, `glStencilMask(0xFF)`,
    `glClear(STENCIL_BUFFER_BIT)`.
11. Clip mask write: `glColorMask(false×4)`, `glEnable(STENCIL_TEST)`, `glStencilFunc(ALWAYS)`,
    `glStencilOp(REPLACE)`, textured quad draw → stencil = 1 where clip alpha > 0.
12. `glColorMask(true×4)`, `glStencilFunc(EQUAL, 1)`, `glStencilOp(KEEP)`, `glStencilMask(0)`.

**E2. Backdrop prepare** (when slot has blur backdrop)

13. Acquire pooled downsample FBO, `bindForOverwrite`, `glViewport(content rect)`.
14. Prefilter quad draw sampling root `BackdropInput` texture (shader sets sample rect / flip).

**E3. Backdrop blur** (horizontal then vertical)

15. Acquire horizontal blur FBO, `bindForOverwrite`, `glViewport`, separable blur quad draw.
16. Acquire final blur FBO, `bindForOverwrite`, `glViewport`, vertical blur quad draw.
17. Release horizontal blur borrow.

**E4. Backdrop composite** (when blur prepared)

18. `glBindFramebuffer(scene FBO)`, `glViewport(root)`.
19. Textured quad draw with blending — blurred backdrop + optional crisp root + progressive mask
    + optional noise texture.

**E5. Slot content**

20. `glBindFramebuffer(scene FBO)`, `glViewport(root)`.
21. Textured quad draw with blending — slot content texture with geometry transform / partial UV.

**E6. Stencil teardown** (clip path)

22. `glDisable(GL_STENCIL_TEST)` (in `finally`).

**E7. Backdrop borrow release**

23. Return pooled backdrop FBOs to `FramebufferLendingPool` (no GL; pool may reuse next frame).

### F. Present (`HardwareBitmapPresenter.present`)

24. **GL flush implied** — scene color lives in EGLImage-backed texture attached to scene FBO;
    no explicit `glFinish` in present path today.
25. **Native sync fence** — `SyncFenceCompat.createNativeSyncFence()` + `await` (200 ms cap).
    Not `glFenceSync`; bridges GPU completion before CPU reads the `HardwareBuffer`.
26. **HWUI hand-off** — post `(SceneSlotId, ImageBitmap)` to main; slot modifier `drawImage`
    composites (outside encoder).

Quad draws throughout share the same micro-sequence: bind shader program, bind VAO/VBO,
`glActiveTexture` + `glBindTexture`, set uniforms, `glEnable/disable BLEND`, `glDrawElements`.

---

## Same frame as WebGPU (sketch)

`SceneRenderPipeline` would own one `GPUCommandEncoder` per `render(frame)` call.

```text
encoder = device.createCommandEncoder()

// Import side channel (not in encoder today — see gaps below)
// … EGLImage / copyImageSubData already produced GlStore textures …

sceneView = acquireSceneHwTextureView(slotId, rootSize)   // ring buffer view

pass = encoder.beginRenderPass({
  colorAttachments: [{ view: sceneView, loadOp: 'clear', storeOp: 'store', clearValue: transparent }],
  stencilLoadOp: 'clear', stencilStoreOp: 'store'      // when clip
})

// Noise (optional): separate render pass to R8 texture, or compute noise fill
if (needsNoise) { … renderPass to noiseTexture … }

// Stencil clip write (optional)
if (requiresClip) {
  pass.setStencilReference(1)
  pass.setStencilState(writeMask)   // replace on clip quad
  pass.draw(clipMaskBindGroup)
  pass.setStencilState(testEqual)    // equal 1, keep
}

// Backdrop offscreen passes (not the scene pass)
prepPass = encoder.beginRenderPass({ colorAttachments: [backdropPrepView, loadOp:'clear'] })
prepPass.draw(backdropPrefilterPipeline, backdropBindGroup)
prepPass.end()

blurHPass = encoder.beginRenderPass({ colorAttachments: [blurHView] })
blurHPass.draw(blurHorizontalPipeline, blurBindGroup)
blurHPass.end()

blurVPass = encoder.beginRenderPass({ colorAttachments: [blurVView] })
blurVPass.draw(blurVerticalPipeline, blurBindGroup)
blurVPass.end()

// Composite into scene pass (resume or new beginRenderPass on sceneView)
pass.setPipeline(backdropCompositePipeline)
pass.setBindGroup(0, backdropCompositeBG)  // blur + crisp + mask + noise
pass.draw(6)

pass.setPipeline(contentPipeline)
pass.setBindGroup(0, contentBG)
pass.draw(6)

pass.end()

// Present: copy scene texture → mappable buffer OR export native handle for HWUI
encoder.copyTextureToBuffer(sceneView, presentBuffer, …)
// queue.submit + native fence (Android sync fence equivalent)

encoder.finish()
device.queue.submit([commandBuffer])
```

Capture, Compose layout, and `drawImage` stay on the CPU/main thread as today.

---

## Live steps with no WebGPU equivalent

| Step | Why |
|------|-----|
| `eglCreateImageFromHardwareBuffer` + `glEGLImageTargetTexture2DOES` | Android EGLImage interop from `HardwareBuffer`; WebGPU has no EGLImage import. Would need `AHardwareBuffer` → Vulkan external memory → `GPUExternalTexture` (platform extension), not core WebGPU. |
| `glCopyImageSubData` from EGLImage-backed texture | GLES 3.1 copy between textures; WebGPU uses `copyTextureToTexture` but source must already be a `GPUTexture` with compatible format and import path. |
| `SyncFenceCompat` / HWUI `HardwareBuffer` present | CPU–GPU completion + Android bitmap wrapper for Compose; WebGPU would use `mapAsync` on a buffer or an OS-specific surface/swapchain, not `ImageBitmap`. |
| `Bitmap.wrapHardwareBuffer` + `asImageBitmap` | Android UI bridge; not a GPU API. |
| Pooled GLES FBO `FramebufferLendingPool` with stencil RBO | Maps to transient render pass attachments + depth/stencil texture in WebGPU, but pool policy is GLES-specific. |

---

## Two paper encoder shapes (compare only)

### Shape 1 — Pass-centric (matches today's call order)

One encoder, many `beginRenderPass`/`end` blocks mirroring each FBO bind today:
root scene pass, optional noise pass, backdrop prep pass, blur H/V passes, return to scene
pass for composite + content.

- **Pros:** Minimal reordering from current `SceneRenderPipeline` + passes; easy to diff
  against the GLES list above; stencil clip stays localized.
- **Cons:** More render pass boundaries than necessary; blur chain pays load/store between
  passes; harder to batch barriers on tile-based GPUs.

### Shape 2 — Resource-state table (fewer passes)

Encoder records texture barriers and draws; a small planner collapses compatible draws into
one render pass where attachments match (scene FBO for composite + content; offscreen textures
for blur chain as non-resolve inputs only).

- **Pros:** Fewer pass splits; closer to modern API style; blur intermediates can stay in
  `TEXTURE_READ_ONLY` without an extra `end`/`begin` if the planner keeps one scene pass.
- **Cons:** Needs explicit state tracking (imported HB textures, pool borrows, stencil); bigger
  first refactor; stencil clip still forces depth/stencil attachment on scene pass.

**Recommendation for a future implementation PR:** start with Shape 1 (pass-centric) because it
maps 1:1 to existing `Scene*Pass` classes and keeps the GLES → WebGPU migration auditable.
Consider Shape 2 only after parity screenshots and Perfetto counters match, when pass count
shows up in traces.

---

## Non-goals (this design PR)

- No throwaway `CommandEncoder` Kotlin type in production code.
- No change to capture Path A/B, minSdk, or HWUI consumer-release fence.
- No merge of import (`CapturedFrameImporter`) into the draw encoder until Android import
  extensions are chosen explicitly.
