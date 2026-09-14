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

## Bundled proot binaries (Phase 1)

`app/src/main/assets/proot/` contains *binaries only* (not source) from the
official Termux apt repo, SHA-256 verified at fetch time via
`scripts/fetch-proot.sh`:

- proot 5.1.107.92 (PRoot GPLv2; Termux build, NDK r29, 16KB-page aligned)
  - aarch64: `pool/main/p/proot/proot_5.1.107.92_aarch64.deb`
    SHA256 `1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9`
  - x86_64: `pool/main/p/proot/proot_5.1.107.92_x86_64.deb`
    SHA256 `70236632826c30ec0245082b633bbc7ef1e9fa5531bd51bd4f20231bfcdc999b`
- libtalloc 2.4.3 (runtime dep, SONAME `libtalloc.so.2`)
- libandroid-shmem 0.7 (runtime dep, `libandroid-shmem.so`)
- proot's `libexec/proot/{loader,loader32}` helpers (statically linked)

Upstream: https://github.com/termux/termux-packages (proot package),
PRoot itself: https://github.com/proot-me/proot.
Re-run `sh scripts/fetch-proot.sh` to reproduce the assets from scratch.

Original code in this project (Compose UI, service, Kotlin glue not derived from
Termux) is part of the combined GPLv3-covered app on distribution.
