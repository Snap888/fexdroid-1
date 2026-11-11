package org.anlr.linuxruntime

import android.content.ContentResolver
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class ImportedFile(val file: File, val sha256: String, val sizeBytes: Long, val format: String, val architecture: String, val executableCandidate: Boolean)

internal object AppFileImporter {
    private const val MAX_IMPORT_BYTES = 1L shl 30
    private const val RESERVED_FREE_BYTES = 64L * 1024 * 1024

    fun import(resolver: ContentResolver, source: Uri, displayName: String, filesDir: File): ImportedFile {
        val safeName = displayName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(120).ifBlank { "linux-app" }
        val targetDir = File(filesDir, "imported-apps")
        check(targetDir.exists() || targetDir.mkdirs()) { "Cannot create private import directory" }
        val available = StatFs(targetDir.absolutePath).availableBytes
        check(available > RESERVED_FREE_BYTES) { "Not enough free storage" }
        val id = UUID.randomUUID().toString()
        val temp = File(targetDir, ".$id.part")
        val target = File(targetDir, "$id-$safeName")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            val input = resolver.openInputStream(source) ?: error("Cannot open selected document")
            input.use { stream ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_IMPORT_BYTES) { "File exceeds 1 GiB import limit" }
                        check(total + RESERVED_FREE_BYTES <= available) { "Not enough free storage" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(temp.renameTo(target)) { "Cannot finalize imported file" }
            return inspect(target, digest.digest().toHex(), total, safeName)
        } catch (error: Exception) {
            temp.delete()
            target.delete()
            throw error
        }
    }

    private fun inspect(file: File, sha256: String, size: Long, name: String): ImportedFile {
        val header = ByteArray(64)
        val read = FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) break
                offset += count
            }
            offset
        }
        val bytes = header.copyOf(read)
        val elf = ElfInspector.inspect(bytes)
        val hasShebang = bytes.size >= 2 && bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()
        val lower = name.lowercase()
        val (format, candidate) = when {
            elf != null && lower.endsWith(".appimage") -> "AppImage · ELF" to elf.loadable
            elf != null -> "ELF binary" to elf.loadable
            hasShebang && (lower.endsWith(".sh") || lower.endsWith(".bash")) -> "Shell script · shebang detected" to true
            lower.endsWith(".desktop") -> "Desktop entry (not executable)" to false
            lower.endsWith(".deb") -> "Debian package" to false
            lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".xz") -> "Archive" to false
            else -> "Unrecognized file" to false
        }
        return ImportedFile(file, sha256, size, format, elf?.architecture ?: "Unknown", candidate)
    }

    fun deleteManagedCopy(filesDir: File, path: String): Boolean {
        val managedDir = File(filesDir, "imported-apps").canonicalFile
        val candidate = File(path).canonicalFile
        if (candidate.parentFile != managedDir || !candidate.isFile) return false
        return candidate.delete()
    }

    fun cleanupPartialImports(filesDir: File): Int {
        val managedDir = File(filesDir, "imported-apps")
        if (!managedDir.isDirectory) return 0
        var removed = 0
        managedDir.listFiles()?.filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".part") }?.forEach {
            if (it.delete()) removed++
        }
        return removed
    }

    fun verify(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || !expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().toHex()
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.US_ASCII),
            expectedSha256.lowercase().toByteArray(Charsets.US_ASCII)
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
