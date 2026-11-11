package org.anlr.linuxruntime

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * App-private release layout and atomic rootfs activation.
 * This does not download, unpack, mount or execute a Linux distribution.
 */
internal enum class RootfsInstallPhase { PREPARING, DOWNLOADING, DOWNLOADED, EXTRACTING, EXTRACTED }

internal data class RootfsStagingJournal(
    val releaseId: String,
    val manifestSha256: String,
    val phase: RootfsInstallPhase
)

internal class RuntimeStorageManager(filesDir: File) {
    private val base = File(filesDir, "runtime")
    private val releases = File(base, "rootfs/releases")
    private val staging = File(base, "rootfs/staging")
    private val downloads = File(base, "downloads")
    private val applications = File(base, "applications")
    private val logs = File(base, "logs")
    private val cache = File(base, "cache")
    private val activePointer = File(base, "rootfs/active-release")

    fun initialize() {
        listOf(base, releases, staging, downloads, applications, logs, cache).forEach {
            check(it.exists() || it.mkdirs()) { "Cannot create runtime directory: ${it.name}" }
            check(it.isDirectory) { "Runtime path is not a directory: ${it.name}" }
        }
    }

    fun createStagingRelease(releaseId: String): File {
        validateId(releaseId)
        initialize()
        val target = child(staging, releaseId)
        check(!target.exists()) { "Release already exists in staging" }
        check(target.mkdirs()) { "Cannot create staging release" }
        return target
    }

    fun stagingReleaseDirectory(releaseId: String): File? {
        validateId(releaseId)
        val directory = child(staging, releaseId)
        return directory.takeIf { it.isDirectory }
    }

    fun downloadsDirectory(): File {
        initialize()
        return downloads
    }

    fun updateStagingJournal(releaseId: String, manifestSha256: String, phase: RootfsInstallPhase) {
        validateId(releaseId)
        require(manifestSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid manifest SHA-256" }
        val directory = child(staging, releaseId)
        check(directory.isDirectory) { "Staging release does not exist" }
        writeAtomically(
            File(directory, INSTALL_JOURNAL),
            "$manifestSha256\n${phase.name}\n"
        )
    }

    fun stagingJournal(releaseId: String): RootfsStagingJournal? {
        validateId(releaseId)
        val directory = child(staging, releaseId)
        val journal = File(directory, INSTALL_JOURNAL)
        if (!journal.isFile) return null
        val lines = runCatching { journal.readLines(Charsets.UTF_8) }.getOrNull() ?: return null
        if (lines.size < 2 || !lines[0].matches(Regex("[a-fA-F0-9]{64}"))) return null
        val phase = runCatching { RootfsInstallPhase.valueOf(lines[1]) }.getOrNull() ?: return null
        return RootfsStagingJournal(releaseId, lines[0].lowercase(), phase)
    }

    /** Mark only after the caller has extracted and independently verified the payload. */
    fun markComplete(releaseId: String, manifestSha256: String) {
        validateId(releaseId)
        require(manifestSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid manifest SHA-256" }
        val directory = child(staging, releaseId)
        check(directory.isDirectory) { "Staging release does not exist" }
        writeAtomically(File(directory, COMPLETE_MARKER), manifestSha256.lowercase() + "\n")
    }

    fun activate(releaseId: String, expectedManifestSha256: String) {
        validateId(releaseId)
        require(expectedManifestSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid expected SHA-256" }
        initialize()
        val staged = child(staging, releaseId)
        val released = child(releases, releaseId)
        val stagedMarker = File(staged, COMPLETE_MARKER)
        val releasedMarker = File(released, COMPLETE_MARKER)
        val alreadyPublished = released.isDirectory && releasedMarker.isFile && !staged.exists()
        val marker = if (alreadyPublished) releasedMarker else stagedMarker
        check(marker.isFile) { "Release is incomplete" }
        val recorded = marker.readText().trim()
        check(recorded.equals(expectedManifestSha256, ignoreCase = true)) { "Manifest checksum mismatch" }
        val movedNow = !alreadyPublished
        if (movedNow) {
            check(staged.isDirectory && !released.exists()) { "Release ID is already installed" }
            check(staged.renameTo(released)) { "Cannot atomically publish release" }
        }
        try {
            writeAtomically(activePointer, releaseId + "\n")
        } catch (error: Exception) {
            if (movedNow) released.renameTo(staged)
            throw error
        }
    }

    fun activeReleaseId(): String? {
        if (!activePointer.isFile) return null
        val id = activePointer.readText().trim()
        if (!ID_PATTERN.matches(id)) return null
        val release = child(releases, id)
        val marker = File(release, COMPLETE_MARKER)
        return id.takeIf { release.isDirectory && marker.isFile }
    }

    fun releaseDirectory(releaseId: String): File? {
        validateId(releaseId)
        val release = child(releases, releaseId)
        return release.takeIf { it.isDirectory && File(it, COMPLETE_MARKER).isFile }
    }

    /**
     * Finds interrupted installs without deleting their data. The installer can
     * validate its journal and resume or safely restart each returned release.
     */
    fun recoverStagingReleases(): List<String> {
        initialize()
        return staging.listFiles()
            ?.filter { it.isDirectory && ID_PATTERN.matches(it.name) && !File(it, COMPLETE_MARKER).isFile }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    private fun child(parent: File, name: String): File {
        val result = File(parent, name)
        val parentPath = parent.canonicalFile
        val resultPath = result.canonicalFile
        check(resultPath.parentFile == parentPath) { "Path escapes runtime directory" }
        return result
    }

    private fun validateId(id: String) {
        require(ID_PATTERN.matches(id)) { "Invalid release ID" }
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, ".${target.name}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temp.renameTo(target)) { "Atomic file replacement failed" }
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val COMPLETE_MARKER = "ROOTFS_COMPLETE"
        private const val INSTALL_JOURNAL = "INSTALL_STATE"
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
