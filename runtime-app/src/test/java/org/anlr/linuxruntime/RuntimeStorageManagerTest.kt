package org.anlr.linuxruntime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStorageManagerTest {
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun activatesOnlyCompleteReleaseWithMatchingManifest() {
        val root = Files.createTempDirectory("anlr-storage-").toFile()
        try {
            val manager = RuntimeStorageManager(root)
            val release = manager.createStagingRelease("debian-arm64-v1")
            File(release, "etc").mkdirs()
            val checksum = digest("manifest")
            manager.markComplete("debian-arm64-v1", checksum)
            manager.activate("debian-arm64-v1", checksum)
            assertEquals("debian-arm64-v1", manager.activeReleaseId())
            assertTrue(manager.releaseDirectory("debian-arm64-v1")!!.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesChecksumMismatchAndIncompleteRelease() {
        val root = Files.createTempDirectory("anlr-storage-").toFile()
        try {
            val manager = RuntimeStorageManager(root)
            manager.createStagingRelease("incomplete")
            try {
                manager.activate("incomplete", digest("wrong"))
                throw AssertionError("Expected incomplete release to be rejected")
            } catch (_: IllegalStateException) { }
            manager.createStagingRelease("verified")
            manager.markComplete("verified", digest("right"))
            try {
                manager.activate("verified", digest("wrong"))
                throw AssertionError("Expected checksum mismatch")
            } catch (_: IllegalStateException) { }
            assertNull(manager.activeReleaseId())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsTraversalAndPreservesInterruptedStagingForRecovery() {
        val root = Files.createTempDirectory("anlr-storage-").toFile()
        try {
            val manager = RuntimeStorageManager(root)
            try {
                manager.createStagingRelease("../escape")
                throw AssertionError("Expected invalid ID")
            } catch (_: IllegalArgumentException) { }
            manager.createStagingRelease("partial")
            assertEquals(listOf("partial"), manager.recoverStagingReleases())
            assertTrue(File(root, "runtime/rootfs/staging/partial").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun persistsJournalAndRecoversActivationAfterDirectoryRename() {
        val root = Files.createTempDirectory("anlr-recovery-").toFile()
        try {
            val manager = RuntimeStorageManager(root)
            val staged = manager.createStagingRelease("debian-recovery")
            val checksum = digest("signed-manifest")
            manager.updateStagingJournal("debian-recovery", checksum, RootfsInstallPhase.DOWNLOADING)
            val journal = manager.stagingJournal("debian-recovery")!!
            assertEquals(checksum, journal.manifestSha256)
            assertEquals(RootfsInstallPhase.DOWNLOADING, journal.phase)

            File(staged, "rootfs").mkdirs()
            manager.markComplete("debian-recovery", checksum)
            val published = File(root, "runtime/rootfs/releases/debian-recovery")
            assertTrue(staged.renameTo(published))
            manager.activate("debian-recovery", checksum)
            assertEquals("debian-recovery", manager.activeReleaseId())
        } finally {
            root.deleteRecursively()
        }
    }

}
