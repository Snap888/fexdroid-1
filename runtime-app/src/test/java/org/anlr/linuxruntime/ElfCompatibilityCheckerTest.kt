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
    @Test fun acceptsAbsoluteInterpreterWhenItResolvesInsideActiveRootfs() {
        val root = Files.createTempDirectory("anlr-compat-absolute-").toFile()
        try {
            val loader = File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
            loader.parentFile.mkdirs()
            loader.writeText("")
            File(root, "usr/lib/aarch64-linux-gnu/libc.so.6").writeText("")
            val report = ElfCompatibilityChecker.check(
                elf(interpreter = "/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"), root, ExecutionBackend.NATIVE_ARM64
            )
            assertTrue(report.compatible)
            assertEquals(loader.absolutePath, report.resolvedLibraries["PT_INTERP"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun resolvesDeviceAbsoluteInterpreterPathWithoutDuplicatingRootfs() {
        val root = Files.createTempDirectory("anlr-compat-device-path-").toFile()
        try {
            val loader = File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
            loader.parentFile.mkdirs()
            loader.writeText("")
            File(root, "usr/lib/aarch64-linux-gnu/libc.so.6").writeText("")
            val deviceAbsoluteInterpreter = File(loader, "").absolutePath
            val report = ElfCompatibilityChecker.check(
                elf(interpreter = deviceAbsoluteInterpreter), root, ExecutionBackend.NATIVE_ARM64
            )
            assertTrue(report.reasons.toString(), report.compatible)
            assertEquals(loader.canonicalPath, report.resolvedLibraries["PT_INTERP"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun resolvesDeviceAbsoluteInterpreterThroughActiveRootfsSymlink() {
        val files = Files.createTempDirectory("anlr-files-").toFile()
        val releaseRoot = File(files, "runtime/rootfs/releases/r1/rootfs")
        val activeRoot = File(files, "rootfs")
        try {
            val loader = File(releaseRoot, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
            loader.parentFile.mkdirs()
            loader.writeText("")
            File(releaseRoot, "usr/lib/aarch64-linux-gnu/libc.so.6").writeText("")
            Files.createSymbolicLink(activeRoot.toPath(), releaseRoot.toPath().let { files.toPath().relativize(it) })
            val interpreter = File(activeRoot, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").absolutePath
            val report = ElfCompatibilityChecker.check(
                elf(interpreter = interpreter), activeRoot, ExecutionBackend.NATIVE_ARM64
            )
            assertTrue(report.reasons.toString(), report.compatible)
            assertEquals(loader.canonicalPath, report.resolvedLibraries["PT_INTERP"])
        } finally {
            files.deleteRecursively()
        }
    }


    @Test fun resolvesDeviceInterpreterThroughDataDataAliasAndActiveRootfsSymlink() {
        val base = Files.createTempDirectory("anlr-android-data-alias-").toFile()
        try {
            val userFiles = File(base, "data/user/0/org.anlr.linuxruntime/files")
            val releaseRoot = File(userFiles, "runtime/rootfs/releases/r1/rootfs")
            val activeRoot = File(userFiles, "rootfs")
            val legacyFiles = File(base, "data/data/org.anlr.linuxruntime/files")
            val loader = File(releaseRoot, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
            loader.parentFile.mkdirs()
            loader.writeText("")
            File(releaseRoot, "usr/lib/aarch64-linux-gnu/libc.so.6").writeText("")
            Files.createDirectories(activeRoot.parentFile.toPath())
            Files.createSymbolicLink(activeRoot.toPath(), userFiles.toPath().relativize(releaseRoot.toPath()))
            Files.createDirectories(legacyFiles.parentFile.toPath())
            Files.createSymbolicLink(legacyFiles.toPath(), legacyFiles.parentFile.toPath().relativize(userFiles.toPath()))

            val interpreter = File(legacyFiles, "rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").absolutePath
            val report = ElfCompatibilityChecker.check(
                elf(interpreter = interpreter), activeRoot, ExecutionBackend.NATIVE_ARM64
            )
            assertTrue(report.reasons.toString(), report.compatible)
            assertEquals(loader.canonicalPath, report.resolvedLibraries["PT_INTERP"])
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun resolvesLib64InterpreterAndRejectsSymlinkEscape() {
        val root = Files.createTempDirectory("anlr-compat-lib64-").toFile()
        val outside = Files.createTempDirectory("anlr-compat-outside-").toFile()
        try {
            File(root, "lib64").mkdirs()
            File(root, "lib64/ld-linux-x86-64.so.2").writeText("")
            File(root, "lib64/libc.so.6").writeText("")
            val backend = File(root, "usr/bin/fex").apply {
                parentFile.mkdirs()
                writeText("#!/bin/sh")
                setExecutable(true)
            }
            val lib64Report = ElfCompatibilityChecker.check(
                elf(machine = 62, interpreter = "/lib64/ld-linux-x86-64.so.2"),
                root, ExecutionBackend.FEX_X86_64, backend
            )
            assertTrue(lib64Report.compatible)

            File(root, "lib").delete()
            Files.createSymbolicLink(File(root, "lib").toPath(), outside.toPath())
            File(outside, "ld-linux-aarch64.so.1").writeText("")
            File(outside, "libc.so.6").writeText("")
            val escaped = ElfCompatibilityChecker.check(
                elf(interpreter = "/lib/ld-linux-aarch64.so.1"),
                root, ExecutionBackend.NATIVE_ARM64
            )
            assertFalse(escaped.compatible)
            assertTrue(escaped.reasons.any { it.contains("escapes dependency rootfs") })
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

}
