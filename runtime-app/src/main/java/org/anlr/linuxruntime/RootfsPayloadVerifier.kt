package org.anlr.linuxruntime

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal data class PayloadVerification(
    val actualSha256: String,
    val actualSizeBytes: Long
)

/** Verifies a downloaded rootfs archive against trusted, preconfigured metadata. */
internal object RootfsPayloadVerifier {
    fun verifyArchive(
        archive: File,
        expectedSizeBytes: Long,
        expectedSha256: String,
        maxSizeBytes: Long
    ): PayloadVerification {
        require(expectedSizeBytes > 0) { "Expected archive size must be positive" }
        require(maxSizeBytes > 0 && expectedSizeBytes <= maxSizeBytes) { "Archive exceeds configured size limit" }
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid expected SHA-256" }
        check(archive.isFile) { "Rootfs archive is missing" }
        check(archive.length() == expectedSizeBytes) { "Rootfs archive size mismatch" }

        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileInputStream(archive).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maxSizeBytes) { "Rootfs archive exceeds configured size limit" }
                digest.update(buffer, 0, count)
            }
        }
        check(total == expectedSizeBytes) { "Rootfs archive changed while being verified" }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(MessageDigest.isEqual(
            actual.toByteArray(Charsets.US_ASCII),
            expectedSha256.lowercase().toByteArray(Charsets.US_ASCII)
        )) { "Rootfs archive SHA-256 mismatch" }
        return PayloadVerification(actual, total)
    }
}
