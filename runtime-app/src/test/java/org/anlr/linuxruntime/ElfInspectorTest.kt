package org.anlr.linuxruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfInspectorTest {
    private fun header(elfClass: Int = 2, byteOrder: Int = 1, type: Int = 3, machine: Int = 62): ByteArray {
        val b = ByteArray(64)
        b[0] = 0x7f
        b[1] = 'E'.code.toByte()
        b[2] = 'L'.code.toByte()
        b[3] = 'F'.code.toByte()
        b[4] = elfClass.toByte()
        b[5] = byteOrder.toByte()
        b[6] = 1
        fun put16(offset: Int, value: Int) {
            if (byteOrder == 1) {
                b[offset] = value.toByte()
                b[offset + 1] = (value ushr 8).toByte()
            } else {
                b[offset] = (value ushr 8).toByte()
                b[offset + 1] = value.toByte()
            }
        }
        put16(16, type)
        put16(18, machine)
        return b
    }

    @Test fun detectsX8664LittleEndianElf() {
        val info = ElfInspector.inspect(header())!!
        assertEquals("x86-64 · ELF64", info.architecture)
        assertEquals(62, info.machine)
        assertTrue(info.loadable)
    }

    @Test fun detectsAarch64BigEndianHeader() {
        val info = ElfInspector.inspect(header(byteOrder = 2, machine = 183))!!
        assertEquals("AArch64 · ELF64", info.architecture)
        assertEquals(2, info.byteOrder)
    }

    @Test fun relocatableObjectIsNotRunnable() {
        assertFalse(ElfInspector.inspect(header(type = 1))!!.loadable)
    }

    @Test fun rejectsBadMagicAndTruncatedHeaders() {
        assertNull(ElfInspector.inspect(ByteArray(20)))
        assertNull(ElfInspector.inspect(header().copyOf(19)))
    }

    @Test fun rejectsInvalidClassAndByteOrder() {
        assertNull(ElfInspector.inspect(header(elfClass = 0)))
        assertNull(ElfInspector.inspect(header(byteOrder = 0)))
    }
}
