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
    @Test fun keepsFexHostRuntimeSeparateFromX64GuestRootfs() {
        val temp = Files.createTempDirectory("anlr-plan-fex-").toFile()
        val host = File(temp, "host").apply { mkdirs() }
        val guest = File(host, "usr/share/anlr/x86_64").apply { mkdirs() }
        val app = File(guest, "tmp/anlr-imports/x64-app").apply { parentFile.mkdirs(); writeBytes(staticElf(62)) }
        val fex = File(host, "usr/bin/FEX").apply { parentFile.mkdirs(); writeBytes(staticElf(183)); setExecutable(true) }
        try {
            val plan = LinuxLaunchPlanner.plan(
                app, host, ExecutionBackend.FEX_X86_64, fex,
                dependencyRootfs = guest
            )
            assertEquals(fex.canonicalFile, plan.executable.canonicalFile)
            assertEquals("/tmp/anlr-imports/x64-app", plan.arguments.first())
            assertEquals(host.canonicalPath, plan.environment["ANLR_ROOTFS"])
            assertEquals(guest.canonicalPath, plan.environment["FEX_ROOTFS"])
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun configuresBox64LibraryPathAndKeepsHostExecutablePath() {
        val temp = Files.createTempDirectory("anlr-plan-box64-").toFile()
        val host = File(temp, "host").apply { mkdirs() }
        val guest = File(host, "usr/share/anlr/x86_64").apply { mkdirs() }
        val app = File(guest, "tmp/anlr-imports/x64-app").apply { parentFile.mkdirs(); writeBytes(staticElf(62)) }
        val box64 = File(host, "usr/bin/box64").apply { parentFile.mkdirs(); writeBytes(staticElf(183)); setExecutable(true) }
        File(guest, "lib/x86_64-linux-gnu").mkdirs()
        try {
            val plan = LinuxLaunchPlanner.plan(
                app, host, ExecutionBackend.BOX64_X86_64, box64,
                dependencyRootfs = guest
            )
            assertEquals(box64.canonicalFile, plan.executable.canonicalFile)
            assertEquals(app.canonicalPath, plan.arguments.first())
            assertEquals("1", plan.environment["BOX64_DYNAREC"])
            assertTrue(plan.environment["BOX64_LD_LIBRARY_PATH"]!!.contains("x86_64-linux-gnu"))
        } finally {
            temp.deleteRecursively()
        }
    }

}
