# Scene2 Technical Debt

This folder tracks follow-up work that is real enough to preserve, but not part
of the current implementation slice.

Use one document per purpose so renderer work can move in small verified steps
without mixing API cleanup, shader quality, and validation tasks.

Current source of truth remains `doc/scene2-scratch-renderer-status.md`. These
notes describe future cleanup and investigation, not active architecture by
themselves.

Current notes:

- `scene2-backdrop-blur-kernel-quality.md`
- `scene2-progressive-mask-validation.md`
- `scene2-quad-batch-texture-reservations.md`
- `scene2-stencil-clipping.md`
