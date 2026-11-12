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
            File(release, "rootfs").mkdirs()
            val checksum = digest("manifest")
            manager.markComplete("debian-arm64-v1", checksum)
            manager.activate("debian-arm64-v1", checksum)
            assertEquals("debian-arm64-v1", manager.activeReleaseId())
            assertTrue(manager.releaseDirectory("debian-arm64-v1")!!.isDirectory)
            assertTrue(manager.activeRootDirectory()!!.isDirectory)
            assertTrue(Files.isSymbolicLink(File(root, "rootfs").toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun preservesNonEmptyLegacyRootfsAndDoesNotPublishRelease() {
        val root = Files.createTempDirectory("anlr-legacy-rootfs-").toFile()
        try {
            val legacy = File(root, "rootfs")
            assertTrue(legacy.mkdirs())
            val legacyData = File(legacy, "keep.txt")
            legacyData.writeText("legacy data")
            val manager = RuntimeStorageManager(root)
            val staged = manager.createStagingRelease("new-release")
            assertTrue(File(staged, "rootfs").mkdirs())
            val checksum = digest("new manifest")
            manager.markComplete("new-release", checksum)
            try {
                manager.activate("new-release", checksum)
                throw AssertionError("Expected non-empty legacy rootfs to block activation")
            } catch (_: IllegalStateException) { }
            assertEquals("legacy data", legacyData.readText())
            assertNull(manager.activeReleaseId())
            assertTrue(staged.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun replacesOnlyEmptyLegacyRootfsDirectory() {
        val root = Files.createTempDirectory("anlr-empty-legacy-rootfs-").toFile()
        try {
            val legacy = File(root, "rootfs")
            assertTrue(legacy.mkdirs())
            val manager = RuntimeStorageManager(root)
            val staged = manager.createStagingRelease("new-release")
            assertTrue(File(staged, "rootfs").mkdirs())
            val checksum = digest("new manifest")
            manager.markComplete("new-release", checksum)
            manager.activate("new-release", checksum)
            assertTrue(Files.isSymbolicLink(legacy.toPath()))
            assertEquals("new-release", manager.activeReleaseId())
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
