# AGENTS.md — OpenCode Android Launcher

## Project overview

Native Android app (Kotlin, Jetpack Compose, Material 3) that:
1. Installs a minimal Alpine Linux runtime on-device via PRoot — no root, no PC
2. Installs Node.js + the `opencode-ai` npm package inside that runtime
3. Runs `opencode web` as a persistent background process
4. Hands the user a localhost link that opens OpenCode's own web UI in their browser

Full phased plan lives in `opencode-android-launcher-plan.md` in this repo — read it before starting work, and work through its phases **in order**. Don't jump ahead to UI polish while Phase 1 (runtime bootstrap) is still unproven.

## Non-goals — do not build these

- A native chat UI, message list, or streaming response renderer. `opencode web` provides the entire UI. If you find yourself building chat bubbles or a diff viewer, stop — that means the browser handoff isn't being used correctly.
- Custom OAuth URI-scheme handling. Because the user interacts through a real browser tab, provider OAuth redirects work with normal browser behavior. Don't add deep-link intent filters for this.
- Support for iOS, desktop, or any platform beyond Android.

## Tech stack & conventions

- Kotlin, Jetpack Compose, Material 3 — match existing app conventions (see Koda for icon/color palette style if a shared visual identity is wanted later, though this app doesn't need to match it)
- Coroutines for async work (bootstrap download/extract, process I/O) — no callback-based threading
- `ProcessBuilder` for all proot/shell invocations; keep shell scripts as plain files in `assets/`, don't hand-translate shell logic into Kotlin string concatenation
- Foreground `Service` for the running OpenCode process, following the same pattern as the existing RevNotify app (persistent notification, explicit start/stop, restart-on-crash but not restart-on-user-stop)

## Architecture (see plan doc for full diagram)

```
MainActivity (Compose) → binds → OpenCodeForegroundService → ProcessBuilder/exec
                                        ↓
                          RuntimeBootstrap (adapted from
                          termux-app's TermuxInstaller.java
                          + proot-distro's Alpine plugin)
                                        ↓
                          Alpine rootfs in app-private storage
                          running `opencode web --hostname 127.0.0.1`
```

## Current phase / task queue

Work through `opencode-android-launcher-plan.md` phases 0 → 8 in order. At the start of each session, check which phase's exit criteria are already met before starting new work. Phase 1 (bootstrap) is the highest-risk phase — do not consider it done until a bare `proot <alpine-rootfs> /bin/sh` invocation succeeds via `ProcessBuilder` on a **real aarch64 device**, not just an emulator.

## Licensing constraints — do not skip

- Code adapted from `termux-app` (`TermuxInstaller.java` bootstrap logic) and `proot-distro` (Alpine plugin script) is **GPLv3**. Any file substantially adapted from either must:
  - Keep a comment header noting its origin (repo + file path + commit if known)
  - Be listed in `NOTICES.md` at the repo root
- The overall app repository must carry a `LICENSE` file (GPLv3) and the source must be publicly available — do not mark the repo private after adding this code, and do not add proprietary license headers to files derived from GPLv3 sources
- Code that is genuinely original to this project (Compose UI, the Service, Kotlin glue code not derived from Termux) can be attributed to the project's own license/copyright, but the combined distributed app is governed by GPLv3 as a whole
- If unsure whether a piece of code counts as "derived," err toward crediting it in `NOTICES.md`

## Testing requirements before marking a phase complete

- Phase 1–3: test on a physical aarch64 Android device, not only an emulator — PRoot behavior differs meaningfully between them
- Test cold start, app backgrounded for >30 min (Doze), force-stop + relaunch, and low-storage conditions before considering the bootstrap/service phases done
- Do not mark the battery-optimization exemption flow as complete without verifying the process actually survives Doze on a real device

## Do not

- Do not bundle a precompiled `opencode-ai` binary of unknown provenance — install it via `npm install -g opencode-ai` inside the rootfs so it comes from the official registry
- Do not hardcode a single CPU architecture — detect via `Build.SUPPORTED_ABIS` and fail with a clear error on unsupported ones rather than silently defaulting
- Do not silently retry failed downloads indefinitely — surface failures to the setup UI with a retry action
