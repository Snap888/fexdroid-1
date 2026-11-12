package org.anlr.linuxruntime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeComponentVerifierTest {
    private fun executable(root: File, path: String) {
        File(root, path).apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
    }

    @Test fun detectsMissingTranslationAndDesktopComponents() {
        val root = Files.createTempDirectory("anlr-components-missing-").toFile()
        try {
            executable(root, "bin/sh")
            val report = RuntimeComponentVerifier.inspect(root)
            assertTrue(report.nativeArm64)
            assertFalse(report.fexX86_64)
            assertFalse(report.box64X86_64)
            assertFalse(report.xfce4)
            assertFalse(report.xServer)
            assertFalse(report.complete)
            assertTrue(report.missing.any { it.contains("FEX") })
            assertTrue(report.missing.any { it.contains("Box64") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun acceptsCompleteRuntimeLayout() {
        val root = Files.createTempDirectory("anlr-components-full-").toFile()
        try {
            listOf(
                "bin/sh", "usr/bin/FEX", "usr/bin/box64",
                "usr/share/anlr/x86_64/bin/sh",
                "usr/share/anlr/x86_64/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
                "usr/bin/startxfce4", "usr/bin/xfce4-session",
                "usr/bin/xfce4-panel", "usr/bin/xfdesktop", "usr/bin/xfwm4", "usr/bin/thunar",
                "usr/bin/Xvfb", "usr/bin/xauth", "usr/bin/xdpyinfo"
            ).forEach { executable(root, it) }
            val report = RuntimeComponentVerifier.inspect(root)
            assertTrue(report.launchBackendsReady)
            assertTrue(report.desktopReady)
            assertTrue(report.complete)
            assertTrue(report.missing.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
