# Per-Layer Source / Present — Implementation Plan

Date: 2026-08-17

Based on `docs/plans/2026-08-17-per-layer-source-present-design.md`.

## Slices

1. **Rename and demote the group.** `effectGroup()` → `effectSource()` (deprecate
   or delete the old name). Source `draw()` is `record` + `drawLayer` only. Delete
   group-sized `attachOffscreenTarget` / `drawImage` / `presentedImage` on the
   source. Pre-29 stays passthrough.

2. **Sibling demos.** Move Social Feed / Showcase / other `fillMaxSize().effectGroup()`
   chrome out of the source: feed is `effectSource()`, top bar / nav / FAB are
   sibling `effectLayer`s. Resize Card already has siblings; put the card layer
   beside a source, not inside it.

3. **Per-layer backdrop crop.** From the registered source `GraphicsLayer`, capture
   only the layer’s source-space bounds + blur pad into a small HardwareBuffer
   (reuse `GraphicsLayerCapture` clip/offset if present; otherwise add a crop
   rect on the capture request). Do not rasterize the full source.

4. **Per-layer GL + present.** Each layer gets a layer-sized offscreen target and
   `HardwareBitmapPresenter` (or a shared presenter keyed by slot). `effectLayer`
   `drawImage`s its bitmap (Y-flip). Content capture stays the slot GraphicsLayer.
   `presentingOutput` on the layer, not the source.

5. **Nearest-source registry.** Source registers layer + coordinates on attach;
   layers bind to the nearest source in the same host. Missing source: draw
   content, no effects.

6. **Verify.** `compileDebugKotlin`. Screenshot diffs. Pad: present size is layer
   bounds, Social Feed fps vs ~14 baseline.

## Non-goals (same as design)

Atlas, compute blur, minSdk 29, pre-29 GL present, layers as source descendants.
