# Scene2 Stencil Clipping

## Purpose

Track the next scene2 clipping slice after the intermediate scene framebuffer
foundation. This note is a plan for small verified steps, not an active public
API contract yet.

## Current State

Scene2 now renders into an owned intermediate scene framebuffer before presenting
that texture to the Android default surface. Root, backdrop composite, and slot
content all draw into the same scene target, which gives the renderer a single
place to add stencil state later.

The current scene framebuffer is color-only `RGBA8`. It does not allocate a
stencil attachment, does not write stencil values, and does not expose clip
shape state through slot declarations.

## Target Contract

Stencil clipping should be shape-driven:

- callers provide a clip `Shape`, not a mask texture;
- `RectangleShape` matching the slot content bounds is a no-op fast path;
- non-rectangle shapes require stencil-capable rendering and should fail fast if
  the renderer cannot provide it;
- clip geometry is slot-local, then drawn with the slot render transform so 3D
  rotated cards clip in the same local space as their content;
- clip texture/rasterization is an implementation detail owned by renderer
  resources, not part of public slot API.

## Proposed First Slice

1. Add internal clip state to scene2 slot declarations without exposing a broad
   public API yet.
2. Teach `SceneRenderBufferPool` to acquire a stencil-capable framebuffer when
   the frame contains at least one non-rectangle clip.
3. Add a fail-fast capability check for stencil-required frames.
4. Add a no-op/rectangle path so the existing demo keeps rendering identically
   when no clip is requested.
5. Verify with the current scene that the intermediate framebuffer path remains
   visually unchanged before adding stencil writes.

## Follow-Up Slice

Add the actual stencil draw/test path:

- rasterize or draw the local clip shape into stencil;
- clear stencil per clipped slot or use incrementing values if overlapping clips
  require it;
- draw backdrop composite and content under the stencil test;
- disable stencil state after the clipped slot;
- verify no Y-flip, stretch, rotation drift, alpha regression, or root-space
  backdrop sampling shift.

## Non-Goals

- Do not introduce reusable shape caches until multiple slots need the same
  rasterized shape and size.
- Do not add clip masks as public API.
- Do not mix cumulative ping-pong rendering into the first stencil slice.
- Do not add tint or noise blending while clip correctness is still being
  verified.
