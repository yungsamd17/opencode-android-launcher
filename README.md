# OpenCode Android Launcher

Native Android app (Kotlin, Jetpack Compose, Material 3) that installs Alpine Linux via PRoot,
installs Node.js + `opencode-ai`, runs `opencode web`, and hands you a localhost link.

Full plan: `opencode-android-launcher-plan.md`. Agent rules: `AGENTS.md`.

## Status

Phase 1 done and verified on a real aarch64 phone (`proot <rootfs> /bin/sh`
prints `proot-ok`, exit 0). Phase 2 done (guest `node=v24 npm=11
opencode=1.18` via `apk` + npm). Phase 3 done: foreground service
running `opencode web --hostname 127.0.0.1 --port 4096` with auto-restart,
persistent notification, and an "Open OpenCode" browser handoff.
Phase 5a/5b done: all-files access grant + project folder picker, and the
service bind-mounts the picked folder into the guest at `/project` and
runs opencode there (falls back to guest home when none is picked).
Phase 5 done: `github-cli` installs inside the guest and GitHub login uses
the device-code flow (one-time code + browser link on the GitHub screen) —
opencode's agent runs git itself from there.

## Build (CI)

Pushes to `main` and PRs build via `.github/workflows/ci.yml`:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Local sandboxes without Android SDK/JDK should let CI verify — never commit a non-building state.

## Licensing

GPLv3 — see `LICENSE` and `NOTICES.md`. Source must stay public.
