---
name: imla_adb_screenshot_half
description: Capture Imla Android screenshots through the repo-local half-size ADB screenshot wrapper to reduce visual-inspection token cost while preserving enough detail for renderer checks.
---

# Imla Half-Size ADB Screenshot

Use this skill for ordinary Imla visual verification screenshots when full native resolution is not required. It is especially useful for renderer smoke checks, orientation markers, layout sanity checks, and screenshots that will be opened inline in Codex.

## Tool

The repo-local tool is:

```bash
tools/adb-screenshot-half [--device adb-serial] [--timeout seconds] [--out path]
```

It captures `screencap -p` through `tools/adb-timeout`, extracts PNG bytes, resizes the image to half width and half height, and writes a PNG. The extensionless shell wrapper is the stable entrypoint; it creates `.codex/adb-screenshot-venv` and installs Pillow if needed. The `.py` file is the implementation detail.

## Defaults

- `--timeout 20`
- `--out diagnostics/apa/screenshots/screenshot-half-<timestamp>.png`

## Commands

Capture from the current explicit test device:

```bash
tools/adb-screenshot-half --device T81164GB23417442888 --out diagnostics/apa/screenshots/current-half.png
```

Capture with the default timestamped destination:

```bash
tools/adb-screenshot-half --device T81164GB23417442888
```

## Workflow

1. Launch or interact with the app through `tools/adb-timeout`.
2. Wait briefly for the target frame to settle when needed.
3. Capture with `tools/adb-screenshot-half` instead of raw `exec-out screencap -p`.
4. Open the half-size PNG for inline visual inspection.
5. Use full-size screenshots only when checking exact pixels, fine text, masks, clips, or screenshot-diff inputs that must match a full-resolution reference.
6. Always force-stop `dev.serhiiyaremych.imla` through `tools/adb-timeout` after renderer diagnostics.
