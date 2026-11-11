package org.anlr.linuxruntime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
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
            TarArchiveEntry("usr/bin/sh").apply { linkName = "dash" }.also {
                it.linkFlag = TarArchiveEntry.LF_SYMLINK
            } to byteArrayOf()
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

    @Test fun rejectsTraversalAndAbsoluteSymlinks() {
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

        val symlink = TarArchiveEntry("etc/passwd")
        symlink.linkFlag = TarArchiveEntry.LF_SYMLINK
        symlink.linkName = "/etc/passwd"
        val badLink = archive(symlink to byteArrayOf())
        val root2 = Files.createTempDirectory("anlr-rootfs-link-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SafeRootfsExtractor.extractTarGz(badLink, root2, 1024)
            }
        } finally {
            badLink.delete()
            root2.deleteRecursively()
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
