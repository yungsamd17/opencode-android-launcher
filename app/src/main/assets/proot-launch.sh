#!/system/bin/sh
# proot-launch.sh — guest entry point for `opencode web` (Phase 3).
#
# Kept as a plain asset per project convention: shell logic lives here,
# Kotlin only execs it via ProcessBuilder with explicit args.
# Args: $1 = rootfs dir, $2.. = guest command (default: /bin/sh).
#
# Phase 1 uses ProotRunner.buildCommand() directly for the smoke test;
# this script becomes the canonical launcher once the proot binary ships.
set -eu
ROOTFS="${1:?usage: proot-launch.sh <rootfs> [cmd...]}"
shift
if [ "$#" -eq 0 ]; then
  set -- /bin/sh
fi
# PROOT_BIN is injected by the app (explicit path, never bare $PATH).
exec "${PROOT_BIN:?PROOT_BIN not set}" \
  --rootfs="$ROOTFS" \
  --link2symlink \
  --kill-on-exit \
  --bind=/proc \
  --bind=/dev \
  --bind=/sys \
  --cwd=/ \
  -- "$@"
