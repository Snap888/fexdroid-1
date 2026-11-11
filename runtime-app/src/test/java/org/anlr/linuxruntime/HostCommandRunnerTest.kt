package org.anlr.linuxruntime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCommandRunnerTest {
    @Test fun capturesOutputAndExitCode() {
        val dir = Files.createTempDirectory("anlr-shell-").toFile()
        try {
            val result = HostCommandRunner.run("printf 'hello'; exit 7", dir, shellPath = "/bin/sh")
            assertEquals("hello", result.output)
            assertEquals(7, result.exitCode)
            assertFalse(result.timedOut)
            assertFalse(result.truncated)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun enforcesOutputLimit() {
        val dir = Files.createTempDirectory("anlr-shell-").toFile()
        try {
            val result = HostCommandRunner.run("yes x", dir, shellPath = "/bin/sh", maxOutputBytes = 128)
            assertEquals(128, result.output.toByteArray().size)
            assertTrue(result.truncated)
            assertFalse(result.timedOut)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun enforcesTimeout() {
        val dir = Files.createTempDirectory("anlr-shell-").toFile()
        try {
            val result = HostCommandRunner.run("sleep 5", dir, shellPath = "/bin/sh", timeoutMs = 100)
            assertTrue(result.timedOut)
            assertEquals(null, result.exitCode)
        } finally {
            dir.deleteRecursively()
        }
    }
}
