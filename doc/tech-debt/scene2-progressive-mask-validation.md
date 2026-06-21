# Scene2 Progressive Mask Validation

## Purpose

Keep progressive-mask follow-up work focused on mask semantics and verification,
not on blur geometry or batch renderer cleanup.

## Current State

Progressive masks are rasterized from Compose `Brush` instances at slot-local
size, imported as GL textures, and passed to the blur shader as optional mask
input.

The current semantics are:

- alpha from the mask controls blur strength;
- the mask is sampled in slot-local space through `visibleInSample`;
- the blur output remains opaque;
- ordinary opacity masking is separate from progressive blur strength.

The current texture slot wiring works through `QuadBatchRenderer` deferred
shader configuration, but that ordering is easy to misread. The reservation API
cleanup is tracked separately in
`doc/tech-debt/scene2-quad-batch-texture-reservations.md`.

## Planned Change

After the variable-kernel blur body is replaced, revalidate progressive masks
against that algorithm.

Focus on:

- whether mask alpha maps linearly to sigma or needs an easing curve;
- whether the mask should affect blur radius, kernel weights, or source choice;
- whether slot-local mask rasterization remains correct for rotated cards;
- whether repeated passes preserve expected progressive strength;
- whether diagnostics need a temporary crisp/low-pass tint toggle again.

## Non-Goals

- Do not merge progressive blur strength with opacity mask blending.
- Do not introduce a separate mask data type when `Texture2D` already carries the
  imported mask texture.
- Do not move Compose `Brush` ownership into renderer resources.

## Verification

Use a high-contrast gradient mask over a high-frequency background. Confirm:

- zero-alpha mask areas show crisp backdrop, not low-pass fog;
- one-alpha mask areas show full requested blur;
- intermediate mask values vary blur strength, not only blend opacity;
- mask alignment survives slot translation, scaling, and rotation;
- no Y-flip appears when mask textures use bottom-left GL origin.

