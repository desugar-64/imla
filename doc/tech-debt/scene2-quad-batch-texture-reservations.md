# Scene2 Quad Batch Texture Reservations

## Purpose

Track `QuadBatchRenderer` texture-reservation cleanup so specialized shaders do
not manipulate `Renderer2D` internals directly.

## Current State

`SceneBackdropBlurPass` uses one submitted source texture plus an optional
progressive mask texture. The source texture is carried by the quad. The mask is
declared through `QuadBatchRenderer.begin(reservedTextures = ...)`, then shader
configuration receives the resolved texture-slot map.

This removes the previous mutable captured slot variable and makes the ordering
explicit: extra textures are declared at begin time, reserved immediately after
`Renderer2D.beginScene(...)` resets texture slots, and consumed by deferred
shader configuration during flush.

`BackdropCompositeShaderEffect` also uses this API for noise and mask textures.
The remaining direct `textureSlots` writes are inside renderer/batch ownership
code.

## Current Shape

Use the narrow reservation API on `QuadBatchRenderer.begin(...)`:

```kotlin
quadBatchRenderer.begin(
    targetSize = contentSize,
    shaderProgram = shaderProgram,
    reservedTextures = listOf(progressiveMask.texture),
    configureShader = { shader, reservedTextures ->
        shader.shader.setInt(
            "u_MaskTexIndex",
            reservedTextures[progressiveMask.texture] ?: 0
        )
    }
)
```

The important shape is explicit ownership of reservation order:

- callers declare extra textures before shader configuration;
- `QuadBatchRenderer` returns stable texture-slot indices for those textures;
- shader configuration no longer depends on mutable captured slot variables;
- renderer resource ownership remains outside the batch renderer.

## Remaining Change

No known specialized shader call site writes `renderer2D.data.textureSlots`
directly. Keep future extra-texture shader inputs on `reservedTextures` unless a
broader renderer refactor replaces `QuadBatchRenderer`.

## Non-Goals

- Do not introduce a broad shader configuration DSL until there are multiple
  call sites that need it.
- Do not manually bind GL textures in scene passes.
- Do not move `Texture2D` ownership out of renderer resources.

## Verification

Before and after this cleanup, progressive masks should render identically.
Verify with a strong mask gradient and confirm:

- no Y-flip;
- no mask/slot alignment shift;
- blur strength follows the mask texture;
- rotated cards keep stable backdrop sampling.
