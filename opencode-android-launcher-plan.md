# OpenCode Android Launcher — Build Plan

## Goal

A native Android app that:
1. Installs a minimal Linux runtime on-device (no root, no PC)
2. Auto-installs Node.js + `opencode-ai` inside it
3. Runs `opencode web` as a persistent background process
4. Hands the user a link that opens OpenCode's own web UI in their browser (chat, git diffs, file editing — all of it, since it's the real OpenCode interface, not a rebuilt one)

Deliberately *not* building: a native chat UI, SSE stream parsing, a diff viewer, tool-approval screens. `opencode web` already does all of that. This app's whole job is bootstrap + process management + handoff.

## Architecture

```
┌─────────────────────────────────────────────┐
│  MainActivity (Compose)                      │
│  - Setup/progress screen                     │
│  - Home screen: status + "Open OpenCode"     │
│  - Settings: storage access, restart/stop    │
└───────────────┬───────────────────────────────┘
                │ binds to
┌───────────────▼───────────────────────────────┐
│  OpenCodeForegroundService                    │
│  - Owns the runtime lifecycle                 │
│  - Persistent notification (start/stop)       │
│  - Restarts process if it dies                │
└───────────────┬───────────────────────────────┘
                │ ProcessBuilder / exec
┌───────────────▼───────────────────────────────┐
│  RuntimeBootstrap (adapted from               │
│  TermuxInstaller.java + proot-distro)         │
│  - Detects device ABI (arm64/x86_64)          │
│  - Downloads Alpine minirootfs + proot binary │
│  - Verifies checksums, extracts to app files  │
│  - apk add nodejs npm git (inside proot)      │
│  - npm install -g opencode-ai (inside proot)  │
└───────────────┬───────────────────────────────┘
                │ proot exec
┌───────────────▼───────────────────────────────┐
│  Alpine rootfs (private app storage)          │
│  running: opencode web --hostname 127.0.0.1   │
└─────────────────────────────────────────────┘
```

Nice side effect of this architecture: since the user interacts through a *real browser tab*, OAuth flows for model providers (Claude, Gemini, etc.) just work with normal browser redirects — no custom URI-scheme handling needed, unlike apps that build a native chat UI.

## Phased Plan

### Phase 0 — Project skeleton
- New Kotlin/Compose project, min SDK ~26 (PRoot/proot needs a reasonably modern kernel; check current Termux min SDK for reference)
- Package name, app icon, basic nav: Setup screen ↔ Home screen ↔ Settings screen
- Add `INTERNET` permission; decide on `MANAGE_EXTERNAL_STORAGE` vs. SAF for later repo access

### Phase 1 — Runtime bootstrap (the hard part)
- Port the relevant slice of `TermuxInstaller.java`: bootstrap zip download, checksum verification, extraction into `filesDir/proot-env/`
- Pull the Alpine plugin logic from `proot-distro` for rootfs URLs + checksums per architecture (aarch64, x86_64 at minimum — most phones are aarch64)
- Source a `proot` binary built for Android/Bionic (Termux's package build recipes cover this; you're bundling/downloading the binary itself, not linking against its code)
- Get a bare `proot <alpine-rootfs>` shell working end-to-end via `ProcessBuilder` before touching anything else — this is the milestone that de-risks the whole project
- **Test on real hardware early** — emulator x86_64 vs. real-device aarch64 behave differently under proot

### Phase 2 — Package + OpenCode install
- Inside the rootfs: `apk add nodejs npm git openssh` (or whatever OpenCode's install docs specify)
- `npm install -g opencode-ai`
- Wrap this as a one-time "first run" setup sequence with progress reporting back to the UI (percentage or step labels — downloading, extracting, installing packages)
- Handle failure/retry (flaky downloads, low storage, wrong architecture)

### Phase 3 — Foreground Service
- `OpenCodeForegroundService` starts `opencode web --hostname 127.0.0.1 --port <fixed or random>` via proot exec, captures stdout to find the URL/port it bound to
- Persistent notification with Start/Stop actions (same pattern you used in RevNotify)
- Auto-restart on unexpected process death; don't auto-restart on user-initiated stop
- Request battery-optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) so Doze doesn't kill it — prompt for this explicitly rather than silently, users are wary of that permission

