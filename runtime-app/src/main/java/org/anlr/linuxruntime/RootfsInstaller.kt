package org.anlr.linuxruntime

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal data class RootfsInstallResult(
    val releaseId: String,
    val rootfsDirectory: File,
    val extractedEntries: Int,
    val extractedBytes: Long,
    val manifestSha256: String
)

/**
 * Installs only a signature-verified Debian ARM64 manifest. Interrupted downloads
 * resume from .part; interrupted extraction is safely discarded and repeated from
 * the already verified archive. Staging is retained on every failure.
 */
internal object RootfsInstaller {
    @Synchronized
    fun install(
        manifestBytes: ByteArray,
        signatureBase64: String,
        trustedPublicKeyBase64: String,
        expectedKeyId: String,
        filesDir: File,
        maxArchiveSizeBytes: Long,
        maxExtractedBytes: Long,
        onDownloadProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): RootfsInstallResult {
        val manifest = RootfsManifestVerifier.verify(
            manifestBytes, signatureBase64, trustedPublicKeyBase64,
            expectedKeyId, maxArchiveSizeBytes, maxExtractedBytes
        )
        require(maxExtractedBytes > 0) { "Expanded rootfs size limit must be positive" }
        val storage = RuntimeStorageManager(filesDir)
        storage.initialize()

        val published = storage.releaseDirectory(manifest.releaseId)
        if (published != null) {
            val publishedRoot = File(published, "rootfs")
            check(publishedRoot.isDirectory &&
                (File(publishedRoot, "bin/sh").exists() || File(publishedRoot, "usr/bin/sh").exists())) {
                "Published rootfs is missing its Linux shell"
            }
            storage.activate(manifest.releaseId, manifest.manifestSha256)
            return RootfsInstallResult(
                manifest.releaseId, storage.activeRootDirectory() ?: publishedRoot, 0, 0, manifest.manifestSha256
            )
        }

        val staging = storage.stagingReleaseDirectory(manifest.releaseId)
            ?: storage.createStagingRelease(manifest.releaseId)
        // Crash may occur after writing ROOTFS_COMPLETE but before rename/active pointer.
        // Re-validate the staged shell and finish activation instead of downloading again.
        if (storage.isStagingReleaseComplete(manifest.releaseId, manifest.manifestSha256)) {
            val stagedRoot = File(staging, "rootfs")
            check(stagedRoot.isDirectory &&
                (File(stagedRoot, "bin/sh").exists() || File(stagedRoot, "usr/bin/sh").exists())) {
                "Completed staging release is missing its Linux shell"
            }
            storage.activate(manifest.releaseId, manifest.manifestSha256)
            return RootfsInstallResult(
                manifest.releaseId, storage.activeRootDirectory() ?: File(storage.releaseDirectory(manifest.releaseId)!!, "rootfs"),
                0, 0, manifest.manifestSha256
            )
        }
        val previousJournal = storage.stagingJournal(manifest.releaseId)
        check(previousJournal == null || previousJournal.manifestSha256 == manifest.manifestSha256) {
            "Staging directory belongs to a different signed rootfs manifest"
        }

        storage.updateStagingJournal(manifest.releaseId, manifest.manifestSha256, RootfsInstallPhase.DOWNLOADING)
        val archive = RootfsArchiveDownloader.download(
            manifest, storage.downloadsDirectory(), onDownloadProgress
        )
        storage.updateStagingJournal(manifest.releaseId, manifest.manifestSha256, RootfsInstallPhase.DOWNLOADED)

        check(staging.usableSpace >= manifest.expandedSizeBytes) {
            "Insufficient free space for expanded Debian rootfs"
        }
        val extractedRoot = File(staging, "rootfs")
        if (extractedRoot.exists()) deleteTreeNoFollow(extractedRoot.toPath())
        check(extractedRoot.mkdirs()) { "Cannot create rootfs extraction directory" }
        storage.updateStagingJournal(manifest.releaseId, manifest.manifestSha256, RootfsInstallPhase.EXTRACTING)
        val extraction = SafeRootfsExtractor.extractTarGz(
            archive, extractedRoot, manifest.expandedSizeBytes
        )
        check(File(extractedRoot, "bin/sh").exists() || File(extractedRoot, "usr/bin/sh").exists()) {
            "Downloaded archive is not a usable Debian ARM64 rootfs: /bin/sh is missing"
        }
        val runtimeAbi = File(extractedRoot, "usr/lib/anlr/runtime-abi")
        check(runtimeAbi.isFile && runtimeAbi.readText().trim() == manifest.runtimeAbi) {
            "Rootfs does not contain the required ANLR-patched glibc runtime ABI"
        }
        storage.updateStagingJournal(manifest.releaseId, manifest.manifestSha256, RootfsInstallPhase.EXTRACTED)
        storage.markComplete(manifest.releaseId, manifest.manifestSha256)
        storage.activate(manifest.releaseId, manifest.manifestSha256)

        return RootfsInstallResult(
            manifest.releaseId, storage.activeRootDirectory() ?: File(storage.releaseDirectory(manifest.releaseId)!!, "rootfs"),
            extraction.entriesExtracted, extraction.bytesExtracted, manifest.manifestSha256
        )
    }

    private fun deleteTreeNoFollow(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
