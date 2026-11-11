package org.anlr.linuxruntime

/** Minimal bounds-checked ELF header parser. It classifies metadata, not safety or ABI compatibility. */
internal data class ElfInfo(
    val elfClass: Int,
    val byteOrder: Int,
    val type: Int,
    val machine: Int,
    val architecture: String,
    val loadable: Boolean
)

internal object ElfInspector {
    fun inspect(header: ByteArray): ElfInfo? {
        if (header.size < 20) return null
        if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) return null
        val elfClass = header[4].toInt() and 0xff
        val byteOrder = header[5].toInt() and 0xff
        val identVersion = header[6].toInt() and 0xff
        if (elfClass !in 1..2 || byteOrder !in 1..2 || identVersion != 1) return null
        fun u16(offset: Int): Int {
            val first = header[offset].toInt() and 0xff
            val second = header[offset + 1].toInt() and 0xff
            return if (byteOrder == 1) first or (second shl 8) else (first shl 8) or second
        }
        val type = u16(16)
        val machine = u16(18)
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
}
