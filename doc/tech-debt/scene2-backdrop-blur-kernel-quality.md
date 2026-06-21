# Scene2 Backdrop Blur Kernel Quality

## Purpose

Track the remaining blur-quality work separately from geometry and resource
wiring.

## Current State

The scratch renderer currently uses a simple two-pass separable blur. It is good
enough to prove the pass chain:

1. prepare backdrop sample;
2. blur horizontally into a borrowed FBO;
3. blur vertically into the processed FBO;
4. composite the processed backdrop in screen space;
5. draw slot content.

Large blur areas showed visible bending or banding during verification.

Observed results:

- full `highp` fragment math fixed the artifact but cost too much performance;
- `RGB10_A2` intermediate FBOs did not remove the artifact;
- disabling Wronski-style prepare prefilter did not remove the artifact;
- forcing progressive blur strength to `1.0` did not remove the artifact;
- high precision in prepare, composite, and selected blur shader paths improved
  the result enough for the current prototype;
- the current blur radius cap is `8px`.

## Planned Change

Replace the temporary blur body with a higher-quality variable-kernel algorithm
while preserving the current pass boundaries.

The next algorithm should:

- keep blur radius measured in root/screen pixels;
- preserve the stable diagonal sample envelope for rotated slots;
- keep UV-bounds validity and weight renormalization;
- avoid clamp-based edge hiding as the primary fix;
- evaluate precision costs before switching whole shaders to `highp`;
- prefer paired or optimized taps if they preserve visual quality.

## Non-Goals

- Do not change the slot/modifier API as part of blur kernel replacement.
- Do not replace the current sample-region geometry while tuning blur math.
- Do not treat intermediate FBO format alone as the quality fix without new
  evidence.

## Verification

Use a blur-heavy scene with large flat and patterned areas. Check:

- bending or banding;
- edge darkening;
- root-space drift during X, Y, Z, and XY rotation;
- stretch at rotated slot corners;
- performance regression on the lower-end device.

