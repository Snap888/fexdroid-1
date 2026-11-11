package org.anlr.linuxruntime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfCompatibilityCheckerTest {
    private fun elf(
        machine: Int = 183,
        interpreter: String? = "/lib/ld-linux-aarch64.so.1",
        needed: List<String> = listOf("libc.so.6")
    ) = ElfInfo(2, 1, 2, machine, "test", true, interpreter, needed)

    @Test fun resolvesNativeArm64InterpreterAndLibraries() {
        val root = Files.createTempDirectory("anlr-compat-").toFile()
        try {
            File(root, "lib").mkdirs()
            File(root, "lib/ld-linux-aarch64.so.1").writeText("")
            File(root, "lib/libc.so.6").writeText("")
            val report = ElfCompatibilityChecker.check(elf(), root, ExecutionBackend.NATIVE_ARM64)
            assertTrue(report.compatible)
            assertEquals(2, report.resolvedLibraries.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun reportsMissingDependenciesAndRejectsWrongArchitecture() {
        val root = Files.createTempDirectory("anlr-compat-missing-").toFile()
        try {
            val missing = ElfCompatibilityChecker.check(elf(needed = listOf("libmissing.so")), root, ExecutionBackend.NATIVE_ARM64)
            assertFalse(missing.compatible)
            assertEquals(listOf("libmissing.so"), missing.missingLibraries)
            val wrong = ElfCompatibilityChecker.check(elf(machine = 62), root, ExecutionBackend.NATIVE_ARM64)
            assertFalse(wrong.compatible)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun x64BackendRequiresInstalledExecutableAndX64Libraries() {
        val root = Files.createTempDirectory("anlr-compat-x64-").toFile()
        val backend = File(root, "usr/bin/fex").apply { parentFile.mkdirs(); writeText("#!/bin/sh"); setExecutable(true) }
        try {
            File(root, "lib/x86_64-linux-gnu").mkdirs()
            File(root, "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2").writeText("")
            File(root, "lib/x86_64-linux-gnu/libc.so.6").writeText("")
            val report = ElfCompatibilityChecker.check(
                elf(machine = 62, interpreter = "/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"),
                root, ExecutionBackend.FEX_X86_64, backend
            )
            assertTrue(report.compatible)
            val noBackend = ElfCompatibilityChecker.check(
                elf(machine = 62), root, ExecutionBackend.FEX_X86_64, null
            )
            assertFalse(noBackend.compatible)
        } finally {
            root.deleteRecursively()
        }
    }
}
