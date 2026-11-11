package org.anlr.linuxruntime

import org.junit.Assert.assertThrows
import org.junit.Test

class RootfsArchiveDownloaderTest {
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
