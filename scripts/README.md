# Enhanced Benchmark Analysis Pipeline

This directory contains enhanced scripts for comprehensive Android benchmark analysis that combines both Perfetto trace data and JSON benchmark metrics.

## Key Features

- **Dual Analysis**: Analyzes both Perfetto trace data and Android Benchmark JSON output
- **Comprehensive Metrics**: Frame timing, jank percentage, custom trace sections, and detailed performance statistics
- **Auto-discovery**: Automatically finds latest benchmark results with `--latest` flag
- **Enhanced Comparison**: Compares both trace and JSON metrics with meaningful percentage changes
- **Storage System**: Store analysis results for future comparison

## Quick Start

### 1. Analyze Latest Benchmark Results

```bash
# Activate the Perfetto environment
source perfetto-env/bin/activate

# List available metrics in the latest benchmark trace
python scripts/trace_analyzer.py --latest --list

# Analyze all traceable events (static names + wildcards for interpolated names; matches run_benchmark.sh)
python scripts/trace_analyzer.py --latest \
  "BlurBehindView#id" "BlurEffect#applyEffect" "BlurEffect#init" "DualKawaseBlurEffect" \
  "GraphicsLayerRenderer#copyTextureToFramebuffer" "GraphicsLayerRenderer#initialize" \
  "GraphicsLayerRenderer#reinitialize" "GraphicsLayerRenderer#renderGraphicsLayer" "GraphicsLayerRenderer#updateGraphicsLayer" \
  "MainActivity#Content" "MaskTextureRenderer#renderMask" "MaskTextureRenderer#shouldRedraw" \
  "QuadBatchRenderer#submit" "QuadBatchRenderer#submitDebug" \
  "RenderableRootLayer#initialize" "RenderableRootLayer#updateTex" "Renderer2D#beginScene" "Renderer2D#endScene" \
  "RenderingPipeline#renderAll" "ShaderLibrary#loadShader" "SimpleQuadRenderer#draw" "SimpleRenderer#flush" \
  "aaDownsampling" "bindDefaultFBO" "bindUniformBlock" \
  "blendLayers" "blendToDefaultBuffer" "blitBackground" "blitFirstFBO" "blitForeground" "blitFramebuffers" "blitResult" \
  "cutArea" "cutBackgroundRegion" "defaultQuadVertexMapper" "disableBlending" "downsample" "drawFullQuadStatic" \
  "drawGraphicsLayer" "drawIndexed" "drawMask" "drawNoiseTextureOnce" "drawQuad" "enableBlending" "extraHPass" "extraVPass" \
  "flush" "fullSizeBuffer" "generateMipMaps" "glBlitFramebuffer" "glClear" "glGenerateMipmap" "glUnBindFramebuffer" \
  "glViewport" "horizontalPass" "init" "invalidateAttachments" "preProcess" "recordCanvas" "setClearColor" "setFloat" \
  "setFloat2" "setFloat3" "setFloat4" "setFloatArray" "setInt" "setIntArray" "setMaskProp" "setMat3" "setMat4" \
  "setStyle" "setup" "shaderCompile" "submitQuad" "surfaceTexture#updateTexImage" "textureBind" "uboBind" "uboSetData" \
  "uploadDataIfNeed" "upsample" "useDefaultProgram" "vaoBind" "vboBind" "vboCreateDynamic" "vboCreateStatic" \
  "vboSetData" "verticalPass" \
  "drawLayerToExtTexture*" "drawQuad*" "glBindFramebuffer*" "glBufferSubData*"

# Or run the comprehensive benchmark script that includes the full set:
./scripts/run_benchmark.sh my_benchmark_name

# Analyze and store results with JSON data included
python scripts/trace_analyzer.py --latest BlurEffect#applyEffect flush --store baseline --include-json
```

### 2. Compare Benchmark Results

```bash
# Compare two stored benchmark results
python scripts/benchmark_compare.py benchmark_results/baseline.json benchmark_results/optimized.json
```

## Usage Examples

### Trace Analysis

