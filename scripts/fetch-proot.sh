#!/bin/sh
# fetch-proot.sh — download Termux-built proot + runtime deps for Android/Bionic.
#
# Provenance: official Termux apt repo (https://packages.termux.dev).
# Every .deb is SHA-256 verified against the repo's Packages index before use.
# We bundle the *binaries* only (not their code) per the build plan — see NOTICES.md.
#
# Why jniLibs and not assets/ + chmod: apps targeting SDK 29+ cannot exec
# binaries from writable app-data dirs (SELinux EACCES, error=13). The native
# library dir IS executable, so the proot payload ships as "native libs" and
# runs from applicationInfo.nativeLibraryDir. application also needs
# android:extractNativeLibs="true" for this to be extracted on install.
#
# Naming: proot + its loaders are renamed to *.so (installer-extracted with
# exec permission); proot is told the loader paths via PROOT_LOADER[_32] env.
# libtalloc keeps its exact SONAME (libtalloc.so.2) — proot DT_NEEDED asks for
# it by that name. If the installer ever skips non-.so names, the app falls
# back to extracting them from its own APK (ZipFile on sourceDir).
#
# Usage: sh scripts/fetch-proot.sh [jniLibs-dir]
# Output: <jniLibs-dir>/<arm64-v8a|x86_64>/{libproot.so,libproot_loader.so,
#   libproot_loader32.so,libtalloc.so.2,libandroid-shmem.so}
set -eu
OUT="${1:-app/src/main/jniLibs}"
REPO="https://packages.termux.dev/apt/termux-main"
mkdir -p "$OUT"

fetch_deb() { # <filename> <sha256> <out.deb>
  if [ -f "$3" ] && [ "$(sha256sum "$3" | awk '{print $1}')" = "$2" ]; then
    echo "cached $1"
    return 0
  fi
  echo "downloading $1 ..."
  curl --fail --location --retry 3 --output "$3.tmp" "$REPO/$1"
  ACTUAL="$(sha256sum "$3.tmp" | awk '{print $1}')"
  if [ "$ACTUAL" != "$2" ]; then
    echo "SHA256 MISMATCH for $1: expected $2 got $ACTUAL" >&2
    rm -f "$3.tmp"
    return 1
  fi
  mv -f "$3.tmp" "$3"
}

extract_member() { # <deb> <member-prefix> <dest-file>  (python: no `ar` on all hosts)
  python3 - "$1" "$2" "$3" <<'EOF'
import sys
deb, prefix, dest = sys.argv[1], sys.argv[2], sys.argv[3]
with open(deb, 'rb') as f:
    assert f.read(8) == b'!<arch>\n', 'not an ar archive'
    while True:
        hdr = f.read(60)
        if not hdr:
            break
        name = hdr[0:16].decode().strip().rstrip('/')
        size = int(hdr[48:58].decode().strip())
        if name.startswith(prefix):
            with open(dest, 'wb') as o:
                o.write(f.read(size))
            print('extracted ' + name + ' -> ' + dest)
        else:
            f.seek(size, 1)
        if size % 2:
            f.seek(1, 1)
EOF
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for ARCH in aarch64 x86_64; do
  case "$ARCH" in
    aarch64) ABI=arm64-v8a
      PROOT_SHA=1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9
      TALLOC_SHA=ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da
      SHMEM_SHA=0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6
      ;;
    x86_64) ABI=x86_64
      PROOT_SHA=70236632826c30ec0245082b633bbc7ef1e9fa5531bd51bd4f20231bfcdc999b
      TALLOC_SHA=7ca2eaae2e53b28228a01301bc410b62845403d6317c25b8e0a7f40681de0628
      SHMEM_SHA=ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797
      ;;
  esac
  D="$work/$ARCH"
  mkdir -p "$D"
  fetch_deb "pool/main/p/proot/proot_5.1.107.92_${ARCH}.deb" "$PROOT_SHA" "$D/proot.deb"
  fetch_deb "pool/main/libt/libtalloc/libtalloc_2.4.3_${ARCH}.deb" "$TALLOC_SHA" "$D/libtalloc.deb"
  fetch_deb "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_${ARCH}.deb" "$SHMEM_SHA" "$D/libandroid-shmem.deb"
  for PKG in proot libtalloc libandroid-shmem; do
    extract_member "$D/$PKG.deb" "data.tar" "$D/$PKG-data.tar.xz"
    unxz -f "$D/$PKG-data.tar.xz"
  done
  U="$D/usr"
  mkdir -p "$U/bin" "$U/lib" "$U/libexec"
  tar -xf "$D/proot-data.tar" -C "$U/bin" --strip-components=7 ./data/data/com.termux/files/usr/bin/proot
  tar -xf "$D/proot-data.tar" -C "$U/libexec" --strip-components=7 ./data/data/com.termux/files/usr/libexec/proot/loader ./data/data/com.termux/files/usr/libexec/proot/loader32
  tar -xf "$D/libtalloc-data.tar" -C "$U/lib" --strip-components=7 ./data/data/com.termux/files/usr/lib/libtalloc.so.2 ./data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3
  tar -xf "$D/libandroid-shmem-data.tar" -C "$U/lib" --strip-components=7 ./data/data/com.termux/files/usr/lib/libandroid-shmem.so
  DEST="$OUT/$ABI"
  mkdir -p "$DEST"
  cp "$U/bin/proot" "$DEST/libproot.so"
  cp "$U/libexec/proot/loader" "$DEST/libproot_loader.so"
  cp "$U/libexec/proot/loader32" "$DEST/libproot_loader32.so"
  # libtalloc.so.2 is a symlink in the .deb — apks can't hold symlinks,
  # and the linker needs the real bytes under the exact SONAME.
  cp -L "$U/lib/libtalloc.so.2" "$DEST/libtalloc.so.2"
  cp "$U/lib/libandroid-shmem.so" "$DEST/libandroid-shmem.so"
  echo "--- $ABI:"
  ls -la "$DEST"
done
echo "OK -> $OUT"
