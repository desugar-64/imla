# Scene2 Effects Data Flow Prototype

Date: 2026-06-02

This note tracks the proposed effect data flow for the scratch scene renderer.
It is a working design prototype, not an implementation contract. Keep it small
and update it as each slice proves or rejects the shape.

## Design Direction

Represent effects as immutable scene data. Execute them through renderer-owned
passes.

The scene should say what a slot wants. It should not expose render passes,
shader programs, framebuffers, OpenGL textures, or renderer handles. The GL
pipeline may expand one effect declaration into one pass, several passes, or a
batched draw without changing the scene-facing API.

## Data Flow

1. Compose pre-draw calls the renderer with the root source layer and registered
   slots.
2. The main thread captures root and slot content into closeable hardware-buffer
   frames.
3. The renderer freezes declarative slot data into a `SceneRenderSnapshot`.
4. The GL thread imports captured frames into textures.
5. The GL resource store builds a short-lived `SceneRenderFrame`.
6. The render pipeline draws root, backdrop/effect output, slot content, and
   diagnostics from the frame. Progressive mask textures are imported resources
   consumed by the backdrop composite pass, not by the slot content pass.

## Proposed Snapshot Data

```kotlin
data class SceneRenderSnapshot(
    val root: CapturedHardwareBufferFrame,
    val slots: List<SceneSlotDeclaration>,
    val resources: SceneResources,
    val metricsFrame: SceneMetricsFrame?
)
```

```kotlin
data class SceneSlotDeclaration(
    val id: SceneSlotId,
    val drawOrder: Int,
    val geometry: SceneSlotGeometry,
    val backdrop: SceneBackdropEffect?,
    val debug: SceneSlotDebug?
)
```

`SceneSlotDeclaration` must stay immutable and UI-object-free. It may describe
content, backdrop effects, geometry, draw order, and diagnostics. It must not
own Compose layers, hardware buffers, GL textures, shader programs,
framebuffers, or pass objects.

## Proposed Effect Data

```kotlin
data class SceneBackdropEffect(
    val operations: List<SceneBackdropOperation>
)

sealed interface SceneBackdropOperation {
    data class Blur(
        val sigmaPx: Float,
        val hasProgressiveMask: Boolean = false
    ) : SceneBackdropOperation
}
```

Start by threading `Blur(sigmaPx)` as data, but do not implement real blur yet.
The first render slice should use the existing screen-space sampled backdrop
debug visualization as the placeholder for slots that request backdrop blur.
`hasProgressiveMask` is frozen from renderer resource availability. The brush
itself remains a Compose-side input and does not cross into GL snapshots.

## Proposed GL Frame Data

```kotlin
data class SceneRenderFrame(
    val rootTexture: OpenGLHardwareBufferTexture2D,
    val slotTextures: Map<SceneSlotId, OpenGLHardwareBufferTexture2D>,
    val slots: List<SceneSlotDeclaration>,
    val targetSize: IntSize,
    val metricsFrame: SceneMetricsFrame?
)
```

`SceneRenderFrame` is GL-thread render input. It may reference GL textures and
renderer-owned cached resources. It should still be treated as input data for
the current render, not as an object that decides how to draw itself.

## Optional Render Commands

Do not introduce render commands until there is enough pressure from multiple
effects or passes. When needed, commands should be rebuilt from the frame each
render and discarded immediately.

```kotlin
sealed interface SceneRenderCommand {
    data object DrawRoot : SceneRenderCommand

    data class DrawBackdropEffect(
        val slotId: SceneSlotId,
        val geometry: SceneSlotGeometry,
        val effect: SceneBackdropEffect
    ) : SceneRenderCommand

    data class DrawSlotContent(
        val slotId: SceneSlotId,
        val geometry: SceneSlotGeometry
    ) : SceneRenderCommand

    data class DrawDebugBounds(
        val slotId: SceneSlotId,
        val geometry: SceneSlotGeometry,
        val debug: SceneSlotDebug
    ) : SceneRenderCommand
}
```

The first implementation slice can skip commands and let
`SceneBackdropEffectPass` scan `frame.slots` directly. Add a planner only when
direct slot scanning starts duplicating ordering, grouping, or filtering logic.

## Pass Boundary

The render pipeline should stay explicit:

```kotlin
rootPresentPass.render(frame)
backdropEffectPass.render(frame)
slotContentPass.render(frame)
debugPass.render(frame)
```

Passes are internal GPU execution units. They can own shader programs, temporary
FBOs, and batching details. They should not mutate scene declarations or depend
on Compose/UI objects.

## Prepared Backdrop Slice

Backdrop effects should prepare a slot-local working set rather than drawing
directly from the root texture. The source is a renderer-selected
`BackdropInput`, not a hard-coded clean root texture, so future cumulative
composition can pass the current scene-color texture instead.

```kotlin
data class BackdropInput(
    val texture: Texture2D,
    val size: IntSize,
    val coordinateOrigin: CoordinateOrigin,
    val localToInput: Mat4
)
```

```kotlin
data class BackdropBaseInput(
    val texture: Texture2D,
    val size: IntSize,
    val coordinateOrigin: CoordinateOrigin,
    val localToInput: Mat4,
    val visibleLocalRect: Rect
)
```

