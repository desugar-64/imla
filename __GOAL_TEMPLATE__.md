# Goal Template

Use this template for third-party agent goals. Be explicit about scope,
constraints, expected verification, and final reporting. More detail is better
when the task touches Android/JVM tests, renderer behavior, or architecture
boundaries.

```text
Goal: <one exact outcome>

Context:
- Current commit/base:
- Relevant files:
- Existing behavior that must not change:
- Why this task exists:
- Repository must finish clean and committed.

Required approach:
1. <specific first step>
2. <specific second step>
3. <preferred seam/design>
4. <exact tests to add/change>
5. <docs to update, if any>

Allowed:
- <explicitly allowed production seam>
- <explicitly allowed test fake>
- <allowed docs/tests>

Forbidden:
- Do not <known bad workaround>.
- Do not <scope creep>.
- Do not <runtime behavior change>.
- Do not <global Gradle/test config change>.
- Do not <Android framework hacks>.

Implementation hints:
- Prefer this shape:
  ```kotlin
  <small example of desired seam or API>
  ```
- Avoid this shape:
  ```kotlin
  <small example of the risky/banned approach>
  ```

JVM test constraints:
- Do not instantiate Android framework or Compose UI objects if that triggers
  JVM framework initialization.
- Do not add Robolectric unless explicitly requested.
- Do not set `unitTests.isReturnDefaultValues = true`.
- Do not add `--add-opens`.
- Do not use `sun.misc.Unsafe`.
- Do not mutate `android.os.Build`.
- Prefer a narrow internal seam/fake over global test runtime changes.
- If a JVM test cannot be written without those hacks, stop and report the
  blocker.

Source-test constraints:
- Prefer behavioral tests.
- Use source-string tests only for forbidden symbols, files, imports, or assets.
- Scope source assertions to exact class/function bodies.
- Do not broad-grep whole files when an unrelated backend/helper can satisfy the
  same string.

Visual correctness:
- State whether this task can affect rendering output.
- If yes, require screenshot parity or diagnostic screenshots for the relevant
  surface.
- Name the expected good result and the specific bad artifacts to check:
  crop/Y-flip, stretch, rotation drift, stale frames, alpha/color, mask/clip
  alignment, halos, or edge darkening.
- If visual verification cannot run, document the exact blocker.

Expected verification:
- <focused JVM test command>
- <compile command if production code changed>
- <visual/device command if rendering can change>
- Review `git diff` for intentional files only.

Finish criteria:
- End with all intended tracked changes staged and committed.
- Do not stage diagnostics, screenshots, traces, logs, or generated artifacts
  unless explicitly requested as tracked summary docs.
- `git status --short` must be clean.

Expected final response:
1. Commit hash.
2. Files changed.
3. Verification commands run.
4. What behavior changed.
5. What behavior explicitly did not change.
6. Any remaining blocked/manual validation.
7. `git status --short` result.
```

## Scratch Renderer Defaults

For scratch renderer goals, include these defaults unless the task explicitly says
otherwise:

- Current status doc: `doc/scene2-scratch-renderer-status.md`.
- Keep the scratch scene path scene-only and renderer-free for child slots.
- Do not restore removed `UiLayerRenderer`, `ImlaRenderPipeline`,
  `CopyLessRenderingPipeline`, `RenderObject`, or renderer-taking modifier/host
  APIs.
- Do not port the removed Renderer 2 atlas/effects roadmap into the scratch
  prototype.
- Keep Compose `GraphicsLayer` ownership in modifiers and OpenGL ownership in
  the renderer.
- Keep main-thread capture separate from GL-thread import/render.
- Do not add full blur, masks, clips, or atlas routing unless the goal
  explicitly asks for that and requires visual evidence.

## Android/ADB Defaults

- Use `tools/adb-timeout` for every ADB command.
- Prefer `tools/adb-screenshot-half` for ordinary inline screenshot checks.
- Force-stop `dev.serhiiyaremych.imla` after diagnostics.
- Do not leave diagnostic log tags enabled.
- Generated diagnostics usually belong under `diagnostics/apa/` and should not
  be staged unless the goal explicitly asks for a tracked summary.
