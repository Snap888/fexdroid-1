package org.anlr.linuxruntime

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8StreamDecoderTest {
    @Test fun preservesCharactersSplitAcrossReadBoundaries() {
        val source = "Linux: Привет, мир 🌍 — café"
        val bytes = source.toByteArray(Charsets.UTF_8)
        val decoder = Utf8StreamDecoder()
        val output = StringBuilder()
        var offset = 0
        val chunks = listOf(1, 2, 4, 3, 5, 1, 7, 2, 6, 100)
        for (size in chunks) {
            if (offset >= bytes.size) break
            val count = minOf(size, bytes.size - offset)
            output.append(decoder.decode(bytes, offset, count))
            offset += count
        }
        output.append(decoder.decode(ByteArray(0), 0, 0, endOfInput = true))
        assertEquals(source, output.toString())
    }

    @Test fun replacesMalformedTrailingSequenceAtEndOfInput() {
        val decoder = Utf8StreamDecoder()
        assertEquals("", decoder.decode(byteArrayOf(0xF0.toByte(), 0x9F.toByte()), 0, 2))
        assertEquals("�", decoder.decode(ByteArray(0), 0, 0, endOfInput = true))
    }
}
