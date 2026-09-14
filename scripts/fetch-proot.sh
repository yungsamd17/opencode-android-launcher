#!/bin/sh
# fetch-proot.sh — download Termux-built proot + runtime deps for Android/Bionic.
#
# Provenance: official Termux apt repo (https://packages.termux.dev).
# Every file is SHA-256 verified against the repo's Packages index before use.
# We bundle the *binaries* only (not their code) per the build plan — see NOTICES.md.
#
# Usage: sh scripts/fetch-proot.sh [out-dir]
# Output: <out-dir>/<aarch64|x86_64>/{proot,libtalloc.so*,libandroid-shmem.so*}
set -eu
OUT="${1:-/tmp/proot-pkgs}"
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

for ARCH in aarch64 x86_64; do
  D="$OUT/$ARCH"
  mkdir -p "$D"
  case "$ARCH" in
    aarch64)
      PROOT_SHA=1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9
      TALLOC_SHA=ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da
      SHMEM_SHA=0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6
      ;;
    x86_64)
      PROOT_SHA=70236632826c30ec0245082b633bbc7ef1e9fa5531bd51bd4f20231bfcdc999b
      TALLOC_SHA=7ca2eaae2e53b28228a01301bc410b62845403d6317c25b8e0a7f40681de0628
      SHMEM_SHA=ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797
      ;;
  esac
  fetch_deb "pool/main/p/proot/proot_5.1.107.92_${ARCH}.deb" "$PROOT_SHA" "$D/proot.deb"
  fetch_deb "pool/main/libt/libtalloc/libtalloc_2.4.3_${ARCH}.deb" "$TALLOC_SHA" "$D/libtalloc.deb"
  fetch_deb "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_${ARCH}.deb" "$SHMEM_SHA" "$D/libandroid-shmem.deb"
  for PKG in proot libtalloc libandroid-shmem; do
    extract_member "$D/$PKG.deb" "data.tar" "$D/$PKG-data.tar.xz"
  done
  # data.tar.xz -> *-data.tar (member is "data.tar.xz")
  for PKG in proot libtalloc libandroid-shmem; do
    unxz -f "$D/$PKG-data.tar.xz"
  done
  echo "--- $ARCH contents (proot):"
  tar -tf "$D/proot-data.tar"
done
echo "OK -> $OUT"
