package org.anlr.linuxruntime

import java.io.File

internal data class RuntimeComponentReport(
    val nativeArm64: Boolean,
    val fexX86_64: Boolean,
    val box64X86_64: Boolean,
    val x86GuestRootfs: Boolean,
    val xfce4: Boolean,
    val xServer: Boolean,
    val missing: List<String>
) {
    val launchBackendsReady: Boolean get() = nativeArm64 && fexX86_64 && box64X86_64 && x86GuestRootfs
    val desktopReady: Boolean get() = xfce4 && xServer
    val complete: Boolean get() = launchBackendsReady && desktopReady
}

/**
 * Checks the actual installed payload, not merely the ABI marker.
 * A rootfs is not considered a usable ANLR system unless its translation
 * backends, x86-64 guest userspace and desktop prerequisites are present.
 */
internal object RuntimeComponentVerifier {
    fun inspect(rootfs: File): RuntimeComponentReport {
        fun executable(path: String) = File(rootfs, path).let { it.isFile && it.canExecute() }
        fun directory(path: String) = File(rootfs, path).isDirectory

        val nativeShell = executable("bin/sh") || executable("usr/bin/sh")
        val fex = executable("usr/bin/FEX")
        val box64 = executable("usr/bin/box64")
        val guest = directory("usr/share/anlr/x86_64") &&
            (executable("usr/share/anlr/x86_64/bin/sh") ||
             executable("usr/share/anlr/x86_64/usr/bin/sh")) &&
            File(rootfs, "usr/share/anlr/x86_64/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2").exists()
        val xfce = executable("usr/bin/startxfce4") &&
            executable("usr/bin/xfce4-session") &&
            executable("usr/bin/xfce4-panel") &&
            executable("usr/bin/xfwm4")
        val xserver = executable("usr/bin/Xvfb") && executable("usr/bin/xauth")

        val missing = buildList {
            if (!nativeShell) add("ARM64 shell")
            if (!fex) add("FEX (/usr/bin/FEX)")
            if (!box64) add("Box64 (/usr/bin/box64)")
            if (!guest) add("x86-64 guest rootfs / dynamic loader")
            if (!xfce) add("XFCE4 session/panel/window manager")
            if (!xserver) add("Xvfb/X11 server tools")
        }
        return RuntimeComponentReport(nativeShell, fex, box64, guest, xfce, xserver, missing)
    }

    fun requireComplete(rootfs: File) {
        val report = inspect(rootfs)
        check(report.complete) {
            "ANLR system is incomplete: ${report.missing.joinToString()}. Install the full ANLR Debian/Ubuntu runtime payload."
        }
    }
}
