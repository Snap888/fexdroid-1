package org.anlr.linuxruntime

import java.io.File
import java.io.FileOutputStream

internal data class RuntimeLayout(
    val base: File,
    val rootfs: File,
    val releases: File,
    val staging: File,
    val downloads: File,
    val applications: File,
    val logs: File,
    val cache: File,
    val activePointer: File
)

internal data class ActiveRootfs(val releaseId: String, val manifestSha256: String)

internal object RuntimeStorage {
    private val releaseIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private val sha256Pattern = Regex("[a-fA-F0-9]{64}")

    fun initialize(filesDir: File): RuntimeLayout {
        val base = File(filesDir, "anlr-runtime").canonicalFile
        val rootfs = child(base, "rootfs")
        val layout = RuntimeLayout(
            base = base,
            rootfs = rootfs,
            releases = child(rootfs, "releases"),
            staging = child(rootfs, "staging"),
            downloads = child(base, "downloads"),
            applications = child(base, "applications"),
            logs = child(base, "logs"),
            cache = child(base, "cache"),
            activePointer = child(rootfs, "active.release")
        )
        listOf(layout.releases, layout.staging, layout.downloads, layout.applications, layout.logs, layout.cache)
            .forEach { directory ->
                check(directory.exists() || directory.mkdirs()) { "Cannot create runtime directory: $directory" }
                check(directory.isDirectory) { "Runtime path is not a directory: $directory" }
            }
        return layout
    }

    fun releaseDirectory(layout: RuntimeLayout, releaseId: String): File {
        require(releaseIdPattern.matches(releaseId)) { "Invalid rootfs release id" }
        return child(layout.releases, releaseId)
    }

    /**
     * Activates only a fully staged release. The installer must create ROOTFS_COMPLETE
     * after extraction and verification; a partial download can never become active.
     */
    fun activate(layout: RuntimeLayout, releaseId: String, manifestSha256: String) {
        require(sha256Pattern.matches(manifestSha256)) { "Invalid manifest SHA-256" }
        val release = releaseDirectory(layout, releaseId)
        check(release.isDirectory && File(release, "ROOTFS_COMPLETE").isFile) {
            "Rootfs release is incomplete"
        }
        val temp = File(layout.rootfs, ".active-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write("$releaseId\n${manifestSha256.lowercase()}\n".toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(temp.renameTo(layout.activePointer)) { "Cannot atomically activate rootfs" }
        } finally {
            temp.delete()
        }
    }

    fun active(layout: RuntimeLayout): ActiveRootfs? {
        if (!layout.activePointer.isFile) return null
        val lines = runCatching { layout.activePointer.readLines(Charsets.UTF_8) }.getOrNull() ?: return null
        if (lines.size < 2) return null
        val releaseId = lines[0]
        val digest = lines[1]
        if (!releaseIdPattern.matches(releaseId) || !sha256Pattern.matches(digest)) return null
        val release = runCatching { releaseDirectory(layout, releaseId) }.getOrNull() ?: return null
        if (!release.isDirectory || !File(release, "ROOTFS_COMPLETE").isFile) return null
        return ActiveRootfs(releaseId, digest.lowercase())
    }

    private fun child(parent: File, name: String): File {
        val canonicalParent = parent.canonicalFile
        val child = File(canonicalParent, name).canonicalFile
        check(child.path.startsWith(canonicalParent.path + File.separator)) {
            "Runtime path escaped its parent"
        }
        return child
    }
}
