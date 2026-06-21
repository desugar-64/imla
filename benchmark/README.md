# IMLA Performance Benchmark

## Quick Start

```bash
# Run benchmark (requires connected Android device)
./gradlew :benchmark:connectedBenchmarkAndroidTest

# View results
open benchmark/build/reports/androidTests/connected/benchmark/index.html
```

## What It Measures

**Worst-case performance** of OpenGL ES rendering pipeline with 56 metrics covering effects, layers, GL operations, and memory management.

## Understanding Results

All metrics show **MAXIMUM execution times** (MaxMs suffix). Lower is better.

### Performance Targets (60fps = 16.67ms budget)

| Range | Interpretation |
|-------|----------------|
| **< 5ms** | ✅ Excellent |
| **5-10ms** | ✅ Good |
| **10-16ms** | ⚠️ Acceptable |
| **> 16ms** | 🚨 Needs optimization |

### Key Metrics to Watch

- **RenderingPipeline#renderAllMaxMs**: Overall pipeline (⚠️ > 20ms = critical)
- **GraphicsLayerRenderer*MaxMs**: UI layer conversion (⚠️ > 5ms = bottleneck)
- **GaussianBlurEffectMaxMs**: Blur performance (⚠️ > 10ms = slow)
- **gl*MaxMs**: OpenGL operations (⚠️ > 2ms = inefficient)

## Detailed Analysis

For min/avg/max/percentile statistics from Perfetto trace:

```bash
# Activate Python environment
source perfetto-env/bin/activate

# Analyze components
python scripts/trace_analyzer.py --latest "RenderingPipeline*" "GraphicsLayerRenderer*"

# Get overview
python scripts/trace_analyzer.py --latest "*Effect*" "*Renderer*" "gl*"

# Store baseline
python scripts/trace_analyzer.py --latest "*" --store baseline_name
```

## Atlas Performance Captures

Use the app `benchmark` variant for atlas performance traces. The manual benchmark scene is
separate from the atlas renderer switch so the same visible scene can run atlas-off and atlas-on.

Performance conclusions require a settled, repeated redraw window. Do not use
a static scene trace that renders once, a launch-only burst, or a one-off surface
update as performance evidence. Before comparing atlas-on/off or before/after
code changes, make sure the measured scene is animating, running through a
continuous or immediate render loop, or otherwise producing many surface updates
after warm-up. A valid performance trace should show many `render.total`,
`slot.content.capture`, and app trace slices over the measurement window, and
the same slot names, app variant, props, and scene state across compared traces.
Static `AtlasBenchmarkScene` traces are useful for routing and visual checks
only unless a continuous redraw driver is enabled.

