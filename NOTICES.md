# Notices

This project will adapt bootstrap logic from:

- `termux/termux-app` — `TermuxInstaller.java` bootstrap logic (GPLv3)
  https://github.com/termux/termux-app
- `termux/proot-distro` — Alpine plugin script, rootfs URLs + checksums (GPLv3)
  https://github.com/termux/proot-distro

## Phase 1 status

No verbatim GPLv3 code is vendored yet. The download → verify → extract flow in
`RuntimeBootstrap.kt` follows the same steps as:

- `termux/termux-app` — `app/src/main/java/com/termux/app/TermuxInstaller.java`
  (staging dir, checksum verification, atomic rename; master as of 2026-09-14)
  https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxInstaller.java
- `termux/proot-distro` — `distro-build/alpine.sh` + `build-recipes/alpine.yaml`
  (Alpine minirootfs CDN URL pattern, `.sha256` sidecar, tmp-file download;
  Alpine 3.23.3; commit 43a79f8c / 2026-04-03 recipe)
  https://github.com/termux/proot-distro/blob/43a79f8c/distro-build/alpine.sh

Only the URL pattern + procedural steps (facts/ideas) are used — all Kotlin here
is original glue. If verbatim adaptation lands later, it will carry a GPLv3
origin header in-file and be listed here with exact commits.

Original code in this project (Compose UI, service, Kotlin glue not derived from
Termux) is part of the combined GPLv3-covered app on distribution.
