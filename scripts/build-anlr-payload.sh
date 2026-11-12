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

# The independent runtime consumes downloaded rootfs archives; it does not need
# the legacy APK's libfxprobe JNI helper.
export WITH_APK_PARTS=0
scripts/build-payload.sh

# Check the exact staged rootfs under the same absolute path baked into glibc.
scripts/test-payload-qemu.sh 'echo ANLR_ROOTFS_SMOKE_BEGIN; /usr/bin/id; /bin/sh -c "echo ANLR_GUEST_EXEC_OK"; test -x /usr/bin/FEX; echo ANLR_ROOTFS_SMOKE_END'

echo
echo "ANLR payload built and QEMU smoke test passed."
echo "ARM64 archive: build/payload/assets/rootfs-arm64.tar"
echo "x86-64 archive: build/payload/assets/rootfs-x86_64.tar"
echo "Embedded runtime root: $(cat build/payload-stage/arm64/usr/lib/anlr/runtime-root)"
echo
echo "This does NOT sign a release manifest or configure the app trust anchor."
echo "Publish the archives over HTTPS and sign the exact manifest bytes with the offline release key."
