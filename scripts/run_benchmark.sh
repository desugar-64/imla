#!/bin/bash
# IMLA Benchmark Automation Script
# Usage: ./scripts/run_benchmark.sh [baseline_name]

set -e

# Configuration
DEFAULT_NAME="benchmark_$(date +%Y%m%d_%H%M%S)"
NAME=${1:-$DEFAULT_NAME}

echo "🚀 Running IMLA Benchmark: $NAME"
echo "=================================="

# 1. Run the benchmark
echo "📱 Running Android benchmark..."
./gradlew :benchmark:connectedBenchmarkAndroidTest --no-daemon

# 2. Find the newest trace & benchmark JSON (Perfetto env required)
TRACE_FILE=$(python3 - <<'PY'
import glob, os
files = glob.glob("benchmark/build/outputs/connected_android_test_additional_output/**/*.perfetto-trace", recursive=True)
print(max(files, key=os.path.getmtime) if files else "")
PY
)
if [ -z "$TRACE_FILE" ]; then
    echo "❌ No trace file found under benchmark outputs!"
    exit 1
fi

JSON_FILE=$(python3 - <<'PY'
import glob, os
files = glob.glob("benchmark/build/outputs/connected_android_test_additional_output/**/*-benchmarkData.json", recursive=True)
print(max(files, key=os.path.getmtime) if files else "")
PY
)

echo "📊 Latest trace: $(basename "$TRACE_FILE")"
if [ -n "$JSON_FILE" ]; then
    echo "📝 Benchmark JSON: $(basename "$JSON_FILE")"
else
    echo "⚠️  Benchmark JSON not found; JSON metrics will be skipped"
fi

# 3. Activate virtual environment and store results
echo "💾 Storing benchmark results..."
source perfetto-env/bin/activate

# All trace events in the project (static names + wildcards for interpolated names)
TRACE_EVENTS=(
    "BlurBehindView#id"
    "BlurEffect#applyEffect"
    "BlurEffect#init"
    "DualKawaseBlurEffect"
    "GraphicsLayerRenderer#copyTextureToFramebuffer"
    "GraphicsLayerRenderer#initialize"
    "GraphicsLayerRenderer#reinitialize"
    "GraphicsLayerRenderer#renderGraphicsLayer"
    "GraphicsLayerRenderer#updateGraphicsLayer"
    "MainActivity#Content"
    "MaskTextureRenderer#renderMask"
    "MaskTextureRenderer#shouldRedraw"
    "PostBlendEffect#computeBackgroundCropUv"
    "PostBlendEffect#computeForegroundCropUv"
    "QuadBatchRenderer#submit"
    "QuadBatchRenderer#submitDebug"
    "RenderableRootLayer#initialize"
    "RenderableRootLayer#updateTex"
    "Renderer2D#beginScene"
    "Renderer2D#endScene"
    "RenderingPipeline#renderAll"
    "ShaderLibrary#loadShader"
    "SimpleQuadRenderer#draw"
    "SimpleRenderer#flush"
    "aaDownsampling"
    "bindDefaultFBO"
    "bindUniformBlock"
    "blendLayers"
    "blendToDefaultBuffer"
    "blitBackground"
    "blitFirstFBO"
    "blitForeground"
    "blitFramebuffers"
    "blitResult"
    "cutArea"
    "cutBackgroundRegion"
    "defaultQuadVertexMapper"
    "disableBlending"
    "downsample"
    "drawFullQuadStatic"
    "drawGraphicsLayer"
    "drawIndexed"
    "drawMask"
    "drawNoiseTextureOnce"
    "drawQuad"
    "enableBlending"
    "extraHPass"
    "extraVPass"
    "flush"
    "fullSizeBuffer"
    "generateMipMaps"
    "glBlitFramebuffer"
    "glClear"
    "glGenerateMipmap"
    "glUnBindFramebuffer"
    "glViewport"
    "horizontalPass"
    "init"
    "invalidateAttachments"
    "preProcess"
    "recordCanvas"
    "setClearColor"
    "setFloat"
    "setFloat2"
    "setFloat3"
    "setFloat4"
    "setFloatArray"
    "setInt"
    "setIntArray"
    "setMaskProp"
    "setMat3"
    "setMat4"
    "setStyle"
    "setup"
    "shaderCompile"
    "submitQuad"
    "surfaceTexture#updateTexImage"
    "textureBind"
    "uboBind"
    "uboSetData"
    "uploadDataIfNeed"
    "upsample"
    "useDefaultProgram"
    "vaoBind"
    "vboBind"
    "vboCreateDynamic"
    "vboCreateStatic"
    "vboSetData"
    "verticalPass"

    # Wildcards for interpolated trace names
    "drawLayerToExtTexture*"
    "drawQuad*"
    "glBindFramebuffer*"
    "glBufferSubData*"
)

python scripts/trace_analyzer.py --latest "${TRACE_EVENTS[@]}" --store "$NAME" --include-json

echo "✅ Benchmark complete! Results stored as: $NAME"
echo "📂 Results location: benchmark_results/$NAME.json"
