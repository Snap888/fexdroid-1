package org.anlr.linuxruntime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ELF metadata used for compatibility preflight; it does not prove that a binary is safe. */
internal data class ElfInfo(
    val elfClass: Int,
    val byteOrder: Int,
    val type: Int,
    val machine: Int,
    val architecture: String,
    val loadable: Boolean,
    val interpreter: String? = null,
    val neededLibraries: List<String> = emptyList()
)

internal object ElfInspector {
    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val PT_INTERP = 3
    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_STRSZ = 10L
    private const val MAX_PROGRAM_HEADERS = 4096
    private const val MAX_DYNAMIC_ENTRIES = 65536
    private const val MAX_STRING_TABLE_BYTES = 8 * 1024 * 1024

    fun inspect(header: ByteArray): ElfInfo? {
        if (header.size < 20 || !hasElfMagic(header)) return null
        val elfClass = header[4].toInt() and 0xff
        val byteOrder = header[5].toInt() and 0xff
        if (elfClass !in 1..2 || byteOrder !in 1..2 || header[6].toInt() and 0xff != 1) return null
        val buffer = ByteBuffer.wrap(header).order(if (byteOrder == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val type = buffer.getShort(16).toInt() and 0xffff
        val machine = buffer.getShort(18).toInt() and 0xffff
        return buildInfo(elfClass, byteOrder, type, machine)
    }

    fun inspect(file: File): ElfInfo? {
        if (!file.isFile || file.length() < 52) return null
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val headerBytes = readAt(input, 0, minOf(64L, input.length()).toInt())
                val basic = inspect(headerBytes) ?: return null
                val is64 = basic.elfClass == 2
                val order = if (basic.byteOrder == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                val header = ByteBuffer.wrap(headerBytes).order(order)
                val phoff = if (is64) header.getLong(32) else uint(header.getInt(28))
                val phentsize = header.getShort(if (is64) 54 else 42).toInt() and 0xffff
                val phnum = header.getShort(if (is64) 56 else 44).toInt() and 0xffff
                val minimumEntrySize = if (is64) 56 else 32
                if (phnum == 0) return basic
                require(phnum <= MAX_PROGRAM_HEADERS && phentsize >= minimumEntrySize)
                requireRange(phoff, phentsize.toLong() * phnum, input.length())

                data class Segment(val type: Long, val offset: Long, val vaddr: Long, val fileSize: Long)
                val segments = ArrayList<Segment>(phnum)
                repeat(phnum) { index ->
                    val offset = phoff + index.toLong() * phentsize
                    val bytes = readAt(input, offset, minimumEntrySize)
                    val ph = ByteBuffer.wrap(bytes).order(order)
                    val pType = uint(ph.getInt(0))
                    val pOffset: Long
                    val pVaddr: Long
                    val pFileSize: Long
                    if (is64) {
                        pOffset = ph.getLong(8)
                        pVaddr = ph.getLong(16)
                        pFileSize = ph.getLong(32)
                    } else {
                        pOffset = uint(ph.getInt(4))
                        pVaddr = uint(ph.getInt(8))
                        pFileSize = uint(ph.getInt(16))
                    }
                    if (pType == PT_LOAD.toLong() || pType == PT_DYNAMIC.toLong() || pType == PT_INTERP.toLong()) {
                        requireRange(pOffset, pFileSize, input.length())
                        segments += Segment(pType, pOffset, pVaddr, pFileSize)
                    }
                }

                val interpSegment = segments.firstOrNull { it.type == PT_INTERP.toLong() }
                val interpreter = interpSegment?.let {
                    require(it.fileSize in 2..4096)
                    readCString(readAt(input, it.offset, it.fileSize.toInt()))
                }

                val dynamic = segments.firstOrNull { it.type == PT_DYNAMIC.toLong() }
                if (dynamic == null) return basic.copy(interpreter = interpreter)
                val entrySize = if (is64) 16 else 8
                require(dynamic.fileSize % entrySize == 0L)
                val entryCount = dynamic.fileSize / entrySize
                require(entryCount <= MAX_DYNAMIC_ENTRIES)

                val neededOffsets = ArrayList<Long>()
                var stringTableAddress: Long? = null
                var stringTableSize: Long? = null
                repeat(entryCount.toInt()) { index ->
                    val entry = ByteBuffer.wrap(
                        readAt(input, dynamic.offset + index.toLong() * entrySize, entrySize)
                    ).order(order)
                    val tag = if (is64) entry.getLong(0) else uint(entry.getInt(0))
                    val value = if (is64) entry.getLong(8) else uint(entry.getInt(4))
                    when (tag) {
                        DT_NULL -> return@repeat
                        DT_NEEDED -> neededOffsets += value
                        DT_STRTAB -> stringTableAddress = value
                        DT_STRSZ -> stringTableSize = value
                    }
                }
                if (neededOffsets.isEmpty()) return basic.copy(interpreter = interpreter)
                val strAddress = stringTableAddress ?: return null
                val strSize = stringTableSize ?: return null
                require(strSize in 1..MAX_STRING_TABLE_BYTES.toLong())
                val load = segments.firstOrNull {
                    it.type == PT_LOAD.toLong() && strAddress >= it.vaddr &&
                        strAddress - it.vaddr < it.fileSize
                } ?: return null
                val stringOffset = load.offset + (strAddress - load.vaddr)
                requireRange(stringOffset, strSize, input.length())
                val strings = readAt(input, stringOffset, strSize.toInt())
                val libraries = neededOffsets.map { offset ->
                    require(offset in 0 until strSize)
                    readCString(strings, offset.toInt())
                }
                basic.copy(interpreter = interpreter, neededLibraries = libraries)
            }
        }.getOrNull()
    }

    private fun buildInfo(elfClass: Int, byteOrder: Int, type: Int, machine: Int): ElfInfo {
        val name = when (machine) {
            3 -> "x86"
            40 -> "ARM"
            62 -> "x86-64"
            183 -> "AArch64"
            243 -> "RISC-V"
            else -> "ELF machine $machine"
        }
        val archLabel = name + " · ELF" + if (elfClass == 2) "64" else "32"
        return ElfInfo(elfClass, byteOrder, type, machine, archLabel, type == 2 || type == 3)
    }

    private fun hasElfMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x7f.toByte() &&
            bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    private fun uint(value: Int): Long = Integer.toUnsignedLong(value)

    private fun requireRange(offset: Long, size: Long, fileSize: Long) {
        require(offset >= 0 && size >= 0 && offset <= fileSize && size <= fileSize - offset) {
            "ELF segment points outside the file"
        }
    }

    private fun readAt(input: RandomAccessFile, offset: Long, size: Int): ByteArray {
        require(offset >= 0 && size >= 0)
        val bytes = ByteArray(size)
        input.seek(offset)
        input.readFully(bytes)
        return bytes
    }

    private fun readCString(bytes: ByteArray, start: Int = 0): String {
        require(start in bytes.indices)
        var end = start
        while (end < bytes.size && bytes[end].toInt() != 0) end++
        require(end < bytes.size) { "Unterminated ELF string" }
        return String(bytes, start, end - start, Charsets.UTF_8)
    }
}
