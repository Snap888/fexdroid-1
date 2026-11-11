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

- Applications: copy selected files into ANLR-private storage, calculate SHA-256, inspect ELF magic/class/endianness/e_machine/e_type, retain format and architecture metadata, and delete managed copies. Import is capped at 1 GiB and checks free storage. Execution remains disabled until a real runtime backend/rootfs is installed.
- Terminal: a command console with output retained in the current view, built-in ANLR commands and a 12-second-bounded Android app-sandbox shell with timeout enforcement that does not block while draining a pipe. This is explicitly not yet a Linux guest TTY; command history and PTY support are not implemented.
- XFCE4: persistent session profile controls for resolution, UI scale and fullscreen/windowed mode. Starting XFCE remains gated until rootfs, X server and input/audio bridges are implemented.
- Home: device compatibility summary and shortcuts.

The terminal command runner is a diagnostic app-sandbox shell, not a security boundary for untrusted commands. Do not expose it as a remote shell. Linux guest terminal must be a separate backend once the rootfs exists.


## Import pipeline (implemented, execution disabled)

Selected documents are copied from Android's Storage Access Framework into `files/imported-apps` using a temporary file and rename-on-completion. The importer computes SHA-256 while streaming, enforces a 1 GiB per-file ceiling and reserves 64 MiB of free space. It records ELF class, byte order, machine and type through a bounds-checked standalone parser. Unit tests cover x86-64 little-endian, AArch64 big-endian, relocatable objects, truncated headers and invalid identification bytes. The architecture label is informational: it does not prove ABI compatibility or safe execution. Shell scripts are only marked as candidates when a shebang is present; desktop entries are metadata-only. No imported file is executed by Android's host shell.

The original SAF URI is retained as provenance for older records, but the managed private copy is the runtime input. Removing an application removes its managed copy. Legacy entries without a managed copy remain visible but are not executable.


## Runtime storage foundation

RuntimeStorageManager initializes an app-private runtime layout (`rootfs/releases`, `rootfs/staging`, downloads, applications, logs and cache). A staged release can be activated only after a caller marks it complete with a 64-character manifest SHA-256 and activation supplies the same checksum. Publishing a release and updating the active pointer use rename-based atomic steps with rollback. Release IDs are validated, path traversal is rejected, and incomplete staging directories can be cleaned. This is only the storage/activation foundation: no distribution is downloaded, extracted or cryptographically authenticated yet.
