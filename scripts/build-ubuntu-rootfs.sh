#!/usr/bin/env bash
# Build the Ubuntu 24.04 ARM64 userspace used as ANLR's native Linux runtime.
#
# The cross sysroots and the FEX x86-64 guest remain Debian Trixie because the
# patched ANLR glibc and FEX build are currently pinned to that ABI. The Ubuntu
# userspace is paired with the same patched glibc (newer, backward-compatible
# glibc ABI) when build-payload.sh assembles the device image.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/rootfs
IMAGE=ubuntu:24.04
NAME="anlr-ubuntu-arm64-$$"
mkdir -p "$OUT"
docker rm -f "$NAME" >/dev/null 2>&1 || true

RUNTIME_PKGS=(
  bash coreutils busybox procps file strace ca-certificates
  fonts-dejavu-core libstdc++6 libzstd1 zlib1g libexpat1
  libdrm2 libx11-6 libx11-xcb1 libxcb1 libxcb-dri3-0 libxcb-present0
  libxcb-shm0 libxcb-sync1 libxcb-xfixes0 libxcb-randr0 libxshmfence1
  libwayland-client0 libvulkan1 vulkan-tools xvfb x11-utils
  pulseaudio pulseaudio-utils libpulse0 libasound2t64
  libnss3 libfontconfig1 libfreetype6 libdbus-1-3
  libgl1 libegl1 libgbm1 libxkbcommon0 libudev1 libcap2 libusb-1.0-0
  libxtst6 libxrandr2 libxss1 libxcomposite1 libxdamage1 libxfixes3
  libxi6 libxcursor1 libxinerama1 libsm6 libice6
  libatk1.0-0t64 libatk-bridge2.0-0t64 libpango-1.0-0 libcairo2
  libopenal1 libsdl2-2.0-0
)

echo ">> Ubuntu 24.04 ARM64 runtime rootfs"
docker run --platform linux/arm64 --name "$NAME" ubuntu:24.04 bash -euc "
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq --no-install-recommends ${RUNTIME_PKGS[*]} >/dev/null
  dpkg-query -W -f='${Package} ${Version}\n' > /packages.txt
  apt-get clean
  rm -rf /var/lib/apt/lists/* /var/cache/debconf/*-old /var/log/* /tmp/* /var/tmp/*
"
rm -rf "$OUT/ubuntu-arm64-stage"
mkdir -p "$OUT/ubuntu-arm64-stage"
docker export "$NAME" | tar -C "$OUT/ubuntu-arm64-stage" -xf -
docker rm "$NAME" >/dev/null
mv "$OUT/ubuntu-arm64-stage/packages.txt" "$OUT/ubuntu-arm64.packages.txt"
(
  cd "$OUT/ubuntu-arm64-stage"
  rm -rf usr/share/doc/* usr/share/man/* usr/share/info/* usr/share/lintian
  find usr/share/locale -mindepth 1 -maxdepth 1 ! -name locale.alias -exec rm -rf {} + 2>/dev/null || true
)
tar -C "$OUT/ubuntu-arm64-stage" --numeric-owner --owner=0 --group=0 --sort=name --mtime='@0' \
  --hard-dereference -cf "$OUT/rootfs-arm64.tar" .
rm -rf "$OUT/ubuntu-arm64-stage"
cp "$OUT/ubuntu-arm64.packages.txt" "$OUT/rootfs-arm64.packages.txt"
echo "Ubuntu runtime rootfs: $OUT/rootfs-arm64.tar"
du -h "$OUT/rootfs-arm64.tar"
