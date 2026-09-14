# OpenCode Android Launcher

Native Android app (Kotlin, Jetpack Compose, Material 3) that installs Alpine Linux via PRoot,
installs Node.js + `opencode-ai`, runs `opencode web`, and hands you a localhost link.

Full plan: `opencode-android-launcher-plan.md`. Agent rules: `AGENTS.md`.

## Status

Phase 1 done and verified on a real aarch64 phone (`proot <rootfs> /bin/sh`
prints `proot-ok`, exit 0). Phase 2 in progress: guest `apk add nodejs npm
git openssh` + `npm install -g opencode-ai` via the Setup screen's
"Install Node + OpenCode" button.

## Build (CI)

Pushes to `main` and PRs build via `.github/workflows/ci.yml`:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Local sandboxes without Android SDK/JDK should let CI verify — never commit a non-building state.

## Licensing

GPLv3 — see `LICENSE` and `NOTICES.md`. Source must stay public.
