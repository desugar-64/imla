# Compose Draw vs View Draw and Pre-Draw Hook Notes

## Compose Draw vs View Draw
- Compose draw is state-tracked. A `DrawModifierNode` only re-draws when it observes state that
  changes. This is wired via `snapshotObserver.observeReads(...)` in
  `jetpack-compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/NodeCoordinator.kt`.
- Compose caches display lists. `GraphicsLayerOwnerLayer.updateDisplayList()` records into a
  `GraphicsLayer` only when `isDirty` is true; `isDirty` is set by explicit invalidation.
  See `jetpack-compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/GraphicsLayerOwnerLayer.android.kt`.
- View draw is invalidate-driven at the view level. A single `View.invalidate()` typically
  triggers a root draw, and `ViewTreeObserver.OnDraw` fires for that draw pass.

## Why Blur Can Go Stale
- Async content (for example `AsyncImage`) can update its own node and trigger a draw for itself,
  but the blur node might not have observed that state. Without a direct state read or explicit
  invalidation, the blur node draw may be skipped, leaving its captured content stale.

## How Haze Helps (Pattern to Copy)
- Haze uses a pre-draw listener bridge:
  - The source node schedules a `withFrameNanos { ... }` callback before draw.
  - The effect node registers an `OnPreDrawListener(::invalidateDraw)` with each area.
  - When the source draws, it triggers pre-draw listeners, which invalidate effect nodes.
- Key references:
  - `HazeSourceNode.launchPreDraw()` in
    `haze/src/commonMain/kotlin/dev/chrisbanes/haze/HazeSourceNode.kt`.
  - `HazeEffectNode.shouldUsePreDrawListener()` and the pre-draw listener registration in
    `haze/src/commonMain/kotlin/dev/chrisbanes/haze/HazeEffectNode.kt`.

## How a Pre-Draw Hook Helps
- A pre-draw hook fires when the source is about to draw. If async content changed, the source
  draw will happen; the hook can then invalidate blur nodes so they re-capture content in the
  same frame.
- This gives a controlled invalidation path without a constant redraw loop.
- It can be combined with throttling (for example, only re-capture if a quiet period elapsed)
  to avoid tap ripple jank while still keeping async content fresh.