```kotlin
data class PreparedBackdrop(
    val input: BackdropInput,
    val output: BorrowedBackdropTexture,
    val visibleLocalRect: Rect,
    val sampleLocalRect: Rect,
    val downsampleScale: Float
)

data class BorrowedBackdropTexture(
    val image: BackdropTexture,
    val framebuffer: Framebuffer
)

data class BackdropTexture(
    val texture: Texture2D,
    val contentRect: IntRect
)
```

The prepare pass uses a shader draw into a bucketed framebuffer. It does not
use a framebuffer blit because transformed X/Y/Z slots need fragment-level
local-to-input mapping. Cross-pass sampling data is carried by `BackdropTexture`.
The framebuffer is only borrowed storage ownership so the producing pass can
release it. The prepared texture is untinted production-shaped data. Diagnostic
tint belongs only to explicit debug visualization.

`BackdropBaseInput` is the crisp source view for the visible slot-local area. It
borrows the same source texture as `BackdropInput` and carries only sampling
metadata: source size, coordinate origin, local-to-source transform, and the
slot-local visible rect. It does not own the texture, a framebuffer, or any
release responsibility. The composite pass uses it to sample crisp scene color
where progressive mask alpha says blur strength is zero.

The current diagnostic composite draws the downsampled/pre-filtered texture back
through the slot transform with a visible tint. Test scene slot fills are
semi-transparent with frames and markers so the prepared patch can be inspected
for size, shift, stretch, Y-flip, and rotation mistakes.

When a progressive mask texture is available, the composite pass uses
`scene_backdrop_progressive_composite.frag` instead of the plain diagnostic
composite. That shader samples three inputs in one draw: the processed backdrop
texture, the crisp base texture through `BackdropBaseInput`, and the slot-local
mask texture rasterized from the Compose `Brush`. Mask alpha blends between
crisp base and processed backdrop. This is still diagnostic progressive
composition; the processed backdrop is currently the blur-stage diagnostic copy,
not a variable-kernel blur result.

`QuadBatchRenderer.reserveTextureSlot(texture)` is a renderer helper for this
specialized composite path. It reserves additional texture units inside the
active quad batch so a shader can address base and mask textures by uniform
index while the quad's normal texture index still points at the processed
backdrop texture. It does not take ownership of textures or change batching
outside the active draw.

The progressive mask capture cache lives in renderer resources. It rasterizes a
client-provided Compose `Brush` at slot-local `IntSize` into a hardware-buffer
frame, keys the capture by brush/size/density/layout direction, imports changed
frames into `Texture2D`, and lets the GL resource store retain the latest mask
texture for slots whose brush did not change.

## Blur Stage Slice

The blur stage now has a future-shaped input object but intentionally simple
sampling math:

```kotlin
data class BackdropBlurInput(
    val source: BackdropCompositeInput,
    val sigmaPx: Float,
    val downsampleScale: Float,
    val filterRadiusPx: Float
)
```

`BackdropBlurInput` is pass-local shader data. It describes the source texture
view, the requested scene sigma, the prepared texture downsample scale, and the
capped filter radius used by this slice. It does not own textures or framebuffers
and it does not contain progressive mask data yet.

`SceneBackdropBlurPass` executes two fixed separable draws with
`scene_backdrop_separable_blur.frag`: horizontal into a temporary borrowed FBO,
then vertical into the final processed backdrop FBO. The shader exposes
future-facing uniforms for source texel step, blur direction, sigma, radius, and
downsample scale, but the body is still a capped 13-tap Gaussian-style sampler.
The progressive mask remains in composite only.

The current prepare shader uses a pure Wronski-style 8-tap antialiasing
downsample filter. It deliberately excludes the older prefilter's chromatic
aberration, desaturation, edge shaping, and diagnostic tint. The first slice
uses a single shader pass. Very large diagnostic sigma values may force a
quarter-resolution target to expose upscaling artifacts visually; proper
cascaded 2x downsample stages are still future work.

## Pass Metrics Slice

Renderer pass metrics should report execution boundaries, not public effect
names. The first fixed buckets are root present, backdrop prepare, backdrop
blur stage, backdrop composite, and slot content.

Scene pass timings are GL-thread CPU wall time spent issuing the pass. They are
not GPU timer-query results. Work-volume counters are pass-specific estimates:
backdrop prepare reports latest prepared output pixels and estimated texture
samples. The Wronski-style prepare filter currently reports 8 taps per output
pixel. The current backdrop blur stage is still a diagnostic processed-texture
copy, so it reports one texture sample per output pixel.

## Current Incremental Plan

1. Add `SceneBackdropEffect` and `SceneBackdropOperation.Blur`, then carry them
   through registered slot snapshots into `SceneSlotDeclaration`.
2. Add a diagnostic-only `SceneBackdropEffectPass` that prepares a slot-local
   downsampled backdrop texture, then composites it back visibly.
3. Verify Y orientation, root-space sampling, bounds, alpha, and rotation
   stability with the existing debug cards.
4. Add a diagnostic processed-backdrop stage between prepare and composite.
5. Add progressive mask capture/import and composite-time crisp-vs-processed
   blending without changing the processed backdrop stage.
6. Replace the diagnostic processed stage with a two-pass fixed blur that keeps
   the future variable-kernel data shape.
7. Only after that, wire the progressive mask into blur strength.

## Non-Goals For The First Slice

- Do not add production blur kernels.
- Do not introduce atlas routing.
- Do not add coverage masks or clips.
- Do not use progressive masks inside the blur shader yet.
- Do not expose render passes or GL resources through modifiers.
- Do not create a general command planner until a second effect or pass needs
  shared ordering/filtering logic.
