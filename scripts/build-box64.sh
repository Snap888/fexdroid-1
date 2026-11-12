#!/usr/bin/env bash
# Cross-build Box64 for the ANLR ARM64 host rootfs.
# Box64 is an alternative x86-64 userspace backend; it is not a Wine/Windows
# compatibility layer and does not replace FEX.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/config.sh

BOX64_TAG="${BOX64_TAG:-v0.4.4}"
SYSROOT="$PWD/build/rootfs/sysroot-arm64"
OUT="$PWD/build/box64"
SRC="$OUT/src"
BUILD="$OUT/obj"
INSTALL="$OUT/install"

[[ -d "$SYSROOT/usr/include" ]] || { echo "run scripts/build-rootfs.sh first" >&2; exit 1; }
[[ -f "$PWD/build/glibc/install$FXD_LDSO" ]] || { echo "run scripts/build-glibc.sh first" >&2; exit 1; }

mkdir -p "$OUT"
if [[ ! -d "$SRC/.git" ]]; then
  git clone -q --depth 1 --branch "$BOX64_TAG" https://github.com/ptitSeb/box64.git "$SRC"
fi
git -C "$SRC" fetch -q --tags --force
git -C "$SRC" checkout -q "$BOX64_TAG"
git -C "$SRC" describe --tags --exact-match | grep -qx "$BOX64_TAG" || {
  echo "Box64 source is not at pinned tag $BOX64_TAG" >&2
  exit 1
}

rm -rf "$BUILD" "$INSTALL"
cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_SYSTEM_NAME=Linux \
  -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
  -DCMAKE_SYSROOT="$SYSROOT" \
  -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
  -DCMAKE_INSTALL_PREFIX=/usr \
  -DCMAKE_INSTALL_LIBDIR=lib/aarch64-linux-gnu \
  -DARM_DYNAREC=ON \
  -DBAD_SIGNAL=ON \
  -DNOBOX64=OFF \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,--dynamic-linker=$FXD_LDSO" \
  -DCMAKE_SHARED_LINKER_FLAGS="" \
  -DNOGIT=OFF
cmake --build "$BUILD" --parallel "${JOBS:-$(nproc)}"
DESTDIR="$INSTALL" cmake --install "$BUILD"

BOX64_BIN="$INSTALL/usr/bin/box64"
[[ -x "$BOX64_BIN" ]] || { echo "Box64 binary was not installed at $BOX64_BIN" >&2; find "$INSTALL" -maxdepth 5 -type f; exit 1; }
file "$BOX64_BIN"
readelf -h "$BOX64_BIN" | grep -q 'AArch64' || { echo "Box64 is not an AArch64 executable" >&2; exit 1; }
readelf -l "$BOX64_BIN" | grep -F "$FXD_LDSO" || { echo "Box64 does not use the ANLR patched loader" >&2; exit 1; }
printf '%s\n' "$BOX64_TAG" > "$OUT/version.txt"
echo "Box64 $BOX64_TAG built for ANLR: $BOX64_BIN"
