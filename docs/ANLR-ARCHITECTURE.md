# ANLR — independent runtime architecture

## Goal

Run Linux ARM64 programs natively and Linux x86-64 ELF programs through a selectable translation backend on Android ARM64. This is a separate product and application ID, not a renamed Steam launcher.

## Reference implementation findings

The existing FEXDroid app already contains substantial engineering work: Android-patched FEX, patched glibc, Debian ARM64 and amd64 root filesystems, Mesa Turnip/KGSL patches, Vulkan thunks, Xvfb/fxpresent display plumbing, audio/input bridges, process supervision, diagnostics and a reproducible payload build pipeline.

The app is tightly coupled to Steam/game workflows and its glibc payload expects a specific absolute private-data path. ANLR must not point at or overwrite that payload. Port individual components behind clean interfaces and rebuild path-sensitive payloads for ANLR's own application ID.

## Proposed modules

- Android UI: device report, runtime installer, application library, launch profiles, logs.
- Device probe: ABI, page size, memory, Android API, SELinux/seccomp observations, KGSL/Vulkan.
- Rootfs manager: immutable versioned ARM64 base, optional amd64/i386 guest rootfs, checksum and atomic activation.
- Process runtime: native ARM64 launcher, FEX backend, optional Box64 backend; process groups and lifecycle.
- Graphics: Vulkan loader + Turnip when actually available; X11/Xwayland compatibility; experimental direct Surface/AHardwareBuffer path.
- Audio/input: audio server bridge, Android input mapping and gamepad virtual-device strategy.
- Compatibility DB: per-app launch flags, known blockers, tested device/driver matrix.

## Non-negotiable constraints

1. No claim of native execution for x86-64: x86-64 requires translation or emulation.
2. No claim of GPU acceleration from KGSL file existence alone; run Vulkan capability tests.
3. Do not assume FEX supports 16 KB host pages.
4. Do not make proot/ptrace the default hot path for games.
5. Keep downloaded Linux distributions and user data separate from app binaries and update atomically.
6. Preserve upstream licenses and source notices for FEX, Mesa, Box64, glibc and helper components.
7. No Steam-specific assumptions in the core runtime.

## First implementation scope

The `:runtime-app` module is independent. The first commit adds a launcher and truthful hardware reconnaissance only. Next, port device probes into a testable library and create the rootfs installer contract before introducing multi-gigabyte native payloads.
