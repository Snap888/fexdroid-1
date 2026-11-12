package org.anlr.linuxruntime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeRootfsExtractorTest {
    private fun archive(vararg entries: Pair<TarArchiveEntry, ByteArray>): File {
        val file = Files.createTempFile("anlr-safe-rootfs-", ".tar.gz").toFile()
        TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(file))).use { tar ->
            entries.forEach { (entry, bytes) ->
                if (entry.isFile) entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                if (entry.isFile) tar.write(bytes)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        return file
    }

    @Test fun extractsRegularFilesAndRelativeSymlinks() {
        val bytes = "PRETTY_NAME=Debian".toByteArray()
        val tar = archive(
            TarArchiveEntry("etc/os-release") to bytes,
            TarArchiveEntry("usr/bin/sh", TarConstants.LF_SYMLINK).apply { linkName = "dash" } to byteArrayOf()
        )
        val root = Files.createTempDirectory("anlr-rootfs-out-").toFile()
        try {
            val result = SafeRootfsExtractor.extractTarGz(tar, root, 1024)
            assertEquals(2, result.entriesExtracted)
            assertEquals(1, result.regularFilesExtracted)
            assertEquals("PRETTY_NAME=Debian", File(root, "etc/os-release").readText())
            assertTrue(Files.isSymbolicLink(File(root, "usr/bin/sh").toPath()))
        } finally {
            tar.delete()
            root.deleteRecursively()
        }
    }

    @Test fun rejectsTraversalAndNormalizesGuestAbsoluteSymlinks() {
        val traversal = archive(TarArchiveEntry("../escape") to "x".toByteArray())
        val root1 = Files.createTempDirectory("anlr-rootfs-bad-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SafeRootfsExtractor.extractTarGz(traversal, root1, 1024)
            }
        } finally {
            traversal.delete()
            root1.deleteRecursively()
        }

        val normalizedTraversal = archive(TarArchiveEntry("usr/../escape") to "x".toByteArray())
        val rootAlias = Files.createTempDirectory("anlr-rootfs-alias-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SafeRootfsExtractor.extractTarGz(normalizedTraversal, rootAlias, 1024)
            }
        } finally {
            normalizedTraversal.delete()
            rootAlias.deleteRecursively()
        }

        val symlink = TarArchiveEntry("bin/sh", TarConstants.LF_SYMLINK)
        symlink.linkName = "/usr/bin/dash"
        val safeLink = archive(symlink to byteArrayOf())
        val root2 = Files.createTempDirectory("anlr-rootfs-link-").toFile()
        try {
            SafeRootfsExtractor.extractTarGz(safeLink, root2, 1024)
            assertTrue(Files.isSymbolicLink(File(root2, "bin/sh").toPath()))
            assertEquals(
                "../usr/bin/dash",
                Files.readSymbolicLink(File(root2, "bin/sh").toPath()).toString()
            )
        } finally {
            safeLink.delete()
            root2.deleteRecursively()
        }

        val escapingLink = TarArchiveEntry("etc/out", TarConstants.LF_SYMLINK)
        escapingLink.linkName = "../../../../outside"
        val badLink = archive(escapingLink to byteArrayOf())
        val root3 = Files.createTempDirectory("anlr-rootfs-link-bad-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SafeRootfsExtractor.extractTarGz(badLink, root3, 1024)
            }
        } finally {
            badLink.delete()
            root3.deleteRecursively()
        }
    }

    @Test fun enforcesExpandedSizeLimit() {
        val tar = archive(TarArchiveEntry("large.bin") to ByteArray(64) { 1 })
        val root = Files.createTempDirectory("anlr-rootfs-limit-").toFile()
        try {
            assertThrows(IllegalStateException::class.java) {
                SafeRootfsExtractor.extractTarGz(tar, root, 32)
            }
        } finally {
            tar.delete()
            root.deleteRecursively()
        }
    }
}
