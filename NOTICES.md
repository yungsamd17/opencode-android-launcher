# Notices

This project will adapt bootstrap logic from:

- `termux/termux-app` — `TermuxInstaller.java` bootstrap logic (GPLv3)
  https://github.com/termux/termux-app
- `termux/proot-distro` — Alpine plugin script, rootfs URLs + checksums (GPLv3)
  https://github.com/termux/proot-distro

## Phase 0 status

No GPLv3-derived code is vendored yet. When Phase 1 ports that logic:

- Keep a comment header in each adapted file noting origin (repo + file path + commit)
- List the exact files/commits here
- Keep the repo public under GPLv3 (`LICENSE`)

Original code in this project (Compose UI, service, Kotlin glue not derived from
Termux) is part of the combined GPLv3-covered app on distribution.
