---
name: imla_adb_timeout
description: Run Imla ADB commands through the repo-local timeout wrapper so physical-device installs, launches, input, screenshots, package-manager calls, and diagnostics fail fast instead of hanging.
---

# Imla ADB Timeout

Use this skill for any ADB operation in this repo, especially with physical devices.

## Tool

Use the repo-local wrapper:

```bash
tools/adb-timeout [--timeout seconds] [--device adb-serial] -- <adb-args...>
```

The wrapper forwards adb stdout/stderr unchanged, returns adb's exit code on normal completion, and returns `124` on timeout.

## Defaults

- Use `--timeout 10` for quick reads such as `devices`, `shell wm size`, `pidof`, or `getprop`.
- Use `--timeout 20` for taps, swipes, screenshots, `force-stop`, and small shell commands.
- Use `--timeout 60` for install and launch.
- Use `--timeout 180` or higher only for known long-running operations such as instrumentation tests.

## Examples

```bash
tools/adb-timeout --timeout 10 devices -l
tools/adb-timeout --device T81164GB23417442888 --timeout 20 shell wm size
tools/adb-timeout --device T81164GB23417442888 --timeout 60 install -r app/build/outputs/apk/debug/app-debug.apk
tools/adb-timeout --device T81164GB23417442888 --timeout 20 shell am force-stop dev.serhiiyaremych.imla
tools/adb-timeout --device T81164GB23417442888 --timeout 20 exec-out screencap -p > diagnostics/apa/screenshots/physical.png
```

## Workflow

1. Discover devices with `tools/adb-timeout --timeout 10 devices -l`.
2. Pick an explicit device serial when more than one target may exist.
3. Wrap every ADB command in this tool during verification.
4. If a command returns `124`, treat it as an infrastructure/device timeout, not an app assertion failure.
5. Always force-stop the app through this wrapper after renderer diagnostics.
