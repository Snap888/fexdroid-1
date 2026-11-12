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

The terminal still executes a bounded Android host diagnostic shell. It is not a Debian shell and is not a PTY. There is no Linux guest launcher, FEX/Box64 payload, terminal resize/signal bridge, XFCE display server, input/audio/clipboard bridge, or enabled imported-app execution path. The rootfs installer core is not exposed as a user-facing install action until a maintainer supplies a pinned production Ed25519 public key and a signed manifest endpoint. The signed manifest must include `expandedSizeBytes` and `runtimeAbi: anlr-glibc-v1`; extraction is limited to the signed expanded size, disk space is checked before unpacking, and the extracted ABI marker is verified. Stock Debian rootfs images are intentionally rejected because Android seccomp and absolute interpreter paths require the repository-built patched glibc payload.


### Native PTY milestone

The app now builds an arm64-v8a JNI PTY transport using POSIX master/slave pseudo-terminals. It supports process spawn, byte-stream read/write, terminal window-size ioctl, process-group signals and bounded child cleanup. The terminal screen currently uses it for an interactive **Android host** shell, with persisted command history, Ctrl+C and size updates. This is not yet a Debian guest shell and the text view is not a full ANSI terminal emulator. The guest launcher must be connected to this transport after the rootfs loader and device execution policy are validated.


The native ARM64 guest launcher uses the rootfs-patched absolute ELF interpreter path when it resolves inside the active rootfs, and passes a clean guest environment through execve. A Debian guest PTY action is exposed when an active rootfs exists. This launch route still requires the ANLR-patched glibc/FEX payload produced by the repository's payload build; a stock Debian rootfs archive is not sufficient for Android's seccomp and absolute-path constraints.

## Runtime payload build (Ubuntu + FEX + Box64)

The ANLR payload pipeline now targets an Ubuntu 24.04 ARM64 userspace for the native Linux environment. The reproducible cross-build toolchain and the FEX x86-64 guest sysroot remain Debian Trixie for now: the ANLR-patched glibc build and FEX toolchain are pinned to that ABI. The Ubuntu userspace is paired with the patched ANLR glibc during payload assembly; this mixed build must pass the QEMU smoke tests before it is considered usable.

- `scripts/build-ubuntu-rootfs.sh` creates the Ubuntu 24.04 ARM64 runtime rootfs.
- `scripts/build-fex.sh` builds FEX-Emu with host/guest thunks.
- `scripts/build-box64.sh` cross-builds Box64 v0.4.4 with ARM64 DynaRec and the Android signal workaround.
- `scripts/build-anlr-payload.sh` assembles the signed-manifest-compatible tar.gz payload and runs QEMU checks for the ARM64 shell, FEX presence and Box64 startup.
- `.github/workflows/anlr-payload.yml` builds on an Ubuntu 24.04 GitHub runner and publishes a CI artifact for inspection.

The current pipeline is a build/test artifact, not yet an in-app downloadable release. The app still refuses installation without a pinned Ed25519 trust anchor and a signed manifest. Do not treat a successful QEMU run as proof that Android's seccomp, SELinux, executable-memory policy, FEX/Box64 dynarec or graphics stack works on a phone.
