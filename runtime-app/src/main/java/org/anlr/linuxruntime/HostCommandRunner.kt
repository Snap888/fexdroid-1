package org.anlr.linuxruntime

import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal data class HostCommandResult(
    val output: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val truncated: Boolean
)

/**
 * Bounded, time-limited Android host diagnostic command runner.
 * This is deliberately not presented as a Linux guest shell or an interactive PTY.
 */
internal object HostCommandRunner {
    const val DEFAULT_TIMEOUT_MS = 12_000L
    const val MAX_OUTPUT_BYTES = 12 * 1024

    fun run(
        command: String,
        workingDirectory: File,
        shellPath: String = "/system/bin/sh",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = MAX_OUTPUT_BYTES,
        charset: Charset = Charsets.UTF_8
    ): HostCommandResult {
        require(command.isNotBlank()) { "Command must not be blank" }
        require(timeoutMs > 0) { "Timeout must be positive" }
        require(maxOutputBytes > 0) { "Output limit must be positive" }

        val process = ProcessBuilder(shellPath, "-c", command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
        val captured = ByteArrayOutputStream(minOf(maxOutputBytes, 4096))
        val truncated = AtomicBoolean(false)
        fun processId(): Int? = runCatching {
            (Process::class.java.getMethod("pid").invoke(process) as Long).toInt()
        }.getOrNull()?.takeIf { it > 0 }

        fun killTree(pid: Int, visited: MutableSet<Int>) {
            if (!visited.add(pid)) return
            val childrenFile = File("/proc/$pid/task/$pid/children")
            val children = runCatching {
                childrenFile.readText().trim().split(Regex("\\s+"))
                    .mapNotNull { it.toIntOrNull() }
            }.getOrDefault(emptyList())
            children.forEach { child -> killTree(child, visited) }
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }

        fun stopProcessTree() {
            processId()?.let { killTree(it, HashSet()) }
            process.destroyForcibly()
        }
        val reader = thread(name = "anlr-command-output", isDaemon = true) {
            process.inputStream.use { input ->
                val buffer = ByteArray(2048)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(captured) {
                        val remaining = maxOutputBytes - captured.size()
                        if (remaining > 0) captured.write(buffer, 0, minOf(remaining, count))
                        if (count > remaining) {
                            truncated.set(true)
                            stopProcessTree()
                            break
                        }
                    }
                }
            }
        }

        val completed = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            stopProcessTree()
            Thread.currentThread().interrupt()
            throw interrupted
        }
        if (!completed) stopProcessTree()
        runCatching { reader.join(1_000) }
        if (reader.isAlive) {
            runCatching { process.inputStream.close() }
            runCatching { reader.join(250) }
        }

        val text = synchronized(captured) { captured.toByteArray().toString(charset) }
        return HostCommandResult(
            output = text,
            exitCode = if (completed && !truncated.get()) process.exitValue() else null,
            timedOut = !completed && !truncated.get(),
            truncated = truncated.get()
        )
    }
}
