package org.anlr.linuxruntime

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

internal data class ExtractionResult(
    val entriesExtracted: Int,
    val regularFilesExtracted: Int,
    val bytesExtracted: Long
)

/**
 * Conservative tar.gz extractor for untrusted rootfs archives.
 * Guest-absolute symlinks are normalized inside rootfs; devices, FIFOs, traversal and symlink-parent writes are rejected.
 */
internal object SafeRootfsExtractor {
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_ENTRIES = 250_000

    fun extractTarGz(
        archive: File,
        destination: File,
        maxExtractedBytes: Long
    ): ExtractionResult {
        require(archive.isFile) { "Rootfs archive is missing" }
        require(maxExtractedBytes > 0) { "Invalid extracted-size limit" }
        check(destination.exists() || destination.mkdirs()) { "Cannot create rootfs staging directory" }
        val root = destination.canonicalFile.toPath()
        check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Rootfs destination is not a directory" }
        check(destination.listFiles()?.isEmpty() == true) { "Rootfs destination must be empty" }

        var entries = 0
        var files = 0
        var totalBytes = 0L
        val directoryModes = ArrayList<Pair<Path, Int>>()

        FileInputStream(archive).use { fileInput ->
            GzipCompressorInputStream(BufferedInputStream(fileInput), true).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        entries++
                        check(entries <= MAX_ENTRIES) { "Rootfs archive contains too many entries" }
                        val target = resolveArchivePath(root, entry.name)
                        ensureSafeParents(root, target.parent)

                        when {
                            entry.isDirectory -> {
                                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                                    check(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                                        "Conflicting rootfs directory entry"
                                    }
                                } else {
                                    Files.createDirectory(target)
                                }
                                directoryModes += target to entry.mode
                            }
                            entry.isSymbolicLink -> {
                                check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Duplicate rootfs path" }
                                val linkText = entry.linkName
                                require(linkText.isNotBlank() && !linkText.contains('\\')) {
                                    "Invalid rootfs symlink rejected"
                                }
                                val rawLink = Paths.get(linkText)
                                val linkPath = if (rawLink.isAbsolute) {
                                    val guestTarget = root.resolve(linkText.removePrefix("/")).normalize()
                                    require(guestTarget.startsWith(root)) { "Absolute guest symlink escapes rootfs" }
                                    target.parent.relativize(guestTarget)
                                } else {
                                    rawLink
                                }
                                val resolved = target.parent.resolve(linkPath).normalize()
                                require(resolved.startsWith(root)) { "Rootfs symlink escapes destination" }
                                Files.createSymbolicLink(target, linkPath)
                            }
                            entry.isLink -> {
                                check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Duplicate rootfs path" }
                                val linkTarget = resolveArchivePath(root, entry.linkName)
                                check(Files.isRegularFile(linkTarget, LinkOption.NOFOLLOW_LINKS)) {
                                    "Rootfs hardlink target must be an existing regular file"
                                }
                                Files.createLink(target, linkTarget)
                            }
                            entry.isFile -> {
                                check(entry.size >= 0 && entry.size <= maxExtractedBytes - totalBytes) {
                                    "Rootfs expanded size exceeds configured limit"
                                }
                                check(target.parent.toFile().usableSpace >= entry.size) {
                                    "Insufficient free space while extracting rootfs"
                                }
                                check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Duplicate rootfs path" }
                                FileOutputStream(target.toFile()).use { output ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var remaining = entry.size
                                    while (remaining > 0) {
                                        val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                        check(count > 0) { "Truncated rootfs tar entry" }
                                        output.write(buffer, 0, count)
                                        remaining -= count
                                        totalBytes += count
                                    }
                                    output.fd.sync()
                                }
                                applyMode(target, entry.mode)
                                files++
                            }
                            else -> error("Unsupported special file in rootfs archive: ${entry.name}")
                        }
                    }
                }
            }
        }

        directoryModes.asReversed().forEach { (path, mode) -> applyMode(path, mode) }
        return ExtractionResult(entries, files, totalBytes)
    }

    private fun resolveArchivePath(root: Path, rawName: String): Path {
        require(rawName.isNotBlank() && !rawName.startsWith("/") && !rawName.contains('\\')) {
            "Absolute or invalid rootfs path rejected"
        }
        val relative = Paths.get(rawName).normalize()
        require(!relative.isAbsolute && relative.toString().isNotEmpty() && relative.none { it.toString() == ".." }) {
            "Rootfs archive path traversal rejected"
        }
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root)) { "Rootfs archive path escapes destination" }
        return resolved
    }

    private fun ensureSafeParents(root: Path, parent: Path?) {
        if (parent == null) return
        require(parent.startsWith(root)) { "Rootfs parent path escapes destination" }
        var current = root
        val relative = root.relativize(parent)
        for (segment in relative) {
            current = current.resolve(segment)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current)
                applyMode(current, 0x1ed)
            } else {
                check(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(current)) { "Rootfs archive attempts to write through a symlink" }
            }
        }
    }

    private fun applyMode(path: Path, mode: Int) {
        // Preserve ordinary rwx bits; never restore setuid/setgid/sticky bits from an archive.
        val permissions = EnumSet.noneOf(PosixFilePermission::class.java)
        val bits = listOf(
            0x100 to PosixFilePermission.OWNER_READ,
            0x80 to PosixFilePermission.OWNER_WRITE,
            0x40 to PosixFilePermission.OWNER_EXECUTE,
            0x20 to PosixFilePermission.GROUP_READ,
            0x10 to PosixFilePermission.GROUP_WRITE,
            0x08 to PosixFilePermission.GROUP_EXECUTE,
            0x04 to PosixFilePermission.OTHERS_READ,
            0x02 to PosixFilePermission.OTHERS_WRITE,
            0x01 to PosixFilePermission.OTHERS_EXECUTE
        )
        bits.forEach { (bit, permission) -> if (mode and bit != 0) permissions.add(permission) }
        Files.setPosixFilePermissions(path, permissions)
    }
}