```bash
./gradlew -q :app:assembleBenchmark
tools/adb-timeout --device <serial> --timeout 60 install -r -d app/build/outputs/apk/benchmark/app-benchmark.apk
tools/adb-timeout --device <serial> --timeout 20 push diagnostics/apa/imla-smoke.perfetto.cfg /data/misc/perfetto-configs/imla-smoke.perfetto.cfg

# Atlas off (via opt-out), visible benchmark scene on.
tools/adb-timeout --device <serial> --timeout 20 shell setprop log.tag.ImlaAtlasBenchmarkScene DEBUG
tools/adb-timeout --device <serial> --timeout 20 shell setprop log.tag.ImlaAtlasDisabled DEBUG
tools/adb-timeout --device <serial> --timeout 20 shell am force-stop dev.serhiiyaremych.imla
tools/adb-timeout --device <serial> --timeout 25 shell perfetto --txt -c /data/misc/perfetto-configs/imla-smoke.perfetto.cfg -o /data/misc/perfetto-traces/imla-atlas-off-benchmark-scene.perfetto-trace &
PERFETTO_PID=$!
sleep 1
tools/adb-timeout --device <serial> --timeout 60 shell am start -n dev.serhiiyaremych.imla/dev.serhiiyaremych.imla.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
wait "$PERFETTO_PID"
tools/adb-timeout --device <serial> --timeout 20 pull /data/misc/perfetto-traces/imla-atlas-off-benchmark-scene.perfetto-trace diagnostics/apa/traces/imla-atlas-off-benchmark-scene.perfetto-trace
tools/adb-timeout --device <serial> --timeout 20 exec-out screencap -p > diagnostics/apa/screenshots/imla-atlas-off-benchmark-scene.png
tools/imla-perfetto-feedback analyze diagnostics/apa/traces/imla-atlas-off-benchmark-scene.perfetto-trace

# Atlas on (default), same visible benchmark scene.
tools/adb-timeout --device <serial> --timeout 20 shell setprop log.tag.ImlaAtlasBenchmarkScene DEBUG
tools/adb-timeout --device <serial> --timeout 20 shell setprop log.tag.ImlaAtlasDisabled INFO
tools/adb-timeout --device <serial> --timeout 20 shell am force-stop dev.serhiiyaremych.imla
tools/adb-timeout --device <serial> --timeout 25 shell perfetto --txt -c /data/misc/perfetto-configs/imla-smoke.perfetto.cfg -o /data/misc/perfetto-traces/imla-atlas-on-benchmark-scene.perfetto-trace &
PERFETTO_PID=$!
sleep 1
tools/adb-timeout --device <serial> --timeout 60 shell am start -n dev.serhiiyaremych.imla/dev.serhiiyaremych.imla.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
wait "$PERFETTO_PID"
tools/adb-timeout --device <serial> --timeout 20 pull /data/misc/perfetto-traces/imla-atlas-on-benchmark-scene.perfetto-trace diagnostics/apa/traces/imla-atlas-on-benchmark-scene.perfetto-trace
tools/adb-timeout --device <serial> --timeout 20 exec-out screencap -p > diagnostics/apa/screenshots/imla-atlas-on-benchmark-scene.png
tools/imla-perfetto-feedback analyze diagnostics/apa/traces/imla-atlas-on-benchmark-scene.perfetto-trace

# Optional screenshot parity check.
tools/screenshot-diff diagnostics/apa/screenshots/imla-atlas-off-benchmark-scene.png diagnostics/apa/screenshots/imla-atlas-on-benchmark-scene.png --pixel-threshold 8 --smooth-radius 1.0 --out diagnostics/apa/screenshot-diff/atlas-benchmark-scene-off-vs-on

# Cleanup.
tools/adb-timeout --device <serial> --timeout 20 shell setprop log.tag.ImlaAtlasBenchmarkScene INFO
tools/adb-timeout --device <serial> --timeout 20 shell setprop log.tag.ImlaAtlasDisabled INFO
tools/adb-timeout --device <serial> --timeout 20 shell am force-stop dev.serhiiyaremych.imla
```

Debug builds are useful for counters and screenshot parity, but they are not valid for performance
comparison. The `benchmark` variant is release-like, installable, profileable through the app
manifest, and keeps minification off so Perfetto sections and symbols remain readable. Atlas is
enabled by default in all builds. The benchmark scene remains off by default.
`log.tag.ImlaAtlasBenchmarkScene=DEBUG` launches the stable multi-slot scene;
`setprop log.tag.ImlaAtlasDisabled DEBUG` disables atlas for the next app process. Reset the
tag to `INFO` and force-stop the app after diagnostics to return to the default atlas path.

Masked atlas blur has a stricter internal switch and is not enabled by normal atlas execution.
For masked visual diagnostics, enable the relevant scene tag plus the masked atlas tag for one
app process, then reset all tags and force-stop the app:

```bash
tools/adb-timeout --device <serial> --timeout 20 shell setprop log.tag.ImlaMaskSemanticsScene DEBUG
tools/adb-timeout --device <serial> --timeout 20 shell setprop log.tag.ImlaSceneMaskedAtlas DEBUG
```

## Output Files

- **HTML Report**: `benchmark/build/reports/androidTests/connected/benchmark/`
- **JSON Metrics**: `benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/`
- **Perfetto Trace**: Same directory as JSON file
- **Stored Analysis**: `benchmark_results/` (when using --store)

## Why Mode.Max?

Uses `TraceSectionMetric.Mode.Max` for reliable worst-case measurements, perfect for optimization focus and automated testing.

## Troubleshooting

- Ensure device connected: `adb devices`
- App must be installed and profileable
- Screen unlocked during test
- Verify `<profileable android:shell="true" />` in manifest
