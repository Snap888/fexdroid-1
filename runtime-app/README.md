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
