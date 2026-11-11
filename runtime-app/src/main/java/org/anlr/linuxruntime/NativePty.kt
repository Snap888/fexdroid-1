package org.anlr.linuxruntime

import java.io.Closeable
import java.io.IOException

/**
 * Native pseudo-terminal backed by Android's POSIX PTY APIs.
 * This is a transport primitive; the caller supplies the executable and environment.
 */
internal class NativePty private constructor(
    private var handle: Long
) : Closeable {
    private val fd: Int get() = handle.toInt()
    private val childPid: Int get() = (handle ushr 32).toInt()

    fun read(buffer: ByteArray): Int {
        check(handle != 0L)
        val count = nativeRead(fd, buffer)
        if (count < 0) throw IOException("PTY read failed: errno ${-count}")
        return count
    }

    fun write(bytes: ByteArray): Int {
        check(handle != 0L)
        val count = nativeWrite(fd, bytes)
        if (count < 0) throw IOException("PTY write failed: errno ${-count}")
        return count
    }

    fun resize(rows: Int, columns: Int) {
        require(rows in 1..1000 && columns in 1..1000)
        check(handle != 0L && nativeResize(fd, rows, columns) == 0) { "PTY resize failed" }
    }

    fun sendSignal(signal: Int) {
        require(signal in 1..64)
        check(handle != 0L && nativeSignal(childPid, signal) == 0) { "PTY signal delivery failed" }
    }

    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        nativeClose(current.toInt(), (current ushr 32).toInt())
    }

    companion object {
        init { System.loadLibrary("anlr_pty") }

        fun spawn(
            executable: String,
            arguments: List<String>,
            workingDirectory: String,
            rows: Int = 24,
            columns: Int = 80,
            environment: Map<String, String> = emptyMap(),
            inheritEnvironment: Boolean = true
        ): NativePty {
            require(executable.startsWith("/") && workingDirectory.startsWith("/"))
            require(rows in 1..1000 && columns in 1..1000)
            val session = NativePty(0L)
            session.handle = session.nativeSpawn(executable, arguments.toTypedArray(), workingDirectory, rows, columns, inheritEnvironment, environment.map { "${it.key}=${it.value}" }.toTypedArray())
            check(session.handle != 0L) { "Cannot create PTY process" }
            return session
        }

    }

    private external fun nativeSpawn(
            executable: String, arguments: Array<String>, workingDirectory: String,
            rows: Int, columns: Int, inheritEnvironment: Boolean, environment: Array<String>
        ): Long
    private external fun nativeRead(fd: Int, buffer: ByteArray): Int
    private external fun nativeWrite(fd: Int, buffer: ByteArray): Int
    private external fun nativeResize(fd: Int, rows: Int, columns: Int): Int
    private external fun nativeSignal(pid: Int, signal: Int): Int
    private external fun nativeClose(fd: Int, pid: Int)
}
