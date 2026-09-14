# OpenCode Android Launcher

Native Android app (Kotlin, Jetpack Compose, Material 3) that installs Alpine Linux via PRoot,
installs Node.js + `opencode-ai`, runs `opencode web`, and hands you a localhost link.

Full plan: `opencode-android-launcher-plan.md`. Agent rules: `AGENTS.md`.

## Status

Phase 0 skeleton: Setup ↔ Home ↔ Settings nav, ABI detection, foreground-service stub,
bootstrap POC entry point. Phase 1 (real proot + Alpine bootstrap) is next.

## Build (CI)

Pushes to `main` and PRs build via `.github/workflows/ci.yml`:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Local sandboxes without Android SDK/JDK should let CI verify — never commit a non-building state.

## Licensing

GPLv3 — see `LICENSE` and `NOTICES.md`. Source must stay public.
