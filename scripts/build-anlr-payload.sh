#!/usr/bin/env bash
# Build and smoke-test the Debian/FEX payload for the independent ANLR Android app.
#
# IMPORTANT: the rootfs path is compiled into patched glibc, its ELF interpreters,
# and FEX configuration. Do not build dependencies with the legacy app's default
# package path and then reuse them for ANLR.
set -euo pipefail
cd "$(dirname "$0")/.."

export FEXROOT_PATH="${FEXROOT_PATH:-/data/data/org.anlr.linuxruntime/files/rootfs}"
case "$FEXROOT_PATH" in
  /data/data/org.anlr.linuxruntime/files/rootfs) ;;
  *)
    echo "Refusing unexpected ANLR root path: $FEXROOT_PATH" >&2
    echo "Set FEXROOT_PATH explicitly only when the Android package path is intentionally different." >&2
    exit 2
    ;;
esac
export FEXROOT_PATH

echo "== ANLR payload build =="
echo "Compiled guest root: $FEXROOT_PATH"
echo "Build dependencies and payload under this same path."

# CI can build/cache the expensive toolchain stages independently, then set
# ANLR_PREBUILT_STAGES=1 to assemble and test without rebuilding them.
if [[ "${ANLR_PREBUILT_STAGES:-0}" != 1 ]]; then
  scripts/build-rootfs.sh
  scripts/build-x86-sysroot.sh
  scripts/build-ubuntu-rootfs.sh
  scripts/build-glibc.sh
  scripts/build-fex.sh
  scripts/build-box64.sh

  if [[ "${BUILD_MESA:-0}" == 1 ]]; then
    scripts/build-mesa.sh
  fi
fi

if [[ "${BUILD_MESA:-0}" == 1 ]]; then
  export WITH_MESA=1
else
  export WITH_MESA=0
fi

# Produce two independent, reproducible distributions. Both use the same
# ANLR-patched glibc ABI and FEX/Box64 host backends, but retain their own
# package database and userspace files. Never overwrite Debian with Ubuntu.
export WITH_APK_PARTS=0
mkdir -p build/anlr-release

build_variant() {
  local distro="$1" version="$2" rootfs_archive="$3"
  local out="build/anlr-${distro,,}-payload"
  local stage="build/anlr-${distro,,}-stage"
  local archive="build/anlr-release/${distro,,}-arm64-rootfs.tar.gz"
  local metadata="build/anlr-release/${distro,,}-archive-metadata.json"

  echo "== Assemble $distro $version runtime =="
  ANLR_PAYLOAD_OUT="$out" ANLR_STAGE_DIR="$stage" \
    ANLR_ARM64_ROOTFS_ARCHIVE="$rootfs_archive" scripts/build-payload.sh

  local a="$stage/arm64" x="$stage/x86_64"
  install -D -m 0755 build/box64/install/usr/bin/box64 "$a/usr/bin/box64"
  # Compile a tiny x86-64 guest executable on the x86-64 CI host. This verifies
  # Box64 actually launches an ELF, rather than merely printing its own version.
  mkdir -p "$x/usr/share/anlr/smoke"
  cat > build/anlr-x86_64-smoke.c <<'EOF'
#include <stdio.h>
int main(void) {
    puts("ANLR_X86_64_ELF_EXEC_OK");
    return 0;
}
EOF
  gcc -m64 -O2 -o "$x/usr/share/anlr/smoke/anlr-x86_64-smoke" build/anlr-x86_64-smoke.c
  file "$x/usr/share/anlr/smoke/anlr-x86_64-smoke" | grep -q 'ELF 64-bit.*x86-64'
  install -D -m 0644 build/box64/version.txt "$a/usr/share/anlr/box64-version"
  mkdir -p "$a/usr/share/anlr"
  rm -rf "$a/usr/share/anlr/x86_64"
  cp -a "$x" "$a/usr/share/anlr/x86_64"

  python3 - "$a/usr/share/fex-emu/Config.json" "$FEXROOT_PATH/usr/share/anlr/x86_64" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
cfg = json.loads(p.read_text())
cfg.setdefault("Config", {})["RootFS"] = str(pathlib.Path(sys.argv[2]).resolve())
p.write_text(json.dumps(cfg, indent=2) + "\n")
PY

  tar -C "$a" --numeric-owner --owner=0 --group=0 --sort=name --mtime='@0' \
    --hard-dereference -cf - . | gzip -n -9 > "$archive"
  python3 - "$a" "$archive" "$distro" "$version" > "$metadata" <<'PY'
import hashlib, json, os, pathlib, sys
root, archive = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
expanded = sum(p.stat().st_size for p in root.rglob("*") if p.is_file() and not p.is_symlink())
h = hashlib.sha256()
with archive.open("rb") as f:
    for block in iter(lambda: f.read(1024 * 1024), b""): h.update(block)
print(json.dumps({
  "architecture": "aarch64", "archiveFormat": "tar.gz",
  "distribution": sys.argv[3], "distributionVersion": sys.argv[4],
  "archiveSizeBytes": archive.stat().st_size, "expandedSizeBytes": expanded,
  "archiveSha256": h.hexdigest(), "runtimeAbi": "anlr-glibc-v1",
  "runtimeRoot": (root / "usr/lib/anlr/runtime-root").read_text().strip(),
  "backends": {"fex": os.environ.get("FEX_TAG", "FEX-2609"),
               "box64": pathlib.Path("build/box64/version.txt").read_text().strip()}
}, indent=2) + "\n")
PY

  # FEX consumes a guest-absolute path relative to its configured RootFS.
  # Box64 is a host ARM64 binary: run it from the mounted guest root so its
  # x86-64 dynamic loader and libraries resolve inside that rootfs.
  ANLR_STAGE_DIR="$stage" scripts/test-payload-qemu.sh \
    'set -euo pipefail
     smoke=/usr/share/anlr/smoke/anlr-x86_64-smoke
     echo ANLR_ROOTFS_SMOKE_BEGIN
     "$FXD_ROOT/usr/bin/id"
     "$FXD_ROOT/bin/sh" -c "echo ANLR_GUEST_EXEC_OK"
     test -x "$FXD_ROOT/usr/bin/FEX"
     test -x "$FXD_ROOT/usr/bin/box64"
     test -x "$FEX_ROOTFS$smoke"
     echo ANLR_FEX_SMOKE_BEGIN
     "$FXD_ROOT/usr/bin/FEX" "$smoke"
     echo ANLR_BOX64_SMOKE_BEGIN
     cd "$FEX_ROOTFS"
     BOX64_DYNAREC=0 BOX64_LD_LIBRARY_PATH="$FEX_ROOTFS/usr/lib/x86_64-linux-gnu:$FEX_ROOTFS/lib/x86_64-linux-gnu" "$FXD_ROOT/usr/bin/box64" ".$smoke"
     echo ANLR_ROOTFS_SMOKE_END'
  echo "Built $distro $version: $archive"
}

build_variant Debian Trixie build/rootfs/rootfs-arm64.tar
build_variant Ubuntu 24.04 build/rootfs/ubuntu-arm64.tar

echo
echo "Both distribution archives are in build/anlr-release/."
echo "Metadata is unsigned. Do NOT ship until each exact manifest is signed with the offline release key and its public key is pinned in the ANLR app."
