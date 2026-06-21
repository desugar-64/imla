---
name: kotlin_outline
description: Use the repo-local Kotlin outline tool to inspect Kotlin file or directory structure before broad source reads, especially in Imla renderer, Compose modifier, and Android UI code.
---

# Kotlin Outline

Use this skill when navigating Kotlin code in this checkout before opening large files or broad directories.

## Tool

The repo-local tool is:

```bash
development/kotlin-outline <path>
```

It uses `ast-grep` to produce a shallow syntax outline: package, types, functions, properties, selected annotations, visibility, modifiers, direct syntactic supertypes, and line numbers. It does not resolve types, overrides, expect/actual pairs, or transitive inheritance. The extensionless shell wrapper is the stable entrypoint; the `.py` file is the implementation detail.

## Commands

Inspect one file:

```bash
development/kotlin-outline imla/src/main/java/dev/serhiiyaremych/imla/uirenderer/ImlaSceneRenderer.kt
```

Inspect a directory:

```bash
development/kotlin-outline imla/src/main/java/dev/serhiiyaremych/imla/uirenderer
```

Include private declarations:

```bash
development/kotlin-outline <path> --include-private
```

Filter to annotated declarations:

```bash
development/kotlin-outline <path> --annotations Composable,Stable,Immutable
```

## Workflow

1. Start with `rg --files` or targeted `rg` to find likely Kotlin paths.
2. Run `development/kotlin-outline` on the smallest useful file or directory.
3. Open only the files and line ranges surfaced by the outline.
4. Use full source reads for behavior, semantics, and implementation details after the outline narrows the search.

## Verification

Run the unit tests after changing the tool:

```bash
python3 -m unittest development.kotlin_outline_tests
```
