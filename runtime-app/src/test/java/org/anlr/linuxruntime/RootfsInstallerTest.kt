package org.anlr.linuxruntime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsInstallerTest {
    @Test fun acceptsRootfsBuiltForTheActiveAppFilesDirectory() {
        val files = Files.createTempDirectory("anlr-installer-files-").toFile()
        val rootfs = Files.createTempDirectory("anlr-installer-rootfs-").toFile()
        try {
            val oldRelease = File(files, "old-release").apply { mkdirs() }
            Files.createSymbolicLink(File(files, "rootfs").toPath(), oldRelease.toPath())
            assertTrue(File(rootfs, "usr/bin").mkdirs())
            File(rootfs, "usr/bin/sh").writeText("shell")
            assertTrue(File(rootfs, "usr/lib/anlr").mkdirs())
            File(rootfs, "usr/lib/anlr/runtime-abi").writeText("anlr-glibc-v1\n")
            File(rootfs, "usr/lib/anlr/runtime-root").writeText(File(files.canonicalFile, "rootfs").path + "\n")
            RootfsInstaller.validateRootfsIdentity(rootfs, "anlr-glibc-v1", files)
        } finally {
            files.deleteRecursively()
            rootfs.deleteRecursively()
        }
    }

    @Test fun rejectsRootfsBuiltForTheLegacyPackagePath() {
        val files = Files.createTempDirectory("anlr-installer-files-").toFile()
        val rootfs = Files.createTempDirectory("anlr-installer-rootfs-").toFile()
        try {
            assertTrue(File(rootfs, "bin").mkdirs())
            File(rootfs, "bin/sh").writeText("shell")
            assertTrue(File(rootfs, "usr/lib/anlr").mkdirs())
            File(rootfs, "usr/lib/anlr/runtime-abi").writeText("anlr-glibc-v1\n")
            File(rootfs, "usr/lib/anlr/runtime-root").writeText("/data/data/ro.cobrabm.fexdroid/files/rootfs\n")
            assertThrows(IllegalStateException::class.java) {
                RootfsInstaller.validateRootfsIdentity(rootfs, "anlr-glibc-v1", files)
            }
        } finally {
            files.deleteRecursively()
            rootfs.deleteRecursively()
        }
    }
}