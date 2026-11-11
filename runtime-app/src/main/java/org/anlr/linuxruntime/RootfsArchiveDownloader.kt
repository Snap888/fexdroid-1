package org.anlr.linuxruntime

import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Resumable HTTPS downloader for an already signature-verified rootfs manifest. */
internal object RootfsArchiveDownloader {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 4
    private const val BUFFER_SIZE = 64 * 1024

    fun download(
        manifest: VerifiedRootfsManifest,
        downloadsDirectory: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File {
        require(manifest.archiveFormat == "tar.gz")
        require(manifest.archiveSizeBytes > 0)
        require(manifest.archiveSha256.matches(Regex("[a-fA-F0-9]{64}")))
        check(downloadsDirectory.exists() || downloadsDirectory.mkdirs()) {
            "Cannot create rootfs download directory"
        }
        check(downloadsDirectory.isDirectory)
        val directory = downloadsDirectory.canonicalFile
        // Bind resumable bytes to the signed archive digest. A reused releaseId
        // with a newer manifest must never append to bytes from the previous archive.
        val archiveIdentity = manifest.archiveSha256.lowercase()
        val partial = File(directory, "${manifest.releaseId}-${archiveIdentity}.tar.gz.part")
        val completed = File(directory, "${manifest.releaseId}-${archiveIdentity}.tar.gz")
        check(partial.canonicalFile.parentFile == directory && completed.canonicalFile.parentFile == directory) {
            "Invalid rootfs download path"
        }

        if (completed.isFile) {
            runCatching {
                RootfsPayloadVerifier.verifyArchive(
                    completed, manifest.archiveSizeBytes, manifest.archiveSha256,
                    manifest.archiveSizeBytes
                )
            }.onSuccess { return completed }
            completed.delete()
        }

        var offset = partial.length()
        if (offset > manifest.archiveSizeBytes) {
            check(partial.delete()) { "Cannot discard oversized partial archive" }
            offset = 0
        }
        if (offset == manifest.archiveSizeBytes) {
            return finalizeDownload(partial, completed, manifest)
        }
        check(directory.usableSpace >= manifest.archiveSizeBytes - offset) {
            "Insufficient free space for rootfs archive"
        }

        var currentUrl = URI(manifest.downloadUrl).toURL()
        var redirects = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Rootfs download interrupted")
            val connection = currentUrl.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    check(redirects++ < MAX_REDIRECTS) { "Too many rootfs download redirects" }
                    val location = connection.getHeaderField("Location")
                        ?: error("Rootfs redirect has no Location")
                    val next = currentUrl.toURI().resolve(location)
                    check(next.scheme == "https" && !next.host.isNullOrBlank() &&
                        next.userInfo == null && next.fragment == null) {
                        "Rootfs redirect must remain on HTTPS"
                    }
                    currentUrl = next.toURL()
                    continue
                }

                val append = when (status) {
                    HttpURLConnection.HTTP_OK -> false
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val contentRange = connection.getHeaderField("Content-Range") ?: ""
                        val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
                        validateContentRange(
                            contentRange, offset, manifest.archiveSizeBytes, contentLength
                        )
                        true
                    }
                    else -> error("Rootfs download failed with HTTP $status")
                }
                if (!append) offset = 0
                val mode = if (append) true else false
                FileOutputStream(partial, mode).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = offset
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedIOException("Rootfs download interrupted")
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= manifest.archiveSizeBytes) { "Rootfs server sent too much data" }
                            output.write(buffer, 0, count)
                            onProgress(total, manifest.archiveSizeBytes)
                        }
                        output.fd.sync()
                        check(total == manifest.archiveSizeBytes) { "Rootfs download is incomplete" }
                    }
                }
                return finalizeDownload(partial, completed, manifest)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun finalizeDownload(
        partial: File,
        completed: File,
        manifest: VerifiedRootfsManifest
    ): File {
        RootfsPayloadVerifier.verifyArchive(
            partial, manifest.archiveSizeBytes, manifest.archiveSha256,
            manifest.archiveSizeBytes
        )
        try {
            Files.move(
                partial.toPath(), completed.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), completed.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return completed
    }

    /**
     * A request for bytes=offset- must be answered with the complete remaining
     * interval. Reject truncated or internally inconsistent 206 responses before
     * appending them; the verified prefix remains available for a later retry.
     */
    internal fun validateContentRange(
        header: String,
        expectedOffset: Long,
        expectedTotal: Long,
        contentLength: Long
    ) {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header)
            ?: error("Invalid Content-Range for resumed rootfs download")
        val start = match.groupValues[1].toLongOrNull()
            ?: error("Invalid Content-Range start")
        val end = match.groupValues[2].toLongOrNull()
            ?: error("Invalid Content-Range end")
        val total = match.groupValues[3].toLongOrNull()
            ?: error("Invalid Content-Range total")
        check(start == expectedOffset) { "Server resumed at wrong byte offset" }
        check(total == expectedTotal) { "Server returned an unexpected archive size" }
        check(end == expectedTotal - 1 && end >= start) {
            "Server returned an incomplete or invalid byte range"
        }
        val expectedLength = end - start + 1
        check(contentLength < 0 || contentLength == expectedLength) {
            "Rootfs Content-Length does not match Content-Range"
        }
    }
}
