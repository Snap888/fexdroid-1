package org.anlr.linuxruntime

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
            validateRootfsIdentity(publishedRoot, manifest.runtimeAbi, filesDir)
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
            validateRootfsIdentity(stagedRoot, manifest.runtimeAbi, filesDir)
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
        validateRootfsIdentity(extractedRoot, manifest.runtimeAbi, filesDir)
        storage.updateStagingJournal(manifest.releaseId, manifest.manifestSha256, RootfsInstallPhase.EXTRACTED)
        storage.markComplete(manifest.releaseId, manifest.manifestSha256)
        storage.activate(manifest.releaseId, manifest.manifestSha256)

        return RootfsInstallResult(
            manifest.releaseId, storage.activeRootDirectory() ?: File(storage.releaseDirectory(manifest.releaseId)!!, "rootfs"),
            extraction.entriesExtracted, extraction.bytesExtracted, manifest.manifestSha256
        )
    }

    /**
     * Installs a user-selected archive from Android Storage Access Framework.
     * This path deliberately does not claim publisher authenticity: the SHA-256
     * is calculated locally and the user must explicitly choose the archive.
     */
    @Synchronized
    fun installLocalArchive(
        archive: File,
        distribution: String,
        filesDir: File,
        maxArchiveSizeBytes: Long,
        maxExtractedBytes: Long
    ): RootfsInstallResult {
        require(distribution == "Debian" || distribution == "Ubuntu") { "Unsupported Linux distribution" }
        require(maxExtractedBytes > 0) { "Expanded rootfs size limit must be positive" }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maxArchiveSizeBytes) { "Selected rootfs archive exceeds size limit" }
                digest.update(buffer, 0, count)
            }
        }
        val archiveSize = archive.length()
        check(archiveSize in 1..maxArchiveSizeBytes) { "Selected rootfs archive is empty or too large" }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val slug = if (distribution == "Debian") "debian" else "ubuntu"
        val releaseId = "local-$slug-${sha256.take(12)}"
        val storage = RuntimeStorageManager(filesDir)
        storage.initialize()

        val published = storage.releaseDirectory(releaseId)
        if (published != null) {
            val root = File(published, "rootfs")
            validateRootfsIdentity(root, "anlr-glibc-v1", filesDir)
            storage.activate(releaseId, sha256)
            return RootfsInstallResult(releaseId, storage.activeRootDirectory() ?: root, 0, 0, sha256)
        }

        val staging = storage.stagingReleaseDirectory(releaseId) ?: storage.createStagingRelease(releaseId)
        val previousJournal = storage.stagingJournal(releaseId)
        check(previousJournal == null || previousJournal.manifestSha256 == sha256) {
            "Staging directory belongs to a different local archive"
        }
        storage.updateStagingJournal(releaseId, sha256, RootfsInstallPhase.EXTRACTING)
        check(staging.usableSpace >= archiveSize * 2) {
            "Insufficient free space for rootfs extraction"
        }
        val root = File(staging, "rootfs")
        if (root.exists()) deleteTreeNoFollow(root.toPath())
        check(root.mkdirs()) { "Cannot create local rootfs extraction directory" }
        val extraction = SafeRootfsExtractor.extractTarGz(archive, root, maxExtractedBytes)
        validateRootfsIdentity(root, "anlr-glibc-v1", filesDir)
        val osRelease = File(root, "etc/os-release").takeIf { it.isFile }?.readText().orEmpty()
        val actualDistro = when {
            Regex("(?m)^ID=ubuntu$").containsMatchIn(osRelease) -> "Ubuntu"
            Regex("(?m)^ID=debian$").containsMatchIn(osRelease) -> "Debian"
            else -> error("Archive is not a recognized Debian/Ubuntu rootfs")
        }
        check(actualDistro == distribution) {
            "Selected $distribution but archive contains $actualDistro"
        }
        storage.updateStagingJournal(releaseId, sha256, RootfsInstallPhase.EXTRACTED)
        storage.markComplete(releaseId, sha256)
        storage.activate(releaseId, sha256)
        return RootfsInstallResult(
            releaseId, storage.activeRootDirectory() ?: File(storage.releaseDirectory(releaseId)!!, "rootfs"),
            extraction.entriesExtracted, extraction.bytesExtracted, sha256
        )
    }

    /** Validate the payload's compile-time paths as well as its ABI marker. */
    internal fun validateRootfsIdentity(rootfs: File, expectedRuntimeAbi: String, filesDir: File) {
        check(rootfs.isDirectory &&
            (File(rootfs, "bin/sh").exists() || File(rootfs, "usr/bin/sh").exists())) {
            "Rootfs is missing its Linux shell"
        }
        val runtimeAbi = File(rootfs, "usr/lib/anlr/runtime-abi")
        check(runtimeAbi.isFile && runtimeAbi.readText().trim() == expectedRuntimeAbi) {
            "Rootfs does not contain the required ANLR-patched glibc runtime ABI"
        }
        val runtimeRoot = File(rootfs, "usr/lib/anlr/runtime-root")
        check(runtimeRoot.isFile) { "Rootfs is missing its compiled runtime-root identity" }
        val compiledPath = File(runtimeRoot.readText().trim()).absoluteFile
        check(compiledPath.name == "rootfs") {
            "Rootfs contains an invalid compiled runtime-root path"
        }
        val compiledParent = requireNotNull(compiledPath.parentFile) {
            "Rootfs compiled runtime-root path has no parent"
        }
        // Canonicalize only the parent: filesDir/rootfs may already be a symlink
        // to the currently active release, but the compiled ABI path is that stable link.
        val compiledRoot = File(compiledParent.canonicalFile, compiledPath.name)
        val expectedRoot = File(filesDir.canonicalFile, "rootfs")
        check(compiledRoot == expectedRoot) {
            "Rootfs was built for a different Android package path: $compiledRoot (expected $expectedRoot)"
        }
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