```bash
# Analyze specific trace file with custom metrics
python scripts/trace_analyzer.py path/to/trace.perfetto-trace "BlurEffect*" "flush*"

# Export results as JSON
python scripts/trace_analyzer.py --latest BlurEffect#applyEffect --format json

# Store results for later comparison
python scripts/trace_analyzer.py --latest BlurEffect#applyEffect flush --store current_run
```

### Benchmark Comparison

The enhanced comparison tool analyzes three categories:

1. **Trace Metrics**: Direct Perfetto trace analysis results
2. **Frame Performance**: JSON benchmark metrics (frame timing, jank, etc.)
3. **Custom Trace Sections**: Benchmark's own trace section measurements

Example output:
```
📊 Comparing 'baseline' vs 'optimized'
================================================================================
🔍 TRACE METRICS COMPARISON
✅ flush: 0.062ms → 0.055ms (-11.3%)

📈 FRAME PERFORMANCE METRICS COMPARISON
✅ gfxFrameTime50thPercentileMs: 5.0ms → 4.2ms (-16.0%)

🎯 Overall: 2 improvements, 0 regressions
```

## Key Metrics Tracked

### Frame Performance (from JSON)
- `gfxFrameTime50th/90th/95th/99thPercentileMs`: Frame timing percentiles
- `gfxFrameJankPercent`: Percentage of janky frames
- `frameOverrunMs`: Performance headroom (negative = good)
- `frameCount` & `gfxFrameTotalCount`: Frame counts

### Custom Trace Sections
- `BlurEffect#applyEffectAverageMs`: Blur rendering time
- `flushAverageMs`: GPU flush time
- Any other `TraceSectionMetric` from your benchmark

### Perfetto Trace Analysis
- Detailed statistics (mean, median, P90, P95, P99)
- Event counts and timing distributions
- Custom metric patterns with wildcards

## Best Practices

1. **Store Baselines**: Always store a baseline before making optimizations
   ```bash
   python scripts/trace_analyzer.py --latest BlurEffect#applyEffect flush --store baseline --include-json
   ```

2. **Use Consistent Metrics**: Compare the same metrics across runs for meaningful results

3. **Include JSON Data**: Use `--include-json` when storing to get comprehensive frame performance data

4. **Regular Comparisons**: Compare frequently to catch regressions early
   ```bash
   python scripts/benchmark_compare.py benchmark_results/baseline.json benchmark_results/latest.json
   ```

5. **Automate in CI**: The comparison script exits with non-zero code on regressions, making it CI-friendly

## File Structure

```
scripts/
├── trace_analyzer.py      # Enhanced Perfetto trace analysis
├── benchmark_compare.py   # Comprehensive benchmark comparison
└── README.md             # This file

benchmark_results/        # Stored analysis results (auto-created)
├── baseline.json
├── optimized.json
└── current_run.json
```

## Performance Targets

- **P50 frame time**: < 8ms (well under 16.67ms budget for 60fps)
- **P90 frame time**: < 12ms
- **Frame jank**: < 1%
- **Frame overrun**: Negative values (performance headroom)
- **Sustained performance**: Stable metrics over 15+ minutes

## Troubleshooting

### No Benchmark Files Found
```bash
❌ No recent benchmark trace file found
```
Ensure benchmarks have been run and output files exist in:
`benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/`

### Perfetto Environment Issues
```bash
source perfetto-env/bin/activate
```
Make sure the Perfetto Python environment is activated.

### Missing Metrics
Use `--list` to see available metrics in your trace:
```bash
python scripts/trace_analyzer.py --latest --list --limit 100
```

## Integration with Your Workflow

### Before Optimization
```bash
python scripts/trace_analyzer.py --latest BlurEffect#applyEffect flush --store before_optimization --include-json
```

### After Optimization
```bash
python scripts/trace_analyzer.py --latest BlurEffect#applyEffect flush --store after_optimization --include-json
python scripts/benchmark_compare.py benchmark_results/before_optimization.json benchmark_results/after_optimization.json
```

### CI Integration
```bash
# In CI pipeline
python scripts/benchmark_compare.py benchmark_results/baseline.json benchmark_results/current_pr.json
if [ $? -eq 1 ]; then
  echo "Performance regression detected!"
  exit 1
fi
```