### Phase 4 — Home UI + browser handoff
- Status card: stopped / starting / running, with the bound URL once known
- "Open OpenCode" button → `Intent.ACTION_VIEW` on the localhost URL (Chrome Custom Tabs is a nicer feel than a bare browser intent)
- Settings: re-run setup, wipe rootfs, view logs, storage access grant

### Phase 5 — Repo access, files, and git

**File access:** SAF folder pickers hand you a `content://` URI, which a native process (proot/opencode) can't bind-mount or operate on directly. Request **all-files access** (`MANAGE_EXTERNAL_STORAGE`) instead so you get a real filesystem path (e.g. `/storage/emulated/0/Projects/myrepo`) to bind-mount into the rootfs — this is why AndCode requests the same permission. Trade-off: it draws extra Play Store scrutiny (special declaration required), so plan to ship via F-Droid/direct APK first and deal with Play Store separately if you want it there.

**Git — no custom UI needed.** Once `git` is installed inside the Alpine rootfs, OpenCode's agent runs git itself (status, diff, commit) as normal tool use. The only thing the app needs to solve is auth, once:
- `apk add github-cli` inside the rootfs, then shell out to `gh auth login` — this uses GitHub's device-code flow (prints a code + a `github.com/login/device` link), which fits the app's existing "here's a link, open your browser" pattern with no custom OAuth redirect handling
- Alternative: generate an SSH keypair on first run (`ssh-keygen`) and display the public key for the user to paste into GitHub — more manual, no `gh` dependency
- Credentials live in the app's private storage, same trust boundary as any locally-stored token on a non-rooted device

**User flow:** connect GitHub (device code + link) → grant all-files access → pick/create a project folder on shared storage, `gh repo clone` into it (or point at an existing folder) → open the OpenCode web link and work — files stay on real shared storage the whole time, so any other Android app can also read/edit them in parallel.

- Decide default: fresh workspace inside the rootfs vs. always prompting for a folder

### Phase 6 — Polish / hardening
- Architecture mismatch handling (clear error if a device reports an unsupported ABI)
- Low storage pre-check before starting the ~150-300MB download
- Optional: boot-time auto-start (mirrors Termux:Boot) if you want 24/7 availability
- Crash reporting / log export for your own debugging

### Phase 7 — Licensing & distribution
- Since the bootstrap/proot-distro code is GPLv3, your repo needs a GPLv3 `LICENSE` and publicly available source
- Add a `NOTICES.md` crediting `termux-app` and `proot-distro` with links to the exact files/commits you adapted
- You can still distribute via Play Store/F-Droid/direct APK and even charge — just the source has to be available to anyone you give the binary to
- Keep any of your own genuinely original code (UI, service, glue logic) clearly identified — doesn't change the overall license of the combined app, but keeps the attribution honest

### Phase 8 — Testing & release
- Test across at least one aarch64 phone and, if possible, one x86_64 device/emulator
- Test cold start, backgrounding for hours, force-stop + relaunch, low-storage scenarios
- Soft-launch to yourself for a week before wider release

## Open decisions to make before Phase 1
- Fixed port (simpler, riskier if occupied) vs. random port (need to parse it from process output)
- Bundle Alpine minirootfs in the APK (bigger download, works offline after install) vs. fetch on first run (smaller APK, needs network on setup) — AndCode does the latter
- One persistent rootfs vs. supporting multiple isolated workspaces later

## Suggested next step
Get Phase 1 working as a standalone proof of concept (a debug-only screen with a "test bootstrap" button and a raw log view) before building any real UI around it — that's where the actual risk lives.
