---
name: imla_scene_counters
description: Read Imla scene renderer counters from repo-local Perfetto analysis. Use when evaluating render reasons, root captures, slot content captures, mask/clip captures, geometry refreshes, scene renders, or whether an interaction is content-capture-heavy versus geometry-only.
---

# Imla Scene Counters

Use this skill after capturing an Imla trace with `tools/imla-perfetto-feedback`.

## Counter Output

The primary generated file is:

```bash
diagnostics/apa/traces/<trace>.perfetto-trace_analysis/60_scene_counters.txt
```

If it is empty, either the trace did not include scene-counter events or the app did not execute the scene path during the captured window.

## Important Counters

- `ImlaScene/root.capture.commit`: committed root clean plate captures.
- `ImlaScene/frame.commit.total`: committed scene frames.
- `ImlaScene/geometry.refresh`: geometry-only scene refresh attempts that produced a frame.
- `ImlaScene/render.request.*`: render requests by reason.
- `ImlaScene/render.consume.*`: reasons consumed by actual GL renders.
- `ImlaScene/render.total`: scene GL renders.
- `ImlaScene/slot.content.capture`: foreground slot content captures.
- `ImlaScene/slot.mask.capture`: blur mask captures.
- `ImlaScene/slot.clip.capture`: clip mask captures.
- `ImlaScene/render.slot.composite`: slots composited by the GL scene renderer.
- `ImlaScene/render.backdrop.composite`: backdrop blur composite passes.
- `ImlaScene/render.content.composite`: foreground content composite passes.
- `ImlaScene/render.stencil.setup`: stencil clip setup passes.

`max_value` is the cumulative app-session counter value observed during the trace. `observed_delta` is the difference between the first and last observed values inside the trace window.

## Reading A Trace

For idle validation:

```text
render.total should be low.
slot.content.capture should not climb continuously.
root.capture.commit should not climb continuously.
```

For bottom sheet or placement motion:

```text
geometry.refresh should increase.
slot.content.capture and slot.mask.capture should stay flat or grow much more slowly.
render.consume.SlotChanged should correspond to actual scene renders.
```

For content/style changes:

```text
slot.content.capture may increase for content changes.
slot.mask.capture or slot.clip.capture may increase for mask/clip changes.
render.backdrop.composite and render.content.composite show GL pass volume.
```

## Workflow

1. Capture idle and interaction traces:

```bash
tools/imla-perfetto-feedback capture --device <serial> --no-exercise
tools/imla-perfetto-feedback smoke --device <serial>
```

2. Open `60_scene_counters.txt` and compare counters between traces.
3. Cross-check `50_renderer_trace_anchors.txt` for slice timing and count details.
4. Use screenshot diff only after a rendering behavior change; counters do not prove visual correctness.
