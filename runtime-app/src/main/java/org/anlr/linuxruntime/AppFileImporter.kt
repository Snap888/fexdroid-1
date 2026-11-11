package org.anlr.linuxruntime

import android.content.ContentResolver
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class ImportedFile(
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
    val format: String,
    val architecture: String,
    val executableCandidate: Boolean
)

internal object AppFileImporter {
    private const val MAX_IMPORT_BYTES = 1L shl 30 // 1 GiB safety ceiling

    fun import(
        resolver: ContentResolver,
        source: Uri,
        displayName: String,
        filesDir: File
    ): ImportedFile {
        val safeName = displayName
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .take(120)
            .ifBlank { "linux-app" }

        val targetDir = File(filesDir, "imported-apps")
        check(targetDir.exists() || targetDir.mkdirs()) { "Cannot create private import directory" }
        val stat = StatFs(targetDir.absolutePath)
        val available = stat.availableBytes
        check(available > 64L * 1024 * 1024) { "Not enough free storage" }

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
                        check(total + 64L * 1024 * 1024 <= available) { "Not enough free storage" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(temp.renameTo(target)) { "Cannot finalize imported file" }
            val metadata = inspect(target, digest.digest().toHex(), total, safeName)
            return metadata
        } catch (error: Throwable) {
            temp.delete()
            target.delete()
            throw error
        }
    }

    private fun inspect(file: File, sha256: String, size: Long, name: String): ImportedFile {
        val header = ByteArray(20)
        val read = FileInputStream(file).use { it.read(header) }
        val isElf = read >= 20 &&
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        val machine = if (isElf) {
            val littleEndian = header[5].toInt() == 1
            val lo = header[18].toInt() and 0xff
            val hi = header[19].toInt() and 0xff
            if (littleEndian) lo or (hi shl 8) else (lo shl 8) or hi
        } else -1

        val architecture = when (machine) {
            3 -> "x86 (32-bit)"
            40 -> "ARM (32-bit)"
            62 -> "x86-64"
            183 -> "AArch64"
            243 -> "RISC-V"
            else -> if (isElf) "ELF machine $machine" else "Unknown"
        }
        val lower = name.lowercase()
        val (format, candidate) = when {
            isElf && lower.endsWith(".appimage") -> "AppImage · ELF" to true
            isElf -> "ELF executable" to true
            lower.endsWith(".sh") -> "Shell script (review before execution)" to true
            lower.endsWith(".desktop") -> "Desktop entry (not executable)" to false
            lower.endsWith(".deb") -> "Debian package" to false
            lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".xz") -> "Archive" to false
            else -> "Unrecognized file" to false
        }
        return ImportedFile(file, sha256, size, format, architecture, candidate)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
