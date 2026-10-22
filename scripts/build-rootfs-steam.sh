#!/usr/bin/env bash
# Phase 5: the x86_64 guest rootfs for Steam (amd64 + i386 multiarch), too big for the
# APK (~550 MB). Contains only Debian packages; Steam itself is downloaded from Valve on
# the phone when the user installs it. The app installs this tarball from a file
# (adb push / download), see LinuxEnv.installExtraRootfs().
#
# Output: build/rootfs/rootfs-x86_64-steam.tar.zst (+ .packages.txt)
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/rootfs
mkdir -p "$OUT"
APT_SNAPSHOT=""
[[ -n "${SNAPSHOT:-}" ]] && APT_SNAPSHOT="--snapshot $SNAPSHOT"

PKGS=(
  libc6 libstdc++6 coreutils bash ca-certificates curl xz-utils file tar
  steam-libs steam-libs-i386
  libx11-6 libx11-6:i386 libxcb1 libxcb1:i386 libx11-xcb1 libx11-xcb1:i386
  libxkbcommon0 libxkbcommon-x11-0 libxss1 libxss1:i386 libxinerama1 libxinerama1:i386
  libxdamage1 libxfixes3 libxext6 libxext6:i386 libxrandr2 libxrender1 libxcomposite1 libxcursor1
  libsdl2-2.0-0 libsdl2-2.0-0:i386 libglib2.0-0t64 libglib2.0-0t64:i386 libfontconfig1 libfontconfig1:i386
  fontconfig fonts-liberation libdbus-1-3 libdbus-1-3:i386 dbus-daemon dbus-x11
  libpulse0 libpulse0:i386 pulseaudio-utils libnss3 libnspr4 libgtk-3-0t64 libgbm1 libegl1
  libvulkan1 libvulkan1:i386 vulkan-tools libusb-1.0-0 lsof procps
  libibus-1.0-5 libxtst6 libxtst6:i386 libvdpau1

  # === OpenGL / GLVND ===
  libopengl0
  libgl1
  libglx0
  libglvnd0

  # === Full XFCE4 desktop ===
  xfce4
  xfce4-goodies
  xfce4-terminal
  xfce4-notifyd
  desktop-base
  tango-icon-theme
  thunar-volman
  xfce4-power-manager
  mousepad
  ristretto

  # === Qt 5 runtime ===
  libqt5core5t64
  libqt5gui5t64
  libqt5widgets5t64
  libqt5network5t64
  libqt5dbus5t64
  libqt5svg5
  libqt5x11extras5
  qt5-image-formats-plugins
  qt5-gtk-platformtheme

  # === Qt 6 runtime ===
  libqt6core6t64
  libqt6gui6
  libqt6widgets6
  libqt6network6
  libqt6dbus6
  libqt6svg6
  qt6-image-formats-plugins
  qt6-qpa-plugins

  # === AppImage support ===
  squashfs-tools
  fuse3
  libfuse3-4
  libfuse2t64
)

cname="fexdroid-steamfs-$$"
docker rm -f "$cname" >/dev/null 2>&1 || true
docker run --platform linux/amd64 --name "$cname" debian:trixie-slim bash -euc "
  export DEBIAN_FRONTEND=noninteractive
  sed -i 's/^Components: main\$/Components: main contrib non-free non-free-firmware/' /etc/apt/sources.list.d/debian.sources
  dpkg --add-architecture i386
  apt-get update $APT_SNAPSHOT -qq
  apt-get install -y -qq --no-install-recommends ${PKGS[*]} >/dev/null
  apt-get clean
  rm -rf /var/lib/apt/lists/*
  dpkg-query -W -f='\${Package}:\${Architecture} \${Version}\n' > /packages.txt
"
rm -rf "$OUT/steam-stage" && mkdir -p "$OUT/steam-stage"
docker export "$cname" | tar -C "$OUT/steam-stage" -xf - 2>/dev/null || true
docker rm "$cname" >/dev/null
( cd "$OUT/steam-stage"
  rm -rf usr/share/doc/* usr/share/man/* usr/share/info/* usr/share/lintian var/log/* .dockerenv
  find usr/share/locale -mindepth 1 -maxdepth 1 ! -name 'locale.alias' -exec rm -rf {} + 2>/dev/null || true
)
mkdir -p "$OUT/steam-stage/dev/shm" "$OUT/steam-stage/run/pressure-vessel"
rm -rf "$OUT/steam-stage/proc" "$OUT/steam-stage/sys"
chmod 1777 "$OUT/steam-stage/tmp" "$OUT/steam-stage/var/tmp" "$OUT/steam-stage/dev/shm"
mv "$OUT/steam-stage/packages.txt" "$OUT/rootfs-x86_64-steam.packages.txt"
python3 scripts/lib/relativize-symlinks.py "$OUT/steam-stage"
mkdir -p "$OUT/steam-stage/opt/fexdroid-tests"
tar -C "$OUT/steam-stage" --numeric-owner --owner=0 --group=0 --sort=name --hard-dereference -cf - . | zstd -T0 -19 -q -o "$OUT/rootfs-x86_64-steam.tar.zst" -f
rm -rf "$OUT/steam-stage"
du -h "$OUT/rootfs-x86_64-steam.tar.zst"
wc -l < "$OUT/rootfs-x86_64-steam.packages.txt"