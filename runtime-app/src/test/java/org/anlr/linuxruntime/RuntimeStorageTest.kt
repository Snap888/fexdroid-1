package org.anlr.linuxruntime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeStorageTest {
    @Test fun createsPrivateLayoutAndStartsWithoutActiveRootfs() {
        val temp = createTempDir(prefix = "anlr-layout-")
        try {
            val layout = RuntimeStorage.initialize(temp)
            assertEquals(null, RuntimeStorage.active(layout))
            assertEquals(true, layout.applications.isDirectory)
            assertEquals(true, layout.releases.isDirectory)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun activatesOnlyCompletedRelease() {
        val temp = createTempDir(prefix = "anlr-activate-")
        try {
            val layout = RuntimeStorage.initialize(temp)
            val release = RuntimeStorage.releaseDirectory(layout, "debian-arm64-1")
            assertEquals(true, release.mkdirs())
            assertThrows(IllegalStateException::class.java) {
                RuntimeStorage.activate(layout, "debian-arm64-1", "a".repeat(64))
            }
            File(release, "ROOTFS_COMPLETE").writeText("verified")
            RuntimeStorage.activate(layout, "debian-arm64-1", "A".repeat(64))
            val active = RuntimeStorage.active(layout)!!
            assertEquals("debian-arm64-1", active.releaseId)
            assertEquals("a".repeat(64), active.manifestSha256)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun rejectsPathTraversalAndInvalidDigest() {
        val temp = createTempDir(prefix = "anlr-traversal-")
        try {
            val layout = RuntimeStorage.initialize(temp)
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeStorage.releaseDirectory(layout, "../outside")
            }
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeStorage.activate(layout, "valid-release", "not-a-digest")
            }
            assertNull(RuntimeStorage.active(layout))
        } finally {
            temp.deleteRecursively()
        }
    }
}
