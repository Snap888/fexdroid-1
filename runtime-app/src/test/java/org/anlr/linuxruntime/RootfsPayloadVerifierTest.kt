package org.anlr.linuxruntime

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RootfsPayloadVerifierTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun acceptsExactSizeAndDigest() {
        val bytes = "ANLR rootfs test payload".toByteArray()
        val file = Files.createTempFile("anlr-rootfs-", ".tar").toFile()
        try {
            file.writeBytes(bytes)
            val result = RootfsPayloadVerifier.verifyArchive(file, bytes.size.toLong(), sha256(bytes), 1024)
            assertEquals(bytes.size.toLong(), result.actualSizeBytes)
            assertEquals(sha256(bytes), result.actualSha256)
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsWrongDigestAndSize() {
        val bytes = "payload".toByteArray()
        val file = Files.createTempFile("anlr-rootfs-", ".tar").toFile()
        try {
            file.writeBytes(bytes)
            assertThrows(IllegalStateException::class.java) {
                RootfsPayloadVerifier.verifyArchive(file, bytes.size.toLong(), "0".repeat(64), 1024)
            }
            assertThrows(IllegalStateException::class.java) {
                RootfsPayloadVerifier.verifyArchive(file, bytes.size.toLong() + 1, sha256(bytes), 1024)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsInvalidDigestAndConfiguredSizeLimit() {
        val bytes = byteArrayOf(1, 2, 3)
        val file = Files.createTempFile("anlr-rootfs-", ".tar").toFile()
        try {
            file.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java) {
                RootfsPayloadVerifier.verifyArchive(file, 3, "bad", 1024)
            }
            assertThrows(IllegalArgumentException::class.java) {
                RootfsPayloadVerifier.verifyArchive(file, 3, sha256(bytes), 2)
            }
        } finally {
            file.delete()
        }
    }
}
