#!/bin/sh
# EXPERIMENT (NOTES N-049, N-050): Valve's native arm64 Steam client, not translated by FEX.
# Called by the app with DISPLAY, PULSE_SERVER, FEX_ROOTFS... already set.
#
#   fexdroid-steam-arm64.sh [steam args...]
#
# - The client lives in its own home, $FXD_FILES/home-arm64, apart from the x86 client's.
#   If missing, its first package is downloaded from Valve's update servers and checked
#   against the hash in Valve's list; the client downloads the rest itself.
# - Its programs name /lib/ld-linux-aarch64.so.1 as their interpreter; our glibc starts
#   them through the rootfs's loader (patches/glibc/0004) and nothing of Valve's is changed.
set -eu
FXD_FILES="${FXD_FILES:?}"
export HOME="$FXD_FILES/home-arm64"
STEAMROOT="$HOME/.local/share/Steam"
FEX="$FXD_ROOT/usr/bin/FEX"
CDN=https://client-update.steamstatic.com
# Valve's stable arm64 channel, the same client version as the x86 one. (The Steam Deck beta
# channel works too, but the client leaves it for this one at the first sign-in and downloads
# itself again.)
LIST=steam_client_linuxarm64
log() { echo "[fexdroid-steam-arm64] $*"; }

mkdir -p "$STEAMROOT/package" "$HOME/.steam"
fetch() { # url, file
    PATH=/usr/bin:/bin "$FEX" "$FEX_ROOTFS/usr/bin/curl" -fL --retry 3 -o "$2" "$1"
}

if [ ! -x "$STEAMROOT/steamrtarm64/steam" ]; then
    log "the arm64 client is not installed; asking Valve for its list of packages"
    tmp="$STEAMROOT/.first-package"
    rm -rf "$tmp" && mkdir -p "$tmp"
    fetch "$CDN/$LIST" "$tmp/list.txt"
    # The block "bins_linuxarm64_linuxarm64" of the list: its file and its sha2.
    seed=$(busybox awk '/^\t"bins_linuxarm64_linuxarm64"/{f=1} f&&/"file"/{gsub(/"/,"",$2); print $2; exit}' "$tmp/list.txt")
    sum=$(busybox awk '/^\t"bins_linuxarm64_linuxarm64"/{f=1} f&&/"sha2"/{gsub(/"/,"",$2); print $2; exit}' "$tmp/list.txt")
    [ -n "$seed" ] && [ -n "$sum" ] || { log "Valve's list has no first package"; exit 1; }
    log "downloading $seed (about 110 MB)"
    fetch "$CDN/$seed" "$tmp/seed.zip"
    got=$(sha256sum "$tmp/seed.zip" | cut -d' ' -f1)
    [ "$got" = "$sum" ] || { log "the package does not match Valve's hash ($got, not $sum)"; exit 1; }
    mkdir -p "$tmp/out"
    busybox unzip -q -o "$tmp/seed.zip" -d "$tmp/out"
    # The archive names its files with "\" between directories.
    ( cd "$tmp/out"
      busybox find . -name '*\\*' | while IFS= read -r f; do
          to=$(printf '%s' "$f" | tr '\\' '/')
          if [ -d "$f" ]; then mkdir -p "$to"; else mkdir -p "$(dirname "$to")"; mv "$f" "$to"; fi
      done
      busybox find . -depth -type d -name '*\\*' -exec rmdir {} + 2>/dev/null || true )
    chmod -R u+rwx "$tmp/out"
    cp -a "$tmp/out/." "$STEAMROOT/"
    rm -rf "$tmp"
    [ -x "$STEAMROOT/steamrtarm64/steam" ] || { log "the package has no steamrtarm64/steam"; exit 1; }
    log "first package unpacked; the client downloads the rest at its first start"
fi
ln -sfn "$STEAMROOT" "$HOME/.steam/root"
ln -sfn "$STEAMROOT" "$HOME/.steam/steam"
# What Valve's steam.sh links for games: sdk64 is where an x86-64 game finds the client's
# x86-64 steamclient.so, sdkarm64 holds steam-launch-wrapper, binNN the overlay.
for l in sdk32:linux32 sdk64:linux64 sdkarm64:linuxarm64 bin32:ubuntu12_32 bin64:ubuntu12_64 binarm64:steamrtarm64; do
    ln -sfn "$STEAMROOT/${l#*:}" "$HOME/.steam/${l%%:*}"
done

# The x86 client's library (Dota and the runtimes it installed) as a second library, so a
# game is not downloaded twice. Steam keeps the entry once it is there.
lf="$STEAMROOT/steamapps/libraryfolders.vdf"
if [ -d "$FXD_FILES/steamlib/steamapps" ] && [ -f "$lf" ] && ! grep -q "\"$FXD_FILES/steamlib\"" "$lf"; then
    n=$(grep -c '^	"[0-9]*"$' "$lf")
    sed -i '$d' "$lf"
    printf '\t"%s"\n\t{\n\t\t"path"\t\t"%s"\n\t\t"label"\t\t""\n\t\t"apps"\n\t\t{\n\t\t}\n\t}\n}\n' \
        "$n" "$FXD_FILES/steamlib" >> "$lf"
    log "the x86 client's library added: $FXD_FILES/steamlib"
fi

# A game's video settings live in each client's userdata (<account>/<app>/local/cfg/video.txt);
# without the x86 client's, Dota starts here with its defaults, heavier and 1.2 GB bigger
# (NOTES N-060). Take the newer one.
X86_USERDATA="$FXD_FILES/home/user/.local/share/Steam/userdata"
for v in "$X86_USERDATA"/*/*/local/cfg/video.txt; do
    [ -f "$v" ] || continue
    to="$STEAMROOT/userdata/${v#"$X86_USERDATA"/}"
    if [ ! -f "$to" ] || [ "$v" -nt "$to" ]; then
        mkdir -p "$(dirname "$to")" && cp -p "$v" "$to" && log "video settings from the x86 client: ${v#"$X86_USERDATA"/}"
    fi
