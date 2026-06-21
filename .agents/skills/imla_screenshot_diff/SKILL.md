---
name: imla_screenshot_diff
description: Compare Imla Android renderer screenshots with repo-local metrics and visual diff artifacts. Use when visual verification needs screenshot comparison, noise-tolerant renderer diffs, blur/mask/clip regression checks, or threshold-gated image comparison.
---

# Imla Screenshot Diff

Use this skill when screenshots need more than manual inspection, especially after renderer, blur, mask, clip, geometry, or bottom sheet changes.

## Tool

The repo-local tool is:

```bash
tools/screenshot-diff <reference.png> <candidate.png> [options]
```

It compares two same-size screenshots, prints metrics, and writes a report plus visual artifacts. The extensionless shell wrapper is the stable entrypoint; it creates `.codex/screenshot-diff-venv` and installs Pillow if needed. The `.py` file is the implementation detail.

## Outputs

The output directory contains:

- `report.md` and `report.json`
- `reference.png` and `candidate.png`
- `diff.png`
- `diff-amplified.png`
- `threshold-mask.png`
- `contact-sheet.png`

By default artifacts go to `diagnostics/screenshot-diff/<generated-name>/`. Pass `--out <dir>` for a specific destination.

## Commands

Compare two screenshots and generate artifacts:

```bash
tools/screenshot-diff diagnostics/screenshots/before.png diagnostics/screenshots/after.png
```

Use noise-tolerant comparison for blur-heavy output:

```bash
tools/screenshot-diff before.png after.png --pixel-threshold 8 --smooth-radius 1.0
```

Gate a regression check:

```bash
tools/screenshot-diff before.png after.png --pixel-threshold 8 --fail-changed-ratio 0.02
```

Compare only a region:

```bash
tools/screenshot-diff before.png after.png --crop 0,900,2076,1800 --out .codex/screenshot-diff/bottom-sheet
```

## Metrics

- `changed_ratio`: fraction of pixels whose max-channel delta is above `--pixel-threshold`.
- `MAE` / `RMSE`: average and squared channel error across the image.
- `max_delta`, `p95`, `p99`: high-end pixel delta distribution.
- `ssim_luma_global`: coarse luminance similarity score.

## Workflow

1. Capture reference and candidate screenshots from the same emulator display and resolution.
2. Start with `--pixel-threshold 8 --smooth-radius 1.0` for noisy blur comparisons.
3. Open `contact-sheet.png` first; use `threshold-mask.png` to locate structural changes.
4. Use crop mode for bottom sheets, slots, masks, or regions where status bar/time changes would pollute the diff.
5. Add gates only after seeing a known-good metric range for that scenario.
