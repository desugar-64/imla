# Scene2 Root Clean-Plate Reuse Design

Date: 2026-06-05

## Goal

Reduce low-end scene2 bad-window pressure by skipping one expensive root
clean-plate capture when root capture has already proven slow, while still
submitting and rendering the next scene frame.

This is a diagnostic-first design. The first implementation must be off by
default and enabled only by log tag. Production behavior can be considered only
after screenshots show no visual junk and traces show useful pressure relief.

## Context

Recent T811 benchmark traces show the remaining bad window is below slot
content capture:

- Plain-slot good window: frame avg `17.272 ms`, root
  `drawWait[1200x1920]` avg `5.579 ms`, app `RenderThread` `dequeueBuffer`
  avg `0.592 ms`.
- Root-only bad window: frame avg `25.653 ms`, root
  `drawWait[1200x1920]` avg `10.588 ms`, app `RenderThread`
  `dequeueBuffer` avg `3.463 ms`.

The retained stable slot reuse work should not be tuned further in this slice.
The next pressure source to isolate is the coupling between synchronous root
capture and Android display queue pressure.

## Policy

Normal rendering captures the root every scene frame.

When the diagnostic policy is enabled:

1. Track root capture wait against the display frame budget.
2. Count a root capture as slow when it exceeds `0.5 * frameBudget`.
3. After two consecutive slow root captures, skip only the next root capture.
4. Still assemble and submit a scene snapshot for that frame.
5. The snapshot requests previous-root reuse instead of carrying a fresh root
   frame.
6. After one reuse frame, attempt fresh root capture again.

The maximum allowed root staleness is one frame. Reuse is allowed during touch,
passive animation, root-only scenes, slot scenes, and backdrop blur scenes.

For a production candidate, use the same mechanism but move the slow threshold
toward `0.66 * frameBudget` before accepting normal behavior.

## Ownership

The main thread decides whether to request fresh root capture or previous-root
reuse. It must not own or reference GL texture ids.

The GL thread owns the previous imported root texture. A snapshot should model
root input conceptually as:

```text
FreshRoot(capturedFrame)
ReusePreviousRoot
```

The exact Kotlin API can differ, but the ownership boundary must stay the same:
main thread owns capture policy and closeable captured frames; GL owns imported
textures.

## Fallback And Reset

The policy resets when:

- root capture is fast enough;
- root capture fails;
- no previous usable root exists;
- the GL side cannot satisfy `ReusePreviousRoot`;
- surface changes or is recreated;
- root size changes;
- renderer stops or detaches.

If reuse is requested but GL has no previous root texture, do not render black
or placeholder content. Drop/reset that frame, or capture fresh root if still
before the capture decision point.

## Visual Requirements

Visual correctness wins over trace improvement. Reject or redesign the policy
if screenshots show:

- black frames;
- wrong crop;
- Y-flip;
- stretch;
- sudden root jump;
- obvious stale-frame mismatch;
- alpha or color flash;
- mask or clip misalignment;
- backdrop blur halos or edge darkening caused by reuse.

Required diagnostic screenshots:

- root-only;
- plain slot;
- backdrop blur;
- a moving or drag-like case if available;
- high-contrast checkerboard background.

## Verification

Stage 1, diagnostic flag only:

- Enable the diagnostic root reuse log tag.
- Use `0.5 * frameBudget`.
- Verify the policy path triggers: fresh, fresh, reuse previous root once,
  then fresh again.
- Capture screenshots before trusting traces.
- Capture benchmark traces on `T81164GB23417442888` using the speed-compiled
  benchmark package.

Compare:

- frame avg and max;
- root `GraphicsLayerCapture#drawWait[1200x1920]`;
- `SceneRootCapture#Deferred`;
- app `RenderThread` `dequeueBuffer`;
- final present;
- Imla GL render time;
- GPU completion and HWC release waits.

Stage 2, production candidate:

- Move the threshold toward `0.66 * frameBudget`.
- Keep two consecutive slow captures and one reuse frame maximum.
- Enable normal behavior only if Stage 1 proves no visual junk and useful
  pressure relief.

## Non-Goals

- Do not change production renderer behavior in the diagnostic slice.
- Do not re-enable root-only coalescing.
- Do not tune stable slot reuse or pressure dirty-tick coalescing.
- Do not skip the whole scene frame when only root capture should be reused.
- Do not pass renderer or GL objects into child slots.
- Do not restore removed Renderer 2 APIs or docs as architecture sources.