done

# Shader pre-caching off, once, as for the x86 client (fexdroid-steam.sh, NOTES N-031): Steam
# would download precompiled shaders before every game start and process them for ever.
cfg="$STEAMROOT/config/config.vdf"
if [ ! -e "$FXD_FILES/.shader-precache-default-arm64" ] && [ -e "$cfg" ]; then
    if grep -q '"ShaderCacheManager"' "$cfg" && ! grep -q '"DisableShaderCache"' "$cfg"; then
        sed -i '/"ShaderCacheManager"/{n;s/{/{\n\t\t\t\t\t"DisableShaderCache"\t\t"1"/;}' "$cfg"
        log "shader pre-caching: off"
    fi
    : > "$FXD_FILES/.shader-precache-default-arm64"
fi

# Games: x86 ones start through Steam's "fex" tool and the Steam Linux Runtimes. Their
# entry points are replaced by ours (no containers, see _v2-entry-point and fex-compat-tool),
# and the environment below, which is the client's, is kept for fex-compat-tool to undo.
for v in LD_LIBRARY_PATH LD_PRELOAD LIBGL_DRIVERS_PATH LIBGL_ALWAYS_SOFTWARE LP_NUM_THREADS \
         FONTCONFIG_SYSROOT XDG_DATA_DIRS SSL_CERT_FILE SSL_CERT_DIR; do
    if eval "[ -n \"\${$v+x}\" ]"; then eval "export FXD_GAMEENV_$v=\"=\$$v\""; else export "FXD_GAMEENV_$v=-"; fi
done
SHIM_DIR="$FXD_ROOT/usr/lib/fexdroid/steam"
replace_entry_points() {
    # Its own library, and the x86 client's (where Dota is) when the client was given it.
    for ep in "$STEAMROOT"/steamapps/common/SteamLinuxRuntime*/_v2-entry-point \
              "$FXD_FILES"/steamlib/steamapps/common/SteamLinuxRuntime*/_v2-entry-point \
              "$STEAMROOT"/steamapps/common/SteamLinuxRuntime/scout-on-soldier-entry-point-v2 \
              "$STEAMROOT"/steamapps/common/FEX-Emu/fex-compat-tool; do
        [ -f "$ep" ] || continue
        case "$ep" in */fex-compat-tool) ours="$SHIM_DIR/fex-compat-tool" ;; *) ours="$SHIM_DIR/_v2-entry-point" ;; esac
        if ! cmp -s "$ep" "$ours"; then
            [ -f "$ep.valve" ] || mv "$ep" "$ep.valve"
            cp "$ours" "$ep"
            chmod 755 "$ep"
            log "launcher replaced: ${ep#"$STEAMROOT"/steamapps/common/}"
        fi
    done
}
replace_entry_points
( while sleep 5; do replace_entry_points; done ) &

export TMPDIR="${TMPDIR:-$FXD_ROOT/tmp}"
export SDL_VIDEODRIVER=x11
export STEAMOS=1
export LD_LIBRARY_PATH="$STEAMROOT/steamrtarm64:$STEAMROOT/linuxarm64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
# Linux paths (/tmp, /etc, /usr...) are inside the rootfs.
# The leading ":" because the client puts its overlay libraries in front without one.
export LD_PRELOAD=":$FXD_ROOT/usr/lib/fexdroid/libfxpath.so"
# What libraries look for at fixed places, said outright as well.
export FONTCONFIG_SYSROOT="$FXD_ROOT"
export SSL_CERT_FILE="$FXD_ROOT/etc/ssl/certs/ca-certificates.crt"
export SSL_CERT_DIR="$FXD_ROOT/etc/ssl/certs"
export XDG_DATA_DIRS="$FXD_ROOT/usr/local/share:$FXD_ROOT/usr/share"
# The client's vgui asks the X server for a GLX visual and draws through Mesa's software
# driver; ld.so opens the driver from Mesa's built-in /usr path, which it cannot find here.
export LIBGL_DRIVERS_PATH="$FXD_ROOT/usr/lib/aarch64-linux-gnu/dri"
export LIBGL_ALWAYS_SOFTWARE=1
# Mesa's software driver starts threads of its own when a program first draws. Chromium's
# zygote refuses to go on if it finds a second thread in its process.
export LP_NUM_THREADS=0
mkdir -p "$FXD_ROOT/tmp/dumps" "$FXD_ROOT/dev/shm"
# Left behind by a client that did not end by itself.
rm -f "$FXD_ROOT"/dev/shm/u*-ValveIPCSharedObj-* "$FXD_ROOT"/dev/shm/u*-Shm_* 2>/dev/null || true
[ -x "$FXD_ROOT/usr/bin/fxwmfit" ] && "$FXD_ROOT/usr/bin/fxwmfit" 2>/dev/null &

log "starting the arm64 Steam client from $STEAMROOT"
cd "$STEAMROOT"
set -- -no-cef-sandbox -cef-disable-gpu -cef-disable-gpu-compositing "$@"
if [ -f "$FXD_FILES/steam-launch-options.txt" ]; then
    # shellcheck disable=SC2046
    set -- "$@" $(cat "$FXD_FILES/steam-launch-options.txt")
fi
# The client ends with code 42 when it wants to be started again, as after its own update
# (Valve's steam.sh does the same for the x86 client).
while :; do
    rc=0
    "$STEAMROOT/steamrtarm64/steam" "$@" || rc=$?
    [ "$rc" = 42 ] || exit "$rc"
    log "the client asked to be started again"
done
