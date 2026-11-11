package org.anlr.linuxruntime

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Incremental UTF-8 decoder for PTY byte streams. A Unicode code point may be
 * split across native read() calls; decoding each chunk independently corrupts it.
 */
internal class Utf8StreamDecoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val pending = ByteBuffer.allocate(8192)
    private val chars = CharBuffer.allocate(8192)
    private var finished = false

    fun decode(bytes: ByteArray, offset: Int, length: Int, endOfInput: Boolean = false): String {
        check(!finished) { "UTF-8 decoder is already finished" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        require(length <= pending.remaining()) { "PTY UTF-8 input buffer overflow" }
        pending.put(bytes, offset, length)
        pending.flip()

        val output = StringBuilder()
        while (true) {
            val result = decoder.decode(pending, chars, endOfInput)
            chars.flip()
            output.append(chars)
            chars.clear()
            if (result.isError) result.throwException()
            if (!result.isOverflow) break
        }
        pending.compact()

        if (endOfInput) {
            while (true) {
                val result = decoder.flush(chars)
                chars.flip()
                output.append(chars)
                chars.clear()
                if (result.isError) result.throwException()
                if (!result.isOverflow) break
            }
            finished = true
        }
        return output.toString()
    }
}
