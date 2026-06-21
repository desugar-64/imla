---
name: imla_perfetto_feedback
description: Capture and analyze Imla Android renderer smoke traces with the repo-local Perfetto config, SQL queries, and Android Performance Analyzer workflow.
---

# Imla Perfetto Feedback

Use this skill before and after renderer, blur, GL threading, capture, or Compose modifier changes that need runtime feedback beyond screenshots.

## Tool

The repo-local tool is:

```bash
tools/imla-perfetto-feedback
```

It wraps the checked-in Perfetto smoke config, named SQL queries, screenshot capture, and optional Android Performance Analyzer opening. The extensionless shell wrapper is the stable entrypoint; the `.py` file is the implementation detail.

## Commands

Analyze an existing trace:

```bash
tools/imla-perfetto-feedback analyze diagnostics/apa/traces/imla-smoke-2026-05-23.perfetto-trace
```

Capture a new smoke trace on the active emulator, exercise the demo, pull a screenshot, analyze it, and open it in APA:

```bash
tools/imla-perfetto-feedback smoke --device emulator-5580 --open-apa
```

Capture without scripted input:

```bash
tools/imla-perfetto-feedback capture --device emulator-5580 --no-exercise
```

Open a trace in Android Performance Analyzer:

```bash
tools/imla-perfetto-feedback open diagnostics/apa/traces/<trace>.perfetto-trace
```

## Trace Anchors

Start investigation from these labels when present:

- `ImlaSceneCoordinator#commitRootCapture`
- `ImlaSceneCoordinator#refreshSlotGeometry`
- `RenderableRootLayer#captureLayer`
- `GraphicsLayerTexture#captureGraphicsLayer`
- `GraphicsLayerTexture#captureCanvas`
- `SceneLayerRepository#captureSlotContent`
- `SceneMaskRepository#captureSlotMasks`
- `SceneGlRenderer#render`
- `Renderer2D#endScene`
- `Renderer2D#uploadVertices`
- `vboSetData`
- `glBufferData`

Key threads are the main app thread, Android `RenderThread`, and Imla `GLUiLayerRender`.

## Workflow

1. Build and install the app if code changed: `./gradlew -q :app:assembleDebug` then install or launch via ADB.
2. Run `tools/imla-perfetto-feedback smoke --device <serial>`.
3. Read the generated analysis directory next to the trace before editing performance-sensitive code.
4. Open the trace in APA when thread timing, frame timeline, or visual trace navigation is needed.
5. Use the installed official skills `perfetto-trace-analysis` and `perfetto-sql` for deeper AI-assisted trace questions; this repo tool provides the repeatable capture/query baseline.

The scene counter summary is written to `60_scene_counters.txt` when scene counters are present.
