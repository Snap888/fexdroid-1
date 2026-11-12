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

scripts/build-rootfs.sh
scripts/build-x86-sysroot.sh
scripts/build-glibc.sh
scripts/build-fex.sh

if [[ "${BUILD_MESA:-0}" == 1 ]]; then
  scripts/build-mesa.sh
  export WITH_MESA=1
else
  export WITH_MESA=0
fi

# The independent runtime consumes one signed tar.gz archive; it does not need
# the legacy APK's libfxprobe JNI helper. The guest rootfs is nested inside the
# ARM64 rootfs so installation remains one atomic release.
export WITH_APK_PARTS=0
scripts/build-payload.sh

A=build/payload-stage/arm64
X=build/payload-stage/x86_64
ANLR_GUEST="$A/usr/share/anlr/x86_64"
mkdir -p "$(dirname "$ANLR_GUEST")"
rm -rf "$ANLR_GUEST"
cp -a "$X" "$ANLR_GUEST"

# Point FEX at the nested guest rootfs instead of the legacy sibling directory.
python3 - "$A/usr/share/fex-emu/Config.json" "$ANLR_GUEST" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
cfg = json.loads(p.read_text())
cfg.setdefault("Config", {})["RootFS"] = str(pathlib.Path(sys.argv[2]).resolve())
p.write_text(json.dumps(cfg, indent=2) + "\n")
PY

# The archive format and metadata match RootfsManifestVerifier/SafeRootfsExtractor.
mkdir -p build/anlr-release
ARCHIVE=build/anlr-release/debian-arm64-rootfs.tar.gz
tar -C "$A" --numeric-owner --owner=0 --group=0 --sort=name --mtime='@0' --hard-dereference -cf - . | gzip -n -9 > "$ARCHIVE"
python3 - "$A" "$ARCHIVE" > build/anlr-release/archive-metadata.json <<'PY'
import hashlib, json, os, pathlib, sys
root, archive = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
expanded = 0
for base, dirs, files in os.walk(root, followlinks=False):
    for name in files:
        p = pathlib.Path(base) / name
        if not p.is_symlink() and p.is_file(): expanded += p.stat().st_size
h = hashlib.sha256()
with archive.open('rb') as f:
    for block in iter(lambda: f.read(1024 * 1024), b''): h.update(block)
print(json.dumps({"architecture":"aarch64", "archiveFormat":"tar.gz",
                  "archiveSizeBytes":archive.stat().st_size,
                  "expandedSizeBytes":expanded, "archiveSha256":h.hexdigest(),
                  "runtimeAbi":"anlr-glibc-v1",
                  "runtimeRoot":(root / 'usr/lib/anlr/runtime-root').read_text().strip()},
                 indent=2) + "\n")
PY

# QEMU smoke test the ARM64 shell and verify the FEX executable is present.
scripts/test-payload-qemu.sh 'echo ANLR_ROOTFS_SMOKE_BEGIN; /usr/bin/id; /bin/sh -c "echo ANLR_GUEST_EXEC_OK"; test -x /usr/bin/FEX; echo ANLR_ROOTFS_SMOKE_END'

echo
echo "ANLR rootfs archive built: $ARCHIVE"
echo "Archive metadata: build/anlr-release/archive-metadata.json"
echo "Embedded runtime root: $(cat "$A/usr/lib/anlr/runtime-root")"
echo
echo "The metadata is unsigned. Do NOT ship until the exact manifest is signed with the offline release key and its public key is pinned in the ANLR app."
