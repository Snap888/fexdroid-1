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

## User interface milestone

The independent app exposes Home, Applications, Terminal and XFCE4 screens. The application library copies SAF-selected files into private managed storage, records SHA-256 and inspects ELF class, endianness, e_machine and e_type; execution is deliberately disabled until the runtime installer and process backend exist. The current terminal runs commands through Android's app-private `/system/bin/sh` with a bounded execution time and is not a Linux guest terminal/PTY. XFCE settings are persisted as a session profile; no XFCE process is launched yet.

## Next implementation tasks

1. Add a rootfs/runtime service interface and a real guest-shell process API.
2. Copy imported documents into managed app-private runtime storage with checksum, ELF magic/architecture detection and executable metadata.
3. Implement persistent interactive PTY (resize, stdin/stdout, Ctrl-C, process lifecycle) for the guest terminal.
4. Install XFCE4 packages into ARM64 rootfs and launch `startxfce4` through a real X11/XWayland display session.
5. Add session surface, clipboard, keyboard, pointer, touch and audio integration.


## Runtime storage implementation

The app-private directory layout and atomic active-rootfs pointer are implemented in `RuntimeStorage`. Release activation requires a completion marker written only after archive extraction and verification. The installer/download/extractor must be implemented separately; no remote rootfs is trusted or installed by this layer.


## Rootfs release storage

`RuntimeStorageManager` owns the app-private runtime directory layout and staged-release lifecycle. It rejects unsafe release IDs, requires a completion marker and matching manifest SHA-256 before activation, publishes staged directories by rename, updates the active pointer atomically and rolls back the release directory if pointer publication fails. Activation can recover a release already renamed before a process crash. Startup preserves incomplete staging directories; the journal records the manifest digest and phase so download/extraction recovery can continue safely. A real deployment still requires a maintainer-controlled public key and signed manifest distribution.


## Rootfs installer milestone (implemented foundation)

- Ed25519 detached signatures authenticate the exact manifest bytes before JSON parsing.
- The signed manifest is constrained to Debian ARM64 (aarch64), tar.gz, HTTPS, a bounded archive size and SHA-256.
- Downloads resume from a private .part file and are re-hashed before publication.
- Tar extraction rejects traversal and unsupported special files, checks expanded size, converts guest-absolute symlink targets into safe relative links inside the guest root, and refuses writes through symlink parents.
- Install phases are persisted. Interrupted downloads resume; partial extraction is removed without following symlinks and repeated from the verified archive.
- The production trust anchor and manifest URL are intentionally not guessed or embedded. No install UI is enabled until maintainers provide the real public key and signed manifest.

## ELF compatibility preflight

The import scanner now reads ELF program headers and dynamic metadata to record PT_INTERP and DT_NEEDED. This is diagnostic metadata only; dependency resolution against the installed rootfs, symbol-version compatibility, ABI checks and execution remain future work.
