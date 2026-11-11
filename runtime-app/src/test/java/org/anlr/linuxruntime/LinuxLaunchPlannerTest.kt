package org.anlr.linuxruntime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxLaunchPlannerTest {
    private fun staticElf(machine: Int): ByteArray {
        val bytes = ByteArray(64)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1
        bytes[16] = 2
        bytes[18] = machine.toByte()
        bytes[19] = (machine ushr 8).toByte()
        return bytes
    }

    @Test fun plansStaticArm64ExecutableWithoutInvokingIt() {
        val root = Files.createTempDirectory("anlr-plan-root-").toFile()
        val imported = File(root, "imports/app").apply { parentFile.mkdirs(); writeBytes(staticElf(183)); setExecutable(true) }
        val rootfs = File(root, "rootfs").apply { mkdirs() }
        try {
            val plan = LinuxLaunchPlanner.plan(imported, rootfs, ExecutionBackend.NATIVE_ARM64)
            assertEquals(imported.canonicalFile, plan.executable.canonicalFile)
            assertTrue(plan.compatibility.compatible)
            assertEquals(rootfs.canonicalPath, plan.environment["ANLR_ROOTFS"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesArchitectureMismatchBeforeCreatingPlan() {
        val root = Files.createTempDirectory("anlr-plan-wrong-").toFile()
        val imported = File(root, "app").apply { writeBytes(staticElf(62)); setExecutable(true) }
        val rootfs = File(root, "rootfs").apply { mkdirs() }
        try {
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                LinuxLaunchPlanner.plan(imported, rootfs, ExecutionBackend.NATIVE_ARM64)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
