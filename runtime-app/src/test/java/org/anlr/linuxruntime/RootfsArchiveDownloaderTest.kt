package org.anlr.linuxruntime

import org.junit.Assert.assertThrows
import org.junit.Test

class RootfsArchiveDownloaderTest {
    @Test fun checksumMismatchDeletesPartialSoNextAttemptCanRestart() {
        val directory = java.nio.file.Files.createTempDirectory("anlr-download-test").toFile()
        try {
            val partial = java.io.File(directory, "release-corrupt.tar.gz.part")
            val completed = java.io.File(directory, "release-corrupt.tar.gz")
            partial.writeBytes("not-the-signed-archive".toByteArray())
            val manifest = VerifiedRootfsManifest(
                releaseId = "release-corrupt",
                architecture = "aarch64",
                runtimeAbi = "anlr-glibc-v1",
                archiveFormat = "tar.gz",
                downloadUrl = "https://example.invalid/rootfs.tar.gz",
                archiveSizeBytes = partial.length(),
                expandedSizeBytes = 1024,
                archiveSha256 = "0".repeat(64),
                manifestSha256 = "1".repeat(64),
                keyId = "test-key"
            )

            assertThrows(IllegalStateException::class.java) {
                RootfsArchiveDownloader.finalizeDownload(partial, completed, manifest)
            }

            org.junit.Assert.assertFalse("Corrupt partial must be removed", partial.exists())
            org.junit.Assert.assertFalse("Unverified archive must not be published", completed.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun acceptsExactRemainingRange() {
        RootfsArchiveDownloader.validateContentRange(
            "bytes 4096-8191/8192", 4096, 8192, 4096
        )
    }

    @Test fun acceptsMissingContentLengthWhenRangeIsExact() {
        RootfsArchiveDownloader.validateContentRange(
            "bytes 4096-8191/8192", 4096, 8192, -1
        )
    }

    @Test fun rejectsWrongResumeOffset() {
        assertThrows(IllegalStateException::class.java) {
            RootfsArchiveDownloader.validateContentRange(
                "bytes 4095-8191/8192", 4096, 8192, 4097
            )
        }
    }

    @Test fun rejectsWrongTotalSize() {
        assertThrows(IllegalStateException::class.java) {
            RootfsArchiveDownloader.validateContentRange(
                "bytes 4096-8191/9000", 4096, 8192, 4096
            )
        }
    }

    @Test fun rejectsTruncatedRange() {
        assertThrows(IllegalStateException::class.java) {
            RootfsArchiveDownloader.validateContentRange(
                "bytes 4096-6143/8192", 4096, 8192, 2048
            )
        }
    }

    @Test fun rejectsContentLengthMismatch() {
        assertThrows(IllegalStateException::class.java) {
            RootfsArchiveDownloader.validateContentRange(
                "bytes 4096-8191/8192", 4096, 8192, 2048
            )
        }
    }

    @Test fun rejectsMalformedRange() {
        assertThrows(IllegalStateException::class.java) {
            RootfsArchiveDownloader.validateContentRange(
                "bytes */8192", 4096, 8192, -1
            )
        }
    }
}
