# Per-Layer Source / Present Design

Date: 2026-08-17

## Goal

Stop treating the whole window as one GL present. Draw most UI through
Compose/HWUI. Only nodes that declare effects capture a **layer-sized**
backdrop crop and content buffer, run GL, and `drawImage` that result.

This replaces the group-sized hardware-bitmap present slice, which removed
`SurfaceView` but still rasterized `fillMaxSize()` `effectGroup()` into a
2560×1600 buffer on the Xiaomi Pad (~14 fps Social Feed). `wrapHardwareBuffer`
was ~0.1 ms; the cost was full-window capture plus GPU completion of that
buffer.

## Public API

- `Modifier.effectSource()` replaces `Modifier.effectGroup()`. It marks
  backdrop content. It does **not** present through GL.
- `Modifier.effectLayer { … }` is unchanged as a DSL. It is the capture +
  present unit (one hardware bitmap per layer).
- `ImlaHost` still owns `SceneRenderer`, the registry, and the metrics overlay.
  It still has no output view.

Call-site shape (Social Feed, matching the outlined chrome):

```kotlin
ImlaHost {
    Box(Modifier.fillMaxSize()) {
        Feed(Modifier.fillMaxSize().effectSource())
        TopBar(Modifier.align(Alignment.TopCenter).effectLayer { … })
        BottomNav(Modifier.align(Alignment.BottomCenter).effectLayer { … })
        Fab(Modifier.align(Alignment.BottomEnd).effectLayer { … })
    }
}
```

`effectLayer` must be a **sibling** of the source, not a descendant of the
recorded source layer. Presenting the effect during source `record` recaptures
blur into the next backdrop (the RenderEffectX constraint).

## Responsibilities

**`effectSource`**

- Record the content subtree into a `GraphicsLayer` and `drawLayer` it (HWUI).
- Register that layer + coordinates with the host registry as the nearest
  backdrop source.
- Do not attach a group-sized offscreen present target.
- Do not `HardwareRenderer` the full source into a screen-sized
  `HardwareBuffer`.

**`effectLayer`**

- Bind to the nearest `effectSource` in the same `ImlaHost`.
- Capture backdrop: rasterize only this layer’s bounds in source space, plus
  blur padding, from the source `GraphicsLayer`.
- Capture content: existing slot `GraphicsLayer` (icons, labels).
- GL: existing blur / tint / noise / clip on a **layer-sized** FBO.
- Present: `Bitmap.wrapHardwareBuffer` + `drawImage` in this modifier (Y-flip
  once if the FBO is bottom-left). Recycle the previous wrapper immediately.
  Keep a 2-deep output ring per layer so GL does not write a buffer HWUI is
  sampling.
- `presentingOutput` must not kick another capture.

**API 23–28**

Passthrough: source `drawLayer`, layers `drawLayer` / `drawContent()`, no GL.

## Data flow

```text
Compose draw
  effectSource: record GraphicsLayer → drawLayer (feed, HWUI)
  effectLayer:  drawImage latest layer bitmap (or drawLayer until first result)

Capture (per dirty layer, not full window)
  crop source layer → small HardwareBuffer (backdrop)
  slot GraphicsLayer → small HardwareBuffer (content)

GL
  import crops → blur/composite into layer-sized SceneHwBuffer → fence
  wrapHardwareBuffer → main thread invalidateDraw on that layer
```

Scroll updates crop geometry. Source-layer dirty recaptures backdrop crops.
Slot content recapture stays tied to the layer’s own recorded `GraphicsLayer`.

## Non-goals

- Atlas packing of layer backdrops (later).
- Compute blur, raising `minSdk`, a pre-29 GL present path.
- Putting `effectLayer` back inside `effectSource`.
- Reintroducing a host `SurfaceView`.

## Verification

- `./gradlew -q compileDebugKotlin`
- Screenshot diffs: Showcase, Social Feed, Resize Card — Y-flip, shift,
  mask/clip alignment, stale frames.
- Pad overlay / present logs: targets are layer-sized, not 2560×1600.
- Social Feed fps vs the current ~14 on the Xiaomi Pad (relative, benchmark
  variant + `speed` compile).
- Sibling chrome still participates in HWUI z-order (already true without
  SurfaceView).

## Why not keep a group-sized present

The hardware-bitmap slice was the right present *seam* (Compose `drawImage`,
no SurfaceView) and the wrong *unit* (the whole source). Distinct glass
pieces (top bar, nav pill, FAB) should each own a bitmap. The feed stays in
HWUI.
