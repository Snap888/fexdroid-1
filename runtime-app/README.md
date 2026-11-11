# Android Native Linux Runtime (ANLR)

An independent Android application module for Linux ARM64 and x86-64 userspace applications on Android ARM64.

## Current state

The first milestone creates a separately installable Android app (`org.anlr.linuxruntime`) and hardware reconnaissance screen. It does **not** claim FEX, Box64, a Linux rootfs, X11 or Vulkan translation is already bundled. The engine is the next milestone.

## Build

From the repository root:

```bash
./gradlew :runtime-app:assembleDebug
```

APK: `runtime-app/build/outputs/apk/debug/runtime-app-debug.apk`

## Architecture decisions

- Keep this app independent from the existing `:app` FEXDroid/Steam implementation.
- Do not reuse the existing FEXDroid rootfs blindly: its patched glibc embeds an application-specific absolute files path.
- Engine payloads must be versioned, checksummed and installed under this app's private files directory.
- Detect page size, ABI, memory and KGSL visibility before offering an engine install.
- Treat GPU capability as a tested runtime property, not a guess based only on the SoC name.
- Prefer native Linux ARM64 execution first; add FEX and Box64 as pluggable x86-64 backends.
- Keep X11 compatibility and direct Vulkan/Surface presentation as independent display backends.

## Milestones

1. App shell and reconnaissance — separate package, independent build target, device checks.
2. Linux ARM64 base — rootfs acquisition, extraction, package manager, shell process supervisor.
3. x86-64 backend — FEX integration with Android-specific glibc/syscall compatibility; Box64 optional backend.
4. Graphics — Vulkan loader/Turnip detection, X11/Xwayland display, direct Surface presentation experiments.
5. Input/audio — touch, mouse, keyboard, gamepad and audio routing.
6. Application manager — import ELF/AppImage/tar archives, launch profiles, logs, compatibility database.
7. Performance and QA — benchmarks, thermal/memory controls, device matrix, signed release pipeline.

## Known blockers

- FEX host page-size compatibility must be validated; 16 KB page devices cannot be assumed to work.
- Android seccomp and executable-memory policy differ by OS/OEM and need per-device probing.
- KGSL node visibility does not guarantee Turnip initialization or Vulkan presentation.
- A proot-based environment is useful for package management/debugging but should not be the default high-performance game execution path.


CI note: changes to this module trigger the ANLR APK workflow, which publishes the debug APK as a GitHub Actions artifact.

## App interface milestone

The Compose app now has four bottom-navigation destinations: Home, Applications, Terminal and XFCE4.

- Applications: copy selected files into ANLR-private storage, calculate SHA-256, inspect ELF class/endianness/e_machine/e_type, PT_INTERP and DT_NEEDED dependencies, and retain compatibility metadata, and delete managed copies. Import is capped at 1 GiB and checks free storage. Execution remains disabled until a real runtime backend/rootfs is installed.
- Terminal: a command console with built-in ANLR commands and an Android app-sandbox shell runner. Each command has a 12-second timeout, a 12 KiB output cap, merged stdout/stderr and child-process termination on timeout/output overflow. This is explicitly not yet a Linux guest TTY; command history and PTY support are not implemented.
- XFCE4: persistent session profile controls for resolution, UI scale and fullscreen/windowed mode. Starting XFCE remains gated until rootfs, X server and input/audio bridges are implemented.
- Home: device compatibility summary and shortcuts.

The terminal command runner is a diagnostic app-sandbox shell, not a security boundary for untrusted commands. Do not expose it as a remote shell. Linux guest terminal must be a separate backend once the rootfs exists.


## Import pipeline (implemented, execution disabled)

Selected documents are copied from Android's Storage Access Framework into `files/imported-apps` using a temporary file and rename-on-completion. The importer computes SHA-256 while streaming, enforces a 1 GiB per-file ceiling and reserves 64 MiB of free space. It records ELF class, byte order, machine and type through a bounds-checked standalone parser. Unit tests cover x86-64 little-endian, AArch64 big-endian, relocatable objects, truncated headers and invalid identification bytes. The architecture label is informational: it does not prove ABI compatibility or safe execution. Shell scripts are only marked as candidates when a shebang is present; desktop entries are metadata-only. No imported file is executed by Android's host shell.

The original SAF URI is retained as provenance for older records, but the managed private copy is the runtime input. Removing an application deletes only a canonical path directly inside the managed import directory; arbitrary paths from persisted metadata are rejected. On startup, interrupted `.part` imports are cleaned. The Applications screen re-hashes managed files and reports missing or modified content. Legacy entries without a managed copy remain visible but are not executable.


## Runtime storage foundation

RootfsManifestVerifier now verifies detached Ed25519 signatures over the exact manifest bytes, then validates schema, key ID, ARM64 architecture, archive format, HTTPS URL, size and archive digest. The trusted X.509 public key is injected; no production trust anchor or manifest endpoint has been provisioned, so the app must not silently install an arbitrary rootfs. RootfsArchiveDownloader supports HTTPS redirects, resumable `.part` downloads, size limits and SHA-256 verification. SafeRootfsExtractor handles tar.gz files with path traversal checks, bounded expansion, symlink normalization into the guest root, hardlink restrictions and rejection of special files. RootfsInstaller coordinates verification, download, extraction, shell-presence validation and atomic activation. A persistent staging journal records install phase; interrupted downloads are resumable, interrupted extraction is restarted from the verified archive, and incomplete staging is preserved at startup. These components are not yet exposed by an install UI and still require a maintainer-provided pinned public key and published signed Debian manifest.


### ELF backend compatibility preflight

`ElfCompatibilityChecker` compares ELF class, endianness and machine ID with the selected backend (native AArch64, FEX x86-64 or Box64 x86-64), checks the requested interpreter under the selected rootfs and resolves each `DT_NEEDED` name against the backend's known library directories. A missing backend or dependency is reported before launch. This is deliberately a conservative preflight, not a complete glibc symbol-version/relocation audit; it does not prove that an application will run.

### Current execution boundary

The terminal still executes a bounded Android host diagnostic shell. It is not a Debian shell and is not a PTY. There is no Linux guest launcher, FEX/Box64 payload, terminal resize/signal bridge, XFCE display server, input/audio/clipboard bridge, or enabled imported-app execution path. The rootfs installer core is not exposed as a user-facing install action until a maintainer supplies a pinned production Ed25519 public key and a signed manifest endpoint.
